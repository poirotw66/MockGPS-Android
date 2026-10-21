package com.sora.mockgps.ui.theme

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

val BloomWalkCoral = Color(0xFFD95C50)
val BloomWalkGold = Color(0xFFD8A33E)
val BloomWalkSage = Color(0xFF718047)
val BloomWalkCocoa = Color(0xFF3F2921)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB9473F),
    onPrimary = Color(0xFFFFF8F6),
    primaryContainer = Color(0xFFF6D2CC),
    onPrimaryContainer = Color(0xFF3C1210),
    secondary = Color(0xFF6E5A2E),
    onSecondary = Color(0xFFFFF8F1),
    secondaryContainer = Color(0xFFF3E4C4),
    onSecondaryContainer = Color(0xFF261C08),
    tertiary = Color(0xFF55633A),
    onTertiary = Color(0xFFF7F8F2),
    tertiaryContainer = Color(0xFFDCE6C4),
    onTertiaryContainer = Color(0xFF1A220C),
    background = Color(0xFFF3F0EC),
    onBackground = Color(0xFF2C2422),
    surface = Color(0xFFFBF9F7),
    onSurface = Color(0xFF2C2422),
    surfaceVariant = Color(0xFFE6E1DB),
    onSurfaceVariant = Color(0xFF564E49),
    outline = Color(0xFF8A817A),
    outlineVariant = Color(0xFFD3CCC4),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF5C1411),
    primaryContainer = Color(0xFF8A332C),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE4C98A),
    onSecondary = Color(0xFF3D2E0A),
    secondaryContainer = Color(0xFF564418),
    onSecondaryContainer = Color(0xFFF6E7C4),
    tertiary = Color(0xFFC9D4A4),
    onTertiary = Color(0xFF26320E),
    tertiaryContainer = Color(0xFF3E4C22),
    onTertiaryContainer = Color(0xFFE4EDC8),
    background = Color(0xFF12100F),
    onBackground = Color(0xFFE8E2DC),
    surface = Color(0xFF1C1917),
    onSurface = Color(0xFFE8E2DC),
    surfaceVariant = Color(0xFF3A342F),
    onSurfaceVariant = Color(0xFFD0C8C0),
    outline = Color(0xFF9C938C),
    outlineVariant = Color(0xFF3A342F),
    error = Color(0xFFFFB4AB),
)

private val BloomWalkShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val BloomWalkTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
fun BloomWalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BloomWalkTypography,
        shapes = BloomWalkShapes,
        content = content,
    )
}
