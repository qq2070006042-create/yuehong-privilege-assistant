package `in`.hridayan.ashell.shell

import android.os.Handler
import android.os.Looper
import `in`.hridayan.ashell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 公告拉取结果
sealed interface AnnouncementResult {
    data object EndpointNotConfigured : AnnouncementResult
    data class Success(val info: AnnouncementInfo) : AnnouncementResult
    data class Failure(val reason: String) : AnnouncementResult
}

// 服务器返回的元信息
data class AnnouncementInfo(
    val version: String,
    val author: String,
    val announcement: String,
)

// 公告接口：HTTPS GET 拉取版本号 / 作者 / 公告
interface AnnouncementApi {
    fun fetch(onResult: (AnnouncementResult) -> Unit)
}

class HttpAnnouncementApi : AnnouncementApi, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun fetch(onResult: (AnnouncementResult) -> Unit) {
        val endpoint = BuildConfig.SERVER_ANNOUNCEMENT_ENDPOINT.trim()
        if (endpoint.isEmpty()) {
            onResult(AnnouncementResult.EndpointNotConfigured)
            return
        }

        executor.execute {
            val result = runCatching { fetchInfo(endpoint) }
                .getOrElse { error ->
                    AnnouncementResult.Failure(
                        error.message?.takeIf(String::isNotBlank)
                            ?: error.javaClass.simpleName,
                    )
                }
            mainHandler.post { onResult(result) }
        }
    }

    private fun fetchInfo(endpoint: String): AnnouncementResult {
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Announcement endpoint must use HTTPS"
        }

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                AnnouncementResult.Success(parse(body))
            } else {
                AnnouncementResult.Failure(
                    "HTTP $responseCode${body.takeIf(String::isNotBlank)?.let { ": ${it.take(512)}" }.orEmpty()}",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    // 解析服务器响应 JSON，字段名由下方预留常量配置
    private fun parse(body: String): AnnouncementInfo {
        val json = JSONObject(body)
        val moduleInfo = json.optJSONObject("moduleInfo") ?: json
        val announcementInfo = json.optJSONObject("announcement") ?: json
        return AnnouncementInfo(
            version = moduleInfo.optString(FIELD_VERSION).orEmpty(),
            author = announcementInfo.optString(FIELD_AUTHOR)
                .ifBlank { moduleInfo.optString(FIELD_AUTHOR) },
            announcement = announcementInfo.optString(FIELD_ANNOUNCEMENT)
                .ifBlank { announcementInfo.optString("announcement") },
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        // 预留 JSON 字段名，按实际服务器返回格式填写
        const val FIELD_VERSION = "version"
        const val FIELD_AUTHOR = "author"
        const val FIELD_ANNOUNCEMENT = "message"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
