package com.GhostKey.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.GhostKey.app.model.ShizukuStatus
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权管理。
 *
 * 关键点：Shizuku Manager 只会列出「请求过授权」的应用。
 * 因此必须调用 [request]（内部走 Shizuku.requestPermission）后，
 * 本应用才会出现在 Shizuku 的授权列表中；只做状态检测是不行的。
 */
class ShizukuPermissionManager(private val context: Context) {

    private val listeners = mutableListOf<(ShizukuStatus) -> Unit>()

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder 已连接")
        notifyChanged()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder 已断开")
        notifyChanged()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == REQUEST_CODE) {
            Log.i(TAG, "授权结果: granted=${result == PackageManager.PERMISSION_GRANTED}")
            notifyChanged()
        }
    }

    init {
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
    }

    /** 当前状态。 */
    fun status(): ShizukuStatus = try {
        when {
            !Shizuku.pingBinder() ->
                if (isManagerInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.PERMISSION_REQUIRED
            else -> ShizukuStatus.READY
        }
    } catch (t: Throwable) {
        Log.w(TAG, "检测 Shizuku 失败", t)
        ShizukuStatus.NOT_INSTALLED
    }

    /** 是否已拿到 shell/root 身份。 */
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun uidLabel(): String = when (uid()) {
        Process.SHELL_UID -> "shell(2000)"
        0 -> "root(0)"
        -1 -> "未知"
        else -> "${uid()}"
    }

    fun managerVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0).versionName
    }.getOrNull()

    fun isManagerInstalled(): Boolean = managerVersion() != null

    /**
     * 请求授权。调用后本应用才会出现在 Shizuku Manager 的授权列表里。
     * @return 是否成功发起请求（不表示已授权）
     */
    fun request(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "Shizuku 未运行，无法请求授权")
            false
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    }.onFailure { Log.e(TAG, "请求授权失败", it) }.getOrDefault(false)

    fun addListener(listener: (ShizukuStatus) -> Unit) {
        listeners.add(listener)
        listener(status())
    }

    private fun notifyChanged() {
        val current = status()
        listeners.forEach { runCatching { it(current) } }
    }

    fun close() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
        runCatching { Shizuku.removeBinderDeadListener(binderDead) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
        listeners.clear()
    }

    companion object {
        private const val TAG = "ShizukuPerm"
        private const val REQUEST_CODE = 4815
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    }
}
