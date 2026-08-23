package com.paperjump.ui.theme

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Ink-on-paper base with a lava accent — the same palette the game canvas uses.
val LavaOrange = Color(0xFFFF7A29)
val LavaRed = Color(0xFFE0341C)
val LavaEmber = Color(0xFFFFB067)
val CoinGold = Color(0xFFF5C518)
val SpringGreen = Color(0xFF35C46A)
val SkyBlue = Color(0xFF3E8EF7)
val CreatureViolet = Color(0xFF9B30D9)
val PaperCream = Color(0xFFF7F1E1)
val PaperShade = Color(0xFFE7DEC7)
val InkBlack = Color(0xFF15130F)
val InkSoft = Color(0xFF2B2721)
val Charcoal = Color(0xFF1E1B16)

private val DarkScheme = darkColorScheme(
    primary = LavaOrange,
    onPrimary = InkBlack,
    primaryContainer = Color(0xFF5A2408),
    onPrimaryContainer = LavaEmber,
    secondary = CoinGold,
    onSecondary = InkBlack,
    tertiary = SkyBlue,
    onTertiary = InkBlack,
    background = Color(0xFF12100D),
    onBackground = Color(0xFFF2EADA),
    surface = Charcoal,
    onSurface = Color(0xFFF2EADA),
    surfaceVariant = Color(0xFF2A251E),
    onSurfaceVariant = Color(0xFFCBBFA9),
    error = LavaRed,
    outline = Color(0xFF6B6255),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB4460D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF3B1400),
    secondary = Color(0xFF7A5A00),
    onSecondary = Color.White,
    tertiary = Color(0xFF1B5AB8),
    onTertiary = Color.White,
    background = PaperCream,
    onBackground = InkBlack,
    surface = Color(0xFFFFFBF2),
    onSurface = InkBlack,
    surfaceVariant = PaperShade,
    onSurfaceVariant = InkSoft,
    error = LavaRed,
    outline = Color(0xFF8B8071),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
)

/**
 * Rounder than Material's defaults, everywhere.
 *
 * Set on the theme rather than per component so the pieces the app does not draw itself —
 * dialogs, sliders, text fields — round off to match the tiles and buttons that it does.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun PaperJumpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
