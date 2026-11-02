package com.GhostKey.app.data.ota

/**
 * 纯 Kotlin kallsyms 符号表提取器。
 *
 * 定位策略是「自洽闭合链」：每找到一个锚点就顺着结构关系推下一个，
 * 每一步都用可用来自证的不变式校验，任一步对不上就丢弃该候选。
 *
 * ```
 * kallsyms_token_index (256 个严格递增 u16, 首项 0)
 *   → kallsyms_token_table  (256 个 NUL 结尾串，累计偏移与 u16 逐一相等)
 *   → kallsyms_markers      (首项 0、严格递增；与 token_table 之间可能夹 seqs_of_names)
 *   → kallsyms_names 起点   (256 条记录推进量 == markers[1])
 *   → kallsyms_num_syms     (names 之前 u32，且 ceil(num/256) 与 markers 项数吻合)
 *   → kallsyms_offsets      (三种实测布局 + 对齐填充容错)
 *   → kallsyms_relative_base(u64 高 24 位全 1)
 * ```
 *
 * ---
 * 本轮新增/修正的关键点（都是实测踩出来的）：
 *
 * 1. **双字节长度转义**：`kallsyms_names` 记录首字节最高位为 1 时长度占两字节
 *    `len = (b0 & 0x7f) | (b1 << 7)`（内核 `kallsyms_expand_symbol()`）。
 *    按单字节解析会让长符号名多吞字节，names/markers 全线错位。
 *
 * 2. **markers 必须按 num_syms 截断**：toward token_table 方向多读一个 u32 很容易
 *    把后面 seqs 的字节误当成 markers 的尾巴（实测多出 1 项），
 *    必须截到 `ceil(num_syms/256)` 再校验。
 *
 * 3. **段间存在对齐填充**：markers→token_table、offsets→base、base→num_syms
 *    之间都可能有 0~12 字节填充，任何「严格相邻」的假设都会漏。
 *
 * 4. **三种 offsets 布局**：
 *    (a) 经典 `[base][offsets][num_syms][names]...`
 *    (b) `[offsets][base][num_syms][names]...`
 *    (c) 实测 6.12 GKI `[names][markers][token_table][token_index][offsets][base]`
 *
 * 5. **符号名必须剥掉首位类型字符**（t/T/d/D/b/B/r/R/a/A），否则查不到目标符号。
 */
internal object KallsymsExtractor {

    class KallsymsError(message: String) : RuntimeException(message)

    class Result(
        /** kallsyms_relative_base（内核镜像虚拟基址，如 0xFFFFFFC080000000）。 */
        val imageBase: Long,
        /** 去歧义后的符号表（同名不同址的符号已剔除）。 */
        val symbols: Map<String, Long>,
        val numSyms: Int,
    )

    /** 判定「解析成功」的依据：这 7 个符号必须能查到。 */
    private val TARGETS = arrayOf(
        "do_timer_create", "prepare_creds", "selinux_state",
        "init_user_ns", "root_user", "init_groups", "init_ucounts",
    )

    /** kallsyms 偏移值上限（PC 端默认 0x3000000）。 */
    private const val OFFSET_LIMIT = 0x3000000L

    private const val MAX_TOKEN_LEN = 200
    private const val MAX_TOKEN_TABLE_LEN = 4096
    private const val MIN_MARKERS = 64

    /** names 末段长度上限（256 条记录 × 单条最大 ~250 字节）。 */
    private const val MAX_TAIL_LEN = 65536

    /** markers 回溯/前扫跨度。 */
    private const val MARKERS_SPAN = 6 * 1024 * 1024

    private const val NUM_SYMS_MIN = 20000
    private const val NUM_SYMS_MAX = 400000


    private fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
            ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or
            ((d[o + 3].toLong() and 0xFF) shl 24)

    private fun u64(d: ByteArray, o: Int): Long {
        var r = 0L
        for (k in 7 downTo 0) r = (r shl 8) or (d[o + k].toLong() and 0xFF)
        return r
    }

    private fun ByteArray.indexOfByte(b: Byte, from: Int, limit: Int = size): Int {
        var i = from.coerceAtLeast(0)
        val end = minOf(limit, size)
        while (i < end) {
            if (this[i] == b) return i
            i++
        }
        return -1
    }

