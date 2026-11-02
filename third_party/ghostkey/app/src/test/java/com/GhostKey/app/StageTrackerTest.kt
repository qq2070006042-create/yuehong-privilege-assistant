package com.GhostKey.app

import com.GhostKey.app.model.StageState
import com.GhostKey.app.model.StageTracker
import com.GhostKey.app.native.NativeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对「UI 步进器与实际不符」的回归验证。
 * 每条用例都对应一个真机上出现过的错乱现象。
 */
class StageTrackerTest {

    private val S = NativeContract.Status

    @Test
    fun `degraded 必须结束该阶段，不能一直停在 RUNNING`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(1, S.RUNNING, 1_000L)
        t.onEvent(1, S.DEGRADED, 3_000L)
        // 旧实现没有 degraded 分支，节点会永远转圈
        assertEquals(StageState.DEGRADED, t.snapshot().states[1])
        assertEquals(2_000L, t.snapshot().durations[1])
    }

    @Test
    fun `阶段跳跃时前一个 RUNNING 必须被收尾，不允许两个同时转圈`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 100L)
        // 宿主漏发了 0 的终态，直接上报阶段 2 开始
        t.onEvent(2, S.RUNNING, 200L)
        val s = t.snapshot().states
        assertEquals(StageState.SUCCESS, s[0])
        assertEquals(StageState.RUNNING, s[2])
        assertEquals(1, s.count { it == StageState.RUNNING })
    }

    @Test
    fun `结束时未执行的阶段标记 SKIPPED，不能被刷成成功`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.OK, 100L)
        t.onEvent(1, S.RUNNING, 100L)
        t.onEvent(1, S.OK, 200L)
        t.finish(0, 300L)
        val s = t.snapshot().states
        assertEquals(StageState.SUCCESS, s[0])
        assertEquals(StageState.SUCCESS, s[1])
        // 2/3/4 压根没跑
        assertEquals(StageState.SKIPPED, s[2])
        assertEquals(StageState.SKIPPED, s[3])
        assertEquals(StageState.SKIPPED, s[4])
    }

    @Test
    fun `失败收尾：正在跑的阶段变 FAILED，其余变 SKIPPED`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.OK, 50L)
        t.onEvent(1, S.RUNNING, 50L)
        t.finish(5, 90L)
        assertEquals(StageState.FAILED, t.snapshot().states[1])
        assertEquals(StageState.SKIPPED, t.snapshot().states[3])
    }

    @Test
    fun `停止：RUNNING 变 STOPPED 而不是 FAILED，PENDING 变 SKIPPED`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.OK, 10L)
        t.onEvent(1, S.RUNNING, 10L)
        t.stop(40L)
        assertEquals(StageState.STOPPED, t.snapshot().states[1])
        assertEquals(30L, t.snapshot().durations[1])
        assertEquals(StageState.SKIPPED, t.snapshot().states[2])
    }

    @Test
    fun `KSU 跳过后保持 SKIPPED，不会被结束流程改成别的`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(4, S.SKIPPED, 10L)
        t.finish(0, 20L)
        assertEquals(StageState.SKIPPED, t.snapshot().states[4])
    }

    @Test
    fun `进行中阶段的进度随时间增长，且全程单调不减`() {
        val t = StageTracker(clock = { 0L })
        var last = 0f
        t.onEvent(0, S.RUNNING, 0L)
        // 模拟校准阶段跑了 30 秒
        for (ms in 0L..30_000L step 1_000L) {
            val p = t.progress(ms)
            assertTrue("进度回退了: $p < $last", p >= last)
            assertTrue("进度越界: $p", p in 0f..1f)
            last = p
        }
        assertTrue("长时间执行后进度仍为 0", last > 0f)
        // 单阶段权重 13%，封顶 90% → 不应超过 13%
        assertTrue("单阶段进度超出上限: $last", last <= 0.14f)
    }

    @Test
    fun `失败收尾后进度保持不回退`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.OK, 1_000L)
        t.onEvent(1, S.RUNNING, 1_000L)
        // 阶段 1 已爬升一段时间
        val before = t.progress(20_000L)
        t.finish(5, 21_000L)
        val after = t.progress(21_000L)
        assertTrue(
            "失败收尾导致进度回退: $after < $before",
            after >= before - 0.0001f,
        )
    }

    @Test
    fun `停止后进度保持不回退`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        val before = t.progress(10_000L)
        t.stop(11_000L)
        val after = t.progress(11_000L)
        assertTrue("停止导致进度回退: $after < $before", after >= before - 0.0001f)
    }

    @Test
    fun `重复 RUNNING 上报不重置计时起点`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        // 重试场景：同阶段再次上报 running
        t.onEvent(0, S.RUNNING, 5_000L)
        t.onEvent(0, S.OK, 8_000L)
        // 耗时从首次开始算 8s，而不是被重置成 3s
        assertEquals(8_000L, t.snapshot().durations[0])
    }

    @Test
    fun `重复上报同一事件不会破坏状态`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.RUNNING, 10L)
        t.onEvent(0, S.OK, 20L)
        t.onEvent(0, S.OK, 30L)
        assertEquals(StageState.SUCCESS, t.snapshot().states[0])
    }

    @Test
    fun `reset 后回到全 PENDING`() {
        val t = StageTracker(clock = { 0L })
        t.onEvent(0, S.RUNNING, 0L)
        t.onEvent(0, S.OK, 10L)
        t.reset()
        assertTrue(t.snapshot().states.all { it == StageState.PENDING })
        assertTrue(t.snapshot().durations.all { it == 0L })
        assertEquals(0f, t.progress(0L), 0.0001f)
    }
}
