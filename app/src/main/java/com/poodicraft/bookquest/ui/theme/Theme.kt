package com.poodicraft.bookquest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Playful brand colours shared by gradients and highlights. */
object Brand {
    val Violet = Color(0xFF6C4CF1)
    val VioletDeep = Color(0xFF3B1E9E)
    val Sky = Color(0xFF37B6FF)
    val Mint = Color(0xFF17C99A)
    val Sun = Color(0xFFFFB020)
    val Coral = Color(0xFFFF5C7A)
    val Bubblegum = Color(0xFFE07BFF)
    val Ink = Color(0xFF1B1533)
    val Cloud = Color(0xFFF7F1FF)
}

private val LightColors = lightColorScheme(
    primary = Brand.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Brand.VioletDeep,
    secondary = Brand.Coral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDE4),
    onSecondaryContainer = Color(0xFF6B1027),
    tertiary = Brand.Mint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9F5E7),
    onTertiaryContainer = Color(0xFF00382B),
    background = Brand.Cloud,
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEDE6FB),
    onSurfaceVariant = Color(0xFF4B4166),
    outline = Color(0xFFB9AED4),
    error = Color(0xFFD32F4B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A4FF),
    onPrimary = Color(0xFF23106B),
    primaryContainer = Color(0xFF3A2A7A),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFFF93A9),
    onSecondary = Color(0xFF54001B),
    secondaryContainer = Color(0xFF6E203A),
    onSecondaryContainer = Color(0xFFFFDDE4),
    tertiary = Color(0xFF63E3C0),
    onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF10513F),
    onTertiaryContainer = Color(0xFFC9F5E7),
    background = Color(0xFF120E24),
    onBackground = Color(0xFFEDE7FF),
    surface = Color(0xFF1B1533),
    onSurface = Color(0xFFEDE7FF),
    surfaceVariant = Color(0xFF2A2148),
    onSurfaceVariant = Color(0xFFCBBFEA),
    outline = Color(0xFF6C5F92),
    error = Color(0xFFFF8A9B)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun BookQuestTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
