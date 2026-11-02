package roro.stellar.yuehong.shell

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import roro.stellar.yuehong.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class RomV2OtaResult(
    val otaUrl: String? = null,
    val provider: String = "",
    val errorMessage: String = "",
) {
    val successful: Boolean get() = !otaUrl.isNullOrBlank()
}

/** Builds and verifies the current ROM V2 request used by the assistant. */
internal class RomV2OtaResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(): RomV2OtaResult {
        val endpoint = BuildConfig.ROM_PROTOCOL_V2_ENDPOINT.trim()
        val publicKey = BuildConfig.ROM_PROTOCOL_V2_PUBLIC_KEY.trim()
        val keyId = BuildConfig.ROM_PROTOCOL_V2_KEY_ID.trim()
        if (endpoint.isEmpty() || publicKey.isEmpty() || keyId.isEmpty()) {
            return RomV2OtaResult(errorMessage = "ROM V2 接口尚未配置")
        }

        return runCatching { resolveConfigured(endpoint, publicKey, keyId) }
            .getOrElse { error ->
                RomV2OtaResult(
                    errorMessage = error.message?.trim().orEmpty().ifBlank { "无法连接 ROM V2 接口" },
                )
            }
    }

    private fun resolveConfigured(
        endpoint: String,
        expectedPublicKey: String,
        expectedKeyId: String,
    ): RomV2OtaResult {
        OfficialAppSignature.requireOfficial(appContext)
        val target = URL(endpoint)
        require(target.protocol.equals("https", ignoreCase = true)) {
            "ROM V2 接口必须使用 HTTPS"
        }

        val report = RomOtaDeviceReport.collect()
        val proof = DeviceRequestProofFactory.create(
            appContext,
            "rom_ota_resolve",
            report.signatureSubject(),
        )
        val requestBody = report.toJson()
            .put("protocol", 2)
            .put("moduleId", BuildConfig.SERVER_MODULE_ID)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .also(proof::putInto)
            .toString()

        val connection = target.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "YueHongPrivilegeAssistant/${BuildConfig.VERSION_NAME}",
            )
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.readUtf8Limited().orEmpty()
            if (responseCode !in 200..299) {
                val serverMessage = runCatching {
                    JSONObject(responseBody).optString("message")
                }.getOrDefault("")
                throw IOException(serverMessage.ifBlank { "ROM 服务返回 HTTP $responseCode" })
            }

            val payload = SignedServerResponse.verify(
                responseBody,
                proof,
                expectedPublicKey,
                expectedKeyId,
            )
            if (payload.optInt("authorized", 0) != 1) {
                throw IOException("ROM V2 服务未授权本次请求")
            }
            if (payload.optInt("ota_available", 0) != 1) {
                val message = decodeBase64UrlText(payload.optString("message_b64"))
                throw IOException(message.ifBlank { "没有找到当前系统版本对应的 OTA 完整包" })
            }

            val otaUrl = decodeBase64UrlText(payload.optString("ota_url_b64"))
            val scheme = runCatching { URL(otaUrl).protocol.lowercase(Locale.ROOT) }.getOrDefault("")
            if (scheme != "https" && scheme != "http") {
                throw IOException("ROM V2 服务返回了无效 OTA 链接")
            }
            RomV2OtaResult(otaUrl = otaUrl, provider = payload.optString("provider").trim())
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readUtf8Limited(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IOException("ROM V2 响应过大")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun decodeBase64UrlText(value: String): String {
        if (value.isBlank() || !BASE64_URL_PATTERN.matches(value)) return ""
        return runCatching {
            String(
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                Charsets.UTF_8,
            ).trim()
        }.getOrDefault("")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }
}

