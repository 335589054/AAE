package dev.local.arcaea.apkmanager.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 深色主题色板：背景 #0F1115 / 卡片 #171A21 / 主色 #7C5CFF */

val AppBackground = Color(0xFF0F1115)
val AppCard = Color(0xFF171A21)
val AppCardAlt = Color(0xFF1E222C)
val AppPrimary = Color(0xFF7C5CFF)
val AppOutline = Color(0xFF2C323F)
val AppTextDim = Color(0xFF9BA3B4)
val AppYellow = Color(0xFFF4C35B)
val AppRed = Color(0xFFFF6B6B)
val AppGreen = Color(0xFF5BD68A)

private val AppColorScheme = darkColorScheme(
    primary = AppPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE3DDFF),
    secondary = Color(0xFF9C8CFF),
    onSecondary = Color(0xFF141221),
    tertiary = Color(0xFF6FD6C4),
    onTertiary = Color(0xFF0C1F1B),
    background = AppBackground,
    onBackground = Color(0xFFE7E9EF),
    surface = AppCard,
    onSurface = Color(0xFFE7E9EF),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = AppTextDim,
    outline = AppOutline,
    outlineVariant = Color(0xFF232833),
    error = AppRed,
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0xFF4A1418),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, shapes = AppShapes, content = content)
}
