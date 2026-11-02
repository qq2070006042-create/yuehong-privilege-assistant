package roro.stellar.server.daemon

import roro.stellar.server.util.Logger

object DaemonManager {
    private val LOGGER = Logger("DaemonManager")

    fun startDaemon(serverPid: Int, startCommand: String): Int {
        try {
            stopDaemon()

            val classpath = System.getProperty("java.class.path")
            if (classpath == null) {
                LOGGER.e("无法获取 CLASSPATH")
                return -1
            }

            val cmd = "CLASSPATH=$classpath app_process /system/bin --nice-name=stellar_daemon roro.stellar.server.daemon.StellarDaemon $serverPid '$startCommand' >/dev/null 2>&1 & echo \$!"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val pid = process.inputStream.bufferedReader().readLine()?.toIntOrNull() ?: -1
            LOGGER.i("守护进程已启动，服务 PID=$serverPid, daemon PID=$pid")
            return pid
        } catch (e: Exception) {
            LOGGER.e(e, "启动守护进程失败")
            return -1
        }
    }

    fun stopDaemon() {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 stellar_daemon 2>/dev/null || true"))
            LOGGER.i("已停止所有 stellar_daemon 进程")
        } catch (e: Exception) {
            LOGGER.e(e, "停止守护进程失败")
        }
    }
}
