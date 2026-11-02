@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package roro.stellar.yuehong.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

@Composable
internal fun DirtyFragVrScreen(
    managerPackage: String,
    ksudPath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { dirtyFragPreferences(context) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var running by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var removeVrProbe by rememberSaveable { mutableStateOf(prefs.getBoolean(PREF_VR_DISARM, false)) }
    var useKernelBtf by rememberSaveable { mutableStateOf(prefs.getBoolean(PREF_VR_AUTO, true)) }
    var vrOffset by rememberSaveable { mutableStateOf(prefs.getString(PREF_VR_OFFSET, "").orEmpty()) }
    var imageSource by rememberSaveable { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    fun appendLog(message: String) {
        log = (log + message).takeLast(96 * 1024)
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
        appendLog("Parsed tracepoint.funcs offset: $offset (0x${offset.toString(16)})"
            + if (release.isBlank()) "\n" else " · $release\n")
    }

    fun extractOffset(input: String) {
        val source = input.trim()
        if (source.isEmpty() || extracting) return
        extracting = true
        appendLog("Parsing boot image / OTA source…\n")
        scope.launch {
            val output = withContext(Dispatchers.IO) {
                runCatching {
                    DirtyFragVrExtractor.nativeDerive(
                        source,
                        context.cacheDir.absolutePath,
                        false,
                    )
                }.getOrElse { "{\"ok\":false,\"error\":${JSONObject.quote(it.message ?: it.javaClass.simpleName)}}" }
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
                    DirtyFragVrExtractor.nativeDerive(
                        localFile.absolutePath,
                        context.cacheDir.absolutePath,
                        false,
                    )
                }.getOrElse { "{\"ok\":false,\"error\":${JSONObject.quote(it.message ?: it.javaClass.simpleName)}}" }
            }
            applyOffsetResult(output)
            extracting = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) extractOffsetFromUri(uri)
    }

    Scaffold(
        containerColor = colors.background,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dirtyfrag_vr_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.background,
                ),
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DeviceCard(
                title = stringResource(R.string.dirtyfrag_vr_device_info_title),
                info = stringResource(
                    R.string.dirtyfrag_vr_device_info,
                    Build.MANUFACTURER,
                    Build.MODEL,
                    System.getProperty("os.version", "unknown").orEmpty(),
                ),
            )

            DeviceCard(
                title = stringResource(R.string.dirtyfrag_vr_manager_title),
                info = if (managerPackage.isBlank() || ksudPath.isBlank()) {
                    stringResource(R.string.dirtyfrag_vr_manager_missing)
                } else {
                    stringResource(R.string.dirtyfrag_vr_manager_info, managerPackage, ksudPath)
                },
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dirtyfrag_vr_probe_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.dirtyfrag_vr_probe_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    SettingSwitch(
                        title = stringResource(R.string.dirtyfrag_vr_probe_enable),
                        checked = removeVrProbe,
                        onCheckedChange = {
                            removeVrProbe = it
                            persistVrSettings()
                        },
                    )
                    if (removeVrProbe) {
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.7f))
                        SettingSwitch(
                            title = stringResource(R.string.dirtyfrag_vr_kernel_btf),
                            checked = useKernelBtf,
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
                            color = colors.onSurfaceVariant,
                        )
                        if (!useKernelBtf) {
                            OutlinedTextField(
                                value = vrOffset,
                                onValueChange = {
                                    vrOffset = it
                                    persistVrSettings()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.dirtyfrag_vr_offset_label)) },
                                placeholder = { Text("72 or 0x48") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            )
                            OutlinedTextField(
                                value = imageSource,
                                onValueChange = { imageSource = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.dirtyfrag_vr_source_label)) },
                                placeholder = { Text(stringResource(R.string.dirtyfrag_vr_source_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    enabled = !extracting,
                                    onClick = { extractOffset(imageSource) },
                                ) {
                                    Text(stringResource(R.string.dirtyfrag_vr_parse_link))
                                }
                                TextButton(
                                    enabled = !extracting,
                                    onClick = { imagePicker.launch(arrayOf("*/*")) },
                                ) {
                                    Text(stringResource(R.string.dirtyfrag_vr_pick_image))
                                }
                            }
                        }
                    }
                }
            }

            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !running && !extracting && managerPackage.isNotBlank() && ksudPath.isNotBlank(),
                onClick = {
                    if (running) return@MotionButton
                    persistVrSettings()
                    running = true
                    log = ""
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
                                    context.getString(
                                        R.string.dirtyfrag_vr_failed,
                                        outcome.getOrNull() ?: -1,
                                    )
                                },
                            )
                            running = false
                        }
                    }
                },
            ) {
                Icon(
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

            LogCard(log = log)
        }
    }

    LaunchedEffect(log) {
        scrollState.scrollTo(scrollState.maxValue)
    }
}

private const val PREFS_NAME = "dfroot"
private const val PREF_VR_DISARM = "vr_disarm"
private const val PREF_VR_AUTO = "vr_auto"
private const val PREF_VR_OFFSET = "vr_off"

private fun dirtyFragPreferences(context: Context) = context
    .createDeviceProtectedStorageContext()
    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeviceCard(
    title: String,
    info: String,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogCard(log: String) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.dirtyfrag_vr_log_title),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.7f))
            if (log.isBlank()) {
                Text(
                    text = stringResource(R.string.dirtyfrag_vr_log_empty),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(
                        text = log,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}
