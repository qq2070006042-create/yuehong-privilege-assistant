package com.GhostKey.app.model

/** Shizuku 可用性状态。 */
enum class ShizukuStatus {
    /** 未安装 Shizuku Manager。 */
    NOT_INSTALLED,

    /** 已安装但服务未运行（需先用 adb / 无线调试启动）。 */
    NOT_RUNNING,

    /** 服务已运行，但本应用未获授权（需在 Shizuku 中点允许）。 */
    PERMISSION_REQUIRED,

    /** 就绪。 */
    READY,
}
