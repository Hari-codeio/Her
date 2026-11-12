package app.her.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Plum = Color(0xFF5B2A5F)
val PlumDeep = Color(0xFF3A1C3F)
val Rose = Color(0xFFE8A0B4)
val Blush = Color(0xFFFBE4EA)
val Cream = Color(0xFFFFFBF8)
val Gold = Color(0xFFC9A96E)
val Ink = Color(0xFF2D1B2E)

private val Light = lightColorScheme(
    primary = Plum,
    onPrimary = Color.White,
    primaryContainer = Blush,
    onPrimaryContainer = PlumDeep,
    secondary = Color(0xFFB0557F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7D9E3),
    onSecondaryContainer = PlumDeep,
    tertiary = Gold,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E7EA),
    onSurfaceVariant = Color(0xFF5E4B5C),
    surfaceContainer = Color(0xFFF8EEF0),
    surfaceContainerHigh = Color(0xFFF4E6EA),
    outline = Color(0xFFCDB8C2),
)

private val Dark = darkColorScheme(
    primary = Rose,
    onPrimary = PlumDeep,
    primaryContainer = Color(0xFF5B2A5F),
    onPrimaryContainer = Color(0xFFFBE4EA),
    secondary = Color(0xFFF2A6C4),
    onSecondary = PlumDeep,
    secondaryContainer = Color(0xFF6A3555),
    onSecondaryContainer = Color(0xFFFBE4EA),
    tertiary = Gold,
    background = Color(0xFF1B1220),
    onBackground = Color(0xFFF4E8EE),
    surface = Color(0xFF1B1220),
    onSurface = Color(0xFFF4E8EE),
    surfaceVariant = Color(0xFF3A2C3D),
    onSurfaceVariant = Color(0xFFD6C1CC),
    surfaceContainer = Color(0xFF261A2B),
    surfaceContainerHigh = Color(0xFF2F2134),
    outline = Color(0xFF7B6680),
)

private val Display = FontFamily.Serif

private val HerTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
        headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
        headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun HerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = HerTypography, content = content)
}
