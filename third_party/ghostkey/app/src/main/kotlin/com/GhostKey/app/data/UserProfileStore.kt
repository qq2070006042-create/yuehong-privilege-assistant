package com.GhostKey.app.data

import android.content.Context
import android.util.Log
import com.GhostKey.app.model.KernelProfile
import java.io.File

/** 已导入的 profile（文件名 + 解析后的文档）。 */
data class StoredProfile(
    val fileName: String,
    val document: ProfileDocument,
) {
    val release: String get() = document.release
    val complete: Boolean get() = document.isComplete()
    val missing: List<String> get() = document.missingRequiredFields()
    /** 来源标签（IMG 导入会写 _source）。 */
    val source: String? get() = document.getString("_source")
}

/**
 * 用户导入 profile 的持久化存储。
 *
 * 落盘位置：`filesDir/user_profiles/` 下的 .conf 文件（应用私有目录，卸载即清）。
 * 文件内容为 HOCON 文本（[ProfileDocument.toHocon] 渲染），便于用户手工编辑/导出。
 */
class UserProfileStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 列出全部已导入 profile（解析失败的文件跳过并告警）。 */
    fun list(): List<StoredProfile> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".conf") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            runCatching { ProfileDocument.parse(f.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "解析 ${f.name} 失败: ${it.message}") }
                .getOrNull()
                ?.let { StoredProfile(f.name, it) }
        }
    }

    /** 仅返回可执行的完整 profile（可转 KernelProfile）。 */
    fun listComplete(): List<KernelProfile> =
        list().mapNotNull { sp -> runCatching { sp.document.toKernelProfile() }.getOrNull() }

    fun save(fileName: String, text: String): File {
        val f = File(dir, sanitizeFileName(fileName))
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    fun delete(fileName: String): Boolean =
        File(dir, sanitizeFileName(fileName)).let { if (it.isFile) it.delete() else false }

    fun read(fileName: String): String? =
        File(dir, sanitizeFileName(fileName)).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /** 按 release 生成规范文件名（避免路径穿越/非法字符）。 */
    fun documentFileName(release: String): String =
        "user-${sanitizeFileName(release)}.conf"

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    companion object {
        private const val TAG = "UserProfileStore"
        const val DIR = "user_profiles"
    }
}
