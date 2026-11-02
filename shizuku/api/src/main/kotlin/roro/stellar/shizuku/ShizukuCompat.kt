package roro.stellar.shizuku

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shizuku 兼容层
 * 管理 Shizuku Binder 状态和监听器
 */
object ShizukuCompat {

    private const val TAG = "ShizukuCompat"

    // Shizuku API 常量
    private const val BINDER_DESCRIPTOR = "moe.shizuku.server.IShizukuService"
    private const val TRANSACTION_ATTACH_APPLICATION_V13 = 18
    private const val TRANSACTION_ATTACH_APPLICATION_V11 = 14

    private const val BIND_APPLICATION_SERVER_UID = "shizuku:attach-reply-uid"
    private const val BIND_APPLICATION_SERVER_VERSION = "shizuku:attach-reply-version"
    private const val BIND_APPLICATION_SERVER_SECONTEXT = "shizuku:attach-reply-secontext"
    private const val REQUEST_PERMISSION_REPLY_ALLOWED = "shizuku:request-permission-reply-allowed"
    private const val ATTACH_APPLICATION_API_VERSION = "shizuku:attach-api-version"
    private const val ATTACH_APPLICATION_PACKAGE_NAME = "shizuku:attach-package-name"

    // 兼容旧实现键名
    private const val LEGACY_EXTRA_SERVER_UID = "moe.shizuku.privileged.api.intent.extra.SERVER_UID"
    private const val LEGACY_EXTRA_SERVER_VERSION = "moe.shizuku.privileged.api.intent.extra.SERVER_VERSION"
    private const val LEGACY_EXTRA_SERVER_SECONTEXT = "moe.shizuku.privileged.api.intent.extra.SERVER_SECONTEXT"
    private const val LEGACY_EXTRA_API_VERSION = "moe.shizuku.privileged.api.intent.extra.API_VERSION"
    private const val LEGACY_EXTRA_PACKAGE_NAME = "moe.shizuku.privileged.api.intent.extra.PACKAGE_NAME"
    private const val LEGACY_EXTRA_ALLOWED = "moe.shizuku.privileged.api.intent.extra.ALLOWED"

    var binder: IBinder? = null
        private set

    private var service: IShizukuService? = null
    private var serverUid = -1
    private var serverVersion = -1
    private var serverContext: String? = null
    private var binderReady = false

