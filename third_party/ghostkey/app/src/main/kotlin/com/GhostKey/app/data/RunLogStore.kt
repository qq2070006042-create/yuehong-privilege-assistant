package com.GhostKey.app.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次运行的结局。 */
enum class RunResult(val label: String) {
    RUNNING("进行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    STOPPED("已停止"),
    INTERRUPTED("中断"),
}

/**
 * 一次运行的元信息（从日志文件的头/尾部解析得到，不含正文）。
 *
 * 只解析头尾而不整文件读取，是为了让「运行列表」在几十个记录下依然瞬时打开。
 */
data class RunLogMeta(
    val file: File,
    /** 第几次运行（跨重启单调递增，由 [RunLogStore.nextRunId] 保证）。 */
    val runId: Int,
    /** 开始时间（epoch 毫秒）。 */
    val startedAt: Long,
    val result: RunResult,
    val exitCode: Int?,
    val durationMs: Long,
    /** 正文行数；进行中或被中断的运行没有收尾行，为 null。 */
    val lineCount: Int?,
    val device: String,
    val kernel: String,
    val profile: String,
)

/**
 * 运行日志仓库：**每一次 ROOT 运行一个文件**，落在 `filesDir/run_logs/`。
 *
 * 为什么落盘：日志原先只存在于 ViewModel 内存里，进程一被杀（ROOT 流程后
 * 重启设备/清后台都很常见）就全部消失，「每一次运行的记录」这条需求根本立不住。
 * 落盘后列表页直接扫目录，重启也还在。
 *
 * 文件格式（纯文本；头部与尾部是机器可读键值，中间是原文日志）：
 * ```
 * # run=3
 * # start_ms=1761672000000
 * # device=Xiaomi 23127PN0CC
 * # kernel=6.6.118-android15-8-...
 * # profile=k90-ab16143355.conf
 * === log ===
 * [02:10:33] <s> checking Shizuku status...
 * ...
 * === end ===
 * # result=SUCCESS
 * # exit=0
 * # end_ms=1761672209000
 * # duration_ms=209000
 * # lines=42
 * ```
 * 缺少 `# result=` 的文件说明运行途中进程被杀，判定为 [RunResult.INTERRUPTED]；
 * 仍在写入的会话判定为 [RunResult.RUNNING]。
 */
class RunLogStore(private val dir: File) {

    /**
     * 生产入口：日志落在应用私有目录 `filesDir/run_logs/`。
     * 目录本身可注入（见主构造），这样纯文件逻辑能在 JVM 单测里直接跑，不需要 Android 环境。
     */
    constructor(context: Context) : this(File(context.filesDir, DIR))

    /** 正在写入的文件名集合：用来把「还没写收尾行」的当下运行标记成 RUNNING。 */
    private val active = mutableSetOf<String>()

    /** 开始一次运行，返回可追加/收尾的会话。 */
    @Synchronized
    fun begin(runId: Int, device: String, kernel: String, profile: String): Session {
        if (!dir.exists()) dir.mkdirs()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val seq = runId.toString().padStart(4, '0')
        var file = File(dir, "run-$stamp-$seq.log")
        var dup = 1
        while (file.exists()) {
            file = File(dir, "run-$stamp-$seq-$dup.log")
            dup++
        }
        runCatching { file.createNewFile() }
        active.add(file.name)
        val session = Session(file, runId, now)
        session.writeHeader(sanitize(device), sanitize(kernel), sanitize(profile))
        prune()
        return session
    }

    /** 全部运行记录，按开始时间**倒序**（最新在最前）。 */
    @Synchronized
    fun listRuns(): List<RunLogMeta> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files
            .mapNotNull { f -> runCatching { parseMeta(f) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    /** 下一次运行的序号（已有最大序号 + 1）。跨重启延续编号，不会回到 1。 */
    @Synchronized
    fun nextRunId(): Int = (listRuns().maxOfOrNull { it.runId } ?: 0) + 1

    /** 读取某次运行的日志正文（去掉头尾键值块）。IO 密集，请放在后台线程。 */
    fun readBody(meta: RunLogMeta): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            meta.file.useLines { seq ->
                var inBody = false
                for (raw in seq) {
                    val line = raw.trimEnd('\r')
                    when {
                        line == BODY_BEGIN -> inBody = true
                        line == BODY_END -> inBody = false
                        inBody -> out.add(line)
                    }
                }
            }
        }
        return out
    }

