package roro.stellar.manager.ui.features.wired

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.adb.AdbKey
import roro.stellar.manager.adb.PreferenceAdbKeyStore
import roro.stellar.manager.adb.UsbAdbClient
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.BundledPayload
import roro.stellar.yuehong.shell.BundledPayloadCatalog
import roro.stellar.yuehong.ui.BundledPayloadDialog
import roro.stellar.yuehong.ui.LocalPayloadCommandDialog
import java.io.File
import java.io.IOException

internal sealed interface AdbConsoleCommand {
    data class Shell(val command: String) : AdbConsoleCommand
    data class Push(val localHint: String, val remotePath: String) : AdbConsoleCommand
    data class Pull(val remotePath: String, val localName: String) : AdbConsoleCommand
    data class Install(val localHint: String, val flags: List<String>) : AdbConsoleCommand
    data class Reboot(val target: String) : AdbConsoleCommand
    data class Service(val name: String) : AdbConsoleCommand
    data class Local(val name: String) : AdbConsoleCommand
    data object Help : AdbConsoleCommand
}

private fun parseAdbConsoleCommand(input: String): AdbConsoleCommand {
    val original = input.trim()
    require(original.isNotBlank()) { "请输入 ADB 命令" }
    if (!original.startsWith("adb ", ignoreCase = true) && !original.equals("adb", ignoreCase = true)) {
        val shell = when {
            original.startsWith("shell:", ignoreCase = true) -> original.substring(6).trim()
            original.startsWith("shell ", ignoreCase = true) -> original.substring(6).trim()
            else -> original
        }
        require(shell.isNotBlank()) { "Shell 命令为空" }
        return AdbConsoleCommand.Shell(shell)
    }

    val body = original.removePrefixIgnoreCase("adb").trim()
    require(body.isNotBlank()) { "请输入 adb 子命令" }
    if (body.startsWith("shell ", ignoreCase = true)) {
        return AdbConsoleCommand.Shell(body.substring(6).trim().also { require(it.isNotBlank()) })
    }
    if (body.startsWith("exec-out ", ignoreCase = true)) {
        return AdbConsoleCommand.Shell(body.substring(9).trim().also { require(it.isNotBlank()) })
    }

    val args = tokenizeAdbCommand(body)
    return when (args.first().lowercase()) {
        "push" -> {
            require(args.size == 3) { "用法：adb push <本地文件> <目标路径>" }
            AdbConsoleCommand.Push(args[1], args[2])
        }
        "pull" -> {
            require(args.size in 2..3) { "用法：adb pull <目标文件> [本地文件名]" }
            val localName = args.getOrNull(2)?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf(String::isNotBlank)
                ?: args[1].substringAfterLast('/').ifBlank { "adb-pull.bin" }
            AdbConsoleCommand.Pull(args[1], localName)
        }
        "install", "install-multiple" -> {
            require(args.size >= 2) { "用法：adb install [参数] <APK>" }
            require(args.first().equals("install", ignoreCase = true)) { "当前文件选择器一次安装一个 APK" }
            AdbConsoleCommand.Install(args.last(), args.drop(1).dropLast(1))
        }
        "uninstall" -> {
            require(args.size >= 2) { "用法：adb uninstall [参数] <包名>" }
            AdbConsoleCommand.Shell("pm uninstall ${args.drop(1).joinToString(" ") { shellQuote(it) }}")
        }
        "reboot" -> AdbConsoleCommand.Reboot(args.getOrNull(1).orEmpty())
        "root" -> AdbConsoleCommand.Service("root:")
        "unroot" -> AdbConsoleCommand.Service("unroot:")
        "remount" -> AdbConsoleCommand.Service("remount:")
        "disable-verity" -> AdbConsoleCommand.Service("disable-verity:")
        "enable-verity" -> AdbConsoleCommand.Service("enable-verity:")
        "tcpip" -> {
            val port = args.getOrNull(1)?.toIntOrNull()
            require(port != null && port in 1..65535) { "用法：adb tcpip <1-65535>" }
            AdbConsoleCommand.Service("tcpip:$port")
        }
        "usb" -> AdbConsoleCommand.Service("usb:")
        "service" -> {
            require(args.size == 2 && ':' in args[1]) { "用法：adb service <原始服务名>" }
            AdbConsoleCommand.Service(args[1])
        }
        "devices", "get-state", "get-serialno", "wait-for-device", "version" ->
            AdbConsoleCommand.Local(args.first().lowercase())
        "logcat", "bugreport" -> AdbConsoleCommand.Shell(args.joinToString(" "))
        "help", "--help", "-h" -> AdbConsoleCommand.Help
        "start-server", "kill-server", "connect", "disconnect", "pair", "forward", "reverse" ->
            throw IllegalArgumentException("该命令属于电脑端 ADB Server，不适用于手机 OTG 直连")
        else -> throw IllegalArgumentException("未知的 adb 子命令：${args.first()}")
    }
}

