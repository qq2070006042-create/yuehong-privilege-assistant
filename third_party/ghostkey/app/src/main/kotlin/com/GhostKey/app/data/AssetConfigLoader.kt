package com.GhostKey.app.data

import android.content.Context

/** 读取 assets 下的文本配置。 */
class AssetConfigLoader(private val context: Context) {

    fun read(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    fun list(dir: String): List<String> = runCatching {
        context.assets.list(dir)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())
}
