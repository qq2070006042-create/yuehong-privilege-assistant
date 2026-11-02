package roro.stellar.manager.startup.command

import roro.stellar.manager.StellarSettings
import roro.stellar.manager.application
import java.io.File

object Starter {

    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libstellar.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    val internalCommand: String
        get() {
            val baseCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
            val dropPrivileges = StellarSettings.getPreferences()
                .getBoolean(StellarSettings.DROP_PRIVILEGES, false)
            return if (dropPrivileges) {
                "${Chid.path} 2000 $baseCommand"
            } else {
                baseCommand
            }
        }

    val wirelessDebuggingCommand: String
        get() {
            if (!StellarSettings.getPreferences().getBoolean(StellarSettings.WIRELESS_DEBUGGING_SU, false)) {
                return internalCommand
            }
            val quotedCommand = internalCommand.replace("'", "'\\\"'\\\"'")
            return "su -c '$quotedCommand' || $internalCommand"
        }
}
