package roro.stellar.manager.ui.features.wired

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import roro.stellar.manager.adb.UsbAdbClient
import roro.stellar.yuehong.shell.DeviceProfile
import roro.stellar.yuehong.shell.BundledPayload
import roro.stellar.yuehong.shell.BundledPayloadCatalog
import roro.stellar.yuehong.shell.KernelSuManagerSelection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal enum class VivoWiredStage {
    Idle,
    Connecting,
    CollectingDeviceInfo,
    DownloadingPayload,
    TransferringPayload,
    Rebooting,
    Reconnecting,
    WaitingForBoot,
    ExecutingPayload,
    CheckingRoot,
    ActivatingJailbreak,
    Done,
    Failed,
}

internal data class VivoWiredProgress(
    val stage: VivoWiredStage = VivoWiredStage.Idle,
    val running: Boolean = false,
    val status: String = "等待连接 vivo/iQOO 目标设备",
    val target: String = "尚未读取目标设备",
    val transferProgress: Int? = null,
    val logs: List<String> = emptyList(),
    val success: Boolean = false,
)

internal data class VivoWiredLocalRequest(
    val uri: Uri,
    val command: String,
)

/** USB-host ADB privilege flow using a bundled privilege file or a user-selected local file. */
internal class VivoWiredEscalator(
    context: Context,
    private val connectTarget: suspend (waitingAfterReboot: Boolean) -> UsbAdbClient,
    private val publish: (VivoWiredProgress) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val activationLabel = "KernelSU"
    private var state = VivoWiredProgress()

    suspend fun run(
        localRequest: VivoWiredLocalRequest? = null,
        bundledPayload: BundledPayload? = null,
    ) {
        require((localRequest == null) != (bundledPayload == null)) { "必须选择内置载荷或本地文件" }
        var client: UsbAdbClient? = null
        var payloadFile: File? = null
        try {
            update(VivoWiredStage.Connecting, "正在连接目标设备")
            client = connectTarget(false)

            update(VivoWiredStage.CollectingDeviceInfo, "正在读取目标设备信息")
            val profile = collectTargetProfile(client)
            if (localRequest == null) {
                requireVivo(profile)
            } else {
                log("本地提权文件模式跳过 vivo/iQOO 品牌检查")
            }
            setTarget(profile)
            log("已确认目标设备：${profile.brandName} ${profile.modelName}")
            log("型号=${profile.modelName}")
            log("系统=${profile.systemVersion}")
            log("内核=${profile.kernelVersion}")

            val payloadCommand: String
            val suPath: String
            if (bundledPayload != null) {
                validateCommand(bundledPayload.command)
                update(VivoWiredStage.DownloadingPayload, "正在读取内置提权文件")
                val source = BundledPayloadCatalog.fileFor(appContext, bundledPayload)
                    ?: throw IOException("内置提权文件校验失败")
                payloadFile = copyBundledPayload(source)
                payloadCommand = runtimeBundledCommand(bundledPayload)
                suPath = LOCAL_PAYLOAD_SU_PATH
                log("已选择内置提权文件：${bundledPayload.library}")
            } else {
                validateCommand(localRequest!!.command, local = true)
                update(VivoWiredStage.DownloadingPayload, "正在读取本地提权文件")
                payloadFile = copyLocalPayload(localRequest.uri)
                payloadCommand = localRequest.command
                suPath = LOCAL_PAYLOAD_SU_PATH
                log("已读取本地提权文件，不请求服务器")
            }

            update(VivoWiredStage.TransferringPayload, "正在传输提权文件")
            client.shell("rm -f -- '$REMOTE_PAYLOAD'")
            client = pushFileWithRetry(client, payloadFile, REMOTE_PAYLOAD)
            val installCheck = client.shell("chmod 0755 '$REMOTE_PAYLOAD' && test -f '$REMOTE_PAYLOAD' && test -x '$REMOTE_PAYLOAD'")
            if (installCheck.exitCode != 0) throw IOException("目标设备提权文件安装失败：${installCheck.output}")
            log("提权文件已写入目标设备：$REMOTE_PAYLOAD")

            update(VivoWiredStage.Rebooting, "正在重启目标设备")
            log("发送目标设备重启命令")
            try {
                client.reboot()
            } catch (error: IOException) {
                // A successful reboot commonly disconnects USB before adbd can return a close frame.
                log("目标设备已进入重启阶段：${error.message.orEmpty()}")
            }
            client.close()
            client = null

            update(VivoWiredStage.Reconnecting, "等待目标设备重新连接")
            delay(REBOOT_DISCONNECT_GRACE_MS)
            client = connectTarget(true)
            log("目标设备 ADB 已重新连接")

            update(VivoWiredStage.WaitingForBoot, "等待目标设备完成启动")
            client = waitForBootCompleted(client)
            log("目标设备系统启动完成")
            update(VivoWiredStage.WaitingForBoot, "目标设备已启动，等待 5 秒")
            log("目标设备完全开机，等待 5 秒后执行提权命令")
            delay(POST_BOOT_COMMAND_DELAY_MS)

            val commandSource = if (bundledPayload != null) "内置" else "本地"
            update(VivoWiredStage.ExecutingPayload, "正在执行${commandSource}提权命令")
            log("开始执行${commandSource}提权命令：$payloadCommand")
            val execution = client.shell(payloadCommand)
            log("${commandSource}提权命令输出：${execution.output.trim().ifBlank { "<空>" }}")
            log("${commandSource}提权命令退出码=${execution.exitCode}")
            if (execution.exitCode != 0) {
                if (bundledPayload != null) {
                    throw IOException("内置提权命令退出码=${execution.exitCode}")
                }
                log("本地提权命令退出码=${execution.exitCode}，继续检查临时 root")
            }

            log("${commandSource}提权命令执行结束，开始检查临时 root")
            update(VivoWiredStage.CheckingRoot, "正在确认临时 root")
            val rootCheck = client.shell("$suPath -c id")
            if (!ROOT_UID_PATTERN.containsMatchIn(rootCheck.output)) {
                throw IOException("临时 root 检查未返回 uid=0：${rootCheck.output}")
            }
            log("目标设备临时 root 已确认")

            update(VivoWiredStage.ActivatingJailbreak, "正在传输 $activationLabel 激活脚本")
            val activationScript = copyActivationScript()
            try {
                client = pushFileWithRetry(
                    initialClient = client,
                    source = activationScript,
                    destination = ACTIVATION_SCRIPT_DESTINATION,
                    mode = ACTIVATION_SCRIPT_MODE,
                )
            } finally {
                activationScript.delete()
            }
            update(VivoWiredStage.ActivatingJailbreak, "正在激活 $activationLabel")
            val managerPackage = KernelSuManagerSelection.selectedPackage(appContext)
            val manager = KernelSuManagerSelection.selectedManagerTarget(appContext)
                ?.takeIf { it.packageName == managerPackage }
                ?: throw IOException(
                    "未找到所选 KernelSU 管理器 $managerPackage 的兼容 ksud",
                )
            val targetKsudPath = resolveTargetKsudPath(client, manager.libKsudPath, managerPackage)
            log("已选择 KernelSU 管理器包名：$managerPackage")
            log("目标设备 ksud 路径：$targetKsudPath")
            val activationCommand = "YHROOT_KSUD=${shellQuote(targetKsudPath)} " +
                "YHROOT_KSU_PACKAGE=${shellQuote(managerPackage)} " +
                "/system/bin/sh ${shellQuote(ACTIVATION_SCRIPT_DESTINATION)}"
            val activation = client.shell(
                "chmod 0700 ${shellQuote(ACTIVATION_SCRIPT_DESTINATION)} && " +
                    "${shellQuote(suPath)} -c ${shellQuote(activationCommand)}",
            )
            if (activation.output.isNotBlank()) log(activation.output)
            if (activation.exitCode != 0) {
                throw IOException("激活脚本退出码=${activation.exitCode}")
            }

            client.shell("rm -f -- '$REMOTE_PAYLOAD'")
            state = state.copy(
                stage = VivoWiredStage.Done,
                running = false,
                status = "目标设备提权完成",
                success = true,
            )
            log("vivo/iQOO 有线提权流程完成")
        } catch (cancelled: CancellationException) {
            state = state.copy(
                stage = VivoWiredStage.Idle,
                running = false,
                status = "操作已停止",
                success = false,
            )
            log("操作已停止")
            throw cancelled
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            state = state.copy(
                stage = VivoWiredStage.Failed,
                running = false,
                status = reason,
                success = false,
            )
            log("失败：$reason")
        } finally {
            payloadFile?.delete()
            runCatching { client?.shell("rm -f -- '$REMOTE_PAYLOAD'") }
            runCatching { client?.close() }
            publish(state)
        }
    }

    private suspend fun pushFileWithRetry(
        initialClient: UsbAdbClient,
        source: File,
        destination: String,
        mode: Int? = null,
    ): UsbAdbClient {
        var client = initialClient
        var resumeRemoteParts = false
        val completedPartIndices = linkedSetOf<Int>()
        val deferredPartIndices = linkedSetOf<Int>()
        val partFailures = mutableMapOf<Int, Int>()
        var transportFailures = 0
        var reconnects = 0
        while (true) {
            try {
                if (mode == null) {
                    client.push(
                        source = source,
                        destination = destination,
                        resumeRemoteParts = resumeRemoteParts,
                        completedPartIndices = completedPartIndices,
                        deferredPartIndices = deferredPartIndices,
                        onLog = ::log,
                        onProgress = ::publishTransferProgress,
                    )
                } else {
                    client.push(
                        source = source,
                        destination = destination,
                        mode = mode,
                        resumeRemoteParts = resumeRemoteParts,
                        completedPartIndices = completedPartIndices,
                        deferredPartIndices = deferredPartIndices,
                        onLog = ::log,
                        onProgress = ::publishTransferProgress,
                    )
                }
                return client
            } catch (error: UsbAdbClient.TransportException) {
                val interruptedPart = error.transferPartIndex
                if (interruptedPart != null) {
                    val failures = (partFailures[interruptedPart] ?: 0) + 1
                    partFailures[interruptedPart] = failures
                    deferredPartIndices += interruptedPart
                    log(
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
                log("USB ADB 传输中断，关闭失效会话并重新建立 ADB transport")
                runCatching { client.close() }
                client = connectTarget(false)
                log("ADB transport 已重新握手，继续未完成分片")
                resumeRemoteParts = true
            }
        }
    }

    /** Resolve the same recognized library name inside the selected package on the USB target. */
    private suspend fun resolveTargetKsudPath(
        client: UsbAdbClient,
        recognizedPath: String,
        managerPackage: String,
    ): String {
        val packagePaths = client.shell("pm path ${shellQuote(managerPackage)}")
        if (packagePaths.exitCode != 0) {
            throw IOException("目标设备未安装所选管理器 $managerPackage：${packagePaths.output.trim()}")
        }
        val apkPath = packagePaths.output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("package:") && it.endsWith(".apk") }
            ?.removePrefix("package:")
            ?: throw IOException("无法读取目标设备所选管理器 $managerPackage 的 APK 路径")
        val appDirectory = apkPath.substringBeforeLast('/', "")
        val appDirectoryParts = appDirectory.removePrefix("/data/app/").split('/')
        if (!appDirectory.startsWith("/data/app/") || appDirectoryParts.size != 2) {
            throw IOException("目标设备 APK 不在受支持的 /data/app/<安装目录>/<包名目录> 路径中：$apkPath")
        }
        if (appDirectoryParts.last().contains("android", ignoreCase = true)) {
            throw IOException("目标设备管理器包名目录包含 android：${appDirectoryParts.last()}")
        }
        val libraryName = recognizedPath.substringAfterLast('/')
        val targetPath = "$appDirectory/lib/arm64/$libraryName"
        val probe = client.shell(
            "test -x ${shellQuote(targetPath)} && ${shellQuote(targetPath)} late-load --help 2>&1",
        )
        val expectedHelp = listOf("late-load", "--kmi", "--allow-shell", "--package-name")
        if (probe.exitCode != 0 || expectedHelp.any { it !in probe.output }) {
            throw IOException(
                "目标设备没有所选管理器 $managerPackage 对应的兼容 ksud：$targetPath",
            )
        }
        return targetPath
    }

    fun reset() {
        state = VivoWiredProgress()
        publish(state)
    }

    fun clearLogs() {
        state = state.copy(logs = emptyList())
        publish(state)
    }

    private suspend fun waitForBootCompleted(initialClient: UsbAdbClient): UsbAdbClient {
        var client = initialClient
        var transportRebuilt = false
        var bootValueLogged = false
        var lastShellError: IOException? = null
        repeat(BOOT_WAIT_ATTEMPTS) {
            val result = try {
                client.shell("getprop sys.boot_completed")
            } catch (error: UsbAdbClient.ServiceRejectedException) {
                lastShellError = error
                if (transportRebuilt) {
                    throw IOException("ADB 已完成握手，但 Shell 服务仍拒绝请求（A_CLSE）", error)
                }
                log("ADB 已完成握手，但当前 transport 拒绝 Shell；重建 ADB transport")
                client.close()
                client = connectTarget(true)
                transportRebuilt = true
                log("已建立新的 ADB transport，继续读取启动状态")
                return@repeat
            } catch (error: UsbAdbClient.TransportException) {
                lastShellError = error
                if (transportRebuilt) {
                    throw IOException("ADB transport 在启动检查期间再次断开", error)
                }
                log("ADB transport 已断开；重建 ADB transport 后继续读取启动状态")
                client.close()
                client = connectTarget(true)
                transportRebuilt = true
                return@repeat
            } catch (error: IOException) {
                lastShellError = error
                throw error
            }
            val bootValue = result.output.trim()
            if (bootValue == "1") return client
            if (!bootValueLogged) {
                log("sys.boot_completed=$bootValue，设备仍在启动")
                bootValueLogged = true
            }
            delay(BOOT_WAIT_INTERVAL_MS)
        }
        val detail = lastShellError?.message?.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
        throw IOException("等待目标设备启动超时$detail")
    }

    private suspend fun collectTargetProfile(client: UsbAdbClient): DeviceProfile {
        val properties = parseGetProp(shellWithRetry(client, "getprop", "设备属性").output)
        fun first(vararg names: String): String = names.firstNotNullOfOrNull { name ->
            properties[name]?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()

        val brand = first("ro.product.brand", "ro.product.manufacturer", "ro.product.vendor.brand")
        val model = first(
            "ro.vivo.market.name",
            "ro.vivo.product.model",
            "ro.product.marketname",
            "ro.product.model",
            "ro.product.vendor.model",
        )
        val system = first(
            "ro.build.version.bbk",
            "ro.vivo.os.build.display.id",
            "ro.vivo.rom.version",
            "ro.vivo.os.version",
            "ro.build.version.incremental",
            "ro.build.display.id",
        )
        val kernel = shellWithRetry(client, "uname -r", "内核信息").output.trim()
        if (brand.isBlank() || model.isBlank() || system.isBlank() || kernel.isBlank()) {
            throw IOException("目标设备信息读取不完整")
        }
        return DeviceProfile(brand, model, system, kernel)
    }

    private suspend fun shellWithRetry(
        client: UsbAdbClient,
        command: String,
        description: String,
    ): UsbAdbClient.CommandResult {
        var lastError: IOException? = null
        repeat(SHELL_RETRY_ATTEMPTS) { attempt ->
            try {
                return client.shell(command)
            } catch (error: IOException) {
                lastError = error
                if (attempt == 0) {
                    log("目标设备 ADB Shell 尚未就绪，等待${description}服务")
                }
                delay(SHELL_RETRY_INTERVAL_MS)
            }
        }
        val detail = lastError?.message?.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
        throw IOException("ADB Shell 服务未就绪$detail")
    }

    private fun parseGetProp(output: String): Map<String, String> = buildMap {
        output.lineSequence().forEach { line ->
            val match = GETPROP_LINE.matchEntire(line.trim()) ?: return@forEach
            put(match.groupValues[1], match.groupValues[2])
        }
    }

    private fun requireVivo(profile: DeviceProfile) {
        val identity = "${profile.brandName} ${profile.modelName}".lowercase()
        if (VIVO_IDENTITIES.none(identity::contains)) {
            throw IOException("该入口仅支持 vivo/iQOO 目标设备")
        }
    }

    private fun validateCommand(command: String, local: Boolean = false) {
        if (command.isBlank() || command.toByteArray().size > MAX_COMMAND_BYTES ||
            command.indexOf('\u0000') >= 0 || FORBIDDEN_COMMAND_PATTERN.containsMatchIn(command)
        ) {
            throw IOException(if (local) "本地提权命令无效" else "内置提权命令无效")
        }
    }


    private fun runtimeBundledCommand(payload: BundledPayload): String {
        val command = payload.command
            .replace("<payload>", REMOTE_PAYLOAD)
            .replace("/data/local/tmp/preload.so", REMOTE_PAYLOAD)
            .replace("/data/local/tmp/mi_mt6895_43499", REMOTE_PAYLOAD)
            .replace("<su>", "/system/bin/id")
        return if (command.contains("<") || command.contains(">")) {
            "LD_PRELOAD=$REMOTE_PAYLOAD /system/bin/id"
        } else {
            command
        }
    }

    private fun copyActivationScript(): File {
        val target = File.createTempFile("yhroot-kernelsu-", ".sh", appContext.cacheDir)
        return try {
            appContext.assets.open(ACTIVATION_SCRIPT_ASSET).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun copyBundledPayload(source: File): File {
        val target = File.createTempFile("yhroot-wired-bundled-", ".bin", appContext.cacheDir)
        return try {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun copyLocalPayload(uri: Uri): File {
        val target = File.createTempFile("yhroot-wired-local-", ".bin", appContext.cacheDir)
        return try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("本地提权文件无法读取")
            var total = 0L
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_RESOURCE_BYTES) {
                            throw IOException("本地提权文件超过大小上限")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) throw IOException("本地提权文件为空")
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun update(stage: VivoWiredStage, status: String) {
        state = state.copy(
            stage = stage,
            running = true,
            status = status,
            transferProgress = null,
            success = false,
        )
        publish(state)
    }

    private fun publishTransferProgress(transferredBytes: Long, totalBytes: Long) {
        if (totalBytes <= 0L) return
        val percent = ((transferredBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes)
            .toInt()
            .coerceIn(0, 100)
        if (state.transferProgress == percent) return
        state = state.copy(
            transferProgress = percent,
            status = when (state.stage) {
                VivoWiredStage.ActivatingJailbreak -> "正在传输 $activationLabel 激活脚本（$percent%）"
                else -> "正在传输提权文件（$percent%）"
            },
        )
        publish(state)
    }

    private fun setTarget(profile: DeviceProfile) {
        state = state.copy(target = "${profile.brandName} ${profile.modelName} · ${profile.systemVersion}")
        publish(state)
    }

    private fun log(message: String) {
        val added = message.lineSequence().filter(String::isNotBlank).map { "[有线提权] $it" }.toList()
        state = state.copy(logs = (state.logs + added).takeLast(MAX_LOG_LINES))
        publish(state)
    }

    override fun close() = Unit

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        val GETPROP_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
        val VIVO_IDENTITIES = listOf("vivo", "iqoo", "bbk")
        val ROOT_UID_PATTERN = Regex("(?:^|\\s)uid=0(?:\\(root\\))?(?:\\s|\\z)")
        val FORBIDDEN_COMMAND_PATTERN = Regex("[;\\r\\n&]|\\|\\|")
        const val MAX_COMMAND_BYTES = 1024
        const val MAX_RESOURCE_BYTES = 512L * 1024L * 1024L
        const val MAX_LOG_LINES = 1_500
        const val BOOT_WAIT_ATTEMPTS = 90
        const val BOOT_WAIT_INTERVAL_MS = 2_000L
        const val SHELL_RETRY_ATTEMPTS = 30
        const val SHELL_RETRY_INTERVAL_MS = 1_000L
        const val SAME_PART_TRANSFER_ATTEMPTS = 12
        const val MAX_FILE_TRANSFER_RECONNECTS = 64
        const val REBOOT_DISCONNECT_GRACE_MS = 3_000L
        const val POST_BOOT_COMMAND_DELAY_MS = 5_000L
        const val REMOTE_PAYLOAD = "/data/local/tmp/preload.so"
        const val LOCAL_PAYLOAD_SU_PATH = "/data/local/tmp/su"
        const val ACTIVATION_SCRIPT_ASSET = "yhroot_ksu_activate.sh"
        const val ACTIVATION_SCRIPT_DESTINATION = "/data/local/tmp/yhroot_ksu_activate.sh"
        const val ACTIVATION_SCRIPT_MODE = 0x81C0
    }
}
