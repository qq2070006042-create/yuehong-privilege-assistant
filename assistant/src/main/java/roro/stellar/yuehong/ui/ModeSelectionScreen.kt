package roro.stellar.yuehong.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import roro.stellar.yuehong.R
import roro.stellar.yuehong.shell.KernelSuManagerInfo
import roro.stellar.yuehong.shell.KernelSuManagerSelection

@Composable
internal fun ModeSelectionScreen(
    dirtyFragVrPlatformAvailable: Boolean,
    onOpenGhostLock: () -> Unit,
    onOpenDirtyFragVr: () -> Unit,
    onOpenStellar: () -> Unit,
    onOpenVivoWired: () -> Unit,
) {
    val context = LocalContext.current
    var selectedPackage by remember(context) {
        mutableStateOf(KernelSuManagerSelection.selectedPackage(context))
    }
    var refreshingManager by remember { mutableStateOf(true) }
    var showManagerPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        refreshingManager = true
        withContext(Dispatchers.IO) {
            if (KernelSuManagerSelection.selectedManagerTarget(context) == null) {
                KernelSuManagerSelection.refreshSelectedManager(context)
            }
        }
        refreshingManager = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.mode_selection_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showManagerPicker = true },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.kernel_su_manager_select),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = selectedPackage,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !refreshingManager,
                onClick = onOpenStellar,
            ) {
                Text(
                    text = stringResource(R.string.mode_stellar_open),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = dirtyFragVrPlatformAvailable && !refreshingManager,
                onClick = onOpenDirtyFragVr,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mode_dirtyfrag_vr_open),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.mode_dirtyfrag_vr_kernel_required),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !refreshingManager,
                onClick = onOpenGhostLock,
            ) {
                Text(
                    text = stringResource(R.string.mode_ghostlock_open),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            MotionButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !refreshingManager,
                onClick = onOpenVivoWired,
            ) {
                Text(
                    text = stringResource(R.string.mode_vivo_wired_open),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showManagerPicker) {
        KernelSuManagerPickerDialog(
            selectedPackage = selectedPackage,
            onDismiss = { showManagerPicker = false },
            onSelected = { manager ->
                if (manager == null) {
                    selectedPackage = KernelSuManagerSelection.saveSelectedPackage(
                        context,
                        KernelSuManagerSelection.DEFAULT_PACKAGE,
                    )
                } else {
                    selectedPackage = KernelSuManagerSelection.saveSelectedManager(context, manager)
                }
                showManagerPicker = false
            },
        )
    }
}

@Composable
private fun KernelSuManagerPickerDialog(
    selectedPackage: String,
    onDismiss: () -> Unit,
    onSelected: (KernelSuManagerInfo?) -> Unit,
) {
    val context = LocalContext.current
    var managers by remember { mutableStateOf<List<KernelSuManagerInfo>?>(null) }

    LaunchedEffect(Unit) {
        managers = withContext(Dispatchers.IO) {
            KernelSuManagerSelection.scan(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kernel_su_manager_dialog_title)) },
        text = {
            when (val list = managers) {
                null -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(stringResource(R.string.kernel_su_manager_scanning))
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.kernel_su_manager_current, selectedPackage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.kernel_su_manager_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(list, key = { it.packageName }) { manager ->
                                KernelSuManagerRow(
                                    manager = manager,
                                    selected = manager.packageName == selectedPackage,
                                    onClick = { onSelected(manager) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = managers != null,
                onClick = {
                    onSelected(
                        managers?.firstOrNull {
                            it.packageName == KernelSuManagerSelection.DEFAULT_PACKAGE
                        },
                    )
                },
            ) {
                Text(stringResource(R.string.kernel_su_manager_use_default))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun KernelSuManagerRow(
    manager: KernelSuManagerInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = remember(manager.packageName) { drawableToBitmap(manager.icon) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = manager.label,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(modifier = Modifier.size(40.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manager.label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = manager.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
    return runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }.getOrNull()
}
