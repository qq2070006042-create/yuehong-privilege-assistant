package com.GhostKey.app.native

import com.GhostKey.app.model.KernelProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 KernelProfile 序列化为与原生 kernel_table.c 严格对齐的定长 blob。
 *
 * 布局（全小端，共 308 字节，version 4）：
 *   0    magic[4] "C982"
 *   4    u16 version = 4
 *   6    u16 flags (bit0 = recommend_shizuku)
 *   8    u64 page_offset
 *   16   u64 timer_link
 *   24   u64 cred_link
 *   32   u64 selinux_link
 *   40   u64 user_ns
 *   48   u64 root_user
 *   56   u64 groups
 *   64   u64 ucounts
 *   72   u32 task_cred
 *   76   u32 task_struct_size
 *   80   u32 cred_uid
 *   84   u32 kitimer_node
 *   88   char release[128]
 *   216  char vuln_type[32]
 *   248  u64 kitimer_size
 *   256  u64 task_alloc_link   (version 3 起)
 *   264  u32 cred_size         (version 4 起)
 *   268  u32 cred_cap_inh
 *   272  u32 cred_cap_perm
 *   276  u32 cred_cap_eff
 *   280  u32 cred_cap_bset
 *   284  u32 cred_cap_amb
 *   288  u32 cred_security
 *   292  u32 cred_user
 *   296  u32 cred_user_ns
 *   300  u32 cred_ucounts
 *   304  u32 cred_groups
 */
object KernelProfileSerializer {

    const val BLOB_SIZE = 308
    private const val MAGIC = "C982"
    private const val VERSION: Short = 4

    fun serialize(profile: KernelProfile): ByteArray {
        val buf = ByteBuffer.allocate(BLOB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.putShort(VERSION)
        buf.putShort(if (profile.recommendShizuku) 1 else 0)
        buf.putLong(profile.pageOffset)
        buf.putLong(profile.timerLink)
        buf.putLong(profile.credLink)
        buf.putLong(profile.selinuxLink)
        buf.putLong(profile.userNs)
        buf.putLong(profile.rootUser)
        buf.putLong(profile.groups)
        buf.putLong(profile.ucounts)
        buf.putInt(profile.taskCred)
        buf.putInt(profile.taskStructSize)
        buf.putInt(profile.credUid)
        buf.putInt(profile.kitimerNode)
        putFixedString(buf, profile.release, 128)
        putFixedString(buf, profile.vulnType, 32)
        buf.putLong(profile.kitimerSize)
        buf.putLong(profile.taskAllocLink)
        buf.putInt(profile.credSize)
        buf.putInt(profile.credCapInheritable)
        buf.putInt(profile.credCapPermitted)
        buf.putInt(profile.credCapEffective)
        buf.putInt(profile.credCapBset)
        buf.putInt(profile.credCapAmbient)
        buf.putInt(profile.credSecurity)
        buf.putInt(profile.credUser)
        buf.putInt(profile.credUserNs)
        buf.putInt(profile.credUcounts)
        buf.putInt(profile.credGroups)
        return buf.array()
    }

    private fun putFixedString(buf: ByteBuffer, value: String, size: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, size - 1)
        buf.put(bytes, 0, n)
        repeat(size - n) { buf.put(0) }
    }
}
