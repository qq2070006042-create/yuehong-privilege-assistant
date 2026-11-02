package com.GhostKey.app

import com.GhostKey.app.ksu.KsudStage
import com.GhostKey.app.ksu.KsudStage.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KSUD_STAGE（管理器提取 ksud）脚本与输出解析测试。 */
class KsudStageTest {

    @Test
    fun `解析成功标记`() {
        val out = "KSUD_STAGE_OK md5=abc123 size=6402736\n"
        val r = KsudStage.parse(out)
        assertEquals(Outcome.Ok("abc123", 6402736L), r)
    }

    @Test
    fun `解析失败标记`() {
        assertEquals(
            Outcome.Fail("manager_not_installed"),
            KsudStage.parse("KSUD_STAGE_FAIL manager_not_installed\n"),
        )
        assertEquals(
            Outcome.Fail("no_source apk=/data/app/xx/base.apk"),
            KsudStage.parse("KSUD_STAGE_FAIL no_source apk=/data/app/xx/base.apk"),
        )
    }

    @Test
    fun `无输出视为执行异常`() {
        assertEquals(Outcome.Fail("no_output"), KsudStage.parse(""))
        assertEquals(Outcome.Fail("no_output"), KsudStage.parse("sh: pm: not found\n"))
    }

    @Test
    fun `取最后一个标记行`() {
        val out = "KSUD_STAGE_FAIL no_source apk=a\nKSUD_STAGE_OK md5=ff size=10\n"
        val r = KsudStage.parse(out)
        assertTrue(r is Outcome.Ok)
    }

    @Test
    fun `脚本是合法的最小 shell 形状`() {
        val s = KsudStage.SCRIPT
        // 关键元素：候选包名、pm path、两路提取、KSUD_STAGE 标记
        assertTrue(s.contains("me.weishu.kernelsu"))
        assertTrue(s.contains("pm path"))
        assertTrue(s.contains("lib/arm64/libksud.so"))
        assertTrue(s.contains("lib/arm64-v8a/libksud.so"))
        assertTrue(s.contains("KSUD_STAGE_OK"))
        assertTrue(s.contains("KSUD_STAGE_FAIL"))
        // 目标路径与常量一致
        assertTrue(s.contains("T=${KsudStage.TARGET}"))
    }
}
