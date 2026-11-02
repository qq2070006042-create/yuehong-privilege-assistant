@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package roro.stellar.yuehong.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import roro.stellar.yuehong.R
import roro.stellar.yuehong.dfroot.DirtyFragVrExtractor
import roro.stellar.yuehong.dfroot.DirtyFragVrReporter
import roro.stellar.yuehong.dfroot.DirtyFragVrRunner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** DirtyFrag VR controls only; the shared workbench owns the terminal below it. */
@Composable
fun DirtyFragVrWorkbenchControls(
    managerPackage: String,
    ksudPath: String,
    log: String,
    onLogChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { dirtyFragPreferences(context) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var removeVrProbe by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PREF_VR_DISARM, false))
    }
    var useKernelBtf by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PREF_VR_AUTO, true))
    }
    var vrOffset by rememberSaveable { mutableStateOf(prefs.getString(PREF_VR_OFFSET, "").orEmpty()) }
    var imageSource by rememberSaveable { mutableStateOf("") }
    var currentLog by remember { mutableStateOf(log) }

    LaunchedEffect(log) { currentLog = log }

    fun appendLog(message: String) {
        currentLog = (currentLog + message).takeLast(96 * 1024)
        onLogChanged(currentLog)
    }

    fun persistVrSettings() {
        prefs.edit()
            .putBoolean(PREF_VR_DISARM, removeVrProbe)
            .putBoolean(PREF_VR_AUTO, useKernelBtf)
            .putString(PREF_VR_OFFSET, vrOffset.trim())
            .apply()
    }

    fun applyOffsetResult(json: String) {
        val result = runCatching { JSONObject(json) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) {
            appendLog("Offset parsing failed: ${result?.optString("error") ?: json}\n")
            return
        }
        val offset = result.optLong("member", -1)
        if (offset !in 1L..0x100L) {
            appendLog("Offset parsing failed: invalid funcs offset $offset\n")
            return
        }
        vrOffset = offset.toString()
        persistVrSettings()
        val release = result.optString("release").takeIf { it.isNotBlank() }.orEmpty()
        appendLog(
            "Parsed tracepoint.funcs offset: $offset (0x${offset.toString(16)})" +
                if (release.isBlank()) "\n" else " · $release\n",
        )
    }

    fun extractOffset(input: String) {
        val source = input.trim()
        if (source.isEmpty() || extracting) return
        extracting = true
        appendLog("Parsing boot image / OTA source…\n")
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    DirtyFragVrExtractor.nativeDerive(source, context.cacheDir.absolutePath, false)
                }.getOrElse {
                    "{\"ok\":false,\"error\":${JSONObject.quote(it.message ?: it.javaClass.simpleName)}}"
                }
            }
            applyOffsetResult(output)
            extracting = false
        }
    }

    fun extractOffsetFromUri(uri: Uri) {
        if (extracting) return
        extracting = true
        appendLog("Copying selected boot image / OTA file…\n")
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected file")
                    val localFile = File(context.cacheDir, "dirtyfrag-vr-input.img")
                    input.use { source ->
                        FileOutputStream(localFile).use { destination -> source.copyTo(destination) }
                    }
                    DirtyFragVrExtractor.nativeDerive(localFile.absolutePath, context.cacheDir.absolutePath, false)
                }.getOrElse {
                    "{\"ok\":false,\"error\":${JSONObject.quote(it.message ?: it.javaClass.simpleName)}}"
                }
            }
            applyOffsetResult(output)
            extracting = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) extractOffsetFromUri(uri) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
            SettingSwitch(
                title = stringResource(R.string.dirtyfrag_vr_probe_enable),
                checked = removeVrProbe,
                enabled = !running && !extracting,
                onCheckedChange = {
                    removeVrProbe = it
                    persistVrSettings()
                },
            )
            if (removeVrProbe) {
                SettingSwitch(
                    title = stringResource(R.string.dirtyfrag_vr_kernel_btf),
                    checked = useKernelBtf,
                    enabled = !running && !extracting,
                    onCheckedChange = {
                        useKernelBtf = it
                        persistVrSettings()
                    },
                )
                Text(
                    text = stringResource(
                        if (useKernelBtf) R.string.dirtyfrag_vr_kernel_btf_help
                        else R.string.dirtyfrag_vr_manual_help,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!useKernelBtf) {
                    OutlinedTextField(
                        value = vrOffset,
                        onValueChange = {
                            vrOffset = it
                            persistVrSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running && !extracting,
                        label = { Text(stringResource(R.string.dirtyfrag_vr_offset_label)) },
                        placeholder = { Text("72 or 0x48") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = imageSource,
                            onValueChange = { imageSource = it },
                            modifier = Modifier.weight(1f),
                            enabled = !running && !extracting,
                            label = { Text(stringResource(R.string.dirtyfrag_vr_source_label)) },
                            placeholder = { Text(stringResource(R.string.dirtyfrag_vr_source_hint), maxLines = 1) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Button(
                            enabled = !running && !extracting && imageSource.isNotBlank(),
                            shape = MaterialTheme.shapes.medium,
                            onClick = { extractOffset(imageSource) },
                        ) { Text(stringResource(R.string.dirtyfrag_vr_parse_link)) }
                    }
                    TextButton(
                        enabled = !running && !extracting,
                        onClick = { imagePicker.launch(arrayOf("*/*")) },
                    ) { Text(stringResource(R.string.dirtyfrag_vr_pick_image)) }
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    text = stringResource(R.string.mode_dirtyfrag_vr_kernel_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !running && !extracting && managerPackage.isNotBlank() && ksudPath.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                onClick = {
                    if (running) return@Button
                    persistVrSettings()
                    running = true
                    currentLog = ""
                    onLogChanged("")
                    scope.launch(Dispatchers.IO) {
                        val reporter = DirtyFragVrReporter { message ->
                            scope.launch(Dispatchers.Main.immediate) { appendLog(message) }
                        }
                        val outcome = runCatching {
                            DirtyFragVrRunner.run(context, managerPackage, ksudPath, reporter)
                        }
                        withContext(Dispatchers.Main) {
                            outcome.exceptionOrNull()?.let {
                                appendLog("\n[DirtyFrag VR] ${it.message ?: it.javaClass.simpleName}\n")
                            }
                            appendLog(
                                if (outcome.getOrNull() == 0) {
                                    context.getString(R.string.dirtyfrag_vr_finished)
                                } else {
                                    context.getString(R.string.dirtyfrag_vr_failed, outcome.getOrNull() ?: -1)
                                },
                            )
                            running = false
                        }
                    }
                },
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(
                        when {
                            running -> R.string.dirtyfrag_vr_running
                            extracting -> R.string.dirtyfrag_vr_extracting
                            else -> R.string.dirtyfrag_vr_run
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private const val PREFS_NAME = "dfroot"
private const val PREF_VR_DISARM = "vr_disarm"
private const val PREF_VR_AUTO = "vr_auto"
private const val PREF_VR_OFFSET = "vr_off"

private fun dirtyFragPreferences(context: Context) = context
    .createDeviceProtectedStorageContext()
    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
