package com.GhostKey.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 运行日志仓库的纯文件逻辑单测（不依赖 Android 环境，目录可注入）。
 *
 * 覆盖：一次运行的写入→解析闭环、结果判定（成功/失败/停止/中断/进行中）、
 * 正文抽取、序号延续、删除与清空、以及超出保留上限时的裁剪。
 */
class RunLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = RunLogStore(tmp.newFolder("run_logs"))

    @Test
    fun `成功运行能被完整解析出来`() {
        val s = store()
        val session = s.begin(1, "Xiaomi 23127PN0CC", "6.6.118-android15-8", "k90.conf")
        session.append("<s> step one")
        session.append("<b> step two")
        session.finish(RunResult.SUCCESS, 0, 12_000L)

        val runs = s.listRuns()
        assertEquals(1, runs.size)
        val meta = runs.first()
        assertEquals(1, meta.runId)
        assertEquals(RunResult.SUCCESS, meta.result)
        assertEquals(0, meta.exitCode)
        assertEquals(12_000L, meta.durationMs)
        assertEquals(2, meta.lineCount)
        assertEquals("Xiaomi 23127PN0CC", meta.device)
        assertEquals("6.6.118-android15-8", meta.kernel)
        assertEquals("k90.conf", meta.profile)
        assertTrue(meta.startedAt > 0L)

        val body = s.readBody(meta)
        assertEquals(2, body.size)
        assertTrue(body[0].endsWith("<s> step one"))
        assertTrue(body[1].endsWith("<b> step two"))
    }

    @Test
    fun `失败运行保留 exit code`() {
        val s = store()
        s.begin(2, "", "", "").apply {
            append("boom")
            finish(RunResult.FAILED, 8, 1_000L)
        }
        val meta = s.listRuns().single()
        assertEquals(RunResult.FAILED, meta.result)
        assertEquals(8, meta.exitCode)
    }

    @Test
    fun `没收尾的文件判定为中断`() {
        val s = store()
        // 直接构造一个没有 footer 的文件（模拟进程被杀）
        val f = tmp.newFile("run_logs/run-20260101-000000-0009.log")
        f.writeText(
            """
            # run=9
            # start_ms=1767225600000
            === log ===
            [00:00:00] orphan line
            """.trimIndent(),
        )
        val meta = s.listRuns().single()
        assertEquals(RunResult.INTERRUPTED, meta.result)
        assertEquals(9, meta.runId)
        assertNull(meta.exitCode)
        assertEquals(listOf("[00:00:00] orphan line"), s.readBody(meta))
    }

    @Test
    fun `未结束的会话标记为进行中`() {
        val s = store()
        val session = s.begin(3, "", "", "")
        session.append("running...")
        val meta = s.listRuns().single()
        assertEquals(RunResult.RUNNING, meta.result)
        // 收尾后同一文件变为成功
        session.finish(RunResult.SUCCESS, 0, 100L)
        assertEquals(RunResult.SUCCESS, s.listRuns().single().result)
    }

    @Test
    fun `停止的运行记为已停止`() {
        val s = store()
        s.begin(4, "", "", "").apply {
            append("x")
            finish(RunResult.STOPPED, null, 500L)
        }
        assertEquals(RunResult.STOPPED, s.listRuns().single().result)
    }

    @Test
    fun `序号从已有最大值续上`() {
        val s = store()
        assertEquals(1, s.nextRunId())
        s.begin(1, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
        assertEquals(2, s.nextRunId())
        s.begin(2, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
        assertEquals(3, s.nextRunId())
    }

    @Test
    fun `列表按开始时间倒序`() {
        val s = store()
        val older = s.begin(1, "", "", "")
        older.append("old")
        older.finish(RunResult.SUCCESS, 0, 1L)
        // 把第一个文件的开头时间改成更早，确保排序依据是 start_ms 而不是文件名
        val oldMeta = s.listRuns().single()
        val text = oldMeta.file.readText().replace(Regex("# start_ms=\\d+"), "# start_ms=1")
        oldMeta.file.writeText(text)

        s.begin(2, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
        val runs = s.listRuns()
        assertEquals(2, runs.size)
        assertEquals(2, runs.first().runId) // 最新在最前
        assertEquals(1, runs.last().runId)
    }

    @Test
    fun `正文不含头部键值`() {
        val s = store()
        s.begin(1, "dev", "kern", "prof").apply {
            append("only line")
            finish(RunResult.SUCCESS, 0, 1L)
        }
        val text = s.textOf(s.listRuns().single())
        assertTrue(text.contains("# 第 1 次运行"))
        assertTrue(text.contains("# 设备：dev"))
        assertTrue(text.contains("only line"))
    }

    @Test
    fun `单条记录可删除`() {
        val s = store()
        s.begin(1, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
        val meta = s.listRuns().single()
        s.delete(meta)
        assertTrue(s.listRuns().isEmpty())
    }

    @Test
    fun `清空会移除全部记录`() {
        val s = store()
        repeat(3) { i ->
            s.begin(i + 1, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
        }
        assertEquals(3, s.listRuns().size)
        s.clear()
        assertTrue(s.listRuns().isEmpty())
    }

    @Test
    fun `超过保留上限时只留最新的`() {
        val s = store()
        // 显式把 lastModified 拉开，否则同毫秒内建 45 个文件时裁剪顺序不确定
        repeat(45) { i ->
            val runId = i + 1
            s.begin(runId, "", "", "").finish(RunResult.SUCCESS, 0, 1L)
            val meta = s.listRuns().first { it.runId == runId }
            meta.file.setLastModified(1_000L * runId)
        }
        val ids = s.listRuns().map { it.runId }.toSet()
        assertEquals(40, ids.size)
        // 最旧的 5 次应被裁掉，最新的保留
        assertTrue("runId 1 应被裁掉，实际 $ids", !ids.contains(1))
        assertTrue("runId 5 应被裁掉，实际 $ids", !ids.contains(5))
        assertTrue("runId 6 应保留，实际 $ids", ids.contains(6))
        assertTrue("runId 45 应保留，实际 $ids", ids.contains(45))
    }

    @Test
    fun `空目录返回空列表而不是崩溃`() {
        val dir = tmp.newFolder("empty_run_logs")
        assertTrue(RunLogStore(dir).listRuns().isEmpty())
        assertEquals(1, RunLogStore(dir).nextRunId())
    }
}
