package com.GhostKey.app.model

/**
 * 单个阶段在流水线上的真实状态。
 *
 * 由 ViewModel 依据宿主上报的事件维护（权威来源），UI 只做渲染，不再自行推演。
 * 之所以要有 DEGRADED / SKIPPED / STOPPED 这几种“非二元”状态：
 *   - DEGRADED：校准 B 解不出 page_offset 时回退 profile 默认值，这一步确实结束了，
 *     但不是成功。旧实现没有这个状态，节点会一直停在「执行中」，转圈不停。
 *   - SKIPPED：KSU 未内置 ksud 等情况下该阶段压根没跑，不能显示成成功或失败。
 *   - STOPPED：用户点「停止」，与失败区分开。
 */
enum class StageState {
    /** 尚未开始 */
    PENDING,

    /** 正在执行 */
    RUNNING,

    /** 正常完成 */
    SUCCESS,

    /** 完成但降级（如 page_offset 回退默认值） */
    DEGRADED,

    /** 失败 */
    FAILED,

    /** 未执行 */
    SKIPPED,

    /** 被用户中断 */
    STOPPED,
}
