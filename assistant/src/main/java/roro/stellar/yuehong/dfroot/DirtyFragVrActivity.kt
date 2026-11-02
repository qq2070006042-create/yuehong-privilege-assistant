package roro.stellar.yuehong.dfroot

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import roro.stellar.yuehong.ui.DirtyFragVrScreen
import roro.stellar.yuehong.ui.StellarTheme

class DirtyFragVrActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MANAGER_PACKAGE = "roro.stellar.yuehong.extra.DIRTYFRAG_VR_MANAGER_PACKAGE"
        const val EXTRA_KSUD_PATH = "roro.stellar.yuehong.extra.DIRTYFRAG_VR_KSUD_PATH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            finish()
            return
        }
        enableEdgeToEdge()
        val managerPackage = intent.getStringExtra(EXTRA_MANAGER_PACKAGE).orEmpty()
        val ksudPath = intent.getStringExtra(EXTRA_KSUD_PATH).orEmpty()
        setContent {
            StellarTheme {
                DirtyFragVrScreen(
                    managerPackage = managerPackage,
                    ksudPath = ksudPath,
                    onBack = ::finish,
                )
            }
        }
    }
}
