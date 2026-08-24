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
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF3F0504),
    secondary = Color(0xFF786019),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6A3),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF5D6C35),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1EDAD),
    onTertiaryContainer = Color(0xFF1A2500),
    background = Color(0xFFFFF8F1),
    onBackground = BloomWalkCocoa,
    surface = Color(0xFFFFFBF7),
    onSurface = BloomWalkCocoa,
    surfaceVariant = Color(0xFFF4E5DD),
    onSurfaceVariant = Color(0xFF57423C),
    outline = Color(0xFF8B716A),
    outlineVariant = Color(0xFFDBC1BA),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF690F0C),
    primaryContainer = Color(0xFF8F2924),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE8C65F),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF594500),
    onSecondaryContainer = Color(0xFFFFE6A3),
    tertiary = Color(0xFFC5D18C),
    onTertiary = Color(0xFF303E08),
    tertiaryContainer = Color(0xFF46551F),
    onTertiaryContainer = Color(0xFFE1EDAD),
    background = Color(0xFF201A18),
    onBackground = Color(0xFFEDE0DB),
    surface = Color(0xFF201A18),
    onSurface = Color(0xFFEDE0DB),
    surfaceVariant = Color(0xFF55433F),
    onSurfaceVariant = Color(0xFFD9C2BC),
    outline = Color(0xFFA88C85),
    outlineVariant = Color(0xFF55433F),
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
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
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
