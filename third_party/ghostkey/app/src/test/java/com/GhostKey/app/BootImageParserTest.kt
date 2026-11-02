package com.GhostKey.app

import com.GhostKey.app.data.ota.BootImageParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BootImageParser 回归验证。
 * 关键用例对比 PC 端 make.py 在 PKJ110_F27 boot.img 上的真实产出（entry.json）。
 * 若本地不存在真实镜像文件，相关用例自动跳过（不阻塞 CI）。
 */
class BootImageParserTest {

    private val parser = BootImageParser()

    private val bootImgCandidates = listOf(
        "T:/CVE-2026-98260/CVE-2026-98260-NEW - 副本/outputs/PKJ110_F27/boot.img",
        "/t/CVE-2026-98260/CVE-2026-98260-NEW - 副本/outputs/PKJ110_F27/boot.img",
    )

    private fun firstExisting(paths: List<String>): File? =
        paths.firstNotNullOfOrNull { p -> File(p).takeIf { it.isFile } }

    // ---------------------------------------------------------------- LZ4

    @Test
    fun `LZ4 block 解压：纯 literal 序列`() {
        // token=0x80 → litLen=8，无 match（末尾序列）；明文 "ABCDEFGH"
        val block = byteArrayOf(0x80.toByte()) + "ABCDEFGH".toByteArray(Charsets.US_ASCII)
        val out = parser.decompressLz4Block(block)
        assertArrayEquals("ABCDEFGH".toByteArray(Charsets.US_ASCII), out)
    }

    @Test
    fun `LZ4 block 解压：含重叠 match`() {
        // 明文 8 个 'A'：token=0x40(litLen=4, match nibble=0) + "AAAA" + offset=1 + (matchLen=4)
        val block = byteArrayOf(
            0x40.toByte(), 0x41, 0x41, 0x41, 0x41, 0x01, 0x00,
        )
        val out = parser.decompressLz4Block(block)
        assertEquals(8, out.size)
        assertEquals("AAAAAAAA", out.toString(Charsets.US_ASCII))
    }

    // ---------------------------------------------------------------- 真实镜像

    @Test
    fun `解包 PKJ110_F27 bootimg 并校验内核信息`() {
        val bootImg = firstExisting(bootImgCandidates) ?: run {
            println("[SKIP] 未找到真实 boot.img，跳过镜像回归")
            return
        }
        val raw = bootImg.readBytes()
        val info = parser.parse(raw)

        // 与 PC 端 entry.json 真值一致
        assertEquals(
            "6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k",
            info.release,
        )
        assertEquals(
            "ee5ee448d2985eeeb7a8b5ad2e1efc34eeb410a8beae0ab484504d739124d613",
            info.imageSha256,
        )
        assertEquals(true, info.hasBtf)
        assertNotNull(info.structOffsets)

        val ts = info.structOffsets!!.taskStruct!!
        assertEquals(2080L, ts.cred)
        assertEquals(2072L, ts.realCred)
        assertEquals(1560L, ts.pid)
        assertEquals(4800L, ts.size)

        val cred = info.structOffsets!!.cred!!
        assertEquals(8L, cred.uid)
        assertEquals(12L, cred.gid)
        assertEquals(24L, cred.euid)
        assertEquals(64L, cred.capEffective)
        assertEquals(128L, cred.security)

        assertEquals(264L, info.structOffsets!!.kItimerSize)
    }

