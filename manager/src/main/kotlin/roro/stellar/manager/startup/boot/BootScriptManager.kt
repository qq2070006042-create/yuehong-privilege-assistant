package roro.stellar.manager.startup.boot

import com.topjohnwu.superuser.Shell

object BootScriptManager {
    const val SCRIPT_PATH = "/data/adb/service.d/stellar.sh"

    private const val INSTALL_SCRIPT_COMMAND =
        "printf '#!/system/bin/sh\\nwhile [ \"${'$'}(getprop sys.boot_completed)\" != \"1\" ]; do sleep 3; done\\nS=${'$'}(ls ${'$'}(pm path roro.stellar.manager | cut -d: -f2 | sed \"s/base.apk//\")lib/*/libstellar.so | head -n 1); [ -n \"${'$'}S\" ] && \"${'$'}S\"' > /data/adb/service.d/stellar.sh && chmod 755 /data/adb/service.d/stellar.sh"

    private const val REMOVE_SCRIPT_COMMAND = "rm -f $SCRIPT_PATH"

    data class Result(
        val success: Boolean,
        val message: String
    )

    fun hasRootPermission(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (_: Exception) {
            false
        }
    }

    fun isScriptInstalled(): Boolean {
        if (!hasRootPermission()) return false
        return try {
            val result = Shell.cmd("[ -x \"$SCRIPT_PATH\" ]").exec()
            result.code == 0
        } catch (_: Exception) {
            false
        }
    }

    fun installScript(): Result {
        if (!hasRootPermission()) {
            return Result(success = false, message = "No root permission")
        }
        return exec(INSTALL_SCRIPT_COMMAND)
    }

    fun removeScript(): Result {
        if (!hasRootPermission()) {
            return Result(success = false, message = "No root permission")
        }
        return exec(REMOVE_SCRIPT_COMMAND)
    }

    private fun exec(command: String): Result {
        return try {
            val result = Shell.cmd(command).exec()
            if (result.code == 0) {
                Result(success = true, message = "")
            } else {
                val error = result.err.joinToString("\n").ifEmpty { "exit code: ${result.code}" }
                Result(success = false, message = error)
            }
        } catch (e: Exception) {
            Result(success = false, message = e.message ?: "Unknown error")
        }
    }
}