    /** 读一条 kallsyms_names 记录（支持双字节长度转义）。返回下一记录位置，失败返回 -1。 */
    private fun nextRecord(d: ByteArray, p: Int): Int {
        if (p < 0 || p >= d.size) return -1
        val b0 = d[p].toInt() and 0xFF
        val len: Int
        val hdr: Int
        if (b0 and 0x80 != 0) {
            if (p + 1 >= d.size) return -1
            len = (b0 and 0x7F) or ((d[p + 1].toInt() and 0xFF) shl 7)
            hdr = 2
        } else {
            len = b0
            hdr = 1
        }
        if (len < 1) return -1
        val nx = p + hdr + len
        if (nx > d.size) return -1
        return nx
    }

    /** 从 p 起走 k 条记录，返回落点；失败返回 -1。 */
    private fun walk(d: ByteArray, from: Int, k: Int): Int {
        var p = from
        var i = 0
        while (i < k) {
            p = nextRecord(d, p)
            if (p < 0) return -1
            i++
        }
        return p
    }

    private val isKernelAddr: (Long) -> Boolean = { it ushr 40 == 0xFFFFFFL }


    fun extract(data: ByteArray): Result {
        val problems = ArrayList<String>()
        var best: Result? = null
        var bestHit = -1

        for (table in anchorTokenTables(data)) {
            val tokens = try {
                readTokens(data, table)
            } catch (e: KallsymsError) {
                problems.add("token 表 0x${table.toString(16)} 读取失败")
                continue
            }
            var tableLen = 0
            for (t in tokens) tableLen += t.size + 1
            val tokenIndex = table + tableLen

            val markerCands = markersCandidates(data, table)
            if (markerCands.isEmpty()) {
                problems.add("token 表 0x${table.toString(16)} 前无 markers")
                continue
            }

            for ((markersStart, markers0) in markerCands) {
                for (nc in namesCandidates(data, markersStart, markers0)) {
                    val namesStart = nc.namesStart
                    val numSyms = nc.numSyms
                    val markers = nc.markers

                    for ((off, base) in offsetsCandidates(data, nc.numPos, numSyms, tokenIndex)) {
                        val r = build(data, off, numSyms, base, tokens, namesStart, markersStart)
                            ?: continue
                        val hit = TARGETS.count { r.symbols.containsKey(it) }
                        if (hit > bestHit) {
                            bestHit = hit
                            best = r
                        }
                        if (hit == TARGETS.size) return r
                    }
                }
            }
        }

        if (best != null && bestHit >= 1) return best

        legacyExtract(data)?.let { return it }

        val detail = problems.take(3).joinToString("；").ifEmpty { "无候选" }
        throw KallsymsError("kallsyms 提取失败（$detail）")
    }

    private fun build(
        data: ByteArray,
        offsetsOff: Int,
        numSyms: Int,
        base: Long,
        tokens: List<ByteArray>,
        namesStart: Int,
        markersStart: Int,
    ): Result? {
        if (!isKernelAddr(base)) return null
        if (offsetsOff < 0 || offsetsOff.toLong() + 4L * numSyms > data.size) return null
        val symbols = HashMap<String, Long>(numSyms * 2)
        val ambiguous = HashSet<String>()
        var p = namesStart
        for (i in 0 until numSyms) {
            val b0 = data[p].toInt() and 0xFF
            val len: Int
            if (b0 and 0x80 != 0) {
                if (p + 1 >= data.size) return null
                len = (b0 and 0x7F) or ((data[p + 1].toInt() and 0xFF) shl 7)
                p += 2
            } else {
                len = b0
                p += 1
            }
            if (len < 1 || p + len > data.size) return null
            val raw = decode(tokens, data, p, len)
            p += len
            val name = if (raw.length <= 1) raw else raw.substring(1)
            val address = base + u32(data, offsetsOff + 4 * i)
            val prev = symbols[name]
            if (prev != null && prev != address) {
                ambiguous.add(name)
            } else if (prev == null) {
                symbols[name] = address
            }
        }
        for (name in ambiguous) symbols.remove(name)
        if (p < markersStart - 8 || p > markersStart) return null
        return Result(base, symbols, numSyms)
    }


