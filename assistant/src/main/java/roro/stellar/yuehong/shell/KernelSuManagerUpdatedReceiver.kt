package roro.stellar.yuehong.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Refreshes the selected ksud path when Android replaces its manager app. */
class KernelSuManagerUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        KernelSuManagerSelection.invalidateScanCache()
        if (packageName != KernelSuManagerSelection.selectedPackage(context)) return

        if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) {
            return
        }

        val pendingResult = goAsync()
        Thread(
            {
                try {
                    KernelSuManagerSelection.refreshSelectedManager(context)
                } finally {
                    pendingResult.finish()
                }
            },
            "selected-ksud-refresh",
        ).start()
    }
}
