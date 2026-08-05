package `in`.hridayan.ashell.shell

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 提权流程的各个阶段
enum class EscalationStage {
    Idle,
    CheckingPermission,
    CollectingDeviceInfo,
    ReportingToServer,
    ValidatingCommand,
    ExecutingPayload,
    CheckingRoot,
    ActivatingKernelSu,
    Done,
}

// 提权结果
sealed interface EscalationResult {
    data object Idle : EscalationResult
    data class RootConfirmed(val output: String, val suPath: String) : EscalationResult
    data class KernelSuActivated(val output: String) : EscalationResult
    data class KernelSuActivationFailure(val reason: String) : EscalationResult
    data class Failure(val stage: EscalationStage, val reason: String) : EscalationResult
}

// 提权编排器：串联 Shizuku权限检查、设备信息上报、命令校验、命令执行、结果检测
class PrivilegeEscalator(
    private val shell: ShizukuShellController,
    private val compatibilityApi: CompatibilityApi,
) : AutoCloseable {
    var stage by mutableStateOf(EscalationStage.Idle)
        private set

    var result by mutableStateOf<EscalationResult>(EscalationResult.Idle)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        if (stage != EscalationStage.Idle && stage != EscalationStage.Done) return

        resetState()
        log("starting privilege escalation")
        updateStage(EscalationStage.CheckingPermission)
        shell.ensurePermission(
            onGranted = { executor.execute { runFlow() } },
            onDenied = ::cancelForMissingPermission,
        )
    }

    fun reset() {
        resetState()
    }

    fun activateKernelSu() {
        mainHandler.post {
            val rootResult = result as? EscalationResult.RootConfirmed ?: return@post
            if (stage != EscalationStage.Done) return@post
            stage = EscalationStage.ActivatingKernelSu
            result = EscalationResult.Idle
            executor.execute { runKernelSuActivation(rootResult.suPath) }
        }
    }

    private fun resetState() {
        mainHandler.post {
            stage = EscalationStage.Idle
            result = EscalationResult.Idle
        }
    }

    // ShizukuShellController 会显示专用权限提示；这里结束流程但不再生成通用失败结果，
    // 避免"需要 Shizuku 权限"和"提权失败"两个弹窗同时出现。
    private fun cancelForMissingPermission() {
        resetState()
    }

    private fun runFlow() {
        try {
            // 1. 收集设备信息
            updateStage(EscalationStage.CollectingDeviceInfo)
            val profile = DeviceProfile.collect()
            log("model=${profile.model}, system=${profile.systemVersion}, kernel=${profile.kernelVersion}")

            // 2. 上报到服务器，拿服务端下发的提权命令
            updateStage(EscalationStage.ReportingToServer)
            val payload = reportProfile(profile)
            log("server returned payloadCommand for payloadId=${payload.payloadId}")

            // 3. 客户端二次校验命令格式（与服务端校验保持一致）
            updateStage(EscalationStage.ValidatingCommand)
            val validatedCommand = validateCommand(payload.command)
            if (validatedCommand.isEmpty()) {
                fail(EscalationStage.ValidatingCommand, "server returned invalid payloadCommand format")
                return
            }
            val validatedSuPath = validateSuPath(payload.suPath)
            if (validatedSuPath.isEmpty()) {
                fail(EscalationStage.ValidatingCommand, "server returned invalid suPath")
                return
            }
            log("payloadCommand validated as a single device-specific shell command")
            log("su path validated: $validatedSuPath")

            // 4. 通过 Shizuku shell 执行服务器返回的设备专属提权命令。
            //    此时不能要求 su 已经可用，因为该命令本身负责完成提权。
            updateStage(EscalationStage.ExecutingPayload)
            val execution = executeCommand(validatedCommand)
            log("server command output: ${execution.output.trim()}")
            log("server command exit code=${execution.exitCode}")
            if (execution.exitCode != 0) {
                fail(EscalationStage.ExecutingPayload, "server command exited with code ${execution.exitCode}")
                return
            }

            // 5. 不能只相信提权命令的 exit code；通过 su 执行 id，并且必须看到 uid=0。
            updateStage(EscalationStage.CheckingRoot)
            val rootCheck = executeSuCommand(validatedSuPath, ROOT_CHECK_INNER_COMMAND)
            val rootOutput = rootCheck.output.trim()
            val isRoot = rootCheck.exitCode == 0 && ROOT_UID_PATTERN.containsMatchIn(rootOutput)
            log("root check output: $rootOutput")
            log("root detected=$isRoot")

            updateStage(EscalationStage.Done)
            mainHandler.post {
                result = if (isRoot) {
                    EscalationResult.RootConfirmed(
                        execution.output.trim().ifBlank { rootOutput },
                        validatedSuPath,
                    )
                } else {
                    EscalationResult.Failure(
                        EscalationStage.CheckingRoot,
                        "uid=0 was not confirmed after server command (exit=${rootCheck.exitCode})",
                    )
                }
            }
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            fail(stage, reason)
        }
    }

    private fun runKernelSuActivation(suPath: String) {
        val outputs = mutableListOf<String>()
        var failure: Throwable? = null
        var airplaneModeTouched = false
        try {
            runRequiredCustomSuStep("KernelSU late-load", suPath, KERNEL_SU_LATE_LOAD_INNER_COMMAND, outputs)
            // late-load 成功后由 KernelSU 接管，后续命令使用 PATH 中的 KernelSU su。
            runRequiredKernelSuStep("SELinux load_policy", KERNEL_SU_LOAD_POLICY_INNER_COMMAND, outputs)

            // 飞行模式必须逐步执行，不能用 ;/&& 拼成一条命令；finally 会无条件尝试关闭。
            airplaneModeTouched = true
            runRequiredKernelSuStep("airplane mode setting on", AIRPLANE_MODE_SETTING_ON_INNER_COMMAND, outputs)
            runRequiredKernelSuStep("airplane mode broadcast on", AIRPLANE_MODE_BROADCAST_ON_INNER_COMMAND, outputs)
            Thread.sleep(AIRPLANE_MODE_DURATION_MS)
        } catch (error: Throwable) {
            failure = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            if (airplaneModeTouched) {
                val offErrors = mutableListOf<String>()
                listOf(
                    "airplane mode setting off" to AIRPLANE_MODE_SETTING_OFF_INNER_COMMAND,
                    "airplane mode broadcast off" to AIRPLANE_MODE_BROADCAST_OFF_INNER_COMMAND,
                ).forEach { (label, innerCommand) ->
                    runCatching { executeKernelSuCommand(innerCommand) }
                        .onSuccess { execution ->
                            log("$label exit code=${execution.exitCode}")
                            if (execution.output.isNotBlank()) outputs += execution.output.trim()
                            if (execution.exitCode != 0) offErrors += "$label exited with code ${execution.exitCode}"
                        }
                        .onFailure { error -> offErrors += "$label failed: ${error.message ?: error.javaClass.simpleName}" }
                }
                if (offErrors.isNotEmpty()) {
                    val offFailure = IOException(offErrors.joinToString("; "))
                    if (failure == null) failure = offFailure
                    else shell.appendOutput("[飞行模式恢复失败] ${offFailure.message}", isError = true)
                }
            }
        }

        val activationFailure = failure
        if (activationFailure != null) {
            val reason = activationFailure.message?.takeIf(String::isNotBlank)
                ?: activationFailure.javaClass.simpleName
            shell.appendOutput("[KernelSU 激活失败] $reason", isError = true)
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivationFailure(reason)
            }
        } else {
            mainHandler.post {
                stage = EscalationStage.Done
                result = EscalationResult.KernelSuActivated(outputs.joinToString("\n"))
            }
        }
    }

    private fun runRequiredCustomSuStep(
        label: String,
        suPath: String,
        innerCommand: String,
        outputs: MutableList<String>,
    ) {
        log("running $label")
        recordRequiredStep(label, executeSuCommand(suPath, innerCommand), outputs)
    }

    private fun runRequiredKernelSuStep(
        label: String,
        innerCommand: String,
        outputs: MutableList<String>,
    ) {
        log("running $label")
        recordRequiredStep(label, executeKernelSuCommand(innerCommand), outputs)
    }

    private fun recordRequiredStep(
        label: String,
        execution: CommandExecution,
        outputs: MutableList<String>,
    ) {
        log("$label exit code=${execution.exitCode}")
        if (execution.output.isNotBlank()) {
            val output = execution.output.trim()
            outputs += output
            log("$label output: $output")
        }
        if (execution.exitCode != 0) {
            throw IOException("$label exited with code ${execution.exitCode}")
        }
    }

    // 把设备信息 POST 到兼容性服务器，解析返回 JSON 中的 payloadCommand
    private fun reportProfile(profile: DeviceProfile): PayloadInfo {
        val response = synchronousCheck(profile)
        return when (response) {
            is CompatibilityResult.EndpointNotConfigured ->
                throw IOException("Compatibility endpoint not configured")
            is CompatibilityResult.Failure ->
                throw IOException("Server request failed: ${response.reason}")
            is CompatibilityResult.Success -> parsePayload(response.responseBody)
        }
    }

    // 同步调用 CompatibilityApi（阻塞当前线程）
    private fun synchronousCheck(profile: DeviceProfile): CompatibilityResult {
        val latch = java.util.concurrent.CountDownLatch(1)
        var captured: CompatibilityResult = CompatibilityResult.Failure("No result")
        compatibilityApi.check(profile) { result ->
            captured = result
            latch.countDown()
        }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            return CompatibilityResult.Failure("Timed out waiting for server response")
        }
        return captured
    }

    // 解析服务器响应 JSON，提取 payloadCommand 与 payloadId
    // 服务端必须返回 matchMode=exact 且非空的 payloadCommand
    private fun parsePayload(body: String): PayloadInfo {
        val json = JSONObject(body)
        if (json.optString("matchMode") != "exact") {
            throw IOException("Server did not confirm strict exact compatibility matching")
        }
        val command = json.optString("payloadCommand")
            .takeIf(String::isNotBlank)
            ?: json.optString("payload_command")
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing payloadCommand")
        val payloadId = json.optString("payloadId")
            .takeIf(String::isNotBlank)
            ?: json.optString("payload_id")
                .takeIf(String::isNotBlank)
            ?: "unknown"
        val suPath = json.optString("suPath")
            .takeIf(String::isNotBlank)
            ?: json.optString("su_path")
                .takeIf(String::isNotBlank)
            ?: json.optString("ashell_su_path")
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing suPath")
        return PayloadInfo(command, payloadId, suPath)
    }

    // 与服务端使用相同规则：单条设备专属 shell 命令、UTF-8 最多 1024 字节，
    // 禁止 NUL、分号、&、|| 或换行；允许单个管道、重定向、引号及命令替换。
    private fun validateCommand(command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) return ""
        if (cmd.indexOf('\u0000') >= 0 || FORBIDDEN_COMMAND_PATTERN.containsMatchIn(cmd)) return ""
        return cmd
    }

    private fun validateSuPath(value: String): String {
        val path = value.trim()
        if (path.isEmpty() || path.toByteArray(Charsets.UTF_8).size > MAX_SU_PATH_BYTES) return ""
        if (!SU_PATH_PATTERN.matches(path)) return ""
        if (path.split('/').any { it == "." || it == ".." }) return ""
        return path
    }

    // 通过 Shizuku shell 执行命令，合并 stdout/stderr，并把输出和退出码作为同一个结果返回。
    @Suppress("DEPRECATION")
    private fun executeCommand(command: String): CommandExecution {
        val wrappedCommand = "$command 2>&1"
        val process = Shizuku.newProcess(arrayOf("sh", "-c", wrappedCommand), null, "/")
        return try {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            CommandExecution(out, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    private fun executeSuCommand(suPath: String, innerCommand: String): CommandExecution {
        require(validateSuPath(suPath) == suPath) { "Invalid su path" }
        return executeProcess(arrayOf(suPath, "-c", innerCommand))
    }

    private fun executeKernelSuCommand(innerCommand: String): CommandExecution =
        executeProcess(arrayOf("su", "-c", innerCommand))

    @Suppress("DEPRECATION")
    private fun executeProcess(arguments: Array<String>): CommandExecution {
        val process = Shizuku.newProcess(arguments, null, "/")
        return try {
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val output = listOf(stdout, stderr)
                .filter(String::isNotBlank)
                .joinToString("\n")
            CommandExecution(output, process.waitFor())
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun updateStage(next: EscalationStage) {
        mainHandler.post { stage = next }
    }

    private fun log(message: String) {
        shell.appendOutput("[提权] $message")
    }

    private fun fail(stage: EscalationStage, reason: String) {
        mainHandler.post {
            this.stage = EscalationStage.Done
            this.result = EscalationResult.Failure(stage, reason)
        }
        shell.appendOutput("[提权失败][$stage] $reason", isError = true)
    }

    override fun close() {
        executor.shutdownNow()
    }

    private data class PayloadInfo(
        val command: String,
        val payloadId: String,
        val suPath: String,
    )

    private data class CommandExecution(
        val output: String,
        val exitCode: Int,
    )

    private companion object {
        val FORBIDDEN_COMMAND_PATTERN = Regex("[;\\r\\n&]|\\|\\|")
        val SU_PATH_PATTERN = Regex("^/(?:[A-Za-z0-9_+@.-]+/)*[A-Za-z0-9_+@.-]+$")
        val ROOT_UID_PATTERN = Regex("(?:^|\\s)uid=0(?:\\(root\\))?(?:\\s|\\z)")
        const val MAX_COMMAND_BYTES = 1024
        const val MAX_SU_PATH_BYTES = 256
        const val ROOT_CHECK_INNER_COMMAND = "id"
        const val KERNEL_SU_LATE_LOAD_INNER_COMMAND =
            "\$(find /data/app -name libksud.so | grep me.weishu.kernelsu | head -n 1) " +
                "late-load --allow-shell --package-name me.weishu.kernelsu"
        const val KERNEL_SU_LOAD_POLICY_INNER_COMMAND = "load_policy /sys/fs/selinux/policy"
        const val AIRPLANE_MODE_SETTING_ON_INNER_COMMAND = "settings put global airplane_mode_on 1"
        const val AIRPLANE_MODE_BROADCAST_ON_INNER_COMMAND =
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
        const val AIRPLANE_MODE_SETTING_OFF_INNER_COMMAND = "settings put global airplane_mode_on 0"
        const val AIRPLANE_MODE_BROADCAST_OFF_INNER_COMMAND =
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        const val AIRPLANE_MODE_DURATION_MS = 1_000L
    }
}