    /**
     * 全镜像搜索 `kallsyms_token_index`（256 个严格递增 u16、首项 0、步长 1..65），
     * 反推 `kallsyms_token_table` 起点并用「累计偏移逐一相等」验证。
     */
    private fun anchorTokenTables(d: ByteArray, limit: Int = 8): List<Int> {
        val out = ArrayList<Int>()
        val n = d.size
        var i = 0
        while (i + 512 < n) {
            if (d[i].toInt() != 0 || d[i + 1].toInt() != 0) {
                i++
                continue
            }
            for (cand in intArrayOf(i, i + 1)) {
                if (cand and 1 != 0 || cand + 512 > n) continue
                var prev = 0
                var ok = true
                for (k in 1 until 256) {
                    val v = u16(d, cand + 2 * k)
                    val dv = v - prev
                    if (dv < 1 || dv > 65) {
                        ok = false
                        break
                    }
                    prev = v
                }
                if (!ok) continue
                val table = deriveTokenTable(d, cand) ?: continue
                if (!out.contains(table)) {
                    out.add(table)
                    if (out.size >= limit) return out
                }
            }
            i++
        }
        return out
    }

    /** 由 token_index 反推 token_table 起点；要求 256 个串的累计偏移与 u16 数组完全一致。 */
    private fun deriveTokenTable(d: ByteArray, indexStart: Int): Int? {
        val lastOff = u16(d, indexStart + 2 * 255)
        val from = maxOf(0, indexStart - lastOff - 32)
        val to = indexStart - lastOff
        var table = from
        while (table <= to) {
            var q = table
            var ok = true
            for (k in 0 until 256) {
                if (table + u16(d, indexStart + 2 * k) != q) {
                    ok = false
                    break
                }
                val e = d.indexOfByte(0, q, q + MAX_TOKEN_LEN + 1)
                if (e < 0) {
                    ok = false
                    break
                }
                q = e + 1
            }
            if (ok && q - table in 400..MAX_TOKEN_TABLE_LEN) return table
            table++
        }
        return null
    }

    private fun readTokens(data: ByteArray, table: Int): List<ByteArray> {
        val tokens = ArrayList<ByteArray>(256)
        var p = table
        repeat(256) {
            val end = data.indexOfByte(0, p, p + MAX_TOKEN_LEN + 1)
            if (end < 0) throw KallsymsError("truncated token table")
            tokens.add(data.copyOfRange(p, end))
            p = end + 1
        }
        return tokens
    }


    /**
     * 找 markers 数组（首项 0、严格递增），返回 [(起点, 值数组)]，由近及远最多 [limit] 个。
     *
     * 两条路子：
     *  1. 紧贴 token_table 之前（允许 0..24 字节对齐填充）；
     *  2. 向前全跨度扫描（6.4+ 内核在 markers 与 token_table 之间插了
     *     `kallsyms_seqs_of_names`，3 字节/符号，实测跨度可达 300KB+）。
     */
    private fun markersCandidates(d: ByteArray, table: Int, limit: Int = 3): List<Pair<Int, IntArray>> {
        val out = ArrayList<Pair<Int, IntArray>>()
        val seen = HashSet<Int>()

        fun add(start: Int, vals: IntArray) {
            if (start < 0 || vals.size < MIN_MARKERS || vals[0] != 0) return
            if (!seen.add(start)) return
            out.add(start to vals)
        }

        for (pad in intArrayOf(0, 4, 8, 12, 16, 20, 24)) {
            val end = table - pad
            if (end < 4) continue
            val r = walkBackMarkers(d, end) ?: continue
            add(r.first, r.second)
        }
        if (out.isNotEmpty()) return out

        val lo = maxOf(4, table - MARKERS_SPAN)
        var p = table - 4
        while (p >= lo && out.size < limit) {
            if (u32(d, p) == 0L) {
                val v1 = u32(d, p + 4)
                val v2 = u32(d, p + 8)
                val v3 = u32(d, p + 12)
                if (v1 >= 256 && v1 <= 40000 && v2 > v1 && v2 <= 80000 && v3 > v2 && v3 <= 120000) {
                    val vals = ArrayList<Int>(2048)
                    var q = p
                    var prev = -1L
                    while (q + 4 <= d.size && vals.size < 4096) {
                        val v = u32(d, q)
                        if (prev >= 0 && v <= prev) break
                        if (v > 40_000_000L) break
                        vals.add(v.toInt())
                        prev = v
                        q += 4
                    }
                    if (vals.size >= MIN_MARKERS) add(p, vals.toIntArray())
                }
            }
            p -= 4
        }
        return out
    }

