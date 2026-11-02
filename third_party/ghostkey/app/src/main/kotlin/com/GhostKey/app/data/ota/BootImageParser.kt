package com.GhostKey.app.data.ota

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 256MB 安全上限（供顶层 GrowableBuffer 使用）。 */
private const val LZ4_MAX_IMAGE = 0x10000000

/** boot.img 页大小；内核段起点按此对齐。 */
private const val PAGE_SIZE = 4096

/**
 * 默认内核镜像虚拟基址（0xFFFFFFC080000000）。
 * 注意：该值超过 Long.MAX_VALUE，故用其有符号补码表示以保持位模式一致。
 */
private const val DEFAULT_IMAGE_BASE = -0x3F8000000L


private fun u16(d: ByteArray, o: Int): Int =
    (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

private fun u32(d: ByteArray, o: Int): Int =
    (d[o].toInt() and 0xFF) or
        ((d[o + 1].toInt() and 0xFF) shl 8) or
        ((d[o + 2].toInt() and 0xFF) shl 16) or
        ((d[o + 3].toInt() and 0xFF) shl 24)

/** 单字节搜索（替代 ByteArray.indexOf(Byte, Int)）。 */
private fun ByteArray.indexOfByte(b: Byte, from: Int): Int {
    for (i in from.coerceAtLeast(0) until size) {
        if (this[i] == b) return i
    }
    return -1
}

/** 子数组搜索（替代缺失的 ByteArray.indexOf(ByteArray, Int)）。 */
private fun ByteArray.indexOfBytes(sub: ByteArray, from: Int): Int {
    if (sub.isEmpty()) return from.coerceAtLeast(0)
    val limit = size - sub.size
    var i = from.coerceAtLeast(0)
    while (i <= limit) {
        var j = 0
        while (j < sub.size && this[i + j] == sub[j]) j++
        if (j == sub.size) return i
        i++
    }
    return -1
}

/**
 * 纯 Kotlin 版 boot.img → 内核信息解析器（Phase 3，方案 B）。
 *
 * 算法移植自 PC 端工具链：
 *   - tools/extract_offsets.py 的 decode_kernel_image / recover_kernel_phys_load
 *   - tools/btf_offsets.py 的 find_btf / Btf（结构体偏移解析）
 *   - make.py 的产出 schema（kernel_release / image_sha256 / struct_offsets）
 *
 * 当前覆盖（与 PC 端一一对应、已用真实 PKJ110_F27 boot.img 回归验证）：
 *   1. 解包 boot.img（Android v3/v4 头）→ 内核段；支持 LZ4-legacy / gzip / raw arm64 Image
 *   2. 从内核镜像提取 `linux_banner` → 内核 release（uname -r）
 *   3. 定位 BTF 段并解析关键结构体偏移：task_struct / cred / k_itimer
 *   4. kallsyms 全符号表提取（见 [KallsymsExtractor]）→ link_* 符号链接期地址
 *
 * 暂未覆盖（按开发文档"不懂得先放一边"）：
 *   - xbl_config.img 的 FDT 物理加载基址恢复（PC 端 recover_kernel_phys_load；
 *     page_offset 使用 SM8750 直接映射基址默认值，见 ImgToProfile）
 *   - 两节点环扫描 / boot_id 表定位（PC 端 direct_* 直接映射别名，APK profile 未使用）
 */
class BootImageParser {

    /** 解析结果。release 为 uname -r；structOffsets 来自 BTF；symbols 来自 kallsyms（可能为 null）。 */
    data class ExtractedKernelInfo(
        val release: String?,
        val imageSha256: String,
        val imageBytes: Int,
        val imageBase: Long,
        val hasBtf: Boolean,
        val structOffsets: StructOffsets?,
        val symbols: Map<String, Long>? = null,
        val symbolCount: Int = 0,
        val notes: List<String> = emptyList(),
        /** `struct cred` 完整布局（BTF 驱动）；导入 profile 时用于写 cred_* 字段。 */
        val credLayout: StructLayout? = null,
    )

    data class StructOffsets(
        val taskStruct: TaskStructOffsets?,
        val cred: CredOffsets?,
        val kItimerSize: Long?,
    )

    data class TaskStructOffsets(
        val cred: Long,
        val realCred: Long,
        val pid: Long,
        val size: Long,
    )

    data class CredOffsets(
        val uid: Long,
        val gid: Long,
        val euid: Long,
        val capEffective: Long,
        val security: Long,
    )

    /**
     * 通用结构体布局：成员字节偏移 + 结构体大小，全部由镜像 BTF 解析得出。
     *
     * 用途：伪造 cred 页需要 `struct cred` 各字段的真实偏移，而该布局跨 KMI 可能变化，
     * 不能写死。任何内核只要带 BTF，即可用本方法得到本机真实布局。
     */
    data class StructLayout(
        val name: String,
        val size: Long,
        val memberOffsets: Map<String, Long>,
    )

    class ImgExtractError(message: String) : RuntimeException(message)


    private val ANDROID_MAGIC = byteArrayOf(0x41, 0x4E, 0x44, 0x52, 0x4F, 0x49, 0x44, 0x21) // "ANDROID!"
    private val LZ4_LEGACY_MAGIC = byteArrayOf(0x02, 0x21, 0x4C, 0x18)
    private val ARM64_IMAGE_MAGIC = byteArrayOf(0x41, 0x52, 0x4D, 0x64) // "ARM\x64"
    private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08.toByte())


    fun parse(bootImg: ByteArray): ExtractedKernelInfo {
        val image = decodeKernelImage(bootImg)
        val release = extractRelease(image)
        val btfOffset = findBtf(image)
        val structOffsets = if (btfOffset >= 0) parseBtfStructOffsets(image, btfOffset) else null
        val credLayout = if (btfOffset >= 0) Btf(image, btfOffset).layout("cred") else null
        val notes = mutableListOf<String>()
        if (btfOffset < 0) notes += "未在内核镜像中找到 BTF 段，结构偏移缺失"
        if (release == null) notes += "未提取到内核版本（linux_banner 缺失）"

        var symbols: Map<String, Long>? = null
        var symbolCount = 0
        var imageBase = DEFAULT_IMAGE_BASE
        try {
            val ks = KallsymsExtractor.extract(image)
            symbols = ks.symbols
            symbolCount = ks.numSyms
            imageBase = ks.imageBase
        } catch (e: Exception) {
            notes += "kallsyms 提取失败: ${e.message}"
        }

        return ExtractedKernelInfo(
            release = release,
            imageSha256 = sha256(image),
            imageBytes = image.size,
            imageBase = imageBase,
            hasBtf = btfOffset >= 0,
            structOffsets = structOffsets,
            symbols = symbols,
            symbolCount = symbolCount,
            notes = notes,
            credLayout = credLayout,
        )
    }

    /**
     * 通用结构体布局提取（入参为已解码的内核镜像，见 [decodeKernelImage]）。
     *
     * 不绑定任何版本：BTF 描述什么结构体就按什么结构体取，内核没变布局也不会变。
     */
    fun structLayout(image: ByteArray, structName: String): StructLayout? {
        val btfOffset = findBtf(image)
        if (btfOffset < 0) return null
        return Btf(image, btfOffset).layout(structName)
    }

    /** 便捷入口：直接从 boot.img 提结构体布局（内部先解包内核段）。 */
    fun structLayoutOf(bootImg: ByteArray, structName: String): StructLayout? =
        structLayout(decodeKernelImage(bootImg), structName)


    /**
     * boot.img / gzip Image / raw arm64 Image → 未压缩 arm64 Image。
     * 对应 PC 端 extract_offsets.decode_kernel_image。
     */
    fun decodeKernelImage(raw: ByteArray): ByteArray {
        if (raw.size >= 8 && raw.contentEqualsPrefix(ANDROID_MAGIC, 0)) {
            if (raw.size < 44) throw ImgExtractError("Android boot header 不完整")
            val kernelSize = u32(raw, 8)
            val headerSize = u32(raw, 20)
            val version = u32(raw, 40)
            if (version != 3 && version != 4) {
                throw ImgExtractError("不支持的 boot header 版本 $version（仅支持 v3/v4）")
            }
            val start = align(headerSize, PAGE_SIZE)
            val end = start + kernelSize
            if (end > raw.size) throw ImgExtractError("kernel 段超出 boot.img 范围")
            val kernel = raw.copyOfRange(start, end)
            if (kernel.size >= 4 && kernel.contentEqualsPrefix(LZ4_LEGACY_MAGIC, 0)) {
                return decompressLz4Legacy(kernel)
            }
            if (kernel.size >= 3 && kernel.contentEqualsPrefix(GZIP_MAGIC, 0)) {
                return gzipDecompress(kernel)
            }
            return kernel
        }
        if (raw.size >= 3 && raw.contentEqualsPrefix(GZIP_MAGIC, 0)) {
            return gzipDecompress(raw)
        }
        if (raw.size < 64 || !raw.contentEqualsPrefix(ARM64_IMAGE_MAGIC, 56)) {
            throw ImgExtractError("输入既不是 Android boot.img，也不是 arm64 Image")
        }
        return raw
    }

    /**
     * LZ4-legacy 帧 = magic(4) + 重复[ u32 块长度(小端) + 块数据 ]。
     *
     * 关键：内核镜像常被切成 5~6 个块（压缩后每块 4~5MB），必须**逐块解压再拼接**。
     * 旧实现直接 `kernel.copyOfRange(8, size)` 把「首块长度字段之后的全部字节」当成
     * 一个 block，结果只正确解出第一块；从第二块起，块长度字段被当作 LZ4 token 解析，
     * 解码彻底错位 —— 表现为：image 前段（linux_banner、BTF）完全正常，但后段的
     * kallsyms 全部丢失，profile 里 7 个 kallsyms 字段整组为空。
     */
    private fun decompressLz4Legacy(kernel: ByteArray): ByteArray {
        if (kernel.size < 8) throw ImgExtractError("LZ4 段过短")
        val parts = ArrayList<ByteArray>()
        var pos = 4
        while (pos + 4 <= kernel.size) {
            var blockSize = u32(kernel, pos).toInt()
            pos += 4
            if (blockSize <= 0) break
            if (pos + blockSize > kernel.size) blockSize = kernel.size - pos
            if (blockSize <= 0) break
            parts.add(decompressLz4Block(kernel.copyOfRange(pos, pos + blockSize)))
            pos += blockSize
        }
        if (parts.isEmpty()) throw ImgExtractError("LZ4 帧内没有任何块")
        if (parts.size == 1) return parts[0]
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    /**
     * LZ4 block 格式解压（对应 PC 端 lz4.block.decompress）。
     * 与官方格式一致：token 高低 4 位分别控制 literal 长度与 match 长度，
     * match 拷贝允许重叠（逐字节前向复制）。
     */
    internal fun decompressLz4Block(src: ByteArray): ByteArray {
        val out = GrowableBuffer()
        var si = 0
        while (si < src.size) {
            val token = src[si].toInt() and 0xFF
            si++
            var litLen = token shr 4
            if (litLen == 15) {
                do {
                    val b = src[si].toInt() and 0xFF
                    si++
                    litLen += b
                } while (b == 255 && si < src.size)
            }
            if (si + litLen > src.size) throw ImgExtractError("LZ4 literal 越界")
            out.write(src, si, litLen)
            si += litLen
            if (si >= src.size) break
            val offset = (src[si].toInt() and 0xFF) or ((src[si + 1].toInt() and 0xFF) shl 8)
            si += 2
            var matchLen = (token and 0x0F) + 4
            if (matchLen == 4 + 15) {
                do {
                    val b = src[si].toInt() and 0xFF
                    si++
                    matchLen += b
                } while (b == 255 && si < src.size)
            }
            for (i in 0 until matchLen) {
                val b = out[out.size - offset]
                out.put(b)
            }
        }
        return out.toByteArray()
    }


    /** 对应 PC 端 make.py.extract_kernel_version / extract_offsets.derive 的 linux_banner 正则。 */
    private fun extractRelease(image: ByteArray): String? {
        val marker = "Linux version ".toByteArray(Charsets.US_ASCII)
        var i = image.indexOfBytes(marker, 0)
        while (i >= 0) {
            val start = i + marker.size
            var j = start
            while (j < image.size) {
                val c = image[j].toInt()
                if (c == 0 || c == '\r'.code || c == '\n'.code || c == ' '.code) break
                j++
            }
            if (j > start && image[start].toInt() in '0'.code..'9'.code) {
                return image.decodeToString(start, j, throwOnInvalidSequence = false)
            }
            i = image.indexOfBytes(marker, j)
        }
        return null
    }


    /** 对应 PC 端 btf_offsets.find_btf：搜索 BTF 魔数并校验头部。 */
    private fun findBtf(data: ByteArray): Int {
        val needle = byteArrayOf(0x9F.toByte(), 0xEB.toByte(), 0x01.toByte(), 0x00.toByte())
        var idx = 0
        var best = -1
        var bestTypeLen = 0
        while (true) {
            idx = data.indexOfBytes(needle, idx)
            if (idx < 0) break
            if (idx + 24 > data.size) {
                idx += 1
                continue
            }
            val magic = u16(data, idx)
            val hdrLen = u32(data, idx + 4)
            val typeOff = u32(data, idx + 8)
            val typeLen = u32(data, idx + 12)
            val strOff = u32(data, idx + 16)
            val strLen = u32(data, idx + 20)
            if (magic == BTF_MAGIC &&
                hdrLen == 24 &&
                typeLen in 1 until (64 shl 20) &&
                strLen in 1 until (16 shl 20) &&
                idx + hdrLen + typeLen + strLen <= data.size &&
                typeOff == 0 &&
                strOff == typeLen
            ) {
                if (typeLen > bestTypeLen) {
                    best = idx
                    bestTypeLen = typeLen
                }
            }
            idx += 1
        }
        return best
    }

    private fun parseBtfStructOffsets(image: ByteArray, btfOffset: Int): StructOffsets {
        val btf = Btf(image, btfOffset)
        val taskStruct = btf.structSize("task_struct")?.let { size ->
            TaskStructOffsets(
                cred = (btf.memberBitOffset("task_struct", "cred") ?: 0) / 8,
                realCred = (btf.memberBitOffset("task_struct", "real_cred") ?: 0) / 8,
                pid = (btf.memberBitOffset("task_struct", "pid") ?: 0) / 8,
                size = size,
            )
        }
        val cred = btf.memberBitOffset("cred", "uid")?.let { uid ->
            CredOffsets(
                uid = uid / 8,
                gid = (btf.memberBitOffset("cred", "gid") ?: 0) / 8,
                euid = (btf.memberBitOffset("cred", "euid") ?: 0) / 8,
                capEffective = (btf.memberBitOffset("cred", "cap_effective") ?: 0) / 8,
                security = (btf.memberBitOffset("cred", "security") ?: 0) / 8,
            )
        }
        val kItimerSize = btf.structSize("k_itimer")
        return StructOffsets(taskStruct, cred, kItimerSize)
    }


    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun align(value: Int, size: Int): Int = (value + size - 1) / size * size

    private fun gzipDecompress(raw: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }

    /** ByteArray 前缀比较工具（Kotlin 标准库无原生 prefix 匹配）。 */
    private fun ByteArray.contentEqualsPrefix(prefix: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + prefix.size > this.size) return false
        for (i in prefix.indices) {
            if (this[offset + i] != prefix[i]) return false
        }
        return true
    }
}


