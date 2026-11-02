package roro.stellar.yuehong.shell

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class PayloadResource(
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val route: String,
    val routeName: String,
    val sessionId: String,
    val attemptId: String,
    val hasMoreRoutes: Boolean,
)

data class PayloadDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
)

interface PayloadResourceDownloader : AutoCloseable {
    fun download(
        resource: PayloadResource,
        onProgress: (PayloadDownloadProgress) -> Unit = {},
    ): File
}

class HttpPayloadResourceDownloader(context: Context) : PayloadResourceDownloader {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, "stellar-payloads").apply { mkdirs() }

    override fun download(
        resource: PayloadResource,
        onProgress: (PayloadDownloadProgress) -> Unit,
    ): File {
        OfficialAppSignature.requireOfficial(appContext)
        require(resource.size in 1..MAX_RESOURCE_BYTES) { "Invalid resource size for ${resource.name}" }
        require(SHA256_PATTERN.matches(resource.sha256)) { "Invalid SHA-256 for ${resource.name}" }
        val url = URL(resource.url)
        require(url.protocol.equals("https", ignoreCase = true)) { "Resource URL must use HTTPS" }

        val temporary = File.createTempFile("payload-", ".part", cacheDirectory)
        var connection: HttpURLConnection? = null
        return try {
            val activeConnection = url.openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "GET"
            activeConnection.instanceFollowRedirects = true
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.setRequestProperty("Accept", "application/octet-stream")
            activeConnection.setRequestProperty("Cache-Control", "no-store")

            val responseCode = activeConnection.responseCode
            if (!activeConnection.url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Resource ${resource.name} redirected to an insecure URL")
            }
            if (responseCode !in 200..299) {
                val detail = activeConnection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.trim()?.take(512).orEmpty()
                throw IOException("Resource ${resource.name} download failed: HTTP $responseCode${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
            val contentLength = activeConnection.contentLengthLong
            if (contentLength >= 0 && contentLength != resource.size) {
                throw IOException("Resource ${resource.name} size mismatch before download: expected=${resource.size}, actual=$contentLength")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastPercent = -1
            fun publishProgress() {
                val percent = ((written * 100L) / resource.size).toInt().coerceIn(0, 100)
                if (percent == lastPercent) return
                lastPercent = percent
                onProgress(PayloadDownloadProgress(written, resource.size, percent))
            }
            publishProgress()
            activeConnection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > resource.size || written > MAX_RESOURCE_BYTES) {
                            throw IOException("Resource ${resource.name} exceeds declared size")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        publishProgress()
                    }
                    output.fd.sync()
                }
            }
            if (written != resource.size) {
                throw IOException("Resource ${resource.name} size mismatch: expected=${resource.size}, actual=$written")
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(resource.sha256, ignoreCase = true)) {
                throw IOException("Resource ${resource.name} SHA-256 mismatch")
            }
            if (lastPercent != 100) {
                onProgress(PayloadDownloadProgress(written, resource.size, 100))
            }
            temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    override fun close() {
        cacheDirectory.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach(File::delete)
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RESOURCE_BYTES = 512L * 1024L * 1024L
    }
}
