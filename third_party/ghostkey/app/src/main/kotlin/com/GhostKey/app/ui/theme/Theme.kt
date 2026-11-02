package com.GhostKey.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 全局固定色板。
 *
 * 安全工具类界面强调「信息密度 > 装饰」，所以颜色只承担语义区分：
 * 中性色铺底、青色作主操作、蓝/绿/琥珀/红分别对应 系统 / 成功 / 警告 / 失败。
 * 单独抽成 object 是为了让终端、步进器等自绘组件能直接取色，
 * 不依赖 colorScheme 里的某个语义槽位（避免深浅主题切换时语义错位）。
 */
object CvePalette {
    val Backdrop = Color(0xFF070B11)
    val Surface = Color(0xFF0F1620)
    val SurfaceRaised = Color(0xFF151F2B)
    val SurfaceSunken = Color(0xFF04070B)

    val Outline = Color(0xFF22303F)
    val OutlineStrong = Color(0xFF334759)

    val Accent = Color(0xFF4DD8C0)
    val AccentDim = Color(0xFF1F6F63)
    val Info = Color(0xFF6FA8DC)
    val Success = Color(0xFF5BD6A0)
    val Warning = Color(0xFFE8B45A)
    val Danger = Color(0xFFE5646E)

    val TextPrimary = Color(0xFFDDE6F0)
    val TextSecondary = Color(0xFF8DA0B4)
    val TextMuted = Color(0xFF5C6E80)

    /** 成功 / 失败 图标画在实心圆上时用的「挖空色」。 */
    val OnBadge = Color(0xFF04140F)
    val OnBadgeError = Color(0xFF2A070A)
}

private val DarkColorScheme = darkColorScheme(
    primary = CvePalette.Accent,
    onPrimary = Color(0xFF02201C),
    primaryContainer = Color(0xFF123B36),
    onPrimaryContainer = Color(0xFF9DF0E3),
    secondary = CvePalette.Info,
    onSecondary = Color(0xFF02202F),
    secondaryContainer = Color(0xFF12303F),
    onSecondaryContainer = Color(0xFFB9DCF7),
    tertiary = CvePalette.Success,
    onTertiary = Color(0xFF04241A),
    tertiaryContainer = Color(0xFF123A2C),
    onTertiaryContainer = Color(0xFFA6F2CE),
    background = CvePalette.Backdrop,
    onBackground = CvePalette.TextPrimary,
    surface = CvePalette.Surface,
    onSurface = CvePalette.TextPrimary,
    surfaceVariant = CvePalette.SurfaceRaised,
    onSurfaceVariant = CvePalette.TextSecondary,
    outline = CvePalette.Outline,
    outlineVariant = CvePalette.OutlineStrong,
    error = CvePalette.Danger,
    onError = Color(0xFF2A0508),
    errorContainer = Color(0xFF3A151A),
    onErrorContainer = Color(0xFFFFCDD1),
    scrim = Color(0xFF000000),
)

/** 浅色仅作兜底：本应用默认强制深色，保证终端区在任何设备上观感一致。 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0F7A6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEDE7),
    onPrimaryContainer = Color(0xFF03201C),
    secondary = Color(0xFF2F6390),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E6F5),
    onSecondaryContainer = Color(0xFF06202F),
    tertiary = Color(0xFF1F7350),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCBEFDD),
    onTertiaryContainer = Color(0xFF04241A),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF14202C),
    surface = Color(0xFFFBFDFF),
    onSurface = Color(0xFF14202C),
    surfaceVariant = Color(0xFFE6EDF4),
    onSurfaceVariant = Color(0xFF4A5B6C),
    outline = Color(0xFFC3D0DC),
    outlineVariant = Color(0xFFA9B9C7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/** 字号整体收紧一档，给终端与状态信息腾出竖向空间。 */
private val CveTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.15.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp),
)

@Composable
fun CVE98260Theme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CveTypography,
        content = content,
    )
}
