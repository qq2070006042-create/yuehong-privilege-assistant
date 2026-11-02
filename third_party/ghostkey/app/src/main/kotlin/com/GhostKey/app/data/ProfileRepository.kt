package com.GhostKey.app.data

import android.content.Context
import com.GhostKey.app.model.KernelProfile

/**
 * 多层 profile 仓库：内置（assets） + 用户导入（filesDir）合并视图。
 *
 * 解析策略：仅做**精确 release 匹配**（用户完整导入 > 内置）。
 * 不再按 KMI 前缀回退——同 KMI 不同构建的内核符号偏移并不一致，
 * 回退会套用错误的 timer_link / cred_link，导致 KASLR 解算失败（日志中 exit=2）。
 * 无精确匹配时返回 null，由调用方提示用户导入对应 boot.img。
 *
 * 每次调用都从磁盘重读用户层，保证导入/删除后立即生效；内置层懒加载缓存。
 */
class ProfileRepository(context: Context) {

    private val builtin = BuiltinProfileCatalog(AssetConfigLoader(context))
    private val store = UserProfileStore(context)

    val importer: ProfileImportManager = ProfileImportManager(store)

    fun builtinProfiles(): List<KernelProfile> = builtin.all()

    /** Workbench policy: prefer an exact built-in profile before user/imported profiles. */
    fun resolveBuiltin(release: String): KernelProfile? = builtin.match(release)

    /** Exact user-imported profile lookup, kept separate so the workbench can choose source priority. */
    fun resolveImported(release: String): KernelProfile? =
        store.listComplete().firstOrNull { it.release == release }

    fun storedProfiles(): List<StoredProfile> = store.list()

    fun deleteStored(fileName: String): Boolean = store.delete(fileName)

    /**
     * 解析某 release 对应的可执行 profile（用户层优先）。
     *
     * 仅做**精确 release 匹配**：用户导入（完整）> 内置。
     * 不再按 KMI 前缀回退——同 KMI 不同构建的内核符号偏移并不一致，
     * 回退会把其它机型的 timer_link / cred_link 套过来，引发 KASLR 解算失败
     * （日志中 exit=2）。无精确匹配时返回 null，由调用方提示用户导入对应 boot.img。
     */
    fun resolve(release: String): KernelProfile? {
        val user = store.listComplete()
        user.firstOrNull { it.release == release }?.let { return it }
        builtin.match(release)?.let { return it }
        return null
    }

    /** 解析导出用的 ProfileDocument（用户文档优先，否则内置转文档）。 */
    fun resolveDocument(release: String): ProfileDocument? {
        store.list().firstOrNull { it.release == release }?.let { return it.document }
        builtin.match(release)?.let { return ProfileDocument.fromKernelProfile(it) }
        return null
    }

    /** 已知的全部 release（用于 UI 选择列表）。 */
    fun knownReleases(): List<String> =
        (store.list().map { it.release } + builtin.all().map { it.release }).distinct()
}
