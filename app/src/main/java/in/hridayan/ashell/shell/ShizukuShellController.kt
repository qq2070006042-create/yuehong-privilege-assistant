package `in`.hridayan.ashell.shell

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ShizukuStatus {
    Checking,
    Unavailable,
    NeedsPermission,
    Granted,
}

data class OutputLine(val text: String, val isError: Boolean = false)

class ShizukuShellController : AutoCloseable {
    var status by mutableStateOf(ShizukuStatus.Checking)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var permissionRequiredNotice by mutableStateOf(false)
        private set

    var commandStartToken by mutableIntStateOf(0)
        private set

    val output = mutableStateListOf<OutputLine>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var registered = false
    private var permissionRequestInFlight = false
    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingPermissionDeniedAction: (() -> Unit)? = null

    @Volatile
    private var process: ShizukuRemoteProcess? = null

    private var currentDirectory = "/storage/emulated/0"

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        postToMain {
            status = ShizukuStatus.Unavailable
            failPendingPermissionRequest(showNotice = pendingPermissionAction != null)
        }
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            postToMain {
                permissionRequestInFlight = false
                if (result == PackageManager.PERMISSION_GRANTED) {
                    status = ShizukuStatus.Granted
                    permissionRequiredNotice = false
                    val action = pendingPermissionAction
                    clearPendingPermissionActions()
                    action?.invoke()
                } else {
                    status = ShizukuStatus.NeedsPermission
                    failPendingPermissionRequest(showNotice = true)
                }
            }
        }

    fun register() {
        if (registered) return
        registered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
        refreshStatus()
    }

    fun refreshStatus() {
        val next = runCatching {
            when {
                !Shizuku.pingBinder() -> ShizukuStatus.Unavailable
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    ShizukuStatus.Granted
                }
                else -> ShizukuStatus.NeedsPermission
            }
        }.getOrDefault(ShizukuStatus.Unavailable)
        postToMain {
            status = next
            if (next == ShizukuStatus.Granted) permissionRequiredNotice = false
        }
    }

    fun requestPermission() {
        if (hasPermissionNow()) {
            permissionRequestInFlight = false
            status = ShizukuStatus.Granted
            permissionRequiredNotice = false
            val action = pendingPermissionAction
            clearPendingPermissionActions()
            action?.invoke()
            return
        }

        beginPermissionRequest()
    }

    fun ensurePermission(
        onGranted: () -> Unit,
        onDenied: () -> Unit = {},
    ) {
        if (hasPermissionNow()) {
            permissionRequestInFlight = false
            status = ShizukuStatus.Granted
            clearPendingPermissionActions()
            onGranted()
            return
        }

        pendingPermissionAction = onGranted
        pendingPermissionDeniedAction = onDenied
        beginPermissionRequest()
    }

    private fun beginPermissionRequest() {
        permissionRequiredNotice = false
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            status = ShizukuStatus.Unavailable
            failPendingPermissionRequest(showNotice = true)
            return
        }

        status = ShizukuStatus.NeedsPermission
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
            status = ShizukuStatus.NeedsPermission
            failPendingPermissionRequest(showNotice = true)
        }
    }

    fun execute(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || isRunning) return

        ensurePermission(onGranted = {
            if (!isRunning) startCommand(trimmed)
        })
    }

    fun dismissPermissionRequiredNotice() {
        permissionRequiredNotice = false
    }

    @Suppress("DEPRECATION")
    private fun startCommand(
        command: String,
        onComplete: ((exitCode: Int, output: List<String>) -> Unit)? = null,
    ) {
        commandStartToken++
        val commandToRun = handleDirectoryCommand(command) ?: return
        output += OutputLine("$ $command")
        isRunning = true

        executor.execute {
            var remote: ShizukuRemoteProcess? = null
            var exitCode = -1
            val capturedOutput = mutableListOf<String>()
            try {
                remote = Shizuku.newProcess(
                    arrayOf("sh", "-c", "$commandToRun 2>&1"),
                    null,
                    currentDirectory,
                )
                process = remote
                remote.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        capturedOutput += line
                        postToMain { output += OutputLine(line) }
                    }
                }
                exitCode = remote.waitFor()
                if (exitCode != 0) {
                    postToMain { output += OutputLine("[exit code $exitCode]", isError = true) }
                }
            } catch (error: Throwable) {
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: error.javaClass.simpleName
                postToMain { output += OutputLine(message, isError = true) }
            } finally {
                runCatching { remote?.destroy() }
                process = null
                postToMain {
                    isRunning = false
                    onComplete?.invoke(exitCode, capturedOutput.toList())
                }
            }
        }
    }

    private fun failPendingPermissionRequest(showNotice: Boolean) {
        permissionRequestInFlight = false
        val deniedAction = pendingPermissionDeniedAction
        clearPendingPermissionActions()
        if (showNotice) permissionRequiredNotice = true
        deniedAction?.invoke()
    }

    private fun clearPendingPermissionActions() {
        pendingPermissionAction = null
        pendingPermissionDeniedAction = null
    }

    private fun hasPermissionNow(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun stop() {
        runCatching { process?.destroy() }
    }

    fun clear() {
        if (!isRunning) output.clear()
    }

    fun appendOutput(text: String, isError: Boolean = false) {
        val normalized = text.trimEnd()
        if (normalized.isEmpty()) return
        postToMain { output += OutputLine(normalized, isError) }
    }

    private fun handleDirectoryCommand(command: String): String? {
        if (command == "cd" || command.startsWith("cd ")) {
            val target = command.removePrefix("cd").trim().ifBlank { "/storage/emulated/0" }
            currentDirectory = when {
                target == "~" -> "/storage/emulated/0"
                target == ".." -> currentDirectory.substringBeforeLast('/', "").ifBlank { "/" }
                target.startsWith('/') -> target
                else -> "${currentDirectory.trimEnd('/')}/$target"
            }
            output += OutputLine("$ $command")
            output += OutputLine(currentDirectory)
            return null
        }
        return command
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    override fun close() {
        stop()
        if (registered) {
            runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        }
        registered = false
        executor.shutdownNow()
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
