package roro.stellar.yuehong.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import roro.stellar.yuehong.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CompatibilityResult {
    data object EndpointNotConfigured : CompatibilityResult
    data object DeviceNotSupported : CompatibilityResult
    data class Success(val responseBody: String) : CompatibilityResult
    data class Failure(val reason: String) : CompatibilityResult
}

sealed interface ResourceTicketResult {
    data class Success(
        val url: String,
        val sha256: String,
        val route: String,
        val routeName: String,
        val sessionId: String,
        val attemptId: String,
        val hasMoreRoutes: Boolean,
    ) : ResourceTicketResult
    data object Exhausted : ResourceTicketResult
    data class Failure(val reason: String) : ResourceTicketResult
}

sealed interface DeviceProfileUploadResult {
    data class Success(val duplicate: Boolean, val reportCount: Int) : DeviceProfileUploadResult
    data class Failure(val reason: String) : DeviceProfileUploadResult
}

interface CompatibilityApi {
    fun check(
        profile: DeviceProfile,
        onResult: (CompatibilityResult) -> Unit,
    )

    fun uploadUnsupportedProfile(
        profile: DeviceProfile,
        onResult: (DeviceProfileUploadResult) -> Unit,
    )

    fun requestNextTicket(
        resourceId: String,
        sessionId: String,
        attemptId: String,
        failureReason: String,
        onResult: (ResourceTicketResult) -> Unit,
    )

    fun confirmResourceDownload(
        resourceId: String,
        sessionId: String,
        attemptId: String,
        onResult: (Boolean) -> Unit,
    )
}