    /** 从 end（不含）往前读一段严格递增 u32，要求最后读到 0。 */
    private fun walkBackMarkers(d: ByteArray, end: Int): Pair<Int, IntArray>? {
        if (end > d.size) return null
        var p = end - 4
        if (p < 0) return null
        val vals = ArrayList<Int>(2048)
        while (p >= 0 && vals.size < 4096) {
            val v = u32(d, p)
            if (v == 0L) {
                vals.add(0)
                break
            }
            if (vals.isNotEmpty() && v >= vals[vals.size - 1]) return null
            if (v > 40_000_000L) return null
            vals.add(v.toInt())
            p -= 4
        }
        if (vals.size < MIN_MARKERS || vals[vals.size - 1] != 0) return null
        vals.reverse()
        return (end - 4 * vals.size) to vals.toIntArray()
    }


    private class NamesCand(
        val namesStart: Int,
        val numPos: Int,
        val numSyms: Int,
        val markers: IntArray,
    )

    /**
     * 由 markers 反推 names 起点与 num_syms。
     *
     * 切入点：`walk(namesStart, 256) == namesStart + markers[1]`
     * （组推进量等于 markers 相邻差值），这是最快的筛选条件。
     * 搜索窗口用 markers 的尾部值估计（真数组可能被多读 1~2 项，故按
     * 「项数 = ceil(num/256) 且 num ≈ 项数×256」枚举几个可能的尾值）。
     */
    private fun namesCandidates(d: ByteArray, markersStart: Int, markers0: IntArray): List<NamesCand> {
        val out = ArrayList<NamesCand>()
        if (markers0.size < 2) return out
        val tgt = markers0[1] - markers0[0]
        if (tgt <= 0) return out

        val guesses = ArrayList<Int>()
        for (k in 0..2) {
            val m = markers0.size - k
            if (m >= 8) guesses.add(markers0[m - 1])
        }

        for (lastGuess in guesses) {
            val hi = minOf(d.size - 1, markersStart - lastGuess + 64)
            val lo = maxOf(0, markersStart - lastGuess - MAX_TAIL_LEN)
            if (hi < lo) continue
            var start = lo
            while (start <= hi) {
                val p256 = walk(d, start, 256)
                if (p256 < 0 || p256 - start != tgt) {
                    start++
                    continue
                }
                var numPos = -1
                var numSyms = 0
                for (dd in intArrayOf(4, 8, 12, 16)) {
                    val np = start - dd
                    if (np < 4) continue
                    val v = u32(d, np)
                    if (v < NUM_SYMS_MIN || v > NUM_SYMS_MAX) continue
                    val n = v.toInt()
                    val m = (n + 255) / 256
                    if (m <= markers0.size && markers0.size - m <= 2) {
                        numPos = np
                        numSyms = n
                        break
                    }
                }
                if (numPos < 0) {
                    start++
                    continue
                }
                val m = (numSyms + 255) / 256
                if (m < 2) {
                    start++
                    continue
                }
                val markers = markers0.copyOf(m)
                if (!verifyAllGroups(d, start, markers)) {
                    start++
                    continue
                }
                val end = walk(d, start, numSyms)
                if (end < 0 || end < markersStart - 8 || end > markersStart) {
                    start++
                    continue
                }
                out.add(NamesCand(start, numPos, numSyms, markers))
                start++
            }
            if (out.isNotEmpty()) break
        }
        return out.take(4)
    }

    /** 用 markers 的**全部**组边界校验 names 起点。 */
    private fun verifyAllGroups(d: ByteArray, start: Int, markers: IntArray): Boolean {
        for (i in 0 until markers.size - 1) {
            val p = walk(d, start + markers[i], 256)
            if (p < 0 || p != start + markers[i + 1]) return false
        }
        return true
    }


    private fun ascendingAt(d: ByteArray, off: Int, num: Int): Boolean {
        if (off < 0 || off.toLong() + 4L * num > d.size) return false
        var last = u32(d, off)
        val limit = maxOf(OFFSET_LIMIT, d.size.toLong() * 2)
        if (last >= limit) return false
        for (i in 1 until num) {
            val v = u32(d, off + 4 * i)
            if (v < last || v >= limit) return false
            last = v
        }
        return last > 0
    }

