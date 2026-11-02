package roro.stellar.yuehong.shell

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import roro.stellar.yuehong.BuildConfig
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** Security checks shared by every server-facing client path. */
internal object OfficialAppSignature {
    private val expectedCertificateSha256 = hexToBytes(
        "c1158510fe8d23bcd37956c532ff4d34f4cce1f206bf36c8fa32cea7cf0bdc86",
    )

    fun requireOfficial(context: Context) {
        if (context.packageName != BuildConfig.APPLICATION_ID || !isOfficial(context)) {
            throw SecurityException("检测到非官方签名版本，为保护设备安全已禁止连接服务器")
        }
    }

    @Suppress("DEPRECATION")
    private fun isOfficial(context: Context): Boolean = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        signers.size == 1 && MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()),
            expectedCertificateSha256,
        )
    }.getOrDefault(false)

    private fun hexToBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}

internal data class DeviceRequestProof(
    val deviceId: String,
    val publicKeyB64: String,
    val timestamp: Long,
    val nonce: String,
    val signatureB64: String,
) {
    fun toForm(moduleId: String): Map<String, String> = linkedMapOf(
        "protocol" to "2",
        "module_id" to moduleId,
        "version_code" to BuildConfig.VERSION_CODE.toString(),
        "device_id" to deviceId,
        "device_public_key" to publicKeyB64,
        "device_timestamp" to timestamp.toString(),
        "device_nonce" to nonce,
        "device_signature" to signatureB64,
    )

    fun putInto(json: JSONObject): JSONObject = json
        .put("protocol", 2)
        .put("deviceId", deviceId)
        .put("device_public_key", publicKeyB64)
        .put("device_timestamp", timestamp)
        .put("device_nonce", nonce)
        .put("device_signature", signatureB64)
}

internal object DeviceRequestProofFactory {
    fun create(context: Context, purpose: String, subject: String): DeviceRequestProof {
        OfficialAppSignature.requireOfficial(context)
        val identity = ClientIdentity.loadOrCreateSigningIdentity(context)
        val publicKeyB64 = encodeBase64Url(identity.publicKey)
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).let(::encodeBase64Url)
        val message = buildString {
            append("dmkpz-device-proof-v2\n")
            append(purpose).append('\n')
            append(subject).append('\n')
            append(BuildConfig.SERVER_MODULE_ID.trim()).append('\n')
            append(BuildConfig.VERSION_CODE).append('\n')
            append(identity.deviceId).append('\n')
            append(publicKeyB64).append('\n')
            append(timestamp).append('\n')
            append(nonce)
        }.toByteArray(StandardCharsets.UTF_8)

        val signer = Ed25519Signer()
        signer.init(true, identity.privateKey)
        signer.update(message, 0, message.size)
        return DeviceRequestProof(
            deviceId = identity.deviceId,
            publicKeyB64 = publicKeyB64,
            timestamp = timestamp,
            nonce = nonce,
            signatureB64 = encodeBase64Url(signer.generateSignature()),
        )
    }

    fun compatibilitySubject(profile: DeviceProfile): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                "${profile.modelName}\n${profile.kernelVersion}\n${profile.systemVersion}"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

    fun deviceReportSubject(profile: DeviceProfile): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append(profile.brandName).append('\n')
                    append(profile.modelName).append('\n')
                    append(profile.systemVersion).append('\n')
                    append(profile.kernelVersion)
                }.toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

    private const val NONCE_BYTES = 24
}

internal object SignedServerResponse {
    fun verify(
        body: String,
        proof: DeviceRequestProof,
        expectedPublicKey: String = BuildConfig.SERVER_PROTOCOL_V2_PUBLIC_KEY.trim(),
        expectedKeyId: String = BuildConfig.SERVER_PROTOCOL_V2_KEY_ID.trim(),
    ): JSONObject {
        val envelope = JSONObject(body)
        if (envelope.optInt("protocol") != 2 ||
            envelope.optString("key_id") != expectedKeyId
        ) {
            throw SecurityException("服务器签名身份不匹配")
        }

        val payloadBytes = decodeBase64Url(envelope.optString("payload"))
        val signature = decodeBase64Url(envelope.optString("signature"))
        val publicKey = decodeBase64Url(expectedPublicKey)
        if (publicKey.size != ED25519_PUBLIC_KEY_BYTES || signature.size != ED25519_SIGNATURE_BYTES) {
            throw SecurityException("服务器签名格式无效")
        }

        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(payloadBytes, 0, payloadBytes.size)
        if (!verifier.verifySignature(signature)) {
            throw SecurityException("服务器响应签名验证失败")
        }

        val payload = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
        val now = System.currentTimeMillis() / 1000L
        if (payload.optString("module_id") != BuildConfig.SERVER_MODULE_ID.trim() ||
            payload.optInt("version_code", 0) != BuildConfig.VERSION_CODE ||
            payload.optString("device_id") != proof.deviceId ||
            payload.optString("request_nonce") != proof.nonce ||
            payload.optLong("issued_at", 0L) > now + CLOCK_SKEW_SECONDS ||
            payload.optLong("expires_at", 0L) < now - CLOCK_SKEW_SECONDS
        ) {
            throw SecurityException("服务器响应与本次设备请求不匹配")
        }
        return payload
    }

    private fun decodeBase64Url(value: String): ByteArray {
        if (value.isBlank() || !BASE64_URL_PATTERN.matches(value)) {
            throw SecurityException("服务器签名编码无效")
        }
        return runCatching {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrElse { throw SecurityException("服务器签名编码无效") }
    }

    private const val CLOCK_SKEW_SECONDS = 30L
    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private const val ED25519_SIGNATURE_BYTES = 64
    private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
}

private fun encodeBase64Url(value: ByteArray): String = Base64.encodeToString(
    value,
    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
)
