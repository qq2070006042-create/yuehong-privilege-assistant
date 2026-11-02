package roro.stellar.manager.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// 与权限和提权页面使用同一套固定色板，避免动态取色造成页面割裂。
private val LightColorScheme = lightColorScheme(
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

private val DarkColorScheme = darkColorScheme(
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

// Keep the manager and the assistant on the same Material 3 shape scale.
private val YueHongShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StellarTheme(
    themeMode: ThemeMode = ThemePreferences.themeMode.value,
    content: @Composable () -> Unit
) {
    // 客户端外观始终跟随设备状态；保留参数只为兼容上游调用签名。
    @Suppress("UNUSED_VARIABLE")
    val ignoredLegacyPreference = themeMode
    val darkTheme = isSystemInDarkTheme()
    
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = YueHongShapes,
        content = content
    )
}
