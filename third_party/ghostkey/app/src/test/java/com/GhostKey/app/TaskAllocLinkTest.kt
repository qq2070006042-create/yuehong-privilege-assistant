package com.GhostKey.app.native

import com.GhostKey.app.data.ProfileDocument
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * profile blob v3 的 `task_alloc_link` 通路。
 *
 * 这条字段是 `leak_task()` 在尺寸匹配失败时的兜底键（copy_process+0x140 的调用点）。
 * 它横跨三层（HOCON → KernelProfile → 264 字节 blob → 原生 kernel_table.c），
 * 任何一层漏掉都不会编译失败，只会在真机上静默退化成"只按尺寸匹配"，
 * 所以这里把它固定下来。
 */
class TaskAllocLinkTest {

    private val K90_HOCON = """
        release = "6.6.118-android15-8-g0908354dac1b-ab16143355-4k"
        vuln_type = "cve-2026-98260"
        recommend_shizuku = 1
        page_offset = 0xffffff7f80000000
        offset {
            timer_link = 0xffffffc080195d50
            cred_link = 0xffffffc0800e4064
            selinux_link = 0xffffffc08236a2e0
            user_ns = 0xffffffc08213ef50
            root_user = 0xffffffc08213f1e0
            groups = 0xffffffc082140740
            ucounts = 0xffffffc082140cb0
            task_cred = 0x820
            task_struct_size = 0x12c0
            cred_uid = 0x8
            kitimer_node = 0x78
            kitimer_size = 0x108
            task_alloc_link = 0xffffffc0800ab93c
        }
    """.trimIndent()

    @Test
    fun `task_alloc_link 从 HOCON 解析到 KernelProfile`() {
        val profile = ProfileDocument.parse(K90_HOCON).toKernelProfile()
        assertEquals(
            java.lang.Long.parseUnsignedLong("ffffffc0800ab93c", 16),
            profile.taskAllocLink,
        )
    }

    @Test
    fun `序列化为 308 字节 v4 blob 且字段偏移对齐 kernel_table_c`() {
        val profile = ProfileDocument.parse(K90_HOCON).toKernelProfile()
        val blob = KernelProfileSerializer.serialize(profile)

        assertEquals(308, blob.size)
        assertEquals("C982", String(blob, 0, 4, Charsets.US_ASCII))

        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, buf.getShort(4).toInt())
        // 关键：字段偏移必须与 kernel_table.c 的 rd_u64(blob + 256) 严格对齐
        assertEquals(
            java.lang.Long.parseUnsignedLong("ffffffc0800ab93c", 16),
            buf.getLong(256),
        )
        // v4：cred 布局默认值（老 profile 无这些字段时按 6.6.x 布局）
        assertEquals(184, buf.getInt(264))
        assertEquals(48, buf.getInt(268))
        assertEquals(80, buf.getInt(284))
        assertEquals(128, buf.getInt(288))
        assertEquals(160, buf.getInt(304))
    }

    @Test
    fun `v4 cred 布局字段可从 HOCON 覆盖为 5_15 值`() {
        val hocon = K90_HOCON.replace(
            "kitimer_size = 0x108",
            "kitimer_size = 0x108\n            cred_size = 176\n" +
                "            cred_cap_inheritable = 40\n            cred_cap_permitted = 48\n" +
                "            cred_cap_effective = 56\n            cred_cap_bset = 64\n" +
                "            cred_cap_ambient = 72\n            cred_security = 120\n" +
                "            cred_user = 128\n            cred_user_ns = 136\n" +
                "            cred_ucounts = 144\n            cred_groups = 152",
        )
        val profile = ProfileDocument.parse(hocon).toKernelProfile()
        assertEquals(176, profile.credSize)
        assertEquals(120, profile.credSecurity)
        assertEquals(152, profile.credGroups)

        val buf = ByteBuffer.wrap(KernelProfileSerializer.serialize(profile))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(176, buf.getInt(264))
        assertEquals(152, buf.getInt(304))
    }

    @Test
    fun `老 profile 无该字段时默认 0 从而退回尺寸匹配`() {
        val legacy = K90_HOCON.lines()
            .filterNot { it.contains("task_alloc_link") }
            .joinToString("\n")
        val profile = ProfileDocument.parse(legacy).toKernelProfile()

        assertEquals(0L, profile.taskAllocLink)
        assertTrue(profile.taskStructSize > 0)
    }
}
