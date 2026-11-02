package roro.stellar.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import roro.stellar.Stellar

/**
 * A shell-launched Stellar server keeps its classes loaded across APK updates.
 * Replace that stale process after the update, then start a fresh server from
 * the newly installed APK using the previously selected launch route.
 */
class AppReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The old server normally re-sends its manager Binder when the
                // package-replaced broadcast starts this process. Do not start
                // a service just because an APK was updated if none was active.
                if (!awaitBinderState(expected = true, timeoutMs = BINDER_ARRIVAL_TIMEOUT_MS)) {
                    Log.i(TAG, "No running Stellar service after APK update; skip automatic restart")
                    return@launch
                }

                Log.i(TAG, "Stopping the pre-update Stellar server")
                runCatching { Stellar.exit() }
                    .onFailure { Log.w(TAG, "Request to stop the pre-update Stellar server failed", it) }

                if (!awaitBinderState(expected = false, timeoutMs = SERVER_EXIT_TIMEOUT_MS)) {
                    Log.e(TAG, "The pre-update Stellar server did not stop; skip launching a second instance")
                    return@launch
                }

                runCatching {
                    StellarReceiverStarter.start(appContext, forceStart = true)
                }.onFailure {
                    Log.e(TAG, "Failed to restart Stellar with the updated APK", it)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun awaitBinderState(expected: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (Stellar.pingBinder() == expected) return true
            delay(POLL_INTERVAL_MS)
        }
        return Stellar.pingBinder() == expected
    }

    private companion object {
        const val TAG = "AppReplacedReceiver"
        // Keep the waits comfortably within BroadcastReceiver.goAsync()'s
        // execution window, leaving time to launch the replacement server.
        const val BINDER_ARRIVAL_TIMEOUT_MS = 3_000L
        const val SERVER_EXIT_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
