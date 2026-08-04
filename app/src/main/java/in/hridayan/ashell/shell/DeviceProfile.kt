package `in`.hridayan.ashell.shell

import android.os.Build
import android.system.Os
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceProfile(
    val model: String,
    val systemVersion: String,
    val kernelVersion: String,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"model\": \"${model.jsonEscape()}\",")
        appendLine("  \"systemVersion\": \"${systemVersion.jsonEscape()}\",")
        appendLine("  \"kernelVersion\": \"${kernelVersion.jsonEscape()}\"")
        append("}")
    }

    companion object {
        fun collect(): DeviceProfile {
            // 使用独立原生命令读取系统属性，避免仅在当前应用进程内生效的 Build/getprop Hook。
            val model = firstSystemProperty(
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.product.odm.marketname",
                "ro.product.system.marketname",
                "ro.config.marketing_name",
                "ro.vendor.oplus.market.name",
                "ro.oplus.market.name",
                "ro.vivo.market.name",
                "ro.vivo.product.model",
                "ro.product.model",
                "ro.product.vendor.model",
                "ro.product.odm.model",
                "ro.product.system.model",
            ).ifBlank { Build.MODEL.clean() }

            val vendorIdentity = listOf(
                firstSystemProperty("ro.product.brand"),
                firstSystemProperty("ro.product.manufacturer"),
                Build.BRAND.clean(),
                Build.MANUFACTURER.clean(),
            ).joinToString(" ").lowercase(Locale.ROOT)

            val systemVersionProperties = when {
                vendorIdentity.containsAny("oneplus", "oppo", "oplus", "realme") -> arrayOf(
                    "ro.build.version.ota",
                    "ro.build.version.oplusrom",
                    "ro.build.version.opporom",
                    "ro.rom.version",
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("xiaomi", "redmi", "poco", "blackshark", "black shark") -> arrayOf(
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("vivo", "iqoo", "bbk") -> arrayOf(
                    "ro.build.version.bbk",
                    "ro.vivo.os.build.display.id",
                    "ro.vivo.rom.version",
                    "ro.vivo.os.version",
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("huawei") -> arrayOf(
                    "ro.huawei.build.display.id",
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                    "ro.build.version.emui",
                    "hw_sc.build.platform.version",
                )

                vendorIdentity.containsAny("honor") -> arrayOf(
                    "ro.build.display.id",
                    "ro.build.version.magic",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("samsung") -> arrayOf(
                    "ro.build.PDA",
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("google", "pixel") -> arrayOf(
                    "ro.build.id",
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("motorola", "lenovo", "zuk") -> arrayOf(
                    "ro.build.version.full",
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("meizu") -> arrayOf(
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("nubia", "redmagic", "red magic", "zte") -> arrayOf(
                    "ro.build.version.ota",
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                    "ro.build.nubia.rom.name",
                )

                vendorIdentity.containsAny("asus", "rog") -> arrayOf(
                    "ro.build.version.incremental",
                    "ro.build.version.asus",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("sony") -> arrayOf(
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("nothing") -> arrayOf(
                    "ro.build.version.ota",
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                )

                vendorIdentity.containsAny("lg", "lge") -> arrayOf(
                    "ro.lge.swversion",
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                vendorIdentity.containsAny("tecno", "infinix", "itel", "transsion", "nokia", "hmd") -> arrayOf(
                    "ro.build.display.id",
                    "ro.build.version.incremental",
                    "ro.build.id",
                )

                else -> arrayOf(
                    "ro.build.version.ota",
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                    "ro.build.id",
                )
            }

            val systemVersion = firstSystemProperty(*systemVersionProperties).ifBlank {
                Build.VERSION.INCREMENTAL.clean().ifBlank { Build.DISPLAY.clean() }
            }

            val kernelVersion = runCommand("/system/bin/uname", "-r")
                .ifBlank { runCatching { Os.uname().release }.getOrNull().clean() }

            return DeviceProfile(
                model = model,
                systemVersion = systemVersion,
                kernelVersion = kernelVersion,
            )
        }

        private fun firstSystemProperty(vararg names: String): String {
            for (name in names) {
                val value = runCommand("/system/bin/getprop", name)
                if (value.isNotBlank()) return value
            }
            return ""
        }

        private fun runCommand(vararg command: String): String {
            val process = runCatching {
                ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return ""

            return try {
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    ""
                } else if (process.exitValue() != 0) {
                    ""
                } else {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.clean()
                }
            } catch (_: Throwable) {
                ""
            } finally {
                runCatching { process.destroy() }
            }
        }

        private const val COMMAND_TIMEOUT_SECONDS = 2L
    }
}

private fun String.containsAny(vararg values: String): Boolean =
    values.any(::contains)

private fun String?.clean(): String {
    val value = orEmpty().trim()
    return value.takeUnless { it.equals(Build.UNKNOWN, ignoreCase = true) }.orEmpty()
}

private fun String.jsonEscape(): String = buildString(length) {
    for (character in this@jsonEscape) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

