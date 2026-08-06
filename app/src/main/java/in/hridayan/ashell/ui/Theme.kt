package `in`.hridayan.ashell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 使用固定的中性色表面，避免 Android 动态取色把卡片染成壁纸主题色。
private val LightColors = lightColorScheme(
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
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF5F6068),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
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

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC2FF),
    onPrimary = Color(0xFF20205F),
    primaryContainer = Color(0xFF36366F),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC5C6D0),
    onSecondary = Color(0xFF2E3038),
    secondaryContainer = Color(0xFF3B3D46),
    onSecondaryContainer = Color(0xFFE2E2EA),
    tertiary = Color(0xFFD4C0CF),
    background = Color(0xFF111113),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF19191C),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF29292D),
    onSurfaceVariant = Color(0xFFC7C5CC),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF101012),
    surfaceContainerLow = Color(0xFF171719),
    surfaceContainer = Color(0xFF202024),
    surfaceContainerHigh = Color(0xFF29292D),
    surfaceContainerHighest = Color(0xFF333338),
    outline = Color(0xFF919099),
    outlineVariant = Color(0xFF44444A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
