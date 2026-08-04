package `in`.hridayan.ashell.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import `in`.hridayan.ashell.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CompatibilityResult {
    data object EndpointNotConfigured : CompatibilityResult
    data class Success(val responseBody: String) : CompatibilityResult
    data class Failure(val reason: String) : CompatibilityResult
}

interface CompatibilityApi {
    fun check(
        profile: DeviceProfile,
        onResult: (CompatibilityResult) -> Unit,
    )
}

class HttpCompatibilityApi(context: Context) : CompatibilityApi, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceId = ClientIdentity.loadOrCreate(context)

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

    private fun postProfile(
        endpoint: String,
        profile: DeviceProfile,
    ): CompatibilityResult {
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Compatibility endpoint must use HTTPS"
        }

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(
                    JSONObject(profile.toJson())
                        .put("moduleId", BuildConfig.SERVER_MODULE_ID)
                        .put("matchMode", "exact")
                        .put("versionCode", BuildConfig.VERSION_CODE)
                        .put("versionName", BuildConfig.VERSION_NAME)
                        .put("deviceId", deviceId)
                        .toString(),
                )
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                CompatibilityResult.Success(body)
            } else {
                CompatibilityResult.Failure(
                    "HTTP $responseCode${body.takeIf(String::isNotBlank)?.let { ": ${it.take(512)}" }.orEmpty()}",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}

