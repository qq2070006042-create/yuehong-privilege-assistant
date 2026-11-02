package roro.stellar.yuehong.shell

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Metadata for an installed app carrying a compatible KernelSU ksud executable. */
data class KernelSuManagerInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    /** The matching library file in the app's arm64 native library directory. */
    val libKsudPath: String,
)

/** The selected manager identity and its currently recognized ksud path. */
data class KernelSuManagerTarget(
    val packageName: String,
    val libKsudPath: String,
)

/**
 * Stores the manager package selected on the mode-selection page and discovers compatible
 * managers by checking for the ksud late-load CLI, regardless of its native library filename.
 */
object KernelSuManagerSelection {
    const val DEFAULT_PACKAGE: String = "me.weishu.kernelsu"
    private const val PREFS_NAME = "kernel_su_manager_selection"
    private const val PACKAGE_KEY = "package_name"
    private const val RESOLVED_PACKAGE_KEY = "resolved_package_name"
    private const val LIBRARY_PATH_KEY = "library_path"
    private const val LIBRARY_NAME = "libksud.so"
    private const val MIN_RANDOM_LIBRARY_SIZE = 32 * 1024L
    private const val MAX_FINGERPRINT_SCAN_SIZE = 32 * 1024 * 1024L
    private const val SCAN_THREAD_COUNT = 4
    private const val KSUD_HELP_TIMEOUT_SECONDS = 2L
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val APP_PACKAGE_DIRECTORY = Regex("^/data/app/[^/]+/([^/]+)$")
    private val KSUD_BINARY_MARKERS = listOf(
        "Load kernelsu.ko and execute late-load stage scripts",
        "Pass allow_shell=1 when loading kernelsu.ko",
        "Specify kernel KMI version instead of auto-detection",
        "manager package name",
    )
    private val KSUD_PRIMARY_MARKER = KSUD_BINARY_MARKERS.first().toByteArray(Charsets.ISO_8859_1)
    private val KSUD_PRIMARY_SKIP = buildSkipTable(KSUD_PRIMARY_MARKER)
    private val KSUD_HELP_OPTIONS = listOf("--kmi", "--allow-shell", "--package-name")
    @Volatile
    private var cachedScan: List<KernelSuManagerInfo>? = null
    @Volatile
    private var scanGeneration = 0
    private val scanLock = Any()

