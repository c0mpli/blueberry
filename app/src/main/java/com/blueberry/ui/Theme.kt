package com.blueberry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One dark scheme. No wallpaper behind the launcher, so the background actually paints. */
private val Scheme = darkColorScheme(
    primary = Color(0xFF9B8BF0),
    onPrimary = Color(0xFF14101F),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFE8E6F0),
    surface = Color(0xFF15151F),
    onSurface = Color(0xFFE8E6F0),
    surfaceVariant = Color(0xFF1E1E2A),
    onSurfaceVariant = Color(0xFF9E9CB0),
    error = Color(0xFFE07A7A),
)

private val BlueberryTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, lineHeight = 34.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BlueberryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = BlueberryTypography, content = content)
}