class HttpCompatibilityApi(context: Context) : CompatibilityApi, AutoCloseable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun check(
        profile: DeviceProfile,
        onResult: (CompatibilityResult) -> Unit,
    ) {
        val endpoint = BuildConfig.SERVER_COMPATIBILITY_ENDPOINT.trim()
        if (endpoint.isEmpty()) {
            onResult(CompatibilityResult.EndpointNotConfigured)
            return
        }

        executor.execute {
            val result = runCatching { postProfile(endpoint, profile) }
                .getOrElse { error ->
                    CompatibilityResult.Failure(
                        error.message?.takeIf(String::isNotBlank)
                            ?: error.javaClass.simpleName,
                    )
                }
            mainHandler.post { onResult(result) }
        }
    }

    override fun uploadUnsupportedProfile(
        profile: DeviceProfile,
        onResult: (DeviceProfileUploadResult) -> Unit,
    ) {
        val baseEndpoint = BuildConfig.SERVER_PROTOCOL_V2_ENDPOINT.trim()
        if (baseEndpoint.isEmpty()) {
            onResult(DeviceProfileUploadResult.Failure("机型信息上报接口尚未配置"))
            return
        }
        executor.execute {
            val result = runCatching {
                postUnsupportedProfile(appendAction(baseEndpoint, "stellar_device_report"), profile)
            }.getOrElse { error ->
                DeviceProfileUploadResult.Failure(
                    error.message?.takeIf(String::isNotBlank) ?: "机型信息上传失败",
                )
            }
            mainHandler.post { onResult(result) }
        }
    }

    override fun requestNextTicket(
        resourceId: String,
        sessionId: String,
        attemptId: String,
        failureReason: String,
        onResult: (ResourceTicketResult) -> Unit,
    ) {
        val baseEndpoint = BuildConfig.SERVER_PROTOCOL_V2_ENDPOINT.trim()
        if (baseEndpoint.isEmpty()) {
            onResult(ResourceTicketResult.Failure("资源重试票据接口尚未配置"))
            return
        }
        executor.execute {
            val result = runCatching {
                postNextTicket(
                    appendAction(baseEndpoint, "resource_ticket_v2"),
                    resourceId,
                    sessionId,
                    attemptId,
                    failureReason,
                )
            }.getOrElse { error ->
                ResourceTicketResult.Failure(
                    error.message?.takeIf(String::isNotBlank) ?: "下一线路票据申请失败",
                )
            }
            mainHandler.post { onResult(result) }
        }
    }

    override fun confirmResourceDownload(
        resourceId: String,
        sessionId: String,
        attemptId: String,
        onResult: (Boolean) -> Unit,
    ) {
        val baseEndpoint = BuildConfig.SERVER_PROTOCOL_V2_ENDPOINT.trim()
        if (baseEndpoint.isEmpty()) {
            onResult(false)
            return
        }
        executor.execute {
            val result = runCatching {
                postDownloadConfirmation(
                    appendAction(baseEndpoint, "resource_download_complete_v2"),
                    resourceId,
                    sessionId,
                    attemptId,
                )
            }.getOrDefault(false)
            mainHandler.post { onResult(result) }
        }
    }

    private fun postProfile(
        endpoint: String,
        profile: DeviceProfile,
    ): CompatibilityResult {
        OfficialAppSignature.requireOfficial(appContext)
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Compatibility endpoint must use HTTPS"
        }

        val proof = DeviceRequestProofFactory.create(
            appContext,
            "stellar_compatibility",
            DeviceRequestProofFactory.compatibilitySubject(profile),
        )
        val requestBody = JSONObject(profile.toJson())
            .put("moduleId", BuildConfig.SERVER_MODULE_ID)
            .put("matchMode", "exact")
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .also { proof.putInto(it) }
            .toString()

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.readUtf8Limited().orEmpty()

            when {
                responseCode in 200..299 -> {
                    val payload = SignedServerResponse.verify(body, proof)
                    if (isDeviceNotSupportedResponse(payload)) {
                        CompatibilityResult.DeviceNotSupported
                    } else {
                        CompatibilityResult.Success(payload.toString())
                    }
                }
                else -> CompatibilityResult.Failure(
                    httpFailureMessage(responseCode),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isDeviceNotSupportedResponse(json: JSONObject): Boolean {
        return json.optString("errorCode") == DEVICE_NOT_SUPPORTED_ERROR_CODE &&
            !json.optBoolean("compatible", true)
    }

    private fun postNextTicket(
        endpoint: String,
        resourceId: String,
        sessionId: String,
        attemptId: String,
        failureReason: String,
    ): ResourceTicketResult {
        OfficialAppSignature.requireOfficial(appContext)
        val normalizedResourceId = resourceId.trim()
        require(normalizedResourceId.isNotEmpty() && normalizedResourceId.length <= MAX_RESOURCE_ID_LENGTH) {
            "资源 ID 无效"
        }
        require(STATE_ID_PATTERN.matches(sessionId) && STATE_ID_PATTERN.matches(attemptId)) {
            "下载会话无效"
        }
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "资源重试票据接口必须使用 HTTPS"
        }
        val proof = DeviceRequestProofFactory.create(
            appContext,
            "resource_retry_ticket",
            normalizedResourceId,
        )
        val requestBody = JSONObject()
            .put("protocol", 2)
            .put("module_id", BuildConfig.SERVER_MODULE_ID)
            .put("resource_id", normalizedResourceId)
            .put("session_id", sessionId)
            .put("attempt_id", attemptId)
            .put("failure_reason", failureReason.ifBlank { "download_failed" })
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("device_id", proof.deviceId)
            .put("device_public_key", proof.publicKeyB64)
            .put("device_timestamp", proof.timestamp)
            .put("device_nonce", proof.nonce)
            .put("device_signature", proof.signatureB64)
            .toString()

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readUtf8Limited().orEmpty()
            if (responseCode !in 200..299) {
                return ResourceTicketResult.Failure(httpFailureMessage(responseCode))
            }
            val payload = SignedServerResponse.verify(body, proof)
            if (payload.optInt("authorized", 0) != 1) {
                return ResourceTicketResult.Failure("服务器拒绝签发下一线路票据")
            }
            val echoedResourceId = payload.optString("resource_id")
            if (payload.optString("exhausted") == "1") return ResourceTicketResult.Exhausted
            val route = payload.optString("route")
            val sha256 = payload.optString("sha256").trim().lowercase()
            val responseSessionId = payload.optString("download_session_id")
            val responseAttemptId = payload.optString("download_attempt_id")
            if (echoedResourceId != normalizedResourceId || route.isBlank() ||
                !STATE_ID_PATTERN.matches(responseSessionId) || !STATE_ID_PATTERN.matches(responseAttemptId) ||
                !SHA256_PATTERN.matches(sha256)
            ) {
                throw IOException("下一线路票据响应字段无效")
            }
            val encodedUrl = payload.optString("url_b64")
            if (!BASE64_URL_PATTERN.matches(encodedUrl)) {
                throw IOException("下一线路票据 URL 编码无效")
            }
            val ticketUrl = runCatching {
                String(
                    Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    Charsets.UTF_8,
                ).trim()
            }.getOrElse { throw IOException("下一线路票据 URL 编码无效", it) }
            if (!runCatching { URL(ticketUrl).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)) {
                throw IOException("下一线路票据必须使用 HTTPS")
            }
            val routeName = decodeBase64UrlText(payload.optString("route_name_b64")).ifBlank { route }
            ResourceTicketResult.Success(
                url = ticketUrl,
                sha256 = sha256,
                route = route,
                routeName = routeName,
                sessionId = responseSessionId,
                attemptId = responseAttemptId,
                hasMoreRoutes = payload.optString("has_more_routes") == "1",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun postDownloadConfirmation(
        endpoint: String,
        resourceId: String,
        sessionId: String,
        attemptId: String,
    ): Boolean {
        OfficialAppSignature.requireOfficial(appContext)
        val normalizedResourceId = resourceId.trim()
        require(normalizedResourceId.isNotEmpty() && normalizedResourceId.length <= MAX_RESOURCE_ID_LENGTH)
        require(STATE_ID_PATTERN.matches(sessionId) && STATE_ID_PATTERN.matches(attemptId))
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Download confirmation endpoint must use HTTPS"
        }
        val proof = DeviceRequestProofFactory.create(
            appContext,
            "resource_download_complete",
            normalizedResourceId,
        )
        val requestBody = JSONObject()
            .put("protocol", 2)
            .put("module_id", BuildConfig.SERVER_MODULE_ID)
            .put("resource_id", normalizedResourceId)
            .put("session_id", sessionId)
            .put("attempt_id", attemptId)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("device_id", proof.deviceId)
            .put("device_public_key", proof.publicKeyB64)
            .put("device_timestamp", proof.timestamp)
            .put("device_nonce", proof.nonce)
            .put("device_signature", proof.signatureB64)
            .toString()

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readUtf8Limited().orEmpty()
            if (responseCode !in 200..299) return false
            val payload = SignedServerResponse.verify(body, proof)
            payload.optInt("authorized", 0) == 1 &&
                payload.optString("resource_id") == normalizedResourceId &&
                payload.optString("download_session_id") == sessionId &&
                payload.optString("download_attempt_id") == attemptId
        } finally {
            connection.disconnect()
        }
    }

    private fun postUnsupportedProfile(
        endpoint: String,
        profile: DeviceProfile,
    ): DeviceProfileUploadResult {
        OfficialAppSignature.requireOfficial(appContext)
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "机型信息上报接口必须使用 HTTPS"
        }
        val proof = DeviceRequestProofFactory.create(
            appContext,
            "stellar_device_report",
            DeviceRequestProofFactory.deviceReportSubject(profile),
        )
        val requestBody = JSONObject(profile.toJson())
            .put("moduleId", BuildConfig.SERVER_MODULE_ID)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .also { proof.putInto(it) }
            .toString()

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readUtf8Limited().orEmpty()
            if (responseCode !in 200..299) {
                return DeviceProfileUploadResult.Failure(httpFailureMessage(responseCode))
            }
            val payload = SignedServerResponse.verify(body, proof)
            if (payload.optString("status") != "success") {
                DeviceProfileUploadResult.Failure("服务器未能保存机型信息")
            } else {
                DeviceProfileUploadResult.Success(
                    duplicate = payload.optBoolean("duplicate", false),
                    reportCount = payload.optInt("reportCount", 1).coerceAtLeast(1),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun appendAction(endpoint: String, action: String): String =
        endpoint + if ('?' in endpoint) "&action=$action" else "?action=$action"

    private fun decodeBase64UrlText(encoded: String): String = runCatching {
        if (encoded.isBlank() || !BASE64_URL_PATTERN.matches(encoded)) return@runCatching ""
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
            Charsets.UTF_8,
        ).trim()
    }.getOrDefault("")

    private fun InputStream.readUtf8Limited(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= MAX_RESPONSE_BYTES) { "Compatibility response is too large" }
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401, 403 -> "服务器拒绝了设备安全验证"
        409 -> "设备授权状态或兼容档案发生冲突"
        429 -> "请求过于频繁，请稍后重试"
        in 500..599 -> "提权服务器暂时不可用"
        else -> "提权服务器返回异常（HTTP $code）"
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_RESOURCE_ID_LENGTH = 192
        private const val DEVICE_NOT_SUPPORTED_ERROR_CODE = "device_not_supported"
        private const val DEVICE_NOT_SUPPORTED_MESSAGE = "当前机型未适配"
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val STATE_ID_PATTERN = Regex("^[a-f0-9]{24,64}$")
    }
}
