package com.signalless.relay.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark-only palette from UI-PLAN §7.8. */
object RelayColors {
    val Background = Color(0xFF0E0F10)
    val Surface = Color(0xFF17181A)
    val SurfaceRaised = Color(0xFF1E2023)
    val Border = Color(0xFF2A2D31)
    val Text = Color(0xFFECEDEE)
    val Muted = Color(0xFF9BA1A6)
    val Accent = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
    val OnAccent = Color(0xFF06281C)
}

private val RelayColorScheme = darkColorScheme(
    primary = RelayColors.Accent,
    onPrimary = RelayColors.OnAccent,
    secondary = RelayColors.Accent,
    onSecondary = RelayColors.OnAccent,
    background = RelayColors.Background,
    onBackground = RelayColors.Text,
    surface = RelayColors.Surface,
    onSurface = RelayColors.Text,
    surfaceVariant = RelayColors.SurfaceRaised,
    onSurfaceVariant = RelayColors.Muted,
    outline = RelayColors.Border,
    error = RelayColors.Error,
    onError = Color(0xFF2B0B0B),
)

private val RelayTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.2).sp,
        color = RelayColors.Text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = RelayColors.Text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        color = RelayColors.Text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        color = RelayColors.Muted,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
        color = RelayColors.Muted,
    ),
)

private val RelayShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RelayColorScheme,
        typography = RelayTypography,
        shapes = RelayShapes,
        content = content,
    )
}
