package com.GhostKey.app.model

import com.GhostKey.app.native.NativeContract
import com.GhostKey.app.native.NativeContract.Stage
import kotlin.math.exp

/**
 * 五阶段流水线的权威状态机（纯 Kotlin，无 Android 依赖，便于单测）。
 *
 * 之所以把它独立出来：以前逐阶段状态是 UI 从「当前阶段 + 状态串」反推的，
 * 于是出现「节点一直在转圈」「没跑的阶段被刷成成功」这类与实际情况不符的显示。
 * 现在每一次宿主上报都是一次显式事件，状态只在这里变更，UI 只负责渲染。
 */
class StageTracker(
    private val count: Int = Stage.ORDERED.size,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val states = MutableList(count) { StageState.PENDING }
    private val durations = LongArray(count)
    /** 各阶段开始时刻；-1 表示尚未开始（不能用 0 做哨兵，否则 t=0 启动的阶段耗时会被误判）。 */
    private val startedAt = LongArray(count) { -1L }

    /**
     * 进度下限（单调性地保证）。
     *
     * 收尾（finish/stop）时正在跑的阶段原本按「权重 × 90% × 爬升比」贡献进度，
     * 一旦被收尾成 FAILED / STOPPED 贡献归零，进度条会肉眼可见地往回跳。
     * 因此在收尾改变状态之前先把当前进度定格为下限，progress() 永不低于它。
     */
    private var progressFloor = 0f

    data class Snapshot(val states: List<StageState>, val durations: List<Long>)

    fun snapshot(): Snapshot = Snapshot(states.toList(), durations.toList())

    /**
     * 接收一次阶段上报。
     *
     * 关键：任何阶段进入新状态时，都会先把其它仍在 RUNNING 的阶段收尾，
     * 避免「上一个还在转圈、下一个已经在跑」这种双转圈。
     */
    fun onEvent(index: Int, status: String, now: Long = clock()) {
        val i = index.coerceIn(0, count - 1)
        for (k in 0 until count) {
            if (k != i && states[k] == StageState.RUNNING) {
                durations[k] = (now - startedAt[k]).coerceAtLeast(0L)
                states[k] = StageState.SUCCESS
            }
        }
        fun close() {
            if (startedAt[i] >= 0L) durations[i] = (now - startedAt[i]).coerceAtLeast(0L)
        }
        when (status) {
            NativeContract.Status.RUNNING -> {
                if (states[i] != StageState.RUNNING) startedAt[i] = now
                states[i] = StageState.RUNNING
            }            NativeContract.Status.OK -> {
                if (states[i] == StageState.RUNNING) close()
                states[i] = StageState.SUCCESS
            }
            NativeContract.Status.DEGRADED -> {
                if (states[i] == StageState.RUNNING) close()
                states[i] = StageState.DEGRADED
            }
            NativeContract.Status.FAILED -> {
                if (states[i] == StageState.RUNNING) close()
                states[i] = StageState.FAILED
            }
            NativeContract.Status.SKIPPED -> states[i] = StageState.SKIPPED
        }
    }

    /** 流水线结束：仍在跑的按 exit 判定，没跑到的标记「跳过」（不再刷成成功）。 */
    fun finish(exitCode: Int, now: Long = clock()) {
        progressFloor = maxOf(progressFloor, progress(now))
        val ok = exitCode == 0
        for (i in 0 until count) {
            when (states[i]) {
                StageState.RUNNING -> {
                    durations[i] = (now - startedAt[i]).coerceAtLeast(0L)
                    states[i] = if (ok) StageState.SUCCESS else StageState.FAILED
                }
                StageState.PENDING -> states[i] = StageState.SKIPPED
                else -> Unit
            }
        }
    }

    /** 用户中断：与失败区分，未开始的记「跳过」。 */
    fun stop(now: Long = clock()) {
        progressFloor = maxOf(progressFloor, progress(now))
        for (i in 0 until count) {
            when (states[i]) {
                StageState.RUNNING -> {
                    durations[i] = (now - startedAt[i]).coerceAtLeast(0L)
                    states[i] = StageState.STOPPED
                }
                StageState.PENDING -> states[i] = StageState.SKIPPED
                else -> Unit
            }
        }
    }

    /** 运行中的阶段按当前时刻刷新耗时，用于让耗时数字持续增长。 */
    fun liveDurations(now: Long = clock()): List<Long> =
        List(count) { i ->
            if (states[i] == StageState.RUNNING && startedAt[i] >= 0L) {
                (now - startedAt[i]).coerceAtLeast(0L)
            } else {
                durations[i]
            }
        }

    /**
     * 加权进度：已结束阶段计满权重，进行中阶段按预期耗时平滑爬升（封顶该段 90%）。
     * 这样「正在跑」时进度条会持续前进，而不是卡在某个百分比不动。
     * 返回值受 progressFloor 钳制，全程单调不减。
     */
    fun progress(now: Long = clock()): Float {
        var done = 0f
        for (i in 0 until count) {
            val w = Stage.WEIGHTS.getOrElse(i) { 0f }
            when (states[i]) {
                StageState.SUCCESS, StageState.DEGRADED, StageState.SKIPPED -> done += w
                StageState.RUNNING -> {
                    val t = (now - startedAt[i]).coerceAtLeast(0L)
                    val expected = Stage.EXPECTED_MS.getOrElse(i) { 1_000L }
                    val ratio = (1.0 - exp(-t.toDouble() / expected.toDouble())).toFloat()
                    done += w * (ratio * 0.9f)
                }
                else -> Unit
            }
        }
        val totalW = Stage.WEIGHTS.sum()
        val p = if (totalW <= 0f) 0f else (done / totalW).coerceIn(0f, 1f)
        return maxOf(p, progressFloor)
    }

    fun reset() {
        for (i in 0 until count) {
            states[i] = StageState.PENDING
            durations[i] = 0L
            startedAt[i] = -1L
        }
        progressFloor = 0f
    }
}
