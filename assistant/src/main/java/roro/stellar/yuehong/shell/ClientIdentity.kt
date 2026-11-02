package roro.stellar.yuehong.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object ClientIdentity {
    private const val PREFERENCES_NAME = "dmkpz_client_identity"
    private const val DEVICE_ID_KEY = "device_id"
    private const val SIGNING_SEED_KEY = "protocol_v2_signing_seed"
    private const val ENCRYPTED_SIGNING_SEED_KEY = "protocol_v2_signing_seed_ciphertext_v1"
    private const val SIGNING_SEED_IV_KEY = "protocol_v2_signing_seed_iv_v1"
    private const val SOFTWARE_SIGNING_SEED_FILE = "dmkpz_protocol_v2_signing_seed_v1"
    private const val KEYSTORE_ALIAS = "dmkpz_protocol_v2_seed_wrap_v1"
    private const val DEVICE_ID_BYTES = 32
    private const val SIGNING_SEED_BYTES = 32
    private const val LOG_TAG = "ClientIdentity"

    data class SigningIdentity(
        val deviceId: String,
        val privateKey: Ed25519PrivateKeyParameters,
        val publicKey: ByteArray,
    )

    @Synchronized
    fun loadOrCreate(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val existing = preferences.getString(DEVICE_ID_KEY, null)
        existing?.takeIf(::isValid)?.let { return it }

        // The device ID and signing seed form one identity.  If the ID was
        // truncated or otherwise damaged, keeping the old seed would reuse
        // one public key under a new ID and the server would reject it as a
        // key conflict.  Drop the protected seed as part of repairing the
        // pair; the caller will create a fresh key below.
        if (existing != null) {
            check(preferences.edit()
                .remove(SIGNING_SEED_KEY)
                .remove(ENCRYPTED_SIGNING_SEED_KEY)
                .remove(SIGNING_SEED_IV_KEY)
                .commit()
            ) {
                "Unable to repair DMKPZ client identity"
            }
            deleteSoftwareSeed(context)
            runCatching { deleteWrappingKey() }
        }

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

    @Synchronized
    fun loadOrCreateSigningIdentity(context: Context): SigningIdentity {
        try {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val deviceId = loadOrCreate(context)
            val seed = loadOrCreateProtectedSeed(context, preferences)

            val privateKey = Ed25519PrivateKeyParameters(seed, 0)
            seed.fill(0)
            return SigningIdentity(
                deviceId = deviceId,
                privateKey = privateKey,
                publicKey = privateKey.generatePublicKey().encoded,
            )
        } catch (error: Exception) {
            throw IdentityException("本地设备身份数据不可用", error)
        }
    }

    @Synchronized
    fun resetSigningIdentity(context: Context) {
        try {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            check(preferences.edit()
                .remove(SIGNING_SEED_KEY)
                .remove(ENCRYPTED_SIGNING_SEED_KEY)
                .remove(SIGNING_SEED_IV_KEY)
                .commit()
            ) {
                "Unable to reset protocol v2 signing identity"
            }
            deleteSoftwareSeed(context)
            // Removing the app-side seed is sufficient to reset the identity.
            // A broken/unavailable Keystore must not prevent the software-key
            // fallback from being created on the retry.
            runCatching { deleteWrappingKey() }
        } catch (error: Exception) {
            throw IdentityException("无法重置本地设备身份", error)
        }
    }

    class IdentityException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun loadOrCreateProtectedSeed(
        context: Context,
        preferences: android.content.SharedPreferences,
    ): ByteArray {
        val encrypted = preferences.getString(ENCRYPTED_SIGNING_SEED_KEY, null)
        val iv = preferences.getString(SIGNING_SEED_IV_KEY, null)
        if (encrypted != null || iv != null) {
            check(encrypted != null && iv != null) { "Incomplete protected device identity" }
            return decryptSeed(encrypted, iv)
        }

        // Once selected, keep using the same software seed. This is important
        // for stable device identity if the Keystore becomes available again
        // after a temporary failure.
        readSoftwareSeed(context)?.let { return it }

        val legacySeed = preferences.getString(SIGNING_SEED_KEY, null)?.let(::decodeSeed)
        val seed = legacySeed ?: ByteArray(SIGNING_SEED_BYTES).also(SecureRandom()::nextBytes)
        val protected = try {
            encryptSeed(seed)
        } catch (error: Exception) {
            try {
                persistSoftwareSeed(context, seed)
            } catch (persistError: Exception) {
                seed.fill(0)
                throw persistError
            }
            // The fallback file lives in noBackupFilesDir: it remains private
            // to this app but is not copied to another device without the
            // Keystore key that protected the normal representation.
            Log.w(LOG_TAG, "Android Keystore unavailable; using app-private signing-key fallback (${error.javaClass.simpleName})")
            preferences.edit().remove(SIGNING_SEED_KEY).apply()
            return seed
        }

        try {
            check(preferences.edit()
                .putString(ENCRYPTED_SIGNING_SEED_KEY, protected.first)
                .putString(SIGNING_SEED_IV_KEY, protected.second)
                .remove(SIGNING_SEED_KEY)
                .commit()
            ) {
                "Unable to persist protected protocol v2 signing identity"
            }
            return seed
        } catch (error: Throwable) {
            seed.fill(0)
            throw error
        }
    }

    private fun readSoftwareSeed(context: Context): ByteArray? {
        val atomicFile = softwareSeedFile(context)
        return try {
            atomicFile.openRead().use { input ->
                val seed = decodeSeed(input.bufferedReader(Charsets.US_ASCII).use { it.readText() })
                check(seed != null) { "Invalid app-private protocol v2 signing identity" }
                seed
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun persistSoftwareSeed(context: Context, seed: ByteArray) {
        val atomicFile = softwareSeedFile(context)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(encode(seed).toByteArray(Charsets.US_ASCII))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun deleteSoftwareSeed(context: Context) {
        softwareSeedFile(context).delete()
    }

    private fun softwareSeedFile(context: Context): AtomicFile = AtomicFile(
        File(context.noBackupFilesDir, SOFTWARE_SIGNING_SEED_FILE),
    )

    private fun encryptSeed(seed: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateWrappingKey())
        return encode(cipher.doFinal(seed)) to encode(cipher.iv)
    }

    private fun decryptSeed(encrypted: String, iv: String): ByteArray {
        val encryptedBytes = decode(encrypted)
        val ivBytes = decode(iv)
        check(encryptedBytes != null && ivBytes != null && ivBytes.size == GCM_IV_BYTES) {
            "Invalid protected device identity"
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        return cipher.doFinal(encryptedBytes).also {
            check(it.size == SIGNING_SEED_BYTES) { "Invalid protected device identity" }
        }
    }

    private fun loadOrCreateWrappingKey(): SecretKey {
        val keyStore = androidKeyStore()
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun deleteWrappingKey() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    private fun isValid(value: String): Boolean =
        value.length in 22..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun decodeSeed(encoded: String): ByteArray? = runCatching {
        decode(encoded)
    }.getOrNull()?.takeIf { it.size == SIGNING_SEED_BYTES }

    private fun encode(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun decode(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
}
