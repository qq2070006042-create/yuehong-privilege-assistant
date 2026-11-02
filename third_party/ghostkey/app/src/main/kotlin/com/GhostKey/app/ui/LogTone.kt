package com.GhostKey.app.ui

/**
 * 日志语义色调。主页终端与日志详情页共用同一套判定，避免两边颜色对不上。
 *
 * 顺序即优先级：显式错误标记 > error/fail 字样 > warn > 系统层(<s>) > 流程(<b>) > 普通输出。
 * 注意：warn 单独成一档（主页给它黄色底），不与 error 合并。
 */
enum class LogTone { SYSTEM, FLOW, ERROR, WARN, PLAIN }

/** 依据日志原文判定色调（与原生日志的英文标签对齐）。 */
fun logToneOf(line: String): LogTone = when {
    line.startsWith("<->") -> LogTone.ERROR
    line.contains("error", ignoreCase = true) ||
        line.contains("fail", ignoreCase = true) -> LogTone.ERROR
    line.contains("warn", ignoreCase = true) -> LogTone.WARN
    line.startsWith("<s>") -> LogTone.SYSTEM
    line.startsWith("<b>") -> LogTone.FLOW
    else -> LogTone.PLAIN
}
