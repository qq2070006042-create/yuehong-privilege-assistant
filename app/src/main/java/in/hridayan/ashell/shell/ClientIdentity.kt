package `in`.hridayan.ashell.shell

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

internal object ClientIdentity {
    private const val PREFERENCES_NAME = "dmkpz_client_identity"
    private const val DEVICE_ID_KEY = "device_id"
    private const val DEVICE_ID_BYTES = 32

    @Synchronized
    fun loadOrCreate(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        preferences.getString(DEVICE_ID_KEY, null)
            ?.takeIf(::isValid)
            ?.let { return it }

        val random = ByteArray(DEVICE_ID_BYTES).also(SecureRandom()::nextBytes)
        val deviceId = Base64.encodeToString(
            random,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        check(preferences.edit().putString(DEVICE_ID_KEY, deviceId).commit()) {
            "Unable to persist DMKPZ client identity"
        }
        return deviceId
    }

    private fun isValid(value: String): Boolean =
        value.length in 22..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