private data class RomOtaDeviceReport(
    val requestedPackage: String,
    val brand: String,
    val deviceName: String,
    val model: String,
    val codename: String,
    val systemVersion: String,
    val internalOtaVersion: String,
    val region: String,
    val androidVersion: String,
    val colorOsVersion: String,
    val romVersion: String,
    val carrierId: String,
    val kernelVersion: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("requestedPackage", requestedPackage)
        .put("brand", brand)
        .put("deviceName", deviceName)
        .put("model", model)
        .put("codename", codename)
        .put("systemVersion", systemVersion)
        .put("internalOtaVersion", internalOtaVersion)
        .put("region", region)
        .put("androidVersion", androidVersion)
        .put("colorOSVersion", colorOsVersion)
        .put("romVersion", romVersion)
        .put("carrierId", carrierId)
        .put("kernelVersion", kernelVersion)

    fun signatureSubject(): String {
        val fields = listOf(
            requestedPackage,
            brand,
            deviceName,
            model,
            codename,
            systemVersion,
            internalOtaVersion,
            region,
            androidVersion,
            colorOsVersion,
            romVersion,
            carrierId,
            kernelVersion,
        ).joinToString("\n") { it.trim().replace(WHITESPACE, " ") }
        return MessageDigest.getInstance("SHA-256")
            .digest(fields.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        fun collect(): RomOtaDeviceReport {
            val brandText = firstSystemProperty(
                "ro.product.brand",
                "ro.product.vendor.brand",
                "ro.product.manufacturer",
                "ro.product.vendor.manufacturer",
            )
            val productModel = firstSystemProperty(
                "ro.product.model",
                "ro.product.vendor.model",
                "ro.vendor.product.model",
            )
            val marketName = firstSystemProperty(
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.vendor.product.marketname",
                "ro.oplus.market.name",
            )
            val identity = "$brandText $productModel $marketName".lowercase(Locale.ROOT)
            val kernel = runCommand("/system/bin/uname", "-r")
            val androidRelease = firstSystemProperty("ro.build.version.release")
            val androidVersion = androidRelease.takeIf(String::isNotBlank)?.let { "Android$it" }.orEmpty()

            return when {
                identity.containsAny("xiaomi", "redmi", "poco") -> collectXiaomi(
                    identity,
                    productModel,
                    marketName,
                    androidVersion,
                    kernel,
                )

                identity.containsAny("oppo", "oneplus", "realme") -> collectOplus(
                    identity,
                    productModel,
                    marketName,
                    androidVersion,
                    kernel,
                )

                else -> throw IOException("ROM V2 自动取链目前只支持 Xiaomi、Redmi、POCO 与 OPlus 设备")
            }
        }

        private fun collectXiaomi(
            identity: String,
            productModel: String,
            marketName: String,
            androidVersion: String,
            kernel: String,
        ): RomOtaDeviceReport {
            val codename = firstSystemProperty(
                "ro.product.device",
                "ro.product.vendor.device",
                "ro.vendor.product.device",
            )
            val systemVersion = firstSystemProperty(
                "ro.build.version.incremental",
                "ro.system.build.version.incremental",
                "ro.build.display.id",
            )
            if (codename.isBlank() || systemVersion.isBlank()) {
                throw IOException("读取不到 Xiaomi 设备代号或系统版本")
            }
            val brand = when {
                identity.contains("poco") -> "POCO"
                identity.contains("redmi") -> "Redmi"
                else -> "Xiaomi"
            }
            val name = marketName.ifBlank { productModel.ifBlank { codename } }
            return RomOtaDeviceReport(
                requestedPackage = "current",
                brand = brand,
                deviceName = name,
                model = codename,
                codename = codename,
                systemVersion = systemVersion,
                internalOtaVersion = "",
                region = xiaomiRegion(systemVersion),
                androidVersion = androidVersion,
                colorOsVersion = "",
                romVersion = systemVersion,
                carrierId = "",
                kernelVersion = kernel,
            )
        }

        private fun collectOplus(
            identity: String,
            productModel: String,
            marketName: String,
            androidVersion: String,
            kernel: String,
        ): RomOtaDeviceReport {
            val otaVersion = firstSystemProperty("ro.build.version.ota")
            val model = otaVersion.substringBefore('_').ifBlank { productModel }
            val deviceName = marketName.ifBlank { productModel.ifBlank { model } }
            val systemVersion = firstSystemProperty(
                "ro.build.ota.versionname",
                "ro.build.display.id",
                "ro.build.version.incremental",
            )
            if (otaVersion.isBlank() || model.isBlank() || systemVersion.isBlank()) {
                throw IOException("读取不到 OPlus 型号、内部 OTA 版本或系统版本")
            }

            val rawRom = firstSystemProperty(
                "ro.build.version.oplusrom",
                "ro.build.version.opporom",
                "ro.build.version.realmeui",
            )
            val colorOsVersion = when {
                rawRom.isBlank() -> ""
                rawRom.startsWith("ColorOS", ignoreCase = true) -> rawRom
                rawRom.startsWith("V", ignoreCase = true) -> "ColorOS${rawRom.drop(1)}"
                else -> "ColorOS$rawRom"
            }
            val rawRegion = firstSystemProperty(
                "persist.sys.oplus.region",
                "persist.sys.oppo.region",
                "ro.oplus.regionmark",
                "ro.vendor.oplus.regionmark",
            ).uppercase(Locale.ROOT)
            val region = when (rawRegion) {
                "CN", "CHINA" -> "CN"
                "EU", "EEA", "EUEX" -> "EU"
                "IN", "INDIA" -> "IN"
                "SG", "SINGAPORE" -> "SG"
                "RU", "RUSSIA" -> "RU"
                "TR", "TURKEY" -> "TR"
                "TH", "THAILAND" -> "TH"
                "GL", "GLOBAL" -> "GL"
                else -> "GL"
            }
            val brand = when {
                identity.contains("realme") || model.startsWith("RMX", ignoreCase = true) -> "Realme"
                identity.contains("oneplus") -> "OnePlus"
                else -> "OPPO"
            }
            val carrier = firstSystemProperty("ro.build.oplus_nv_id").takeIf { it.all(Char::isDigit) }.orEmpty()

            return RomOtaDeviceReport(
                requestedPackage = "current",
                brand = brand,
                deviceName = deviceName,
                model = model,
                codename = "",
                systemVersion = systemVersion,
                internalOtaVersion = otaVersion,
                region = region,
                androidVersion = androidVersion,
                colorOsVersion = colorOsVersion,
                romVersion = systemVersion,
                carrierId = carrier,
                kernelVersion = kernel,
            )
        }

        private fun xiaomiRegion(version: String): String {
            val suffix = Regex("([A-Z]{2})XM$", RegexOption.IGNORE_CASE)
                .find(version.trim())?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)
            return when (suffix) {
                "CN" -> "CN"
                "MI" -> "Global"
                "EU" -> "EEA"
                "IN" -> "India"
                "ID" -> "Indonesia"
                "RU" -> "Russia"
                "TW" -> "Taiwan"
                "TR" -> "Turkey"
                "JP" -> "Japan"
                "KR" -> "Korea"
                "MX" -> "Mexico"
                "SG" -> "Singapore"
                "TH" -> "Thailand"
                else -> ""
            }
        }

        private fun firstSystemProperty(vararg names: String): String {
            names.forEach { name ->
                val value = runCommand("/system/bin/getprop", name)
                if (value.isNotBlank()) return value
            }
            return ""
        }

        private fun runCommand(vararg command: String): String {
            val process = runCatching {
                ProcessBuilder(*command).redirectErrorStream(true).start()
            }.getOrNull() ?: return ""
            return try {
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return ""
                }
                if (process.exitValue() != 0) ""
                else process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ""
            } finally {
                process.destroy()
            }
        }

        private const val COMMAND_TIMEOUT_SECONDS = 2L
        private val WHITESPACE = Regex("\\s+")
        private fun String.containsAny(vararg values: String) = values.any { contains(it) }
    }
}
