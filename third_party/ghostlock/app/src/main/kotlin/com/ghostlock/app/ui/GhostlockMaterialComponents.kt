@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ghostlock.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardDefaults as MaterialCardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.TopAppBar as MaterialTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/** Small M3 adapters for the upstream screen API; all rendered controls use Material 3. */
internal data class GhostlockCardColors(
    val containerColor: Color,
    val contentColor: Color,
)

internal object GhostlockCardDefaults {
    @Composable
    fun defaultColors(
        color: Color? = null,
        contentColor: Color? = null,
    ): GhostlockCardColors = GhostlockCardColors(
        containerColor = color ?: MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor ?: MaterialTheme.colorScheme.onSurface,
    )
}

private val GhostlockLightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECEBFF),
    onPrimaryContainer = Color(0xFF29217A),
    secondary = Color(0xFF555A68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEEF3),
    onSecondaryContainer = Color(0xFF242832),
    tertiary = Color(0xFF6B5567),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF1B1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF5F6068),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFEAEAEF),
    surfaceContainerHighest = Color(0xFFE3E3E8),
    outline = Color(0xFF777780),
    outlineVariant = Color(0xFFD7D7DD),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val GhostlockDarkColors = darkColorScheme(
    primary = Color(0xFFAEB4FF),
    onPrimary = Color(0xFF171A4F),
    primaryContainer = Color(0xFF292E5D),
    onPrimaryContainer = Color(0xFFE1E3FF),
    secondary = Color(0xFFC4C6D4),
    onSecondary = Color(0xFF292B34),
    secondaryContainer = Color(0xFF343741),
    onSecondaryContainer = Color(0xFFE2E3EF),
    tertiary = Color(0xFFD6C0D1),
    background = Color(0xFF08090D),
    onBackground = Color(0xFFF0F0F7),
    surface = Color(0xFF11131A),
    onSurface = Color(0xFFF0F0F7),
    surfaceVariant = Color(0xFF1D202A),
    onSurfaceVariant = Color(0xFFC8CAD6),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF101218),
    surfaceContainer = Color(0xFF171922),
    surfaceContainerHigh = Color(0xFF20232D),
    surfaceContainerHighest = Color(0xFF292C37),
    outline = Color(0xFF8D91A1),
    outlineVariant = Color(0xFF343846),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val GhostlockShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
internal fun GhostlockMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) GhostlockDarkColors else GhostlockLightColors,
        shapes = GhostlockShapes,
        content = content,
    )
}

@Composable
internal fun GhostlockCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: GhostlockCardColors = GhostlockCardDefaults.defaultColors(),
    showIndication: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val keepM3ClickIndication = showIndication
    val shape = RoundedCornerShape(cornerRadius)
    val materialColors = MaterialCardDefaults.cardColors(
        containerColor = colors.containerColor,
        contentColor = colors.contentColor,
    )
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(insideMargin),
            content = content,
        )
    }
    if (onClick == null) {
        MaterialCard(
            modifier = modifier,
            shape = shape,
            colors = materialColors,
            content = cardContent,
        )
    } else {
        MaterialCard(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = materialColors,
            content = cardContent,
        )
    }
}

internal data class GhostlockDropdownItem(val icon: ImageVector? = null, val title: String)

@Composable
internal fun GhostlockArrowPreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    endActions: (@Composable RowScope.() -> Unit)? = null,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = summary?.takeIf(String::isNotBlank)?.let { value ->
            { Text(value, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                endActions?.invoke(this)
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun GhostlockSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = summary?.takeIf(String::isNotBlank)?.let { value ->
            { Text(value, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun GhostlockSpinnerPreference(
    title: String,
    items: List<GhostlockDropdownItem>,
    selectedIndex: Int,
    summary: String? = null,
    showValue: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = if (showValue) items.getOrNull(selectedIndex)?.title else summary
    androidx.compose.foundation.layout.Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
            supportingContent = displayValue?.takeIf(String::isNotBlank)?.let { value ->
                { Text(value, style = MaterialTheme.typography.bodySmall) }
            },
            trailingContent = {
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    onClick = {
                        expanded = false
                        onSelectedIndexChange(index)
                    },
                )
            }
        }
    }
}

internal enum class GhostlockRadioButtonLocation { Start, End }

@Composable
internal fun GhostlockRadioPreference(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    radioButtonLocation: GhostlockRadioButtonLocation = GhostlockRadioButtonLocation.End,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        leadingContent = if (radioButtonLocation == GhostlockRadioButtonLocation.Start) {
            { RadioButton(selected = selected, onClick = onClick) }
        } else null,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        trailingContent = if (radioButtonLocation == GhostlockRadioButtonLocation.End) {
            { RadioButton(selected = selected, onClick = onClick) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun GhostlockTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    onClick: () -> Unit,
) {
    MaterialTextButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
        Text(text)
    }
}

internal object GhostlockButtonColors {
    @Composable
    fun textButtonColors(): ButtonColors = ButtonDefaults.textButtonColors()

    @Composable
    fun textButtonColorsPrimary(): ButtonColors = ButtonDefaults.textButtonColors()
}

internal data class GhostlockTextFieldColorValues(val labelColor: Color? = null)

internal object GhostlockTextFieldDefaults {
    fun textFieldColors(labelColor: Color? = null) = GhostlockTextFieldColorValues(labelColor)
}

@Composable
internal fun GhostlockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    colors: GhostlockTextFieldColorValues? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = {
            Text(
                text = label,
                color = colors?.labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = singleLine,
    )
}

@Composable
internal fun GhostlockSmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun GhostlockScrollBehavior(state: TopAppBarState): TopAppBarScrollBehavior =
    TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state)

typealias ScrollBehavior = TopAppBarScrollBehavior

@Composable
internal fun GhostlockTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigationIcon: @Composable () -> Unit = {},
) {
    MaterialTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        modifier = modifier,
        navigationIcon = navigationIcon,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
internal fun GhostlockOverlayDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            wasVisible = true
        } else if (wasVisible) {
            wasVisible = false
            onDismissFinished?.invoke()
        }
    }
    if (show) {
        Dialog(onDismissRequest = onDismissRequest) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    summary?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
internal fun GhostlockOverlayBottomSheet(
    show: Boolean,
    title: String,
    allowDismiss: Boolean = true,
    onDismissRequest: () -> Unit,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (show) {
        ModalBottomSheet(
            onDismissRequest = { if (allowDismiss) onDismissRequest() },
            sheetGesturesEnabled = allowDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.width(48.dp)) { startAction?.invoke() }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.width(48.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) { endAction?.invoke() }
                }
                content()
            }
        }
    }
}