    @Test
    fun `kallsyms 符号地址对照 pkj110conf 真值`() {
        val bootImg = firstExisting(bootImgCandidates) ?: run {
            println("[SKIP] 未找到真实 boot.img，跳过 kallsyms 回归")
            return
        }
        val raw = bootImg.readBytes()
        val info = parser.parse(raw)

        assertNotNull("kallsyms 提取应成功", info.symbols)
        val syms = info.symbols!!
        // imageBase 来自 kallsyms_relative_base，而非默认值
        assertEquals(0xFFFFFFC080000000uL.toLong(), info.imageBase)

        // 真值来自 PC 端 syms.txt / offsets.h / pkj110.conf
        // timer_link = do_timer_create + 0xa8 = 0xffffffc0801a4dfc
        assertEquals(0xFFFFFFC0801A4DFCuL.toLong(), syms["do_timer_create"]!! + 0xA8)
        // cred_link = prepare_creds + 0x24 = 0xffffffc0800e4074
        assertEquals(0xFFFFFFC0800E4074uL.toLong(), syms["prepare_creds"]!! + 0x24)
        assertEquals(0xFFFFFFC08239C620uL.toLong(), syms["selinux_state"])
        assertEquals(0xFFFFFFC08216F450uL.toLong(), syms["init_user_ns"])
        assertEquals(0xFFFFFFC08216F6E0uL.toLong(), syms["root_user"])
        assertEquals(0xFFFFFFC082170C40uL.toLong(), syms["init_groups"])
        assertEquals(0xFFFFFFC0821711B0uL.toLong(), syms["init_ucounts"])

        // 漏洞形状符号（PC 端 REQUIRED/ABSENT 校验）
        assertTrue(syms.containsKey("posix_cpu_timer_del"))
        assertFalse(syms.containsKey("timer_lock_sighand"))

        // IMG→profile 全链路：符号齐备时 profile 完整
        val (doc, missing) = com.GhostKey.app.data.ota.ImgToProfile.toProfileDocument(info, "boot.img")
        assertTrue("导入 IMG 后 profile 应完整，实际缺失: $missing", doc.isComplete())
        assertEquals(0xFFFFFF7F80000000uL.toLong(), doc.getLong("page_offset"))
        assertEquals(0xFFFFFFC0801A4DFCuL.toLong(), doc.getLong("offset.timer_link"))
        assertEquals(0xFFFFFFC0800E4074uL.toLong(), doc.getLong("offset.cred_link"))
        assertEquals(0x78L, doc.getLong("offset.kitimer_node"))
    }

    @Test
    fun `解包 OnePlus PJX110 16_0_5_1001 bootimg（6_1_141 gffc 构建）并校验符号`() {
        val bootImg = firstExisting(
            listOf(
                "C:/Users/Again/Desktop/OnePlus_PJX110_PJX110_16.0.5.1001(CN01)-boot(1).img",
            ),
        ) ?: run {
            println("[SKIP] 未找到 PJX110 16.0.5.1001 boot.img，跳过回归")
            return
        }
        val raw = bootImg.readBytes()
        val info = parser.parse(raw)

        assertEquals(
            "6.1.141-android14-11-o-gffc54a6d2287",
            info.release,
        )
        assertEquals(true, info.hasBtf)
        assertNotNull("kallsyms 提取应成功", info.symbols)
        val syms = info.symbols!!
        for (name in listOf(
            "do_timer_create", "prepare_creds", "selinux_state",
            "init_user_ns", "root_user", "init_groups", "init_ucounts",
        )) {
            assertTrue("缺少目标符号: $name", syms.containsKey(name))
        }
        println("[OK] PJX110 gffc 构建 7/7 目标符号齐备, num=${syms.size}")
    }

    @Test
    fun `IMG 提取的 profile 与内置 pkj110conf 逐字段对比`() {
        val bootImg = firstExisting(bootImgCandidates) ?: run {
            println("[SKIP] 未找到真实 boot.img，跳过对比")
            return
        }
        val confFile = firstExisting(
            listOf(
                "app/src/main/assets/kernel_profiles/6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k.conf",
                "T:/CVE-2026-98260/CVE-2026-98260-NEW-APK/app/src/main/assets/kernel_profiles/6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k.conf",
            ),
        ) ?: run {
            println("[SKIP] 未找到内置 6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k.conf，跳过对比")
            return
        }

        val builtin = com.GhostKey.app.data.ProfileDocument.parse(confFile.readText())
        val info = parser.parse(bootImg.readBytes())
        val (extracted, missing) = com.GhostKey.app.data.ota.ImgToProfile.toProfileDocument(info, "boot.img")

        assertTrue("提取的 profile 应完整，实际缺失: $missing", extracted.isComplete())

        // 与内置 conf 的全部数值字段逐一对比（缺一处、错一处都算失败）
        val paths = listOf(
            "page_offset",
            "offset.timer_link", "offset.cred_link", "offset.selinux_link",
            "offset.user_ns", "offset.root_user", "offset.groups", "offset.ucounts",
            "offset.task_cred", "offset.task_struct_size", "offset.cred_uid",
            "offset.kitimer_node",
        )
        val diffs = mutableListOf<String>()
        for (p in paths) {
            val a = builtin.getLong(p)
            val b = extracted.getLong(p)
            if (a != b) diffs += "$p: 内置=${a?.let { "0x%016x".format(it) }} 提取=${b?.let { "0x%016x".format(it) }}"
        }
        assertTrue("提取结果与内置 conf 不一致:\n${diffs.joinToString("\n")}", diffs.isEmpty())

        // 非数值字段也要一致
        assertEquals(builtin.getString("vuln_type"), extracted.getString("vuln_type"))
        assertEquals(builtin.release, extracted.release)
        println("[OK] IMG 提取与内置 pkj110.conf 全部 12 个数值字段一致")
    }
}
