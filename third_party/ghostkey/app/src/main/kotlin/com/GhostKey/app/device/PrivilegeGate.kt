package com.GhostKey.app.device

import android.content.pm.PackageManager
import android.os.Process
import rikka.shizuku.Shizuku

/**
 * R6：Shizuku / uid 前置校验的唯一出处。
 *
 * 改动前，「必须是 shell(2000) 或 root(0)」这条策略在三个地方各写了一遍：
 *   * `ExploitEngine.executeExploit()` —— 宿主进程，先 pingBinder 再判 uid 再判授权；
 *   * `ExploitEngine.checkShizukuStatus()` —— 同一套判断的 UI 版；
 *   * `Cve98260UserService.runChain()` / `isShizukuReady()` —— 服务进程，判 `Process.myUid()`。
 * 三份实现各自演化，就会出现「UI 显示已连接、执行时却被另一份判据拦住」这类
 * 自相矛盾的状态（也正是日志里一类难查问题的来源）。
 *
 * 现在统一到本对象：
 *   * [accepts]     策略本身（唯一一份 uid 白名单）；
 *   * [checkHost]   宿主侧完整校验（Shizuku 存活 + uid + 授权），UI 与执行共用；
 *   * [checkService] 服务侧校验（本进程 uid）。
 *
 * 注意：本对象位于 APK 内，宿主进程与 Shizuku UserService 进程都会加载它；
 * [checkService] 不触碰 Shizuku API，因此在 UserService 进程里调用是安全的。
 */
object PrivilegeGate {

    /** 校验结果分类。用枚举而非字符串，避免调用方靠 `contains` 猜状态。 */
    enum class State {
        /** 通过。 */
        OK,

        /** Shizuku 服务未运行（binder 不通）。 */
        SHIZUKU_DOWN,

        /** uid 不是 shell(2000) / root(0) —— 拿不到写 tracefs 的权限。 */
        BAD_UID,

        /** Shizuku 在跑、uid 也对，但本应用未获授权。 */
        NO_PERMISSION,

        /** 查询过程抛异常（binder 抖动等），按失败处理。 */
        ERROR,
    }

    data class Result(val state: State, val uid: Int) {
        val ok: Boolean get() = state == State.OK

        /** 与既有 `<s> error: ...` 日志风格一致的一句话说明。 */
        val message: String
            get() = when (state) {
                State.OK -> "Stellar service connected: uid=$uid"
                State.SHIZUKU_DOWN -> "Stellar Shizuku-compatible Binder not connected"
                State.BAD_UID -> "Stellar service did not run as shell/root (uid=$uid)"
                State.NO_PERMISSION -> "Stellar manager authorization required"
                State.ERROR -> "Stellar/Shizuku-compatible binder status check failed"
            }
    }

    /** 策略：只接受 adb shell(2000) 与 root(0)。这两个 uid 才有权写 tracefs。 */
    fun accepts(uid: Int): Boolean = uid == Process.SHELL_UID || uid == Process.ROOT_UID

    /**
     * 宿主侧完整校验。任何一步失败都返回对应状态，不抛异常。
     *
     * 注意顺序：先判 binder（否则 getUid 会抛），再判 uid，最后判授权——
     * 这个顺序能给出最准确的失败原因（例如「Shizuku 在跑但 uid 是 app」）。
     */
    fun checkHost(): Result {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return Result(State.SHIZUKU_DOWN, -1)

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (!accepts(uid)) return Result(State.BAD_UID, uid)

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse { return Result(State.ERROR, uid) }
        if (!granted) return Result(State.NO_PERMISSION, uid)

        return Result(State.OK, uid)
    }

    /** 服务侧校验：UserService 必须由 Shizuku 以 shell/root 身份拉起。 */
    fun checkService(uid: Int = Process.myUid()): Result =
        Result(if (accepts(uid)) State.OK else State.BAD_UID, uid)
}
