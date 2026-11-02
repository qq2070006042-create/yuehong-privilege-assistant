package com.GhostKey.app.device

import android.os.Build

/**
 * 内核支持性校验（v1.0.6 起与参考实现 ghostlock-app-main 对齐，移除机型锁）。
 *
 * 参考实现只按内核 release 判断：内置表命中 或 用户已导入覆盖该版本的 profile 即放行；
 * 设备型号仅用于界面展示。本类保持同样策略：
 *   1. release 可读且非模拟器/通用内核（硬性前提）；
 *   2. 内核 KMI 达标（android15-8）或已有可用 profile 覆盖当前内核。
 *
 * 背景：内核表按 `uname -r` 匹配，凡是同内核版本的设备都会命中同一份 offsets。
 * 漏洞能否跑通取决于内核参数，与商用机型无关；机型锁会误伤同 SoC 同 KMI 的设备
 * （如 OPD2409），故不再参与放行判断。
 */
object DeviceBinding {

    /** 目标内核 KMI（来自内置 pkj110.conf 的 release 前缀）。 */
    private const val TARGET_KERNEL_KMI = "android15-8"

    /** 内核版本下限：漏洞自 5.7 引入（CVE-2026-98260 官方结论）。 */
    private val RANGE_MIN = KernelVer(major = 5, minor = 7)

    /** 内核版本号（major.minor.patch），用于范围与修复分支比较。 */
    data class KernelVer(val major: Int, val minor: Int, val patch: Int = 0) : Comparable<KernelVer> {
        override fun compareTo(other: KernelVer): Int {
            if (major != other.major) return major - other.major
            if (minor != other.minor) return minor - other.minor
            return patch - other.patch
        }
    }

    /** 漏洞内核适用性结论。 */
    enum class KernelSupport {
        /** 在漏洞影响范围内（5.7 ≤ 版本 < 所在分支修复版）。 */
        IN_RANGE,

        /** 不在影响范围内（低于 5.7，或已是修复版本）：仍可执行（ROOT），仅弹窗提醒。 */
        OUT_OF_RANGE,

        /** 内核版本不可读 / 模拟器通用内核：保持拦截。 */
        UNKNOWN,
    }

    /**
     * 解析内核 release → major.minor.patch 版本号（纯函数，便于单测）。
     * 如 "6.6.118-android15-8-gxxx" → (6,6,118)；"5.15.41-android13-8" → (5,15,41)。
     */
    fun parseKernelVer(release: String): KernelVer? {
        val m = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(release) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        val patch = m.groupValues[3].toIntOrNull() ?: return null
        return KernelVer(major, minor, patch)
    }

    /**
     * 漏洞影响范围判断（纯函数，便于单测）。依据 CVE-2026-98260 官方结论
     * （奇安信 CERT / kernel.org stable 通告，2026-10）：
     *
     *  - 漏洞（POSIX CPU timer exec() UAF）自 Linux 5.7 引入，潜伏约 6 年半；
     *  - 已修复分支：6.6 ≥ 6.6.158、6.12 ≥ 6.12.112、6.18 ≥ 6.18.54、
     *    7.2 ≥ 7.2.8、7.3+（含 7.3-rc4）；5.10/5.15/6.1 等分支未见修复，仍受影响。
     *
     * 注意：厂商可能回移植修复而不改版本号，此类设备会误报为「在范围内」——
     * 因此范围外（看起来已修复）也只提醒、不拦截；反之范围外老内核（<5.7）不受影响。
     */
    fun kernelSupportOf(release: String): KernelSupport {
        if (release.isBlank() || release == "unknown") return KernelSupport.UNKNOWN
        if (release.contains("generic", ignoreCase = true) ||
            release.contains("emulator", ignoreCase = true)
        ) {
            return KernelSupport.UNKNOWN
        }
        val ver = parseKernelVer(release) ?: return KernelSupport.OUT_OF_RANGE
        return if (ver >= RANGE_MIN && !isFixedVersion(ver)) {
            KernelSupport.IN_RANGE
        } else {
            KernelSupport.OUT_OF_RANGE
        }
    }

    /** 判断版本是否已包含官方修复（各 stable 分支修复版本号）。 */
    private fun isFixedVersion(v: KernelVer): Boolean = when {
        v.major > 7 -> true
        v.major == 7 -> v.minor > 3 || (v.minor == 3) || (v.minor == 2 && v.patch >= 8)
        v.major == 6 -> when (v.minor) {
            18 -> v.patch >= 54
            12 -> v.patch >= 112
            6 -> v.patch >= 158
            else -> false
        }
        else -> false
    }

    /** 当前设备内核的适用性判断。 */
    fun kernelSupport(): KernelSupport = kernelSupportOf(readKernelRelease())

    /** 适用范围说明文案（弹窗 / 设置页共用）。 */
    fun rangeDescription(): String =
        "CVE-2026-98260 影响 Linux 内核 5.7 至修复版前：6.6 < 6.6.158、6.12 < 6.12.112、" +
            "6.18 < 6.18.54、7.2 < 7.2.8、7.3 之前，以及 5.10/5.15/6.1 等未修复分支。" +
            "注意厂商可能回移植修复而不改版本号。"

    /**
     * 内核参数校验：内核版本须命中目标 KMI，且非模拟器 / 通用内核。
     * 这是漏洞真正能跑通的前提。
     * 内核版本统一由 KernelProbe 探测（优先 uname -r，兜底 /proc/version 与 os.version），
     * 以兼容部分对普通应用不可读 /proc/version 的 ROM（实测 ColorOS / Find X8U）。
     */
    fun kernelMatches(): Boolean {
        val release = readKernelRelease()
        if (release.isBlank() || release == "unknown") return false
        if (release.contains("generic", ignoreCase = true) ||
            release.contains("emulator", ignoreCase = true)
        ) {
            return false
        }
        return release.contains(TARGET_KERNEL_KMI, ignoreCase = true)
    }

    /**
     * 放行判断：内核 KMI 达标，或已有可用 profile（内置表 / 用户导入）覆盖当前内核。
     * 模拟器 / 通用内核一律拦截。
     *
     * [profileAvailable]：当前内核能否解析出可执行的 profile
     * （由调用方经 ProfileRepository.resolve 得出）。
     */
    fun isAllowed(profileAvailable: Boolean = false): Boolean {
        val release = readKernelRelease()
        if (release.isBlank() || release == "unknown") return false
        if (release.contains("generic", ignoreCase = true) ||
            release.contains("emulator", ignoreCase = true)
        ) {
            return false
        }
        return kernelMatches() || profileAvailable
    }

    /** 读取真实内核版本（委托 KernelProbe，未提权上下文下稳定可用）。 */
    fun readKernelRelease(): String = KernelProbe.read()

    /** 诊断信息：用于日志与提示，指明内核支持性判断结果。 */
    fun diagnosis(profileAvailable: Boolean = false): String =
        if (isAllowed(profileAvailable)) {
            "内核支持（KMI 达标或已有 profile 覆盖）"
        } else {
            "内核不支持：需命中 KMI $TARGET_KERNEL_KMI，或先在设置页导入覆盖当前内核的 profile"
        }

    /** 当前设备描述，用于日志与提示。 */
    fun describe(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (product=${Build.PRODUCT}, device=${Build.DEVICE}, kernel=${readKernelRelease()})"
}