    /**
     * offsets/base 候选。三种实测布局都试，且容忍 0..12 字节对齐填充：
     *  (a) `[base][offsets][num_syms][names]`
     *  (b) `[offsets][base][num_syms][names]`
     *  (c) `[names][markers][token_table][token_index][offsets][base]`
     */
    private fun offsetsCandidates(
        d: ByteArray,
        numPos: Int,
        numSyms: Int,
        tokenIndex: Int,
    ): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        val seen = HashSet<Int>()

        fun add(off: Int, base: Long) {
            if (!isKernelAddr(base)) return
            if (!ascendingAt(d, off, numSyms)) return
            if (!seen.add(off)) return
            out.add(off to base)
        }

        for (pb in intArrayOf(8, 12, 16, 4)) {
            val bpos = numPos - pb
            if (bpos < 8 || bpos + 8 > d.size) continue
            val base = u64(d, bpos)
            if (!isKernelAddr(base)) continue
            for (pad in intArrayOf(0, 4, 8, 12)) {
                add(bpos - pad - 4 * numSyms, base)
            }
            for (pad in intArrayOf(0, 4, 8, 12)) {
                val off = numPos - pad - 4 * numSyms
                if (off < 0) continue
                val after = off + 4 * numSyms
                if (after + 8 <= d.size) add(off, u64(d, after))
            }
            break
        }

        val end = minOf(d.size, tokenIndex + 0x40000)
        var q = tokenIndex
        while (q.toLong() + 4L * numSyms + 24 <= end) {
            var hit: Long? = null
            for (pad in intArrayOf(0, 4, 8, 12)) {
                val b = u64(d, q + 4 * numSyms + pad)
                if (isKernelAddr(b)) {
                    hit = b
                    break
                }
            }
            if (hit != null) {
                add(q, hit)
                if (out.isNotEmpty()) break
            }
            q += 4
        }

