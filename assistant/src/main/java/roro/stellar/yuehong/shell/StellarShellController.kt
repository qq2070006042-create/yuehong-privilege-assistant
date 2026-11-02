package roro.stellar.yuehong.shell

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import roro.stellar.Stellar
import roro.stellar.StellarRemoteProcess
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class StellarStatus {
    Checking,
    Unavailable,
    NeedsPermission,
    Granted,
}

data class OutputLine(val text: String, val isError: Boolean = false)

data class ShellFileInstallResult(val exitCode: Int, val output: String)

internal const val STELLAR_PAYLOAD_DESTINATION = "/data/local/tmp/preload.so"
internal const val STELLAR_PAYLOAD_MODE = "0755"
private const val ACTIVATION_SCRIPT_MODE = "0700"

class StellarShellController : AutoCloseable {
    var status by mutableStateOf(StellarStatus.Checking)
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
    private var process: StellarRemoteProcess? = null

    private var currentDirectory = "/storage/emulated/0"

    private val binderReceivedListener = Stellar.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Stellar.OnBinderDeadListener {
        postToMain {
            status = StellarStatus.Unavailable
            failPendingPermissionRequest(showNotice = pendingPermissionAction != null)
        }
    }
    private val permissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, _ ->
            if (requestCode == REQUEST_CODE) {
                postToMain {
                    permissionRequestInFlight = false
                    if (allowed) {
                        status = StellarStatus.Granted
                        permissionRequiredNotice = false
                        val action = pendingPermissionAction
                        clearPendingPermissionActions()
                        action?.invoke()
                    } else {
                        status = StellarStatus.NeedsPermission
                        failPendingPermissionRequest(showNotice = true)
                    }
                }
            }
        }

    fun register() {
        if (registered) return
        registered = true
        runCatching {
            Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
            Stellar.addBinderDeadListener(binderDeadListener)
            Stellar.addRequestPermissionResultListener(permissionResultListener)
        }
        refreshStatus()
    }

    fun refreshStatus() {
        val next = runCatching {
            when {
                !Stellar.pingBinder() -> StellarStatus.Unavailable
                Stellar.checkSelfPermission() -> {
                    StellarStatus.Granted
                }
                else -> StellarStatus.NeedsPermission
            }
        }.getOrDefault(StellarStatus.Unavailable)
        postToMain {
            status = next
            if (next == StellarStatus.Granted) permissionRequiredNotice = false
        }
    }

    fun requestPermission() {
        if (hasPermissionNow()) {
            permissionRequestInFlight = false
            status = StellarStatus.Granted
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
            status = StellarStatus.Granted
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
        val binderAvailable = runCatching { Stellar.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            status = StellarStatus.Unavailable
            failPendingPermissionRequest(showNotice = true)
            return
        }

        status = StellarStatus.NeedsPermission
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        runCatching { Stellar.requestPermission(requestCode = REQUEST_CODE) }.onFailure {
            status = StellarStatus.NeedsPermission
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
            var remote: StellarRemoteProcess? = null
            var exitCode = -1
            val capturedOutput = mutableListOf<String>()
            var mainOutputIndex: Int? = null
            var currentOutputPublished = false
            val currentLine = StringBuilder()
            var currentLineActive = false
            var escapeState = 0

            fun publishCurrentLine() {
                if (!currentLineActive) return
                val text = currentLine.toString()
                val replace = currentOutputPublished
                if (replace && capturedOutput.isNotEmpty()) {
                    capturedOutput[capturedOutput.lastIndex] = text
                } else {
                    capturedOutput += text
                }
                postToMain {
                    if (replace && mainOutputIndex != null && mainOutputIndex!! < output.size) {
                        output[mainOutputIndex!!] = OutputLine(text)
                    } else {
                        output += OutputLine(text)
                        mainOutputIndex = output.lastIndex
                    }
                }
                currentOutputPublished = true
            }

            fun commitCurrentLine() {
                if (!currentLineActive && !currentOutputPublished) return
                if (!currentLineActive) currentLineActive = true
                publishCurrentLine()
                currentLine.setLength(0)
                currentLineActive = false
                currentOutputPublished = false
            }

            fun consumeCharacter(character: Char) {
                if (escapeState != 0) {
                    if (escapeState == 1) {
                        escapeState = if (character == '[') 2 else 0
                    } else if (character in '@'..'~') {
                        escapeState = 0
                    }
                    return
                }
                if (character == '\u001B') {
                    escapeState = 1
                    return
                }
                when (character) {
                    '\r' -> {
                        currentLine.setLength(0)
                        currentLineActive = true
                    }
                    '\n' -> commitCurrentLine()
                    '\b' -> if (currentLine.isNotEmpty()) currentLine.deleteCharAt(currentLine.lastIndex)
                    else -> {
                        currentLine.append(character)
                        currentLineActive = true
                    }
                }
            }

            fun consumeProcessOutput() {
                InputStreamReader(remote!!.inputStream, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        for (index in 0 until count) consumeCharacter(buffer[index])
                        publishCurrentLine()
                    }
                }
                commitCurrentLine()
            }

            try {
                val commandPrefix = if (commandToRun.contains("yjazjc")) {
                    "export YJAZJC_LIVE_PROGRESS=1; "
                } else {
                    ""
                }
                remote = Stellar.newProcess(
                    arrayOf("sh", "-c", "$commandPrefix$commandToRun 2>&1"),
                    null,
                    currentDirectory,
                )
                process = remote
                consumeProcessOutput()
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
        Stellar.pingBinder() &&
            Stellar.checkSelfPermission()
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

    @Suppress("DEPRECATION")
    fun removeExistingPayload(): ShellFileInstallResult {
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "rm -f -- '$STELLAR_PAYLOAD_DESTINATION' && " +
                    "[ ! -e '$STELLAR_PAYLOAD_DESTINATION' ] && [ ! -L '$STELLAR_PAYLOAD_DESTINATION' ]",
            ),
            null,
            "/",
        )
        return try {
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun installPayload(source: File): ShellFileInstallResult {
        require(source.isFile) { "Bundled privilege file is missing" }
        val quotedDestination = "'$STELLAR_PAYLOAD_DESTINATION'"
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "cat > $quotedDestination && chmod $STELLAR_PAYLOAD_MODE $quotedDestination && " +
                    "[ -f $quotedDestination ] && [ -x $quotedDestination ]",
            ),
            null,
            "/",
        )
        return try {
            source.inputStream().use { input ->
                remote.outputStream.use { output -> input.copyTo(output) }
            }
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun installActivationScript(source: File, destination: String): ShellFileInstallResult {
        require(source.isFile) { "Activation script is missing" }
        val quotedDestination = "'$destination'"
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "cat > $quotedDestination && " +
                    "chmod $ACTIVATION_SCRIPT_MODE $quotedDestination && " +
                    "[ -f $quotedDestination ] && [ -x $quotedDestination ]",
            ),
            null,
            "/",
        )
        return try {
            source.inputStream().use { input ->
                remote.outputStream.use { output -> input.copyTo(output) }
            }
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    fun removeActivationScript(destination: String): ShellFileInstallResult {
        val remote = Stellar.newProcess(
            arrayOf(
                "sh",
                "-c",
                "rm -f -- '$destination' && " +
                    "[ ! -e '$destination' ] && " +
                    "[ ! -L '$destination' ]",
            ),
            null,
            "/",
        )
        return try {
            remote.outputStream.close()
            val stdout = remote.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stderr = remote.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ShellFileInstallResult(
                exitCode = remote.waitFor(),
                output = listOf(stdout, stderr).filter(String::isNotBlank).joinToString("\n"),
            )
        } finally {
            runCatching { remote.destroy() }
        }
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
            runCatching { Stellar.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Stellar.removeBinderDeadListener(binderDeadListener) }
            runCatching { Stellar.removeRequestPermissionResultListener(permissionResultListener) }
        }
        registered = false
        executor.shutdownNow()
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
