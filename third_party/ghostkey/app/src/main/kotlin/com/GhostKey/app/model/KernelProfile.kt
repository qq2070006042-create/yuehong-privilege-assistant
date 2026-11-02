package com.GhostKey.app.model

/**
 * 内核偏移表（对应 NEW - 副本 的 offsets.h，按 uname -r 区分）。
 * 字段与原生 kernel_table.h 的 struct kernel_offsets 一一对应。
 */
data class KernelProfile(
    val release: String,
    val vulnType: String = "cve-2026-98260",
    val recommendShizuku: Boolean = true,
    val pageOffset: Long,
    val timerLink: Long,
    val credLink: Long,
    val selinuxLink: Long,
    val userNs: Long,
    val rootUser: Long,
    val groups: Long,
    val ucounts: Long,
    val taskCred: Int,
    val taskStructSize: Int,
    val credUid: Int,
    val kitimerNode: Int,
    val kitimerSize: Long = 0x108L,
    /**
     * `copy_process` 里 `kmem_cache_alloc_node(task_struct_cachep, …)` 的返回地址
     * （BL 的下一条指令），未加 slide。
     *
     * kmem_cache_alloc tracepoint 记录的 `call_site` 就是 `_RET_IP_`，因此用它可以
     * 精确定位 fork 的 task_struct 分配，**与 slab 对象尺寸无关** —— 这正是
     * `leak_task()` 在尺寸匹配失败时的兜底键。0 表示未知（老 profile），此时只按尺寸匹配。
     */
    val taskAllocLink: Long = 0L,
    /**
     * 伪造 cred 页涉及的 `struct cred` 布局（blob v4）。
     *
     * 跨 KMI 会平移：android15-8 起（6.6/6.12/6.18）cred 为 184 字节、
     * uid@8、caps 48..80、security@128、user@136；android13-8/14-11
     * （5.15/6.1）为 176 字节、整体前移 8。默认值即 android15-8 布局，
     * 保证老 profile（无这些字段）行为不变。
     */
    val credSize: Int = 184,
    val credCapInheritable: Int = 48,
    val credCapPermitted: Int = 56,
    val credCapEffective: Int = 64,
    val credCapBset: Int = 72,
    val credCapAmbient: Int = 80,
    val credSecurity: Int = 128,
    val credUser: Int = 136,
    val credUserNs: Int = 144,
    val credUcounts: Int = 152,
    val credGroups: Int = 160,
)
