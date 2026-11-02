package com.GhostKey.app.data

import android.util.Log
import com.GhostKey.app.model.KernelProfile

/**
 * 内置内核表目录。
 *
 * 从 assets/kernel_profiles 下各 conf 加载所有 profile（HOCON 子集），
 * 按 uname -r **精确匹配**：内置 profile 的 release 与设备实际 release 完全一致才命中。
 *
 * 不做 KMI（android15-8）前缀回退——同 KMI 的不同内核构建
 * （如 6.6.118 vs 6.6.127，或 9bc34d5b0c79 vs gc4127a25dcf3 这类不同厂商/分支构建）
 * 的 do_timer_create / prepare_creds 等符号地址并不稳定，回退会套用错误的
 * timer_link / cred_link 偏移，导致 calib_a 解 KASLR 失败（日志中 exit=2）。
 * 没有精确匹配的机型应导入对应 boot.img 生成 profile，而不是复用其它机型的。
 *
 * 现有的 pkj110.conf（`release = ...` + 嵌套 `offset { ... }` 块）本身即合法 HOCON，
 * 因此无需改动 assets 即可由 HoconSupport 直接解析。
 */
class BuiltinProfileCatalog(
    private val loader: AssetConfigLoader,
) {

    private val profiles: List<KernelProfile> by lazy { loadAll() }

    fun all(): List<KernelProfile> = profiles

    /**
     * 精确匹配：仅当内置 profile 的 release 与入参完全一致才命中。
     *
     * 不再做 KMI 前缀回退（原因见类注释）：同 KMI 不同构建的内核符号偏移并不一致，
     * 回退会套用错误的 timer_link / cred_link，导致 KASLR 解算失败。
     * 没有精确匹配的机型，应导入对应 boot.img 生成 profile。
     */
    fun match(release: String): KernelProfile? {
        return profiles.firstOrNull { it.release == release }
    }

    private fun loadAll(): List<KernelProfile> {
        val index = loadIndex()
        return index.mapNotNull { (release, file) ->
            val text = loader.read("$DIR/$file") ?: run {
                Log.w(TAG, "读取 $file 失败")
                null
            } ?: return@mapNotNull null
            runCatching { ProfileDocument.parse(text).toKernelProfile() }
                .onFailure { Log.w(TAG, "解析 $file 失败: ${it.message}") }
                .getOrNull()
        }
    }

    /** index.conf 行格式：`release  file  [recommendShizuku]`，`#` 注释。 */
    private fun loadIndex(): Map<String, String> {
        val text = loader.read("$DIR/index.conf") ?: return emptyMap()
        val map = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) map[parts[0]] = parts[1]
        }
        return map
    }

    companion object {
        private const val TAG = "BuiltinProfileCatalog"
        const val DIR = "kernel_profiles"

        /** 从 uname -r 提取 KMI（如 6.6.118-android15-8-gxxx → android15-8）。 */
        fun extractKmi(release: String): String? =
            Regex("android\\d+-\\d+").find(release)?.value
    }
}
