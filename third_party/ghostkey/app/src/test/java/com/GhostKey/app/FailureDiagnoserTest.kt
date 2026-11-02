package com.GhostKey.app

import com.GhostKey.app.engine.FailureDiagnoser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失败归因规则表测试（纯函数，无 Android 依赖）。 */
class FailureDiagnoserTest {

    @Test
    fun `calib_a slide failure hits KASLR rule`() {
        val logs = listOf(
            "[calib_a] profile loaded, kernel 6.6.118-android15-8",
            "[calib_a] collected 2048 records",
            "[calib_a] error: no plausible slide candidate",
            "[pipeline] stage calib_a failed (exit=3)",
        )
        val d = FailureDiagnoser.diagnose(logs)
        assertNotNull(d)
        assertEquals("calib_a", d!!.stage)
        assertTrue(d.title.contains("KASLR"))
        assertTrue(d.suggestions.isNotEmpty())
        assertTrue(d.causes.isNotEmpty())
    }

    @Test
    fun `calib_a no records found hits KASLR rule not fallback`() {
        val logs = listOf(
            "[calib_a] collecting call sites (timer_link=0xffffffc080195d50, probe 2048 / hold 2s)",
            "[calib_a] captured raw=12345 bytes",
            "[calib_a] error: no k_itimer records found (multi-size probe)",
        )
        val d = FailureDiagnoser.diagnose(logs)
        // 必须命中 calib_a 规则，而不是回落到“未命中已知失败模式 / 多数为偶发”的兜底
        assertNotNull(d)
        assertEquals("calib_a", d!!.stage)
        assertTrue(d.title.contains("KASLR"))
        assertTrue(d.suggestions.none { it.contains("多数为偶发") })
    }

    @Test
    fun `inject rootd timeout hits timeout rule`() {
        val logs = listOf(
            "[calib_a] ok",
            "[calib_b] ok",
            "[inject] error: rootd not ready in time",
        )
        val d = FailureDiagnoser.diagnose(logs)
        assertNotNull(d)
        assertEquals("inject", d!!.stage)
        assertTrue(d.title.contains("root 通道"))
    }

    @Test
    fun `inject leftover process warns reboot`() {
        val logs = listOf(
            "[inject] error: previous round left native process(es), refusing to continue",
        )
        val d = FailureDiagnoser.diagnose(logs)
        assertNotNull(d)
        assertTrue(d!!.suggestions.any { it.contains("重启") })
    }

    @Test
    fun `ksu already live suggests reboot before rerun`() {
        val logs = listOf(
            "[ksu] error: kernelsu module already live, skip late-load",
        )
        val d = FailureDiagnoser.diagnose(logs)
        assertNotNull(d)
        assertEquals("ksu", d!!.stage)
        assertTrue(d.title.contains("驻留"))
    }

    @Test
    fun `shizuku error matched without stage tag`() {
        val logs = listOf("<s> error: Shizuku not running, cannot request permission")
        val d = FailureDiagnoser.diagnose(logs)
        assertNotNull(d)
        assertTrue(d!!.title.contains("Shizuku"))
    }

    @Test
    fun `normal progress lines do not misfire`() {
        val logs = listOf(
            "[calib_a] profile loaded ok",
            "[inject] cred plant attempt 1/3",
            "[ksu] late-load started",
        )
        assertNull(FailureDiagnoser.diagnose(logs))
    }

    @Test
    fun `unknown error falls back with exit code`() {
        val d = FailureDiagnoser.fallback(9)
        assertTrue(d.title.contains("exit=9"))
        assertTrue(d.suggestions.any { it.contains("运行日志") })
    }

    @Test
    fun `format produces terminal block lines`() {
        val d = FailureDiagnoser.diagnose(listOf("[verify] error: temporary root verify failed"))
        assertNotNull(d)
        val lines = FailureDiagnoser.format(d!!)
        assertTrue(lines.first().startsWith("<d> 诊断: "))
        assertTrue(lines.any { it.contains("可能原因") })
        assertTrue(lines.any { it.contains("建议") })
    }

    @Test
    fun `latest error wins when multiple failures present`() {
        val logs = listOf(
            "[calib_b] error: insufficient samples",
            "[calib_b] retry",
            "[inject] error: rootd not ready in time",
        )
        val d = FailureDiagnoser.diagnose(logs)
        assertEquals("inject", d!!.stage)
    }
}
