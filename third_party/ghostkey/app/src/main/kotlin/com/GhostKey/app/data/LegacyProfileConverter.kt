package com.GhostKey.app.data

/**
 * v1 旧格式（JSON）→ v2 规范字段的幂等转换。
 *
 * v1 常见布局（GhostLock / 早期导出）：
 * ```
 * {
 *   "kernel_release": "...",
 *   "symbols":       { "timer_link":..., "cred_link":..., ... },
 *   "struct_fields": { "task_struct": { "cred":2080, "size":4800 },
 *                      "cred": { "uid":8 }, "k_itimer": { "size":264 } },
 *   "page_offset": ...
 * }
 * ```
 * v2 规范布局（本工具内部）：`release` + `offset { task_cred/task_struct_size/cred_uid/kitimer_node ... }`。
 *
 * 若输入已是 v2（含 `release` 且含 `offset`），原样返回，保证幂等。
 */
object LegacyProfileConverter {

    private val OFFSET_SYMBOLS = listOf(
        "timer_link", "cred_link", "selinux_link", "user_ns", "root_user", "groups", "ucounts",
    )

    fun convertIfLegacy(root: Map<String, Any?>): Map<String, Any?> {
        if (root.containsKey("release") && root["offset"] is Map<*, *>) return root

        val out = LinkedHashMap<String, Any?>()
        out.putAll(root)

        (root["release"] as? String)?.takeIf { it.isNotBlank() }?.let { out["release"] = it }
            ?: (root["kernel_release"] as? String)?.takeIf { it.isNotBlank() }?.let { out["release"] = it }

        val offset = LinkedHashMap<String, Any?>()
        asMap(root["offset"])?.let { offset.putAll(it) }
        asMap(root["offsets"])?.let { existing ->
            for (k in OFFSET_SYMBOLS + listOf("task_cred", "task_struct_size", "cred_uid", "kitimer_node")) {
                existing[k]?.let { offset[k] = it }
            }
        }

        asMap(root["symbols"])?.let { syms ->
            for (k in OFFSET_SYMBOLS) syms[k]?.let { offset[k] = it }
        }

        asMap(root["struct_fields"])?.let { sf ->
            asMap(sf["task_struct"])?.let { ts ->
                ts["cred"]?.let { offset["task_cred"] = it }
                ts["size"]?.let { offset["task_struct_size"] = it }
            }
            asMap(sf["cred"])?.let { cr ->
                cr["uid"]?.let { offset["cred_uid"] = it }
            }
            asMap(sf["k_itimer"])?.let { ki ->
                (ki["node"] ?: ki["offset"])?.let { offset["kitimer_node"] = it }
            }
            sf["page_offset"]?.let { out["page_offset"] = it }
        }

        (root["page_offset"] ?: root["pageOffset"])?.let { out["page_offset"] = it }

        if (offset.isNotEmpty()) out["offset"] = offset
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
}
