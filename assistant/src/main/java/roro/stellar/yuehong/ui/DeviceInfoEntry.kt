package roro.stellar.yuehong.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import roro.stellar.yuehong.R

/** Opens the assistant's device information details. */
@Composable
fun SharedDeviceInfoButton(compact: Boolean = false) {
    var showDeviceInfo by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "device-info-press-scale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (showDeviceInfo) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "device-info-open-rotation",
    )

    IconButton(
        modifier = (if (compact) Modifier.size(40.dp) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            },
        interactionSource = interactionSource,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        onClick = { showDeviceInfo = true },
    ) {
        Icon(
            modifier = if (compact) Modifier.size(18.dp) else Modifier,
            painter = painterResource(R.drawable.ic_info),
            contentDescription = stringResource(R.string.open_device_info),
        )
    }

    if (showDeviceInfo) {
        DeviceInfoDialog(onDismiss = { showDeviceInfo = false })
    }
}