private fun tokenizeAdbCommand(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    input.forEach { character ->
        when {
            quote != null && character == quote -> quote = null
            quote == null && (character == '\'' || character == '"') -> quote = character
            quote == null && character.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(character)
        }
    }
    require(quote == null) { "命令引号未闭合" }
    if (current.isNotEmpty()) result += current.toString()
    require(result.isNotEmpty()) { "ADB 命令为空" }
    return result
}

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

class VivoWiredActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StellarTheme {
                BackHandler { finish() }
                VivoWiredScreen()
            }
        }
    }
}

internal class VivoWiredViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val adbKey = AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "YueHong-Wired")
    private val _state = MutableStateFlow(VivoWiredProgress())
    val state: StateFlow<VivoWiredProgress> = _state.asStateFlow()
    private var permissionWaiter: CompletableDeferred<Boolean>? = null
    /** USB grants are reused for the lifetime of the attached-device session. */
    private var lastUsbPermissionKey: String? = null
    private var lastUsbPermissionAt: Long = 0L
    private var work: Job? = null
    @Volatile
    private var stopRequested = false
    @Volatile
    private var activeClient: UsbAdbClient? = null
    @Volatile
    private var consoleClient: UsbAdbClient? = null
    private val escalator = VivoWiredEscalator(app, ::connectTarget) { next ->
        if (!stopRequested) _state.value = next
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            permissionWaiter?.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(permissionReceiver, filter)
        }
        refreshDeviceState()
    }

    fun startWithBundledPayload(payload: BundledPayload) {
        startInternal(bundledPayload = payload)
    }

    fun startWithLocalPayload(uri: Uri, command: String) {
        startInternal(localRequest = VivoWiredLocalRequest(uri, command.trim()))
    }

    fun executeAdbCommand(input: String) {
        val parsed = runCatching { parseAdbConsoleCommand(input) }
            .getOrElse { error ->
                reportAdbCommandError(input, error.message.orEmpty())
                return
        }
        when (parsed) {
            is AdbConsoleCommand.Pull,
            is AdbConsoleCommand.Install,
            -> {
                reportAdbCommandError(input, "该命令需要先通过系统文件选择器选择文件")
                return
            }
            AdbConsoleCommand.Help -> {
                appendAdbTestLog(ADB_COMMAND_HELP)
                _state.value = _state.value.copy(status = "ADB 命令帮助已写入日志")
                return
            }
            is AdbConsoleCommand.Local -> {
                if (parsed.name == "version") {
                    appendAdbTestLog("月虹 USB ADB Host v1.3")
                    _state.value = _state.value.copy(status = "ADB Host 版本信息已写入日志")
                    return
                }
            }
            else -> Unit
        }
        startAdbConsoleOperation(input) { client ->
            when (parsed) {
                is AdbConsoleCommand.Push -> {
                    val source = File(parsed.localHint)
                    require(source.isAbsolute) { "adb push 的本地文件必须使用绝对路径" }
                    require(source.isFile) { "本地文件不存在：${parsed.localHint}" }
                    require(source.canRead()) { "本地文件不可读取：${parsed.localHint}" }
                    pushConsoleFileWithRetry(client, source, parsed.remotePath)
                    AdbConsoleResult("推送完成：${source.absolutePath} -> ${parsed.remotePath}")
                }
                is AdbConsoleCommand.Shell -> {
                    val result = client.shell(parsed.command)
                    AdbConsoleResult(result.output.ifBlank { "（无输出）" }, result.exitCode)
                }
                is AdbConsoleCommand.Reboot -> {
                    client.reboot(parsed.target)
                    AdbConsoleResult(
                        "已发送重启命令${parsed.target.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}",
                        keepConnection = false,
                    )
                }
                is AdbConsoleCommand.Service -> {
                    val output = client.service(parsed.name)
                    AdbConsoleResult(
                        output.ifBlank { "服务 ${parsed.name} 已执行" },
                        keepConnection = false,
                    )
                }
                is AdbConsoleCommand.Local -> executeLocalAdbCommand(client, parsed.name)
                else -> throw IllegalArgumentException("ADB 命令分派失败")
            }
        }
    }

    fun pullAdbFile(uri: Uri, command: AdbConsoleCommand.Pull, displayCommand: String) {
        startAdbConsoleOperation(displayCommand) { client ->
            val output = app.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("本地保存位置无法写入")
            val transferred = output.use { destination ->
                client.pull(command.remotePath, destination) { bytes ->
                    publishAdbTransferProgress("正在拉取文件", bytes, -1L)
                }
            }
            AdbConsoleResult("拉取完成：${command.remotePath}，共 $transferred 字节")
        }
    }

    fun installAdbPackage(uri: Uri, command: AdbConsoleCommand.Install, displayCommand: String) {
        startAdbConsoleOperation(displayCommand) { client ->
            require(command.flags.all { it.length <= 128 && '\u0000' !in it }) { "APK 安装参数无效" }
            val remotePath = "/data/local/tmp/yh_adb_install_${System.nanoTime()}.apk"
            try {
                val totalBytes = contentLength(uri)
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IOException("所选 APK 无法读取")
                input.use { source ->
                    client.push(
                        source = source,
                        destination = remotePath,
                        totalBytes = totalBytes,
                        onLog = ::appendAdbTestLog,
                    ) { transferred, total ->
                        publishAdbTransferProgress("正在传输 APK", transferred, total)
                    }
                }
                val flags = command.flags.joinToString(" ") { shellQuote(it) }
                val result = client.shell("pm install $flags ${shellQuote(remotePath)}")
                AdbConsoleResult(result.output.ifBlank { "（无输出）" }, result.exitCode)
            } finally {
                runCatching { client.shell("rm -f -- ${shellQuote(remotePath)}") }
            }
        }
    }

    fun reportAdbCommandError(input: String, reason: String) {
        appendAdbTestLog("$ ${input.trim()}")
        appendAdbTestLog("失败：${reason.ifBlank { "ADB 命令无效" }}")
        _state.value = _state.value.copy(
            running = false,
            status = reason.ifBlank { "ADB 命令无效" },
            success = false,
        )
    }

    private fun startAdbConsoleOperation(
        displayCommand: String,
        operation: suspend (UsbAdbClient) -> AdbConsoleResult,
    ) {
        if (work?.isActive == true) return
        stopRequested = false
        work = viewModelScope.launch(Dispatchers.IO) {
            var client: UsbAdbClient? = null
            var keepConnection = true
            try {
                _state.value = _state.value.copy(
                    stage = VivoWiredStage.Connecting,
                    running = true,
                    status = if (consoleClient != null) {
                        "正在复用 USB ADB 连接执行命令"
                    } else {
                        "正在连接目标设备执行 ADB 命令"
                    },
                    transferProgress = null,
                    success = false,
                )
                appendAdbTestLog("$ ${displayCommand.trim()}")
                client = acquireConsoleClient()
                val result = operation(client)
                keepConnection = result.keepConnection
                appendAdbTestLog(result.output.ifBlank { "（无输出）" })
                appendAdbTestLog("退出码=${result.exitCode}")
                _state.value = _state.value.copy(
                    stage = VivoWiredStage.Idle,
                    running = false,
                    status = if (keepConnection) {
                        "ADB 命令执行完成，连接已保持（退出码=${result.exitCode}）"
                    } else {
                        "ADB 命令执行完成（退出码=${result.exitCode}）"
                    },
                    transferProgress = null,
                    success = result.exitCode == 0,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error is UsbAdbClient.TransportException ||
                    error is UsbAdbClient.ServiceRejectedException
                ) {
                    discardConsoleClient(client)
                }
                if (!stopRequested) {
                    val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
                    appendAdbTestLog("失败：$reason")
                    _state.value = _state.value.copy(
                        stage = VivoWiredStage.Idle,
                        running = false,
                        status = reason,
                        transferProgress = null,
                        success = false,
                    )
                }
            } finally {
                if (!keepConnection) discardConsoleClient(client)
                if (activeClient === client) activeClient = null
            }
        }
    }

    private suspend fun pushConsoleFileWithRetry(
        initialClient: UsbAdbClient,
        source: File,
        destination: String,
    ) {
        var client = initialClient
        var resumeRemoteParts = false
        val completedPartIndices = linkedSetOf<Int>()
        val deferredPartIndices = linkedSetOf<Int>()
        val partFailures = mutableMapOf<Int, Int>()
        var transportFailures = 0
        var reconnects = 0
        while (true) {
            try {
                client.push(
                    source = source,
                    destination = destination,
                    resumeRemoteParts = resumeRemoteParts,
                    completedPartIndices = completedPartIndices,
                    deferredPartIndices = deferredPartIndices,
                    onLog = ::appendAdbTestLog,
                ) { transferred, total ->
                    publishAdbTransferProgress("正在推送文件", transferred, total)
                }
                return
            } catch (error: UsbAdbClient.TransportException) {
                val interruptedPart = error.transferPartIndex
                if (interruptedPart != null) {
                    val failures = (partFailures[interruptedPart] ?: 0) + 1
                    partFailures[interruptedPart] = failures
                    deferredPartIndices += interruptedPart
                    appendAdbTestLog(
                        "分片${interruptedPart.toString().padStart(5, '0')} 已标记为延后处理，" +
                            "先继续后续分片（失败$failures/$SAME_PART_TRANSFER_ATTEMPTS）",
                    )
                    if (failures >= SAME_PART_TRANSFER_ATTEMPTS) throw error
                } else {
                    transportFailures += 1
                }
                reconnects += 1
                if (transportFailures >= SAME_PART_TRANSFER_ATTEMPTS || reconnects >= MAX_FILE_TRANSFER_RECONNECTS) {
                    throw error
                }
                appendAdbTestLog("USB ADB 传输中断，关闭失效会话并重新建立 ADB transport")
                discardConsoleClient(client)
                client = connectTarget(false)
                consoleClient = client
                activeClient = client
                appendAdbTestLog("ADB transport 已重新握手，继续未完成分片")
                resumeRemoteParts = true
            }
        }
    }

    private suspend fun acquireConsoleClient(): UsbAdbClient {
        consoleClient?.let { client ->
            activeClient = client
            appendAdbTestLog("复用现有 USB ADB 连接")
            return client
        }
        return connectTarget(false).also { client ->
            consoleClient = client
            appendAdbTestLog("USB ADB 连接已建立并保持")
        }
    }

    private fun discardConsoleClient(client: UsbAdbClient? = consoleClient) {
        if (client == null) return
        if (consoleClient === client) consoleClient = null
        if (activeClient === client) activeClient = null
        runCatching { client.close() }
    }

    private fun executeLocalAdbCommand(client: UsbAdbClient, command: String): AdbConsoleResult =
        when (command) {
            "devices" -> {
                val usb = client.device
                val manufacturer = runCatching { usb.manufacturerName }.getOrNull().orEmpty()
                val product = runCatching { usb.productName }.getOrNull().orEmpty()
                AdbConsoleResult("${usb.deviceName}\tdevice product:$product manufacturer:$manufacturer")
            }
            "get-state", "wait-for-device" -> AdbConsoleResult("device")
            "get-serialno" -> {
                val serial = runCatching { client.device.serialNumber }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: client.shell("getprop ro.serialno").output.trim()
                AdbConsoleResult(serial.ifBlank { "unknown" })
            }
            else -> throw IllegalArgumentException("未知的本地 ADB 命令：$command")
        }

    private fun startInternal(
        localRequest: VivoWiredLocalRequest? = null,
        bundledPayload: BundledPayload? = null,
    ) {
        require((localRequest == null) != (bundledPayload == null))
        if (work?.isActive == true) return
        stopRequested = false
        discardConsoleClient()
        if (_state.value.stage == VivoWiredStage.Done || _state.value.stage == VivoWiredStage.Failed) {
            escalator.reset()
        }
        work = viewModelScope.launch(Dispatchers.IO) {
            try {
                escalator.run(localRequest = localRequest, bundledPayload = bundledPayload)
            } finally {
                activeClient = null
            }
        }
    }

    fun stop() {
        val active = work ?: return
        if (!active.isActive) return
        stopRequested = true
        _state.value = _state.value.copy(
            stage = VivoWiredStage.Idle,
            running = false,
            status = "操作已停止",
            success = false,
        )
        runCatching { permissionWaiter?.cancel() }
        val client = activeClient
        if (client === consoleClient) {
            discardConsoleClient(client)
        } else {
            runCatching { client?.close() }
        }
        activeClient = null
        active.cancel()
    }

    fun clearLogs() = escalator.clearLogs()

    fun reset() {
        if (work?.isActive != true) {
            escalator.reset()
            refreshDeviceState()
        }
    }

    private fun refreshDeviceState() {
        val device = UsbAdbClient.findAdbDevices(usbManager).firstOrNull()
        val current = _state.value
        if (current.running) return
        if (stopRequested) return
        _state.value = current.copy(
            status = if (device != null) "已检测到有线 ADB 目标设备" else "请通过 OTG 连接 vivo/iQOO 目标设备",
            target = device?.let(::describeUsbDevice) ?: "尚未连接目标设备",
        )
    }

    private fun describeUsbDevice(device: UsbDevice): String {
        val manufacturer = runCatching { device.manufacturerName }.getOrNull().orEmpty().trim()
        val product = runCatching { device.productName }.getOrNull().orEmpty().trim()
        val identity = listOf(manufacturer, product).filter(String::isNotBlank).distinct().joinToString(" ")
        return "${identity.ifBlank { "USB ADB 设备" }} · USB=%04X:%04X".format(device.vendorId, device.productId)
    }

    private fun publishStatus(status: String) {
        if (!stopRequested) _state.value = _state.value.copy(status = status)
    }

    private fun appendAdbTestLog(message: String) {
        val added = message.lineSequence()
            .filter(String::isNotBlank)
            .map { "[ADB测试] $it" }
            .toList()
        _state.value = _state.value.copy(logs = (_state.value.logs + added).takeLast(MAX_LOG_LINES))
    }

    private fun publishAdbTransferProgress(label: String, transferredBytes: Long, totalBytes: Long) {
        val percent = if (totalBytes > 0L) {
            ((transferredBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
        _state.value = _state.value.copy(
            status = if (percent == null) "$label（$transferredBytes 字节）" else "$label（$percent%）",
            transferProgress = percent,
        )
    }

    private fun contentLength(uri: Uri): Long {
        val resolver = app.contentResolver
        val queried = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        if (queried >= 0L) return queried
        return runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
            .getOrDefault(-1L)
    }

    private suspend fun connectTarget(waitingAfterReboot: Boolean): UsbAdbClient {
        val timeout = if (waitingAfterReboot) RECONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS
        return withTimeout(timeout) {
            var lastError: Throwable? = null
            while (true) {
                val device = UsbAdbClient.findAdbDevices(usbManager).firstOrNull()
                if (device == null) {
                    lastUsbPermissionKey = null
                    lastUsbPermissionAt = 0L
                    publishStatus(if (waitingAfterReboot) "等待目标设备重启后重新连接" else "等待 OTG 连接目标设备")
                    delay(DEVICE_SCAN_INTERVAL_MS)
                    continue
                }
                val permissionKey = usbPermissionKey(device)
                if (!usbManager.hasPermission(device)) {
                    val permissionRecentlyGranted = lastUsbPermissionKey == permissionKey &&
                        SystemClock.elapsedRealtime() - lastUsbPermissionAt < USB_PERMISSION_REUSE_GRACE_MS
                    if (permissionRecentlyGranted) {
                        // Do not show Android's permission dialog again while a
                        // transport is being rebuilt for the same USB device.
                        // The open attempt below will succeed as soon as the
                        // framework finishes restoring the existing grant.
                        publishStatus("复用当前 USB 访问授权")
                    } else {
                        publishStatus("等待授予 USB 访问权限")
                        if (!requestUsbPermission(device.deviceId)) {
                            throw IOException("USB 访问权限未授予")
                        }
                        lastUsbPermissionKey = permissionKey
                        lastUsbPermissionAt = SystemClock.elapsedRealtime()
                    }
                } else {
                    lastUsbPermissionKey = permissionKey
                    lastUsbPermissionAt = SystemClock.elapsedRealtime()
                }
                val client = runCatching { UsbAdbClient(usbManager, device, adbKey, app.cacheDir) }
                    .getOrElse { error ->
                        lastError = error
                        delay(DEVICE_SCAN_INTERVAL_MS)
                        continue
                }
                try {
                    publishStatus("正在建立 USB ADB 连接，请确认目标设备调试授权")
                    client.connect()
                    // CNXN is only the transport handshake.  Probe the shell
                    // service before publishing a usable target; otherwise a
                    // rebooted adbd can look connected while every shell open
                    // is rejected on the stale transport.
                    val probe = client.shell("true")
                    if (probe.exitCode != 0) {
                        throw IOException("ADB Shell 探针退出码=${probe.exitCode}")
                    }
                    _state.value = _state.value.copy(target = client.connectionSummary())
                    activeClient = client
                    return@withTimeout client
                } catch (error: Throwable) {
                    lastError = error
                    if (error is UsbAdbClient.ServiceRejectedException) {
                        publishStatus("ADB 已握手，但 Shell 服务尚未就绪")
                    }
                    client.close()
                    delay(DEVICE_SCAN_INTERVAL_MS)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException(lastError?.message ?: "USB ADB 连接失败")
        }
    }

    private suspend fun requestUsbPermission(deviceId: Int): Boolean {
        val device = UsbAdbClient.findAdbDevices(usbManager).firstOrNull { it.deviceId == deviceId } ?: return false
        if (usbManager.hasPermission(device)) return true
        val waiter = CompletableDeferred<Boolean>()
        permissionWaiter?.cancel()
        permissionWaiter = waiter
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            app,
            USB_PERMISSION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
        return try {
            withTimeout(USB_PERMISSION_TIMEOUT_MS) { waiter.await() }
        } finally {
            if (permissionWaiter === waiter) permissionWaiter = null
        }
    }

    private fun usbPermissionKey(device: UsbDevice): String {
        return listOf(
            device.vendorId.toString(),
            device.productId.toString(),
        ).joinToString("|")
    }

    override fun onCleared() {
        val client = activeClient
        if (client != null && client !== consoleClient) runCatching { client.close() }
        discardConsoleClient()
        activeClient = null
        work?.cancel()
        permissionWaiter?.cancel()
        runCatching { app.unregisterReceiver(permissionReceiver) }
        escalator.close()
        super.onCleared()
    }

    private data class AdbConsoleResult(
        val output: String,
        val exitCode: Int = 0,
        val keepConnection: Boolean = true,
    )

    private companion object {
        const val ACTION_USB_PERMISSION = "roro.stellar.yuehong.action.USB_ADB_PERMISSION"
        const val USB_PERMISSION_REQUEST_CODE = 741
        const val DEVICE_SCAN_INTERVAL_MS = 1_000L
        const val USB_PERMISSION_TIMEOUT_MS = 60_000L
        const val USB_PERMISSION_REUSE_GRACE_MS = 15_000L
        const val INITIAL_CONNECT_TIMEOUT_MS = 120_000L
        const val RECONNECT_TIMEOUT_MS = 240_000L
        const val MAX_LOG_LINES = 1_500
        const val SAME_PART_TRANSFER_ATTEMPTS = 12
        const val MAX_FILE_TRANSFER_RECONNECTS = 64
        const val ADB_COMMAND_HELP = """支持命令：
adb shell <命令> / adb exec-out <命令>
adb push <本地文件> <目标路径>
adb pull <目标文件> [本地文件名]
adb install [参数] <APK> / adb uninstall [参数] <包名>
adb devices / get-state / get-serialno / wait-for-device
adb reboot [bootloader|recovery|fastboot|sideload]
adb root / unroot / remount / tcpip <端口> / usb
adb service <原始 adbd 服务名>
adb logcat / bugreport"""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VivoWiredScreen(viewModel: VivoWiredViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val bundledPayloads = remember(context) {
        runCatching { BundledPayloadCatalog.load(context) }.getOrDefault(emptyList())
    }
    var showPayloadPicker by remember { mutableStateOf(false) }
    var pendingLocalPayloadUri by remember { mutableStateOf<Uri?>(null) }
    var localPrivilegeCommand by rememberSaveable { mutableStateOf("") }
    var adbTestCommand by rememberSaveable { mutableStateOf("") }
    var adbToolsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingAdbFileCommand by remember { mutableStateOf<AdbConsoleCommand?>(null) }
    var pendingAdbDisplayCommand by remember { mutableStateOf("") }
    var pendingAdbPathCommand by rememberSaveable { mutableStateOf("") }
    val localPayloadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            localPrivilegeCommand = ""
            pendingLocalPayloadUri = uri
        }
    }
    val adbInputFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pending = pendingAdbFileCommand
        val display = pendingAdbDisplayCommand
        pendingAdbFileCommand = null
        pendingAdbDisplayCommand = ""
        if (uri != null) {
            when (pending) {
                is AdbConsoleCommand.Install -> viewModel.installAdbPackage(uri, pending, display)
                else -> Unit
            }
        }
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val command = pendingAdbPathCommand
        pendingAdbPathCommand = ""
        if (command.isNotBlank()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                viewModel.executeAdbCommand(command)
            } else {
                viewModel.reportAdbCommandError(command, "未授予本机文件访问权限")
            }
        }
    }
    val adbPullFileCreator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingAdbFileCommand as? AdbConsoleCommand.Pull
        val display = pendingAdbDisplayCommand
        pendingAdbFileCommand = null
        pendingAdbDisplayCommand = ""
        if (uri != null && pending != null) {
            viewModel.pullAdbFile(uri, pending, display)
        }
    }
    LaunchedEffect(state.logs.size) {
        if (state.logs.isEmpty()) return@LaunchedEffect
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (visibleLast == null || visibleLast >= state.logs.lastIndex - 1) {
            listState.scrollToItem(state.logs.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.wired_vivo_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "连接设备信息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.running) {
                        Spacer(Modifier.height(3.dp))
                        state.transferProgress?.let { percent ->
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (state.running) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    onClick = viewModel::stop,
                ) {
                    Text(stringResource(R.string.wired_vivo_stop))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f).height(48.dp),
                        onClick = { showPayloadPicker = true },
                    ) {
                        Text(
                            if (state.stage == VivoWiredStage.Failed) {
                                stringResource(R.string.wired_vivo_retry)
                            } else {
                                stringResource(R.string.wired_vivo_start)
                            },
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f).height(48.dp),
                        onClick = { localPayloadPicker.launch(arrayOf("*/*")) },
                    ) {
                        Text(stringResource(R.string.use_local_payload))
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { adbToolsExpanded = !adbToolsExpanded },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = "高级 ADB 工具",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(if (adbToolsExpanded) "▲" else "▼")
                        }
                    }
                    if (adbToolsExpanded) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = adbTestCommand,
                                onValueChange = { adbTestCommand = it },
                                enabled = !state.running,
                                singleLine = true,
                                label = { Text("ADB 命令") },
                            )
                            Button(
                                modifier = Modifier.height(56.dp),
                                enabled = !state.running && adbTestCommand.isNotBlank(),
                                onClick = {
                                    val parsed = runCatching { parseAdbConsoleCommand(adbTestCommand) }
                                        .getOrElse { error ->
                                            viewModel.reportAdbCommandError(adbTestCommand, error.message.orEmpty())
                                            return@Button
                                        }
                                    when (parsed) {
                                        is AdbConsoleCommand.Install,
                                        -> {
                                            pendingAdbFileCommand = parsed
                                            pendingAdbDisplayCommand = adbTestCommand
                                            adbInputFilePicker.launch(arrayOf("*/*"))
                                        }
                                        is AdbConsoleCommand.Pull -> {
                                            pendingAdbFileCommand = parsed
                                            pendingAdbDisplayCommand = adbTestCommand
                                            adbPullFileCreator.launch(parsed.localName)
                                        }
                                        is AdbConsoleCommand.Push -> {
                                            val localFile = File(parsed.localHint)
                                            if (localFile.isAbsolute && localFile.isFile && localFile.canRead()) {
                                                viewModel.executeAdbCommand(adbTestCommand)
                                            } else if (
                                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                                !Environment.isExternalStorageManager()
                                            ) {
                                                pendingAdbPathCommand = adbTestCommand
                                                val appSettings = Intent(
                                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                                runCatching { allFilesAccessLauncher.launch(appSettings) }
                                                    .getOrElse {
                                                        val globalSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                        runCatching { allFilesAccessLauncher.launch(globalSettings) }
                                                            .getOrElse { error ->
                                                                pendingAdbPathCommand = ""
                                                                viewModel.reportAdbCommandError(
                                                                    adbTestCommand,
                                                                    error.message ?: "无法打开本机文件访问设置",
                                                                )
                                                            }
                                                    }
                                            } else {
                                                viewModel.executeAdbCommand(adbTestCommand)
                                            }
                                        }
                                        else -> viewModel.executeAdbCommand(adbTestCommand)
                                    }
                                },
                            ) {
                                Text("执行")
                            }
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.wired_vivo_log),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            enabled = state.logs.isNotEmpty(),
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("vivo/iQOO wired log", state.logs.joinToString("\n")),
                                )
                            },
                        ) {
                            Text(stringResource(R.string.wired_vivo_copy))
                        }
                        TextButton(
                            enabled = state.logs.isNotEmpty() && !state.running,
                            onClick = viewModel::clearLogs,
                        ) {
                            Text(stringResource(R.string.wired_vivo_clear))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (state.logs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.wired_vivo_log_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = listState,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    items(state.logs) { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingLocalPayloadUri?.let { uri ->
        LocalPayloadCommandDialog(
            command = localPrivilegeCommand,
            onCommandChange = { localPrivilegeCommand = it },
            onConfirm = {
                val command = localPrivilegeCommand.trim()
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
                viewModel.startWithLocalPayload(uri, command)
            },
            onDismiss = {
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
            },
        )
    }

    if (showPayloadPicker) {
        BundledPayloadDialog(
            payloads = bundledPayloads,
            onSelect = {
                showPayloadPicker = false
                viewModel.startWithBundledPayload(it)
            },
            onDismiss = { showPayloadPicker = false },
        )
    }
}

/** The OTG privilege actions used by the unified workbench (no standalone page or ADB toolbox). */
@Composable
internal fun VivoWiredWorkbenchControls(viewModel: VivoWiredViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bundledPayloads = remember(context) {
        runCatching { BundledPayloadCatalog.load(context) }.getOrDefault(emptyList())
    }
    var showPayloadPicker by remember { mutableStateOf(false) }
    var pendingLocalPayloadUri by remember { mutableStateOf<Uri?>(null) }
    var localPrivilegeCommand by rememberSaveable { mutableStateOf("") }
    val localPayloadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            localPrivilegeCommand = ""
            pendingLocalPayloadUri = uri
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (state.target == "尚未读取目标设备") state.status else "${state.target} · ${state.status}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (state.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(46.dp),
                onClick = viewModel::stop,
            ) { Text(stringResource(R.string.wired_vivo_stop)) }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    onClick = { showPayloadPicker = true },
                ) {
                    Text(
                        if (state.stage == VivoWiredStage.Failed) {
                            stringResource(R.string.wired_vivo_retry)
                        } else {
                            stringResource(R.string.wired_vivo_start)
                        },
                    )
                }
                androidx.compose.material3.OutlinedButton(
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    onClick = { localPayloadPicker.launch(arrayOf("*/*")) },
                ) { Text(stringResource(R.string.use_local_payload)) }
            }
        }
    }

    pendingLocalPayloadUri?.let { uri ->
        LocalPayloadCommandDialog(
            command = localPrivilegeCommand,
            onCommandChange = { localPrivilegeCommand = it },
            onConfirm = {
                val command = localPrivilegeCommand.trim()
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
                viewModel.startWithLocalPayload(uri, command)
            },
            onDismiss = {
                pendingLocalPayloadUri = null
                localPrivilegeCommand = ""
            },
        )
    }

    if (showPayloadPicker) {
        BundledPayloadDialog(
            payloads = bundledPayloads,
            onSelect = {
                showPayloadPicker = false
                viewModel.startWithBundledPayload(it)
            },
            onDismiss = { showPayloadPicker = false },
        )
    }
}
