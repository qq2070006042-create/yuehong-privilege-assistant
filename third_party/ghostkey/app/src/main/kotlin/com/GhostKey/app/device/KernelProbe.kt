package com.GhostKey.app.device

import java.io.File

/**
 * 内核版本探测：在「未提权」的应用上下文中稳定读取 uname -r。
 *
 * 不同机型 / ROM 下可用来源不同，依次尝试、取第一个非空结果：
 *  1. uname -r        —— 通过 Runtime.exec 执行系统二进制，普通应用通常可读；
 *                       实测 ColorOS / Find X8U 上 /proc/version 对普通应用不可读，
 *                       此路是兜底主路径。
 *  2. /proc/version   —— 部分 ROM 对普通应用不可读（会抛异常），作为补充。
 *  3. os.version 属性 —— 部分机型为空。
 */
object KernelProbe {

    fun read(): String =
        candidates().firstOrNull { it.isNotBlank() } ?: "unknown"

    private fun candidates(): List<String> = listOf(
        runUname(),
        readProcVersion(),
        System.getProperty("os.version").orEmpty(),
    )

    private fun runUname(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("uname", "-r"))
        val out = p.inputStream.bufferedReader().use { it.readLine() }?.trim().orEmpty()
        runCatching { p.destroy() }
        out
    }.getOrDefault("")

    private fun readProcVersion(): String = runCatching {
        val line = File("/proc/version").bufferedReader().use { it.readLine() }.orEmpty()
        Regex("Linux version (\\S+)").find(line)?.groupValues?.get(1) ?: line.trim()
    }.getOrDefault("")
}
