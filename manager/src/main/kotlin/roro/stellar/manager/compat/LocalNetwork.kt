package roro.stellar.manager.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Compatibility helpers for Android's local-network runtime permission. */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val API_LEVEL = 37

    fun hasAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < API_LEVEL ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
