package roro.stellar.manager.util.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class UpdateSource(val displayName: String) {
    GITHUB("GitHub"),
    GITEE("Gitee")
}

object UpdateUtils {

    private const val TAG = "UpdateUtils"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/roro2239/Stellar/releases/latest"
    private const val GITEE_API_URL =
        "https://gitee.com/api/v5/repos/su-su2239/Stellar/releases/latest"

    private fun parseVersionCode(tagName: String): Int {
        val match = Regex("\\((\\d+)\\)").find(tagName)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun parseVersionName(tagName: String): String {
        return tagName.removePrefix("v").replace(Regex("\\(\\d+\\)"), "")
    }

    private fun isInChina(): Boolean {
        return java.util.Locale.getDefault().country == "CN"
    }

    suspend fun getPreferredSource(): UpdateSource = withContext(Dispatchers.IO) {
        if (isInChina()) UpdateSource.GITEE else UpdateSource.GITHUB
    }

    suspend fun checkUpdate(source: UpdateSource? = null): AppUpdate? = withContext(Dispatchers.IO) {
        val actualSource = source ?: getPreferredSource()
        val url = when (actualSource) {
            UpdateSource.GITHUB -> GITHUB_API_URL
            UpdateSource.GITEE -> GITEE_API_URL
        }
        fetchUpdate(url, actualSource.displayName)
    }

    private fun fetchUpdate(apiUrl: String, sourceName: String): AppUpdate? {
        try {
            val requestBuilder = Request.Builder().url(apiUrl).get()
            if (apiUrl == GITHUB_API_URL) {
                requestBuilder.header("Accept", "application/vnd.github+json")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "$sourceName 获取更新信息失败，响应码: ${response.code}")
                return null
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                Log.e(TAG, "响应内容为空")
                return null
            }

            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "")
            val versionCode = parseVersionCode(tagName)
            val versionName = parseVersionName(tagName)
            val body = json.optString("body", "")
            val downloadUrl = findApkDownloadUrl(json.optJSONArray("assets"))

            return if (versionCode > 0) {
                AppUpdate(versionName, versionCode, body, downloadUrl)
            } else {
                Log.e(TAG, "解析 versionCode 失败，tag: $tagName")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "$sourceName 检查更新失败: ${e.message}", e)
            return null
        }
    }

    private fun findApkDownloadUrl(assets: org.json.JSONArray?): String {
        if (assets == null || assets.length() == 0) return ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name", "").endsWith(".apk")) {
                return asset.optString("browser_download_url", "")
            }
        }
        return assets.getJSONObject(0).optString("browser_download_url", "")
    }
}