private const val BTF_MAGIC: Int = 0xEB9F
private const val KIND_STRUCT = 4
private const val KIND_UNION = 5

/**
 * 最小 BTF 类型图解析（仅保留结构体偏移解析所需字段）。
 * 忠实移植自 PC 端 btf_offsets.Btf._parse 的推进逻辑。
 */
private class Btf(data: ByteArray, base: Int) {
    private val data = data
    private val body = base + 24 // hdr_len == 24
    private val strOff: Int
    private val typeLen: Int
    private val types = mutableListOf<BtfType>()

    init {
        strOff = u32(data, base + 16)
        typeLen = u32(data, base + 12)
        val typeOff = u32(data, base + 8)
        parse(body + typeOff)
    }

    private data class BtfType(
        val name: String,
        val kind: Int,
        val size: Int,
        val members: List<BtfMember>,
    )

    private data class BtfMember(val name: String, val bitOffset: Long)

    private fun string(off: Int): String {
        val start = body + strOff + off
        if (start < 0 || start >= data.size) return ""
        val end = data.indexOfByte(0, start)
        val e = if (end < 0 || end > data.size) data.size else end
        return data.decodeToString(start, e, throwOnInvalidSequence = false)
    }

    private fun parse(start: Int) {
        var pos = start
        val end = pos + typeLen
        while (pos + 12 <= end) {
            val nameOff = u32(data, pos)
            val info = u32(data, pos + 4)
            val size = u32(data, pos + 8)
            val kind = (info shr 24) and 0x1F
            val vlen = info and 0xFFFF
            val kflag = (info shr 31) and 1
            pos += 12
            val entry = BtfType(string(nameOff), kind, size, mutableListOf())
            if (kind == KIND_STRUCT || kind == KIND_UNION) {
                val members = entry.members as MutableList
                for (m in 0 until vlen) {
                    val mName = u32(data, pos)
                    val mOff = u32(data, pos + 8)
                    pos += 12
                    val bitOffset: Long = if (kflag == 1) (mOff and 0xFFFFFF).toLong() else mOff.toLong()
                    members.add(BtfMember(string(mName), bitOffset))
                }
            } else if (kind == 13) {
                pos += 8 * vlen
            } else if (kind == 6) {
                pos += 8 * vlen
            } else if (kind == 12) {
                pos += 12 * vlen
            } else if (kind == 15) {
                pos += 12 * vlen
            } else {
                pos += btfPayload(kind)
            }
            types.add(entry)
        }
    }

