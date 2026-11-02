package roro.stellar.yuehong.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghostlock.app.data.AndroidGhostlockRepository
import com.ghostlock.app.ui.DocumentRequest
import com.ghostlock.app.ui.GhostlockEffect
import com.ghostlock.app.ui.GhostlockViewModel
import com.GhostKey.app.viewmodel.ExploitViewModel
import roro.stellar.manager.ui.features.workbench.PrivilegeWorkbenchScreen
import roro.stellar.yuehong.shell.KernelSuManagerSelection
import roro.stellar.yuehong.ui.StellarAssistantApp

class MainActivity : ComponentActivity() {
    private var ghostlockRepository: AndroidGhostlockRepository? = null
    private var pendingDocumentRequest: DocumentRequest? = null

    private val ghostlockViewModel by viewModels<GhostlockViewModel> {
        viewModelFactory {
            initializer {
                val selectedPackage = KernelSuManagerSelection.selectedPackage(applicationContext)
                val selectedKsud = KernelSuManagerSelection
                    .selectedManagerTarget(applicationContext)
                    ?.takeIf { it.packageName == selectedPackage }
                    ?.libKsudPath
                val repository = AndroidGhostlockRepository(
                    applicationContext,
                    launchManagerPackage = selectedPackage,
                    launchKsudPath = selectedKsud,
                )
                ghostlockRepository = repository
                GhostlockViewModel(repository)
            }
        }
    }

    private val ghostKeyViewModel by viewModels<ExploitViewModel>()

    private val ghostlockDocumentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val request = pendingDocumentRequest
        pendingDocumentRequest = null
        if (uri != null && request != null) {
            ghostlockViewModel.onDocumentResult(request, uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ghostlockViewModel.initialize()
        setContent {
            StellarAssistantApp(
                workbenchContent = {
                    PrivilegeWorkbenchScreen(
                        ghostlockViewModel = ghostlockViewModel,
                        ghostKeyViewModel = ghostKeyViewModel,
                        onManagerTargetChanged = { packageName, ksudPath ->
                            ghostlockRepository?.updateAssistantManagerTarget(packageName, ksudPath)
                        },
                        onGhostlockEffect = ::handleGhostlockEffect,
                    )
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ghostlockViewModel.refreshAccessStatus()
    }

    private fun handleGhostlockEffect(effect: GhostlockEffect) {
        when (effect) {
            is GhostlockEffect.PickDocument -> {
                pendingDocumentRequest = effect.request
                val mimeTypes = when (effect.request) {
                    DocumentRequest.ImportOffsetsHocon -> arrayOf("text/plain", "application/octet-stream")
                    DocumentRequest.ImportOffsetsJson -> arrayOf("application/json", "text/plain", "application/octet-stream")
                    else -> arrayOf("*/*")
                }
                ghostlockDocumentPicker.launch(mimeTypes)
            }

            is GhostlockEffect.Toast -> Toast.makeText(this, effect.resourceId, Toast.LENGTH_SHORT).show()
            is GhostlockEffect.Clipboard -> {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("ghostlock", effect.text))
            }
            is GhostlockEffect.KeepScreenAwake -> if (effect.enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            GhostlockEffect.OpenShizuku -> packageManager
                .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?.let(::startActivity)
            is GhostlockEffect.Share -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(effect.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享偏移文件"))
            }
            is GhostlockEffect.CreateProfileDocument,
            GhostlockEffect.PickDebugFolder,
            -> Unit
        }
    }
}
