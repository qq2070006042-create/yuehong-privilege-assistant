package roro.stellar.yuehong.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * APK 内置提权文件目录。
 *
 * 载荷全部随 APK 打包，目录只负责展示和校验；不会根据设备信息自动替换
 * 用户的选择。这样每一份编译期常量载荷都必须由用户明确确认后才会执行。
 */
data class BundledPayload(
    val library: String,
    val vendor: String,
    val displayName: String,
    val models: List<String>,
    val kernelVersion: String?,
    val kernelUnknown: Boolean,
    val buildId: String,
    val command: String,
    val sha256: String,
    val size: Long,
    val source: String,
) {
    val label: String
        get() = buildString {
            append(displayName.ifBlank { library })
            if (!kernelVersion.isNullOrBlank()) append(" · 内核 ").append(kernelVersion)
            if (kernelUnknown) append(" · 内核未知")
            if (buildId.isNotBlank()) append(" · ").append(buildId)
            append("\n").append(library)
        }
}

object BundledPayloadCatalog {
    private const val ASSET_NAME = "bundled_payloads.json"

    fun load(context: Context): List<BundledPayload> {
        val root = JSONObject(
            context.applicationContext.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
        require(root.optString("schema") == "yuehong-bundled-payloads/1") {
            "unsupported bundled privilege payload catalog"
        }
        val array = root.optJSONArray("payloads") ?: error("bundled privilege payload catalog is empty")
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(parse(array.getJSONObject(index)))
        }
    }

    fun fileFor(context: Context, payload: BundledPayload): File? {
        val file = File(context.applicationContext.applicationInfo.nativeLibraryDir, payload.library)
        if (!file.isFile || file.length() != payload.size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        if (!digest.digest().joinToString("") { "%02x".format(it) }
                .equals(payload.sha256, ignoreCase = true)
        ) return null
        return file
    }

    private fun parse(item: JSONObject): BundledPayload = BundledPayload(
        library = item.getString("library"),
        vendor = item.optString("vendor"),
        displayName = item.optString("displayName"),
        models = item.optJSONArray("models").toStringList(),
        kernelVersion = item.optString("kernelVersion").takeIf(String::isNotBlank),
        kernelUnknown = item.optBoolean("kernelUnknown", false),
        buildId = item.optString("buildId"),
        command = item.optString("command").ifBlank { "LD_PRELOAD=<payload> /system/bin/id" },
        sha256 = item.getString("sha256"),
        size = item.getLong("size"),
        source = item.optString("source"),
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) add(optString(index).trim())
        }.filter(String::isNotBlank)
    }
}
