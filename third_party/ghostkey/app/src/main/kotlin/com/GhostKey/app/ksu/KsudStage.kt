package com.GhostKey.app.ksu

/**
 * KSUD_STAGE：从设备已装的 KernelSU 系管理器 APK 中提取 ksud（libksud.so）。
 *
 * 机制背景（对齐 ghostlock-app-main 的 ManagerScanner + PC 端 ksu_activate.sh 实测）：
 *  - KernelSU 系管理器把 ksud 可执行文件伪装成 `libksud.so` 打进 APK，
 *    安装后落在 `<apk目录>/lib/arm64/libksud.so`（即 nativeLibraryDir）；
 *  - ksud 内置多套 KMI 的 kernelsu.ko（android15-6.6_kernelsu.ko 等），
 *    `late-load --kmi <KMI>` 时自动选内置对应 ko，并自动替换 vermagic，
 *    因此「同一 KMI 任意构建号」的内核都能加载；
 *  - 从**设备已装管理器**提取 ksud，版本天然与管理器配套 —— 管理器升级即
 *    获得新 KMI 支持，无需重打本 APK（对比：内置固定 ksud 的 KMI 集冻结在打包时刻）。
 *
 * 脚本在 Shizuku UserService（uid=shell/2000 或 root）上下文执行：
 * `pm path`、读 `/data/local/tmp` 均为 shell 权限内操作。
 */
object KsudStage {

    /** 提取目标路径（shell uid 可写；同时也在 ksu.c find_ksud 兜底链里）。 */
    const val TARGET: String = "/data/local/tmp/ksud"

    /** KSU 管理器候选包名（优先级顺序，对齐参考 ManagerScanner 的家族列表）。 */
    private val MANAGER_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "me.weishu.kernelsu.prerelease",
        "me.weishu.kernelsu.next",
        "com.rifsxd.ksunext",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    /** 管理器 APK 内 ksud 的伪装文件名。 */
    private const val LIB_NAME = "libksud.so"

    /**
     * 【主路径】PackageManager 扫描已装应用找 libksud.so（v1.0.9 起）。
     *
     * 认「文件存在」不认「包名」——自编译 fork（如 自签改 KernelSU v3.3.1，
     * versionCode 32704-6 风格）包名不可预知，逐包名探测会漏。
     *
     * 顺序：
     *  1. 已知家族包名逐个查（nativeLibraryDir 直读，最快最准）；
     *  2. 全量扫描 getInstalledPackages，找任何带 libksud.so 的应用
     *     （需 QUERY_ALL_PACKAGES，manifest 已声明；shell uid 下 PM 调用亦可用）。
     *
     * 返回 (libksud.so 文件, 来源包名)；找不到返回 null。
     */
    fun scanManagerLib(
        pm: android.content.pm.PackageManager,
    ): Pair<java.io.File, String>? {
        for (pkg in MANAGER_PACKAGES) {
            val lib = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                java.io.File(info.nativeLibraryDir, LIB_NAME)
            }.getOrNull()
            if (lib != null && lib.isFile) return lib to pkg
        }
        val infos = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        for (info in infos) {
            val dir = info.applicationInfo?.nativeLibraryDir ?: continue
            val lib = java.io.File(dir, LIB_NAME)
            if (lib.isFile) return lib to info.packageName
        }
        return null
    }

    /**
     * 【回退路径】shell 脚本提取（包名列表循环 + lib 目录直拷 + unzip 兜底）。
     *
     * 流程：
     *  1. 逐个候选包名 `pm path` 找已装管理器 APK；
     *  2. 优先直接拷 `<apk目录>/lib/arm64/libksud.so`（已解压的 native lib，最快）；
     *  3. 回退 `unzip -p <apk> lib/arm64-v8a/libksud.so`（部分 ROM 未解压时）；
     *  4. chmod 755 后回报 `KSUD_STAGE_OK md5=.. size=..`，失败回报
     *     `KSUD_STAGE_FAIL <reason>`，两标记供宿主解析（改名需同步 parser）。
     */
    val SCRIPT: String = """
        T=/data/local/tmp/ksud
        APK=""
        for PKG in ${MANAGER_PACKAGES.joinToString(" ")}; do
          APK="${'$'}(pm path "${'$'}PKG" 2>/dev/null | head -1 | sed 's/^package://')"
          if [ -n "${'$'}APK" ]; then break; fi
        done
        if [ -z "${'$'}APK" ]; then echo "KSUD_STAGE_FAIL manager_not_installed"; exit 0; fi
        LIB="${'$'}(dirname "${'$'}APK")/lib/arm64/libksud.so"
        if [ -r "${'$'}LIB" ]; then cp -f "${'$'}LIB" "${'$'}T" 2>/dev/null; fi
        if [ ! -s "${'$'}T" ]; then
          if command -v unzip >/dev/null 2>&1; then
            unzip -p "${'$'}APK" lib/arm64-v8a/libksud.so > "${'$'}T" 2>/dev/null
          fi
        fi
        if [ ! -s "${'$'}T" ]; then echo "KSUD_STAGE_FAIL no_source apk=${'$'}APK"; exit 0; fi
        chmod 755 "${'$'}T" 2>/dev/null
        MD5="${'$'}(md5sum "${'$'}T" 2>/dev/null | awk '{print ${'$'}1}')"
        echo "KSUD_STAGE_OK md5=${'$'}MD5 size=${'$'}(wc -c < "${'$'}T" 2>/dev/null)"
    """.trimIndent()

    /** 脚本输出解析结果。 */
    sealed interface Outcome {
        /** 提取成功：md5 与字节数（可能为空串/0，取不到时不阻塞主链路）。 */
        data class Ok(val md5: String, val size: Long) : Outcome

        /** 提取失败：原因标记（manager_not_installed / no_source / 无输出=执行异常）。 */
        data class Fail(val reason: String) : Outcome
    }

    /** 解析脚本 stdout（纯函数，便于单测）。取最后一个 OK/FAIL 标记行。 */
    fun parse(output: String): Outcome {
        var result: Outcome = Outcome.Fail("no_output")
        for (line in output.lines()) {
            val t = line.trim()
            when {
                t.startsWith("KSUD_STAGE_OK") -> {
                    val md5 = Regex("md5=([0-9a-fA-F]*)").find(t)?.groupValues?.get(1).orEmpty()
                    val size = Regex("size=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    result = Outcome.Ok(md5, size)
                }
                t.startsWith("KSUD_STAGE_FAIL") -> {
                    val reason = t.removePrefix("KSUD_STAGE_FAIL").trim().ifBlank { "unknown" }
                    result = Outcome.Fail(reason)
                }
            }
        }
        return result
    }
}
