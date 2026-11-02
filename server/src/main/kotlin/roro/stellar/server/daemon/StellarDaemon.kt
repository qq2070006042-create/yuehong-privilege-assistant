package roro.stellar.server.daemon

import android.util.Log

object StellarDaemon {
    private const val TAG = "StellarDaemon"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            return
        }

        val targetPid = args[0].toIntOrNull() ?: return
        val startCommand = args.drop(1).joinToString(" ")

        Log.i(TAG, "守护进程已启动，监控 stellar_server PID: $targetPid")

        while (true) {
            try {
                if (!isProcessAlive(targetPid)) {
                    Log.w(TAG, "检测到目标进程死亡，执行启动命令")
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", startCommand))
                    break
                }
                Thread.sleep(5000)
            } catch (e: Exception) {
                Log.e(TAG, "守护进程错误", e)
            }
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            java.io.File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }
}