    /** 某次运行的完整文本（信息头 + 正文），用于导出/复制。 */
    fun textOf(meta: RunLogMeta): String = buildString {
        appendLine("# 第 ${meta.runId} 次运行")
        appendLine("# 开始：" + fmtFull(meta.startedAt))
        if (meta.device.isNotBlank()) appendLine("# 设备：" + meta.device)
        if (meta.kernel.isNotBlank()) appendLine("# 内核：" + meta.kernel)
        if (meta.profile.isNotBlank()) appendLine("# Profile：" + meta.profile)
        val code = meta.exitCode?.let { " (exit=$it)" } ?: ""
        appendLine("# 结果：" + meta.result.label + code + " · 耗时 " + fmtDuration(meta.durationMs))
        appendLine()
        readBody(meta).forEach { appendLine(it) }
    }

    /** 全部运行记录按时间正序（旧→新）拼接，用于「导出全部」。 */
    fun exportAllText(): String = buildString {
        val all = listRuns().sortedBy { it.startedAt }
        all.forEachIndexed { i, meta ->
            if (i > 0) appendLine()
            appendLine("=".repeat(48))
            appendLine(textOf(meta))
        }
    }.trimEnd()

    fun delete(meta: RunLogMeta) {
        synchronized(this) { active.remove(meta.file.name) }
        runCatching { meta.file.delete() }
    }

    @Synchronized
    fun clear() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return
        for (f in files) {
            if (!active.contains(f.name)) runCatching { f.delete() }
        }
    }


