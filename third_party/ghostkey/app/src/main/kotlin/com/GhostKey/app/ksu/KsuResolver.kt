package com.GhostKey.app.ksu

import android.content.Context

/** KSU 来源（v1.0.9：检测与实际执行共用同一套「认文件不认包名」机制）。 */
sealed class KsuSource {
    /** 设备上安装了可用的 Root 管理器（含自编译 fork）。 */
    data class DeviceManager(val packageName: String, val versionName: String?) : KsuSource()

    /** 回退到 APK 内置的 ksud。 */
    data class Bundled(val note: String = "内置 ksud") : KsuSource()

    /** 均不可用。 */
    data class Unavailable(val reason: String) : KsuSource()
}

/**
 * KSU 管理器解析器（Kotlin 层，best-effort）。
 *
 * v1.0.9 起与 [KsudStage] 对齐：扫描设备上已安装的管理器时**认 libksud.so
 * 文件存在、不认包名**——自编译 fork（如「自签改」KernelSU）包名不可预知，
 * 逐包名探测会漏（1.0.8 真机教训）。
 *
 * 该检测结果同时就是执行路径：提权时优先把命中的管理器 libksud.so 提取为
 * ksud（经 CVE98260_KSUD 环境变量传给原生 find_ksud，第一优先候选），
 * 未检测到才回落 APK 内置 ksud——全程不改原生 C。
 *
 * 注意：Android 11+ 包可见性依赖 manifest 的 QUERY_ALL_PACKAGES（已声明）；
 * <queries> 中保留的已知包名仅作快速通道补充。
 */
class KsuResolver(private val context: Context) {

    fun detect(): KsuSource {
        val pm = context.packageManager
        val lib = KsudStage.scanManagerLib(pm) ?: return KsuSource.Bundled()
        val (file, pkg) = lib
        val version = runCatching {
            pm.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
        if (!file.isFile || file.length() <= 0L) return KsuSource.Bundled()
        return KsuSource.DeviceManager(pkg, version)
    }

    fun label(source: KsuSource): String = when (source) {
        is KsuSource.DeviceManager ->
            "设备管理器 ${source.packageName}" + (source.versionName?.let { " v$it" } ?: "")
        is KsuSource.Bundled -> source.note
        is KsuSource.Unavailable -> "不可用：${source.reason}"
    }

    /** 从 uname -r 提取 KMI（如 android15-8），用于 late-load 参数。 */
    fun kmiOf(release: String): String? = Regex("android\\d+-\\d+").find(release)?.value
}