    private fun btfPayload(kind: Int): Int = when (kind) {
        1 -> 4
        3 -> 12
        14 -> 4
        else -> 0
    }

    fun structSize(name: String): Long? {
        val t = types.firstOrNull { it.kind == KIND_STRUCT && it.name == name } ?: return null
        return t.size.toLong()
    }

    fun memberBitOffset(structName: String, memberName: String): Long? {
        val t = types.firstOrNull { it.kind == KIND_STRUCT && it.name == structName } ?: return null
        return t.members.firstOrNull { it.name == memberName }?.bitOffset
    }

    /** 结构体成员字节偏移表 + 大小（结构体不存在时返回 null）。 */
    fun layout(structName: String): BootImageParser.StructLayout? {
        val t = types.firstOrNull { it.kind == KIND_STRUCT && it.name == structName } ?: return null
        return BootImageParser.StructLayout(
            name = structName,
            size = t.size.toLong(),
            memberOffsets = t.members.associate { it.name to it.bitOffset / 8 },
        )
    }
}


private class GrowableBuffer(initial: Int = 1 shl 20) {
    private var data = ByteArray(initial)
    var size = 0
        private set

    operator fun get(i: Int): Byte = data[i]

    fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var n = data.size
        while (n < size + extra) n = if (n < 1) 1 else n * 2
        if (n > LZ4_MAX_IMAGE) throw BootImageParser.ImgExtractError("LZ4 解压超出安全上限")
        data = data.copyInto(ByteArray(n))
    }

    fun write(src: ByteArray, off: Int, len: Int) {
        ensure(len)
        src.copyInto(data, size, off, off + len)
        size += len
    }

    fun put(b: Byte) {
        ensure(1)
        data[size] = b
        size++
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}
