package com.GhostKey.app.data

import com.GhostKey.app.model.KernelProfile

/**
 * 通用 profile 文档（v2 布局）。
 *
 * 以 `Map<String, Any?>` 驱动，支持点分路径取值（如 `offset.timer_link`），
 * 兼容从 HOCON / JSON 解析出来的任意结构，并可无损渲染回 HOCON。
 *
 * 与 `KernelProfile` 的关系：
 *  - `KernelProfile` 是原生体 wire 模型（定长 blob），字段固定；
 *  - `ProfileDocument` 是通用容器，可表达任意内核的任意字段；
 *  - 通过 [toKernelProfile] / [fromKernelProfile] 双向转换，新内核无需改 Kotlin 代码。
 */
data class ProfileDocument(
    val release: String,
    val schemaVersion: Int = 1,
    val fields: Map<String, Any?>,
) {

    /** 点分路径取值（如 `offset.timer_link`）。返回 null 表示缺字段。 */
    fun get(path: String): Any? {
        val parts = path.split('.')
        var cur: Any? = fields
        for (p in parts) {
            if (cur !is Map<*, *>) return null
            cur = cur[p]
        }
        return cur
    }

    /** 点分路径取 Long（兼容 Long/Int/Double/十六进制或十进制字符串）。 */
    fun getLong(path: String): Long? = coerceLong(get(path))

    /** 点分路径取 String。 */
    fun getString(path: String): String? = get(path)?.toString()

    /** 点分路径取 Boolean（兼容 true/false/1/0/"1"/"0"）。 */
    fun getBoolean(path: String, default: Boolean = false): Boolean =
        when (val v = get(path)) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v.trim() !in setOf("", "0", "false", "False", "FALSE")
            else -> default
        }

    /**
     * 校验是否为可执行的完整 profile。
     * 返回缺失的必填字段路径列表（空列表表示完整）。
     */
    fun missingRequiredFields(): List<String> {
        val missing = mutableListOf<String>()
        for (f in REQUIRED_NUMERIC) {
            if (getLong(f) == null) missing += f
        }
        if (release.isBlank()) missing += "release"
        return missing
    }

    /** 是否完整（所有必填字段齐备）。 */
    fun isComplete(): Boolean = missingRequiredFields().isEmpty()

    /** 转 KernelProfile；缺字段抛 [ProfileConversionException]。 */
    fun toKernelProfile(): KernelProfile {
        val missing = missingRequiredFields()
        if (missing.isNotEmpty()) {
            throw ProfileConversionException("缺少必填字段: ${missing.joinToString()}")
        }
        return KernelProfile(
            release = release,
            vulnType = getString("vuln_type") ?: "cve-2026-98260",
            recommendShizuku = getBoolean("recommend_shizuku", default = true),
            pageOffset = getLong("page_offset")!!,
            timerLink = getLong("offset.timer_link") ?: getLong("timer_link")!!,
            credLink = getLong("offset.cred_link") ?: getLong("cred_link")!!,
            selinuxLink = getLong("offset.selinux_link") ?: getLong("selinux_link")!!,
            userNs = getLong("offset.user_ns") ?: getLong("user_ns")!!,
            rootUser = getLong("offset.root_user") ?: getLong("root_user")!!,
            groups = getLong("offset.groups") ?: getLong("groups")!!,
            ucounts = getLong("offset.ucounts") ?: getLong("ucounts")!!,
            taskCred = (getLong("offset.task_cred") ?: getLong("task_cred")!!).toInt(),
            taskStructSize = (getLong("offset.task_struct_size") ?: getLong("task_struct_size")!!).toInt(),
            credUid = (getLong("offset.cred_uid") ?: getLong("cred_uid")!!).toInt(),
            kitimerNode = (getLong("offset.kitimer_node") ?: getLong("kitimer_node")!!).toInt(),
            kitimerSize = getLong("offset.kitimer_size") ?: getLong("kitimer_size") ?: 0x108L,
            taskAllocLink = getLong("offset.task_alloc_link") ?: getLong("task_alloc_link") ?: 0L,
            credSize = (getLong("offset.cred_size") ?: 184L).toInt(),
            credCapInheritable = (getLong("offset.cred_cap_inheritable") ?: 48L).toInt(),
            credCapPermitted = (getLong("offset.cred_cap_permitted") ?: 56L).toInt(),
            credCapEffective = (getLong("offset.cred_cap_effective") ?: 64L).toInt(),
            credCapBset = (getLong("offset.cred_cap_bset") ?: 72L).toInt(),
            credCapAmbient = (getLong("offset.cred_cap_ambient") ?: 80L).toInt(),
            credSecurity = (getLong("offset.cred_security") ?: 128L).toInt(),
            credUser = (getLong("offset.cred_user") ?: 136L).toInt(),
            credUserNs = (getLong("offset.cred_user_ns") ?: 144L).toInt(),
            credUcounts = (getLong("offset.cred_ucounts") ?: 152L).toInt(),
            credGroups = (getLong("offset.cred_groups") ?: 160L).toInt(),
        )
    }

    /** 渲染为 HOCON 文本。 */
    fun toHocon(): String = HoconSupport.render(fields)

    companion object {
        /** 必填的数值/字符串字段（vuln_type、recommend_shizuku 有默认值，不在此列）。 */
        private val REQUIRED_NUMERIC = listOf(
            "page_offset",
            "offset.timer_link", "offset.cred_link", "offset.selinux_link",
            "offset.user_ns", "offset.root_user", "offset.groups", "offset.ucounts",
            "offset.task_cred", "offset.task_struct_size", "offset.cred_uid", "offset.kitimer_node",
        )

        /** 从 HOCON/JSON 文本解析为 ProfileDocument。 */
        fun parse(text: String, includer: ((String) -> String?)? = null): ProfileDocument {
            val root = HoconSupport.parseObject(text, includer)
            val release = (root["release"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw ProfileConversionException("缺少 release 字段")
            return ProfileDocument(release = release, fields = root)
        }

        /** 从既有 KernelProfile 反向构造 ProfileDocument（用于导出/编辑）。 */
        fun fromKernelProfile(kp: KernelProfile): ProfileDocument {
            val offset = linkedMapOf<String, Any?>(
                "timer_link" to kp.timerLink,
                "cred_link" to kp.credLink,
                "selinux_link" to kp.selinuxLink,
                "user_ns" to kp.userNs,
                "root_user" to kp.rootUser,
                "groups" to kp.groups,
                "ucounts" to kp.ucounts,
                "task_cred" to kp.taskCred,
                "task_struct_size" to kp.taskStructSize,
                "cred_uid" to kp.credUid,
                "kitimer_node" to kp.kitimerNode,
                "kitimer_size" to kp.kitimerSize,
                "task_alloc_link" to kp.taskAllocLink,
                "cred_size" to kp.credSize,
                "cred_cap_inheritable" to kp.credCapInheritable,
                "cred_cap_permitted" to kp.credCapPermitted,
                "cred_cap_effective" to kp.credCapEffective,
                "cred_cap_bset" to kp.credCapBset,
                "cred_cap_ambient" to kp.credCapAmbient,
                "cred_security" to kp.credSecurity,
                "cred_user" to kp.credUser,
                "cred_user_ns" to kp.credUserNs,
                "cred_ucounts" to kp.credUcounts,
                "cred_groups" to kp.credGroups,
            )
            val fields = linkedMapOf<String, Any?>(
                "release" to kp.release,
                "vuln_type" to kp.vulnType,
                "recommend_shizuku" to if (kp.recommendShizuku) 1 else 0,
                "page_offset" to kp.pageOffset,
                "offset" to offset,
            )
            return ProfileDocument(release = kp.release, fields = fields)
        }
    }
}

/** profile 转换/校验异常。 */
class ProfileConversionException(message: String) : RuntimeException(message)


/** 将 HOCON/JSON 中可能出现的数值类型（Long/Int/Double/十六进制或十进制字符串）转为 Long?。 */
internal fun coerceLong(value: Any?): Long? {
    return when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value.isFinite()) value.toLong() else null
        is Float -> if (value.isFinite()) value.toLong() else null
        is Boolean -> if (value) 1L else 0L
        is String -> {
            val s = value.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("0x", ignoreCase = true) || s.startsWith("0X")) {
                runCatching { java.lang.Long.parseUnsignedLong(s.removePrefix("0x").removePrefix("0X"), 16) }
                    .getOrNull()
            } else if (s.any { it == '.' || it == 'e' || it == 'E' }) {
                s.toDoubleOrNull()?.toLong()
            } else {
                s.toLongOrNull()
                    ?: runCatching { java.lang.Long.parseUnsignedLong(s) }.getOrNull()
            }
        }
        else -> null
    }
}
