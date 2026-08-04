package `in`.hridayan.ashell.shell

import android.os.Build
import android.system.Os

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
            val kernel = runCatching { Os.uname().release }.getOrNull()
            return DeviceProfile(
                model = Build.MODEL.clean(),
                systemVersion = Build.DISPLAY.clean(),
                kernelVersion = kernel.clean(),
            )
        }
    }
}

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
