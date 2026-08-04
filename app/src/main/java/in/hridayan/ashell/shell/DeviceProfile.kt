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
                "ro.product.model",
                "ro.product.vendor.model",
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

                vendorIdentity.containsAny("xiaomi", "redmi", "poco") -> arrayOf(
                    "ro.build.version.incremental",
                    "ro.build.display.id",
                )

                else -> arrayOf(
                    "ro.build.version.incremental",
                    "ro.build.display.id",
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

