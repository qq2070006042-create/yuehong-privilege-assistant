package com.GhostKey.app.data

import com.GhostKey.app.data.ota.BootImageParser
import com.GhostKey.app.data.ota.ImgToProfile

/** 导入结果。 */
sealed class ImportOutcome {
    data class Ok(
        val fileName: String,
        val document: ProfileDocument,
    ) : ImportOutcome() {
        val release: String get() = document.release
        val complete: Boolean get() = document.isComplete()
        val missing: List<String> get() = document.missingRequiredFields()
    }

    data class Err(val message: String) : ImportOutcome()
}

/**
 * 导入入口：统一处理 HOCON / JSON / IMG 三类输入，落盘到 [UserProfileStore]。
 * 导入成功但字段不完整时仍会保存（[ImportOutcome.Ok.complete] == false），
 * 由 UI 提示用户补齐缺失字段。
 */
class ProfileImportManager(private val store: UserProfileStore) {

    /** 导入 HOCON/JSON 文本。 */
    fun importProfile(fileName: String, text: String): ImportOutcome {
        return try {
            val root = HoconSupport.parseObject(text)
            val canonical = LegacyProfileConverter.convertIfLegacy(root)
            val release = (canonical["release"] as? String)?.takeIf { it.isNotBlank() }
            if (release == null) {
                return ImportOutcome.Err("缺少 release 字段（v1 旧格式请使用 kernel_release）")
            }
            val doc = ProfileDocument(release = release, fields = canonical)
            val saved = store.save(store.documentFileName(release), doc.toHocon())
            ImportOutcome.Ok(saved.name, doc)
        } catch (e: Exception) {
            ImportOutcome.Err(e.message ?: "解析失败")
        }
    }

    /** 导入 boot.img 原始字节（Phase 3 提取）。 */
    fun importImg(imageFileName: String, bytes: ByteArray): ImportOutcome {
        return try {
            val info = BootImageParser().parse(bytes)
            val (doc, _) = ImgToProfile.toProfileDocument(info, imageFileName)
            val name = store.documentFileName(doc.release)
            store.save(name, doc.toHocon())
            ImportOutcome.Ok(name, doc)
        } catch (e: Exception) {
            ImportOutcome.Err("IMG 解析失败: ${e.message}")
        }
    }
}