    /** 只保留最近 [MAX_RUNS] 次，超出时删最旧的（不动正在写的）。 */
    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return
        if (files.size <= MAX_RUNS) return
        files.sortedBy { it.lastModified() }
            .dropLast(MAX_RUNS)
            .filterNot { active.contains(it.name) }
            .forEach { runCatching { it.delete() } }
    }

    private fun parseMeta(file: File): RunLogMeta {
        var runId = 0
        var startMs = 0L
        var device = ""
        var kernel = ""
        var profile = ""
        for (line in readHeadLines(file, 8)) {
            when {
                line.startsWith(K_RUN) -> runId = line.removePrefix(K_RUN).trim().toIntOrNull() ?: 0
                line.startsWith(K_START) -> startMs = line.removePrefix(K_START).trim().toLongOrNull() ?: 0L
                line.startsWith(K_DEVICE) -> device = line.removePrefix(K_DEVICE).trim()
                line.startsWith(K_KERNEL) -> kernel = line.removePrefix(K_KERNEL).trim()
                line.startsWith(K_PROFILE) -> profile = line.removePrefix(K_PROFILE).trim()
            }
        }
        var result: RunResult? = null
        var exitCode: Int? = null
        var duration = 0L
        var lines: Int? = null
        for (line in readTailLines(file, 6)) {
            when {
                line.startsWith(K_RESULT) ->
                    result = runCatching { RunResult.valueOf(line.removePrefix(K_RESULT).trim()) }.getOrNull()
                line.startsWith(K_EXIT) -> exitCode = line.removePrefix(K_EXIT).trim().toIntOrNull()
                line.startsWith(K_DURATION) -> duration = line.removePrefix(K_DURATION).trim().toLongOrNull() ?: 0L
                line.startsWith(K_LINES) -> lines = line.removePrefix(K_LINES).trim().toIntOrNull()
            }
        }
        if (runId <= 0) {
            runId = Regex("-([0-9]{4,})(?:-[0-9]+)?\\.log$").find(file.name)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }
        if (startMs <= 0L) startMs = file.lastModified()
        val finalResult = when {
            active.contains(file.name) -> RunResult.RUNNING
            else -> result ?: RunResult.INTERRUPTED
        }
        return RunLogMeta(
            file = file,
            runId = runId,
            startedAt = startMs,
            result = finalResult,
            exitCode = exitCode,
            durationMs = duration,
            lineCount = lines,
            device = device,
            kernel = kernel,
            profile = profile,
        )
    }

    /** 逐字节读取前 [maxLines] 行（只读头部几十字节，不整文件读）。 */
    private fun readHeadLines(file: File, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val sb = StringBuilder()
                var pos = 0L
                val len = raf.length()
                while (pos < len && out.size < maxLines) {
                    raf.seek(pos)
                    val b = raf.read()
                    if (b < 0) break
                    pos++
                    val c = b.toChar()
                    if (c == '\n') {
                        out.add(sb.toString())
                        sb.setLength(0)
                    } else if (c != '\r') {
                        sb.append(c)
                    }
                }
                if (out.size < maxLines && sb.isNotEmpty()) out.add(sb.toString())
            }
        }
        return out
    }

    /** 读文件末尾 4KB 再取最后 [maxLines] 行（用于收尾键值）。 */
    private fun readTailLines(file: File, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = if (len > TAIL_BYTES) len - TAIL_BYTES else 0L
                raf.seek(start)
                val buf = ByteArray((len - start).toInt().coerceAtLeast(0))
                raf.readFully(buf)
                val text = String(buf, Charsets.UTF_8)
                val parts = text.split('\n').map { it.trimEnd('\r') }
                val usable = if (start > 0 && parts.isNotEmpty()) parts.drop(1) else parts
                out.addAll(usable.filter { it.isNotBlank() }.takeLast(maxLines))
            }
        }
        return out
    }

    /** 一次运行的写入会话。追加即落盘（每行 flush），进程被杀也只丢最后半行。 */
    inner class Session internal constructor(
        private val file: File,
        private val runId: Int,
        private val startedAt: Long,
    ) {
        private var writer: BufferedWriter? = null
        private var lineCount = 0
        private var finished = false

        internal fun writeHeader(device: String, kernel: String, profile: String) {
            writer = runCatching { BufferedWriter(FileWriter(file, true)) }.getOrNull()
            val w = writer ?: return
            runCatching {
                w.write(K_RUN + runId); w.newLine()
                w.write(K_START + startedAt); w.newLine()
                if (device.isNotBlank()) { w.write(K_DEVICE + device); w.newLine() }
                if (kernel.isNotBlank()) { w.write(K_KERNEL + kernel); w.newLine() }
                if (profile.isNotBlank()) { w.write(K_PROFILE + profile); w.newLine() }
                w.write(BODY_BEGIN); w.newLine()
                w.flush()
            }
        }

        fun append(message: String) {
            synchronized(this) {
                if (finished) return
                val w = writer ?: return
                runCatching {
                    w.write("[" + hms() + "] " + message)
                    w.newLine()
                    w.flush()
                    lineCount++
                }
            }
        }

        fun finish(result: RunResult, exitCode: Int?, durationMs: Long) {
            synchronized(this) {
                if (finished) return
                finished = true
                val w = writer
                if (w != null) {
                    runCatching {
                        w.write(BODY_END); w.newLine()
                        w.write(K_RESULT + result.name); w.newLine()
                        if (exitCode != null) { w.write(K_EXIT + exitCode); w.newLine() }
                        w.write(K_END_MS + System.currentTimeMillis()); w.newLine()
                        w.write(K_DURATION + durationMs); w.newLine()
                        w.write(K_LINES + lineCount); w.newLine()
                        w.flush()
                        w.close()
                    }
                }
                writer = null
                synchronized(this@RunLogStore) { active.remove(file.name) }
            }
        }
    }

    private fun sanitize(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()

    companion object {
        private const val DIR = "run_logs"
        private const val EXT = ".log"
        private const val BODY_BEGIN = "=== log ==="
        private const val BODY_END = "=== end ==="
        private const val K_RUN = "# run="
        private const val K_START = "# start_ms="
        private const val K_DEVICE = "# device="
        private const val K_KERNEL = "# kernel="
        private const val K_PROFILE = "# profile="
        private const val K_RESULT = "# result="
        private const val K_EXIT = "# exit="
        private const val K_END_MS = "# end_ms="
        private const val K_DURATION = "# duration_ms="
        private const val K_LINES = "# lines="
        private const val TAIL_BYTES = 4096L

        /** 最多保留的运行记录数（超出删最旧）。 */
        private const val MAX_RUNS = 40

        private fun hms(): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        private fun fmtFull(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

        fun fmtDuration(ms: Long): String {
            if (ms <= 0L) return "—"
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return if (m > 0) "${m}分${s}秒" else "${s}秒"
        }
    }
}
