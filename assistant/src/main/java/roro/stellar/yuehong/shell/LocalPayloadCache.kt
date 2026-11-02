package roro.stellar.yuehong.shell

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

data class CachedLocalPayloadInfo(
    val displayName: String,
    val command: String,
    val sizeBytes: Long,
)

internal data class CachedLocalPayload(
    val file: File,
    val info: CachedLocalPayloadInfo,
    val sha256: String,
)

/** Stores the last successful local escalation payload outside the backed-up app files. */
internal object LocalPayloadCache {
    private const val DIRECTORY_NAME = "stellar_local_payload"
    private const val PAYLOAD_PREFIX = "payload-"
    private const val PAYLOAD_SUFFIX = ".bin"
    private const val METADATA_FILE = "metadata.properties"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

    fun load(context: Context): CachedLocalPayload? {
        val directory = cacheDirectory(context)
        val metadata = AtomicFile(File(directory, METADATA_FILE))
        return try {
            val properties = Properties()
            metadata.openRead().use(properties::load)

            val fileName = properties.getProperty("fileName") ?: return null
            if (!fileName.matches(Regex("^payload-[0-9a-f]{64}\\.bin$"))) return null

            val displayName = properties.getProperty("displayName")?.takeIf(String::isNotBlank) ?: return null
            val command = properties.getProperty("command")?.takeIf(String::isNotBlank) ?: return null
            val sizeBytes = properties.getProperty("sizeBytes")?.toLongOrNull()
                ?.takeIf { it in 1..MAX_CACHE_BYTES } ?: return null
            val sha256 = properties.getProperty("sha256")?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                ?: return null
            val file = File(directory, fileName)
            if (!file.isFile || file.length() != sizeBytes || fileName != payloadFileName(sha256)) return null

            CachedLocalPayload(
                file = file,
                info = CachedLocalPayloadInfo(displayName, command, sizeBytes),
                sha256 = sha256,
            )
        } catch (_: FileNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        context: Context,
        source: File,
        displayName: String,
        command: String,
        sizeBytes: Long,
        sha256: String,
    ) {
        require(source.isFile && source.length() == sizeBytes) { "Local payload changed before caching" }
        require(sizeBytes in 1..MAX_CACHE_BYTES) { "Local payload size is invalid" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "Local payload hash is invalid" }

        val directory = cacheDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create the private local payload cache")
        }

        val fileName = payloadFileName(sha256)
        val payloadFile = File(directory, fileName)
        writeAtomically(payloadFile) { output ->
            val copiedBytes = source.inputStream().use { input -> input.copyTo(output) }
            if (copiedBytes != sizeBytes) {
                throw IOException("Cached local payload size does not match the source")
            }
        }

        val properties = Properties().apply {
            setProperty("fileName", fileName)
            setProperty("displayName", displayName)
            setProperty("command", command)
            setProperty("sizeBytes", sizeBytes.toString())
            setProperty("sha256", sha256)
        }
        writeAtomically(File(directory, METADATA_FILE)) { output ->
            properties.store(output, "Last successful Stellar local payload")
        }

        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PAYLOAD_PREFIX) && it.name.endsWith(PAYLOAD_SUFFIX) && it.name != fileName }
            ?.forEach(File::delete)
    }

    private fun cacheDirectory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    private fun payloadFileName(sha256: String): String = "$PAYLOAD_PREFIX$sha256$PAYLOAD_SUFFIX"

    private inline fun writeAtomically(file: File, write: (FileOutputStream) -> Unit) {
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            write(output)
            output.fd.sync()
            atomicFile.finishWrite(output)
            output = null
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }
}
