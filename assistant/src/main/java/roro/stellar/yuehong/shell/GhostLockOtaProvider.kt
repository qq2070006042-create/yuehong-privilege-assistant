package roro.stellar.yuehong.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.ghostlock.app.data.RomOtaProviderContract

/** Same-UID bridge so the embedded GhostLock library can request a signed ROM V2 URL. */
class GhostLockOtaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RomOtaProviderContract.METHOD_RESOLVE) {
            return super.call(method, arg, extras)
        }
        val currentContext = context ?: return Bundle().apply {
            putBoolean(RomOtaProviderContract.KEY_SUCCESS, false)
            putString(RomOtaProviderContract.KEY_ERROR, "ROM V2 服务不可用")
        }
        val result = RomV2OtaResolver(currentContext).resolve()
        return Bundle().apply {
            putBoolean(RomOtaProviderContract.KEY_SUCCESS, result.successful)
            putString(RomOtaProviderContract.KEY_URL, result.otaUrl)
            putString(RomOtaProviderContract.KEY_PROVIDER, result.provider)
            putString(RomOtaProviderContract.KEY_ERROR, result.errorMessage)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