        if (out.isEmpty()) {
            val limit = maxOf(OFFSET_LIMIT, d.size.toLong() * 2)
            val cells = d.size / 4
            var i = 0
            while (i < cells) {
                val v = u32(d, i * 4)
                if (v >= limit) {
                    i++
                    continue
                }
                var j = i + 1
                var last = v
                while (j < cells) {
                    val w = u32(d, j * 4)
                    if (w < last || w >= limit) break
                    last = w
                    j++
                }
                if (j - i == numSyms) {
                    val off = i * 4
                    for (pad in intArrayOf(0, 4, 8, 12)) {
                        val bp = off + 4 * numSyms + pad
                        if (bp + 8 <= d.size && isKernelAddr(u64(d, bp))) {
                            add(off, u64(d, bp))
                            break
                        }
                    }
                }
                i = j
            }
        }
        return out
    }


    private fun legacyExtract(data: ByteArray): Result? {
        return try {
            val (offsetsOff, numSyms) = longestAscending(data) ?: return null
            val tokenTable = findTokenTable(data, offsetsOff)
            val tokens = readTokens(data, tokenTable)
            val (markersStart, markers) = findMarkers(data, tokenTable, numSyms)
            val namesStart = findNamesStart(data, markersStart, markers)
            val base = tryRelativeBase(data, offsetsOff, numSyms) ?: return null
            build(data, offsetsOff, numSyms, base, tokens, namesStart, markersStart)
        } catch (e: KallsymsError) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * 最长非降 u32 run（返回 (字节偏移, 条数)）。
     *
     *  1. 上限必须随镜像大小放大：offsets 最大值不超过镜像大小，
     *     写死 48MB 会让 50MB+ 的 raw 内核在数组中途被判定越界。
     *  2. 必须排除全零 run：镜像尾部几百 KB 零填充也满足「非降」。
     */
    private fun longestAscending(data: ByteArray): Pair<Int, Int>? {
        val limit = maxOf(OFFSET_LIMIT, data.size.toLong() * 2)
        var bestOff = 0
        var bestCount = 0
        val n = data.size / 4
        var i = 0
        while (i < n) {
            val v = u32(data, i * 4)
            if (v < limit) {
                var j = i + 1
                var last = v
                while (j < n) {
                    val w = u32(data, j * 4)
                    if (w < last || w >= limit) break
                    last = w
                    j++
                }
                if (last > 0 && j - i > bestCount) {
                    bestOff = i * 4
                    bestCount = j - i
                }
                i = j
            } else {
                i++
            }
        }
        return if (bestCount < 1000) null else bestOff to bestCount
    }

    private fun tryRelativeBase(data: ByteArray, offsetsOff: Int, numSyms: Int): Long? {
        val raw = offsetsOff + 4 * numSyms
        for (delta in intArrayOf(0, 4, 8, 12, 16, 24, 32)) {
            val cand = raw + delta
            if (cand + 8 > data.size) continue
            val value = u64(data, cand)
            if (isKernelAddr(value)) return value
        }
        return null
    }

    /** 旧启发式：恰好 256 个连续、NUL 结尾的可打印短串。 */
    private fun findTokenTable(data: ByteArray, startHint: Int): Int {
        val searchFrom = maxOf(0, startHint - 8 * 1024 * 1024)
        val total = data.size
        var segStart = 0
        var runCount = 0
        var runStartOff = -1
        while (segStart < total) {
            val nul = data.indexOfByte(0, segStart)
            val segEnd = if (nul < 0) total else nul
            val len = segEnd - segStart
            var acceptable = len in 1..MAX_TOKEN_LEN
            if (acceptable) {
                for (k in segStart until segEnd) {
                    val c = data[k].toInt() and 0xFF
                    if (c < 0x20 || c >= 0x7F) {
                        acceptable = false
                        break
                    }
                }
            }
            if (segStart >= searchFrom && acceptable) {
                if (runCount == 0) runStartOff = segStart
                runCount++
            } else {
                if (runCount == 256) return runStartOff
                runCount = 0
            }
            segStart = segEnd + 1
        }
        if (runCount == 256) return runStartOff
        throw KallsymsError("no kallsyms_token_table candidate found")
    }

    /** 旧启发式 markers：按 numSyms 限界回溯。 */
    private fun findMarkers(data: ByteArray, tokenTable: Int, numSyms: Int): Pair<Int, IntArray> {
        val expected = (numSyms + 255) / 256
        val window = minOf(4 * 1024 * 1024, 3 * numSyms + 65536)
        val lo = maxOf(0, tokenTable - window)
        var endPos = tokenTable
        while (endPos % 4 != 0 && endPos > lo) endPos--
        while (endPos >= lo) {
            if (endPos >= 4) {
                val lastVal = u32(data, endPos - 4)
                if (lastVal in 1..4_000_000L) {
                    val values = ArrayList<Int>()
                    var p = endPos - 4
                    var prev = -1L
                    while (p - 4 >= 0 && values.size <= expected) {
                        val v = u32(data, p)
                        if (prev >= 0 && v >= prev) break
                        values.add(v.toInt())
                        prev = v
                        p -= 4
                    }
                    if (values.size >= 2 && values[values.size - 1] == 0) {
                        values.reverse()
                        if (values[0] == 0 &&
                            kotlin.math.abs(values.size - expected) <= 1 &&
                            isStrictlyIncreasing(values)
                        ) {
                            return (endPos - 4 * values.size) to values.toIntArray()
                        }
                    }
                }
            }
            endPos -= 4
        }
        throw KallsymsError("markers array not found before token table")
    }

    private fun isStrictlyIncreasing(values: List<Int>): Boolean {
        for (i in 1 until values.size) {
            if (values[i] <= values[i - 1]) return false
        }
        return true
    }

    /** 旧启发式 names 起点：在估计窗口内逐个试。 */
    private fun findNamesStart(data: ByteArray, markersStart: Int, markers: IntArray): Int {
        if (markers.size < 2) throw KallsymsError("markers too short")
        val tgt = markers[1] - markers[0]
        val last = markers[markers.size - 1]
        val lo = maxOf(0, markersStart - last - MAX_TAIL_LEN)
        val hi = minOf(data.size - 1, markersStart - last + 64)
        var start = lo
        while (start <= hi) {
            val p = walk(data, start, 256)
            if (p >= 0 && p - start == tgt && verifyAllGroups(data, start, markers)) return start
            start++
        }
        throw KallsymsError("names start: no candidate passed validation")
    }

    /** token 索引序列 → 符号名（含首字符类型）。 */
    private fun decode(tokens: List<ByteArray>, d: ByteArray, off: Int, len: Int): String {
        var total = 0
        for (k in 0 until len) total += tokens[d[off + k].toInt() and 0xFF].size
        val out = ByteArray(total)
        var o = 0
        for (k in 0 until len) {
            val t = tokens[d[off + k].toInt() and 0xFF]
            t.copyInto(out, o)
            o += t.size
        }
        return out.decodeToString(0, total, throwOnInvalidSequence = false)
    }
}
