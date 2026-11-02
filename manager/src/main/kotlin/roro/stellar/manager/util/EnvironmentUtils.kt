package roro.stellar.manager.util

import android.os.SystemProperties
import java.io.File
import java.net.NetworkInterface

object EnvironmentUtils {

    fun getWifiIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.name.startsWith("wlan") && it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress

    fun isRooted(): Boolean =
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.any { File("$it/su").exists() } == true

    fun getAdbTcpPort(): Int =
        SystemProperties.getInt("service.adb.tcp.port", -1)
            .takeIf { it != -1 }
            ?: SystemProperties.getInt("persist.adb.tcp.port", -1)
}