    /** Returns the persisted package, falling back to the stock KernelSU package when empty. */
    fun selectedPackage(context: Context): String {
        val appContext = context.applicationContext
        val stored = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PACKAGE_KEY, null)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PACKAGE_KEY, null)
            } else {
                null
            }
        return stored
            ?.trim()
            ?.takeIf(PACKAGE_PATTERN::matches)
            ?: DEFAULT_PACKAGE
    }

    /** Returns the latest recognized selection without performing another package scan. */
    fun selectedManagerTarget(context: Context): KernelSuManagerTarget? {
        val appContext = context.applicationContext
        val selected = selectedPackage(appContext)
        val libraryPath = preferenceStores(appContext).firstNotNullOfOrNull { preferences ->
            if (preferences.getString(RESOLVED_PACKAGE_KEY, null) == selected) {
                preferences.getString(LIBRARY_PATH_KEY, null)
                    ?.takeIf { it.isNotBlank() && File(it).isFile }
            } else {
                null
            }
        } ?: return null
        if (selectedPackage(appContext) != selected) return null
        return KernelSuManagerTarget(selected, libraryPath)
    }

    /** Drops cached scan results when Android reports an app install or update. */
    fun invalidateScanCache() {
        scanGeneration++
        cachedScan = null
    }

    /** Refreshes and stores the selected manager's current install path. */
    fun refreshSelectedManager(context: Context): KernelSuManagerInfo? {
        val appContext = context.applicationContext
        val selected = selectedPackage(appContext)
        clearResolvedManager(appContext, selected)
        val manager = managerInfo(appContext, selected)
        if (manager?.packageName == selected && selectedPackage(appContext) == selected) {
            saveResolvedManager(appContext, selected, manager.libKsudPath)
            return manager
        }
        return null
    }

    /** Saves the current scan result as the selected manager and its resolved path. */
    fun saveSelectedManager(context: Context, manager: KernelSuManagerInfo): String {
        val normalized = manager.packageName.trim()
            .takeIf(PACKAGE_PATTERN::matches)
            ?: DEFAULT_PACKAGE
        val appContext = context.applicationContext
        preferenceStores(appContext).forEach { preferences ->
            preferences.edit()
                .putString(PACKAGE_KEY, normalized)
                .putString(RESOLVED_PACKAGE_KEY, normalized)
                .putString(LIBRARY_PATH_KEY, manager.libKsudPath)
                .commit()
        }
        return normalized
    }

    /** Returns the recognized ksud file for a specific package without changing selection. */
    @Suppress("DEPRECATION")
    fun managerInfo(context: Context, packageName: String): KernelSuManagerInfo? {
        val normalized = packageName.trim().takeIf(PACKAGE_PATTERN::matches) ?: return null
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val application = runCatching {
            packageManager.getApplicationInfo(normalized, 0)
        }.getOrNull() ?: return null
        return managerInfo(application, packageManager)
    }

    @Suppress("DEPRECATION")
    private fun managerInfo(
        application: ApplicationInfo,
        packageManager: PackageManager,
    ): KernelSuManagerInfo? {
        val packageName = application.packageName.orEmpty().trim()
        if (packageName.isEmpty()) return null
        val libraryPath = findLibraryPath(application) ?: return null
        val label = runCatching {
            application.loadLabel(packageManager).toString().trim()
        }.getOrNull().orEmpty().ifBlank { packageName }
        val icon = runCatching { application.loadIcon(packageManager) }
            .getOrElse { packageManager.defaultActivityIcon }
        return KernelSuManagerInfo(packageName, label, icon, libraryPath)
    }

    /** Persists a package name; null or blank deliberately resets to the stock package. */
    fun saveSelectedPackage(context: Context, packageName: String?): String {
        val normalized = packageName?.trim()
            ?.takeIf(PACKAGE_PATTERN::matches)
            ?: DEFAULT_PACKAGE
        val appContext = context.applicationContext
        // DirtyFrag's bootstrap runs after a direct-boot style hand-off. Keep
        // the value in both preference stores and commit before arming any
        // root flow so a fresh context cannot fall back to the default package.
        preferenceStores(appContext).forEach { preferences ->
            preferences
                .edit()
                .putString(PACKAGE_KEY, normalized)
                .remove(RESOLVED_PACKAGE_KEY)
                .remove(LIBRARY_PATH_KEY)
                .commit()
        }
        return normalized
    }

    private fun preferenceStores(context: Context): List<SharedPreferences> {
        val appContext = context.applicationContext
        return buildList {
            add(appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(
                    appContext.createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                )
            }
        }
    }

    private fun saveResolvedManager(context: Context, packageName: String, libraryPath: String) {
        preferenceStores(context).forEach { preferences ->
            preferences.edit()
                .putString(RESOLVED_PACKAGE_KEY, packageName)
                .putString(LIBRARY_PATH_KEY, libraryPath)
                .commit()
        }
    }

    private fun clearResolvedManager(context: Context, packageName: String) {
        preferenceStores(context).forEach { preferences ->
            if (preferences.getString(RESOLVED_PACKAGE_KEY, null) == packageName) {
                preferences.edit()
                    .remove(RESOLVED_PACKAGE_KEY)
                    .remove(LIBRARY_PATH_KEY)
                    .commit()
            }
        }
    }

    /** Copies the selected manager's ksud executable from its installed arm64 library directory. */
    fun copySelectedKsud(context: Context, destination: File): Boolean {
        val libraryPath = selectedManagerTarget(context)?.libKsudPath ?: return false
        val parent = destination.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return runCatching {
            File(libraryPath).inputStream().use { input ->
                FileOutputStream(destination, false).use { output -> input.copyTo(output) }
            }
            destination.isFile && destination.length() > 0L
        }.getOrDefault(false)
    }

    /**
     * Finds installed applications carrying ksud under `/data/app/<install>/<package>/lib/arm64`.
     * Fixed-name libraries are preferred. Randomized names must contain the shared ksud CLI
     * fingerprint and expose the expected `late-load --help` options. An executable arm64 ELF
     * header is checked before reading a randomized candidate's contents. Run this off the main
     * thread because it scans native libraries and probes matching candidates.
     */
    @Suppress("DEPRECATION")
    fun scan(context: Context): List<KernelSuManagerInfo> {
        val appContext = context.applicationContext
        val results = cachedScan ?: synchronized(scanLock) {
            cachedScan ?: run {
                val generation = scanGeneration
                val discovered = scanInstalled(appContext)
                cachedScan = discovered
                if (generation != scanGeneration) cachedScan = null
                discovered
            }
        }
        val selected = selectedPackage(appContext)
        return results
            .sortedWith(
                compareBy<KernelSuManagerInfo> {
                    if (it.packageName == selected) 0 else 1
                }.thenBy { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.packageName },
            )
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun scanInstalled(context: Context): List<KernelSuManagerInfo> {
        val packageManager = context.packageManager
        val applications = runCatching {
            packageManager.getInstalledApplications(0)
        }.getOrElse { emptyList() }
        if (applications.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(minOf(SCAN_THREAD_COUNT, applications.size))
        return try {
            applications.map { application ->
                executor.submit<KernelSuManagerInfo?> {
                    runCatching { managerInfo(application, packageManager) }.getOrNull()
                }
            }.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.distinctBy(KernelSuManagerInfo::packageName)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun findLibraryPath(application: ApplicationInfo): String? {
        val nativeDirectoryFile = application.nativeLibraryDir
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return null

        // Discard apps outside the accepted install layout before canonical-path and
        // directory checks. Most installed apps never need any filesystem probing.
        val rawPackageDirectory = nativeDirectoryFile.parentFile
            ?.parentFile
            ?: return null
        if (!isAllowedAppPackageDirectoryPath(rawPackageDirectory.path)) return null

        val nativeDirectory = runCatching { nativeDirectoryFile.canonicalFile }
            .getOrNull()
            ?: return null

        // Filter the app package directory first, before examining any library files.
        val packageDirectory = nativeDirectory.parentFile
            ?.parentFile
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return null
        if (!isAllowedAppPackageDirectory(packageDirectory)) return null

        val expectedNativeDirectory = runCatching {
            File(packageDirectory, "lib/arm64").canonicalFile
        }.getOrNull() ?: return null
        if (nativeDirectory != expectedNativeDirectory || !nativeDirectory.isDirectory) return null

        // Preserve the historical exact-name match before attempting generic discovery.
        val exactNameLibrary = File(nativeDirectory, LIBRARY_NAME)
        if (isDirectLibraryFile(exactNameLibrary, nativeDirectory)) {
            return exactNameLibrary.canonicalPath
        }
        return findRandomLibrary(nativeDirectory)?.canonicalPath
    }

    private fun isAllowedAppPackageDirectory(directory: File): Boolean {
        return isAllowedAppPackageDirectoryPath(directory.path) && directory.isDirectory
    }

    private fun isAllowedAppPackageDirectoryPath(path: String): Boolean {
        val packageDirectoryName = APP_PACKAGE_DIRECTORY
            .matchEntire(path)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false
        return !packageDirectoryName.contains("android", ignoreCase = true)
    }

    private fun findRandomLibrary(directory: File): File? {
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.name.endsWith(".so", ignoreCase = true) }
            ?.filter { it.length() >= MIN_RANDOM_LIBRARY_SIZE }
            ?.filter { it.length() <= MAX_FINGERPRINT_SCAN_SIZE }
            ?.filter { isDirectLibraryFile(it, directory) }
            ?.filter(::isArm64ExecutableElf)
            ?.sortedByDescending(File::length)
            ?.firstOrNull { candidate ->
                hasKsudBinaryFingerprint(candidate) &&
                    supportsKsudLateLoad(candidate.absolutePath)
            }
    }

    private fun isDirectLibraryFile(file: File, directory: File): Boolean {
        return runCatching {
            file.isFile && file.canonicalFile.parentFile == directory
        }.getOrDefault(false)
    }

    /** Reject ordinary arm64 shared libraries before scanning their full contents. */
    private fun isArm64ExecutableElf(file: File): Boolean {
        return runCatching {
            val header = ByteArray(32)
            DataInputStream(file.inputStream().buffered()).use { it.readFully(header) }
            val isElf = header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            if (!isElf || header[4] != 2.toByte() || header[5] != 1.toByte()) {
                return@runCatching false
            }

            val type = littleEndianUnsignedShort(header, 16)
            val machine = littleEndianUnsignedShort(header, 18)
            val executableType = type == ELF_TYPE_EXECUTABLE || type == ELF_TYPE_SHARED
            val hasEntryPoint = (24..31).any { header[it] != 0.toByte() }
            executableType && machine == ELF_MACHINE_AARCH64 && hasEntryPoint
        }.getOrDefault(false)
    }

    private fun littleEndianUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun hasKsudBinaryFingerprint(file: File): Boolean {
        return runCatching {
            if (file.length() > MAX_FINGERPRINT_SCAN_SIZE) return@runCatching false
            var primaryMarkerFound = false
            var tooLarge = false
            file.inputStream().buffered().use { stream ->
                val overlap = KSUD_PRIMARY_MARKER.size - 1
                val buffer = ByteArray(32 * 1024 + overlap)
                var totalBytes = 0L
                var carriedBytes = 0
                while (true) {
                    val count = stream.read(buffer, carriedBytes, 32 * 1024)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_FINGERPRINT_SCAN_SIZE) {
                        tooLarge = true
                        break
                    }
                    val availableBytes = carriedBytes + count
                    primaryMarkerFound = containsMarker(
                        buffer,
                        availableBytes,
                        KSUD_PRIMARY_MARKER,
                        KSUD_PRIMARY_SKIP,
                    )
                    if (primaryMarkerFound) break
                    carriedBytes = minOf(overlap, availableBytes)
                    System.arraycopy(
                        buffer,
                        availableBytes - carriedBytes,
                        buffer,
                        0,
                        carriedBytes,
                    )
                }
            }
            if (tooLarge || !primaryMarkerFound) return@runCatching false
            val contents = file.inputStream().use {
                String(it.readBytes(), Charsets.ISO_8859_1)
            }
            KSUD_BINARY_MARKERS.all(contents::contains)
        }.getOrDefault(false)
    }

    private fun buildSkipTable(pattern: ByteArray): IntArray {
        val skip = IntArray(256) { pattern.size }
        for (index in 0 until pattern.lastIndex) {
            skip[pattern[index].toInt() and 0xff] = pattern.lastIndex - index
        }
        return skip
    }

    private fun containsMarker(
        data: ByteArray,
        dataSize: Int,
        marker: ByteArray,
        skip: IntArray,
    ): Boolean {
        if (dataSize < marker.size) return false
        var position = marker.lastIndex
        while (position < dataSize) {
            var markerIndex = marker.lastIndex
            var dataIndex = position
            while (markerIndex >= 0 && data[dataIndex] == marker[markerIndex]) {
                markerIndex--
                dataIndex--
            }
            if (markerIndex < 0) return true
            position += skip[data[position].toInt() and 0xff]
        }
        return false
    }

    private fun supportsKsudLateLoad(path: String): Boolean {
        val process = runCatching {
            ProcessBuilder(path, "late-load", "--help")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false

        return try {
            if (!process.waitFor(KSUD_HELP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.exitValue() == 0 &&
                    output.contains("late-load") &&
                    KSUD_HELP_OPTIONS.all(output::contains)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }

    private const val ELF_TYPE_EXECUTABLE = 2
    private const val ELF_TYPE_SHARED = 3
    private const val ELF_MACHINE_AARCH64 = 183
}
