package com.GhostKey.app.data

import com.GhostKey.app.data.ota.BootImageParser
import com.GhostKey.app.data.ota.ImgToProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDataTest {

    /** 与 assets/kernel_profiles/pkj110.conf 等价的 HOCON（含嵌套 offset 块 + 无符号 64 位 hex）。 */
    private val PKJ110_HOCON = """
        release = "6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k"
        vuln_type = "cve-2026-98260"
        recommend_shizuku = 1
        page_offset = 0xffffff7f80000000
        offset {
            timer_link = 0xffffffc0801a4dfc
            cred_link = 0xffffffc0800e4074
            selinux_link = 0xffffffc08239c620
            user_ns = 0xffffffc08216f450
            root_user = 0xffffffc08216f6e0
            groups = 0xffffffc082170c40
            ucounts = 0xffffffc0821711b0
            task_cred = 0x820
            task_struct_size = 0x12c0
            cred_uid = 0x8
            kitimer_node = 0x78
        }
    """.trimIndent()

    @Test
    fun `HOCON 解析 pkj110 conf 字段齐全并转 KernelProfile`() {
        val doc = ProfileDocument.parse(PKJ110_HOCON)
        assertEquals("6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k", doc.release)

        // 无符号 64 位 hex 必须正确解析（不能溢出为负数）
        assertEquals(java.lang.Long.parseUnsignedLong("ffffff7f80000000", 16), doc.getLong("page_offset"))
        assertEquals(java.lang.Long.parseUnsignedLong("ffffffc0801a4dfc", 16), doc.getLong("offset.timer_link"))
        assertEquals(0x820L, doc.getLong("offset.task_cred"))
        assertEquals(0x78L, doc.getLong("offset.kitimer_node"))
        assertTrue(doc.getBoolean("recommend_shizuku"))

        val kp = doc.toKernelProfile()
        assertEquals(java.lang.Long.parseUnsignedLong("ffffff7f80000000", 16), kp.pageOffset)
        assertEquals(java.lang.Long.parseUnsignedLong("ffffffc0801a4dfc", 16), kp.timerLink)
        assertEquals(0x820, kp.taskCred)
        assertEquals(8, kp.credUid)
        assertEquals(0x78, kp.kitimerNode)
        assertTrue(kp.recommendShizuku)
        assertEquals("cve-2026-98260", kp.vulnType)
    }

    @Test
    fun `HOCON 支持点分键 数组 注释 布尔 空值`() {
        val text = """
            release = "test"
            a.b.c = 42
            list = [ 1, 2, 3 ]
            flag = true
            nothing = null
            # 这是注释
            // 这也是注释
            name = "hello world"
        """.trimIndent()
        val doc = ProfileDocument.parse(text)
        assertEquals(42L, doc.getLong("a.b.c"))
        @Suppress("UNCHECKED_CAST")
        val list = doc.get("list") as? List<*>
        assertEquals(listOf(1L, 2L, 3L), list)
        assertTrue(doc.getBoolean("flag"))
        assertNull(doc.get("nothing"))
        assertEquals("hello world", doc.getString("name"))
    }

    @Test
    fun `JSON（HOCON 子集）亦可解析`() {
        val json = """
            {
              "release": "5.15.41-android13-8-gabc",
              "page_offset": "0xffffff8000000000",
              "offset": {
                "timer_link": 100,
                "task_cred": 200
              }
            }
        """.trimIndent()
        val doc = ProfileDocument.parse(json)
        assertEquals("5.15.41-android13-8-gabc", doc.release)
        assertEquals(100L, doc.getLong("offset.timer_link"))
        assertEquals(200L, doc.getLong("offset.task_cred"))
    }

    @Test
    fun `缺失必填字段被检测`() {
        val partial = """
            release = "x"
            offset {
                timer_link = 1
            }
        """.trimIndent()
        val doc = ProfileDocument.parse(partial)
        val missing = doc.missingRequiredFields()
        assertTrue(missing.contains("page_offset"))
        assertTrue(missing.contains("offset.task_cred"))
        assertFalse(doc.isComplete())
    }

    @Test
    fun `render 往返一致`() {
        val doc = ProfileDocument.parse(PKJ110_HOCON)
        val kp = doc.toKernelProfile()
        val back = ProfileDocument.fromKernelProfile(kp)
        val kp2 = back.toKernelProfile()
        assertEquals(kp, kp2)
    }

    @Test
    fun `extractKmi 提取内核模块接口`() {
        assertEquals(
            "android15-8",
            BuiltinProfileCatalog.extractKmi("6.6.118-android15-8-g9bc34d5b0c79-4k"),
        )
        assertEquals(
            "android13-8",
            BuiltinProfileCatalog.extractKmi("5.15.41-android13-8-gabc"),
        )
    }

    @Test
    fun `v1 JSON 旧格式转换为完整 profile`() {
        val json = """
            {
              "kernel_release": "6.6.118-android15-8-gtest",
              "symbols": {
                "timer_link": 1, "cred_link": 2, "selinux_link": 3,
                "user_ns": 4, "root_user": 5, "groups": 6, "ucounts": 7
              },
              "struct_fields": {
                "task_struct": { "cred": 2080, "size": 4800 },
                "cred": { "uid": 8 },
                "k_itimer": { "node": 120 }
              },
              "page_offset": 1000
            }
        """.trimIndent()
        val root = HoconSupport.parseObject(json)
        val canonical = LegacyProfileConverter.convertIfLegacy(root)
        val doc = ProfileDocument.parse(HoconSupport.render(canonical))
        assertTrue("转换后应完整", doc.isComplete())

        val kp = doc.toKernelProfile()
        assertEquals("6.6.118-android15-8-gtest", kp.release)
        assertEquals(1L, kp.timerLink)
        assertEquals(2080, kp.taskCred)
        assertEquals(4800, kp.taskStructSize)
        assertEquals(8, kp.credUid)
        assertEquals(120, kp.kitimerNode)
        assertEquals(1000L, kp.pageOffset)
    }

    @Test
    fun `IMG 解析结果无符号时仅缺 kallsyms 字段`() {
        val info = BootImageParser.ExtractedKernelInfo(
            release = "6.6.118-android15-8-gtest",
            imageSha256 = "deadbeef",
            imageBytes = 100,
            imageBase = 0L,
            hasBtf = true,
            structOffsets = BootImageParser.StructOffsets(
                taskStruct = BootImageParser.TaskStructOffsets(cred = 2080, realCred = 2072, pid = 1560, size = 4800),
                cred = BootImageParser.CredOffsets(uid = 8, gid = 12, euid = 24, capEffective = 64, security = 128),
                kItimerSize = 264,
            ),
            notes = emptyList(),
        )
        val (doc, missing) = ImgToProfile.toProfileDocument(info, "boot.img")
        assertEquals(2080L, doc.getLong("offset.task_cred"))
        assertEquals(4800L, doc.getLong("offset.task_struct_size"))
        assertEquals(8L, doc.getLong("offset.cred_uid"))
        // page_offset（直接映射基址默认值）与 kitimer_node（编译期常量）总是可填
        assertEquals(0xFFFFFF7F80000000uL.toLong(), doc.getLong("page_offset"))
        assertEquals(0x78L, doc.getLong("offset.kitimer_node"))
        // kallsyms 给不出的符号字段应被标为缺失
        assertTrue(missing.contains("offset.timer_link"))
        assertTrue(missing.contains("offset.cred_link"))
        assertTrue(missing.contains("offset.selinux_link"))
        assertTrue(missing.contains("offset.ucounts"))
        assertFalse(doc.isComplete())
    }

    @Test
    fun `IMG 解析结果含 kallsyms 符号时 profile 完整`() {
        val info = BootImageParser.ExtractedKernelInfo(
            release = "6.6.118-android15-8-gtest",
            imageSha256 = "deadbeef",
            imageBytes = 100,
            imageBase = 0xFFFFFFC080000000uL.toLong(),
            hasBtf = true,
            structOffsets = BootImageParser.StructOffsets(
                taskStruct = BootImageParser.TaskStructOffsets(cred = 2080, realCred = 2072, pid = 1560, size = 4800),
                cred = BootImageParser.CredOffsets(uid = 8, gid = 12, euid = 24, capEffective = 64, security = 128),
                kItimerSize = 264,
            ),
            symbols = mapOf(
                "do_timer_create" to 0xFFFFFFC0801A4D54uL.toLong(),
                "prepare_creds" to 0xFFFFFFC0800E4050uL.toLong(),
                "selinux_state" to 0xFFFFFFC08239C620uL.toLong(),
                "init_user_ns" to 0xFFFFFFC08216F450uL.toLong(),
                "root_user" to 0xFFFFFFC08216F6E0uL.toLong(),
                "init_groups" to 0xFFFFFFC082170C40uL.toLong(),
                "init_ucounts" to 0xFFFFFFC0821711B0uL.toLong(),
            ),
            notes = emptyList(),
        )
        val (doc, missing) = ImgToProfile.toProfileDocument(info, "boot.img")
        assertTrue("符号齐备时 profile 应完整，实际缺失: $missing", doc.isComplete())
        // timer_link = do_timer_create + 0xa8；cred_link = prepare_creds + 0x24
        assertEquals(0xFFFFFFC0801A4DFCuL.toLong(), doc.getLong("offset.timer_link"))
        assertEquals(0xFFFFFFC0800E4074uL.toLong(), doc.getLong("offset.cred_link"))
        assertEquals(0xFFFFFFC08216F450uL.toLong(), doc.getLong("offset.user_ns"))
        // 且能无损转为可执行的 KernelProfile
        val kp = doc.toKernelProfile()
        assertEquals("6.6.118-android15-8-gtest", kp.release)
    }
}