    private val receivedListeners = CopyOnWriteArrayList<OnBinderReceivedListener>()
    private val deadListeners = CopyOnWriteArrayList<OnBinderDeadListener>()
    private val permissionListeners = CopyOnWriteArrayList<OnRequestPermissionResultListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val application = object : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle?) {
            if (data == null) return
            serverUid = data.getInt(
                BIND_APPLICATION_SERVER_UID,
                data.getInt(LEGACY_EXTRA_SERVER_UID, -1)
            )
            serverVersion = data.getInt(
                BIND_APPLICATION_SERVER_VERSION,
                data.getInt(LEGACY_EXTRA_SERVER_VERSION, -1)
            )
            serverContext = data.getString(BIND_APPLICATION_SERVER_SECONTEXT)
                ?: data.getString(LEGACY_EXTRA_SERVER_SECONTEXT)
            scheduleBinderReceived()
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
            val allowed = data?.let {
                it.getBoolean(
                    REQUEST_PERMISSION_REPLY_ALLOWED,
                    it.getBoolean(LEGACY_EXTRA_ALLOWED, false)
                )
            } ?: false
            notifyPermissionResult(requestCode, allowed)
        }

        override fun showPermissionConfirmation(requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int) {
            // Sui only, not implemented
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        binderReady = false
        onBinderReceived(null, null)
    }

    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        if (binder === newBinder) return

        if (newBinder == null) {
            binderReady = false
            binder = null
            service = null
            serverUid = -1
            serverVersion = -1
            serverContext = null
            notifyBinderDead()
        } else {
            binder?.unlinkToDeath(deathRecipient, 0)
            binder = newBinder
            service = IShizukuService.Stub.asInterface(newBinder)

            try {
                binder?.linkToDeath(deathRecipient, 0)
            } catch (e: Throwable) {
                Log.w(TAG, "linkToDeath failed", e)
            }

            try {
                val attachedV13 = attachApplicationV13(newBinder, packageName)
                val attached = attachedV13 || attachApplicationV11(newBinder, packageName)
                if (!attached) {
                    // pre-v11 客户端不会收到 bindApplication，仍需标记已连接
                    scheduleBinderReceived()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "attachApplication failed", e)
                // 避免部分 ROM 上 attach 抛异常后一直无法进入 ready
                scheduleBinderReceived()
            }
        }
    }

    @Throws(RemoteException::class)
    private fun attachApplicationV13(binder: IBinder, packageName: String?): Boolean {
        val args = Bundle().apply {
            putInt(ATTACH_APPLICATION_API_VERSION, 13)
            putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName)
            putInt(LEGACY_EXTRA_API_VERSION, 13)
            putString(LEGACY_EXTRA_PACKAGE_NAME, packageName)
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(BINDER_DESCRIPTOR)
            data.writeStrongBinder(application.asBinder())
            data.writeInt(1)
            args.writeToParcel(data, 0)
            val result = binder.transact(TRANSACTION_ATTACH_APPLICATION_V13, data, reply, 0)
            reply.readException()
            return result
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Throws(RemoteException::class)
    private fun attachApplicationV11(binder: IBinder, packageName: String?): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(BINDER_DESCRIPTOR)
            data.writeStrongBinder(application.asBinder())
            data.writeString(packageName)
            val result = binder.transact(TRANSACTION_ATTACH_APPLICATION_V11, data, reply, 0)
            reply.readException()
            return result
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun scheduleBinderReceived() {
        binderReady = true
        notifyBinderReceived()
    }

    private fun notifyBinderReceived() {
        receivedListeners.forEach { listener ->
            mainHandler.post { listener.onBinderReceived() }
        }
    }

    private fun notifyBinderDead() {
        deadListeners.forEach { listener ->
            mainHandler.post { listener.onBinderDead() }
        }
    }

    private fun notifyPermissionResult(requestCode: Int, allowed: Boolean) {
        permissionListeners.forEach { listener ->
            mainHandler.post { listener.onRequestPermissionResult(requestCode, allowed) }
        }
    }

    // Public API
    fun pingBinder(): Boolean = binder?.pingBinder() == true
    fun getUid(): Int = serverUid
    fun getVersion(): Int = serverVersion
    fun getSELinuxContext(): String? = serverContext
    fun isPreV11(): Boolean = serverVersion < 11

    fun addBinderReceivedListener(listener: OnBinderReceivedListener) {
        receivedListeners.add(listener)
        if (binderReady) mainHandler.post { listener.onBinderReceived() }
    }

    fun removeBinderReceivedListener(listener: OnBinderReceivedListener) {
        receivedListeners.remove(listener)
    }

    fun addBinderDeadListener(listener: OnBinderDeadListener) {
        deadListeners.add(listener)
    }

    fun removeBinderDeadListener(listener: OnBinderDeadListener) {
        deadListeners.remove(listener)
    }

    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener) {
        permissionListeners.add(listener)
    }

    fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener) {
        permissionListeners.remove(listener)
    }

    fun checkSelfPermission(): Int = try {
        if (service?.checkSelfPermission() == true) 0 else -1
    } catch (e: RemoteException) {
        Log.w(TAG, "checkSelfPermission failed", e)
        -1
    }

    fun shouldShowRequestPermissionRationale(): Boolean = try {
        service?.shouldShowRequestPermissionRationale() ?: false
    } catch (e: RemoteException) {
        Log.w(TAG, "shouldShowRequestPermissionRationale failed", e)
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            service?.requestPermission(requestCode)
        } catch (e: RemoteException) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    fun interface OnRequestPermissionResultListener {
        fun onRequestPermissionResult(requestCode: Int, allowed: Boolean)
    }
}
