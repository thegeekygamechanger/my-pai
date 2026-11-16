package app.pocketagent.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.LocalGraph

private val Light = lightColorScheme(
    primary = Color(0xFF4A4FC4), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF), onPrimaryContainer = Color(0xFF0C0A66),
    secondary = Color(0xFF5C5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF8A4A65), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E6), onTertiaryContainer = Color(0xFF380720),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC8C5D0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFF0ECF4), surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC0C1FF), onPrimary = Color(0xFF1A1C94),
    primaryContainer = Color(0xFF3236AB), onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC5C4DD), onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559), onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFFFB0CD), onTertiary = Color(0xFF531D36),
    tertiaryContainer = Color(0xFF6E334D), onTertiaryContainer = Color(0xFFFFD8E6),
    background = Color(0xFF121218), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121218), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0D0D13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34343A),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** True black backgrounds for OLED screens; the raised surfaces stay slightly lifted so cards remain visible. */
private fun ColorScheme.pureBlack() = copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0C0C10),
    surfaceContainer = Color(0xFF121216), surfaceContainerHigh = Color(0xFF1A1A20),
    surfaceContainerHighest = Color(0xFF24242A),
)

/** Colours for highlighted code, picked to read well on the code block's background in each theme. */
data class CodeColors(val keyword: Color, val string: Color, val comment: Color, val number: Color)

val LocalCodeColors = staticCompositionLocalOf { CodeColors(Color(0xFF4A4FC4), Color(0xFF1B6E4B), Color(0xFF777680), Color(0xFF8A4A65)) }
val LocalDark = staticCompositionLocalOf { false }
/** False when the user turned animations off in Android's accessibility or developer settings. */
val LocalMotion = staticCompositionLocalOf { true }

@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun a(c: Color): Color {
        val v by animateColorAsState(c, tween(400), label = "theme")
        return v
    }
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer), onPrimaryContainer = a(onPrimaryContainer),
        secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        background = a(background), onBackground = a(onBackground), surface = a(surface), onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant),
        surfaceContainerLowest = a(surfaceContainerLowest), surfaceContainerLow = a(surfaceContainerLow),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerHighest = a(surfaceContainerHighest), outlineVariant = a(outlineVariant),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Base = Typography()
private fun heading(style: androidx.compose.ui.text.TextStyle) = style.copy(fontFamily = Heros, fontWeight = FontWeight.Bold)
private fun text(style: androidx.compose.ui.text.TextStyle) = style.copy(fontFamily = Inter)

/** Headings in the Helvetica-style face, everything else in Inter. */
private val AppTypography = Typography(
    displayLarge = heading(Base.displayLarge), displayMedium = heading(Base.displayMedium),
    displaySmall = heading(Base.displaySmall).copy(letterSpacing = (-0.5).sp),
    headlineLarge = heading(Base.headlineLarge), headlineMedium = heading(Base.headlineMedium), headlineSmall = heading(Base.headlineSmall),
    titleLarge = heading(Base.titleLarge),
    titleMedium = text(Base.titleMedium).copy(fontWeight = FontWeight.SemiBold), titleSmall = text(Base.titleSmall).copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = text(Base.bodyLarge).copy(lineHeight = 25.sp), bodyMedium = text(Base.bodyMedium).copy(lineHeight = 21.sp), bodySmall = text(Base.bodySmall),
    labelLarge = text(Base.labelLarge), labelMedium = text(Base.labelMedium), labelSmall = text(Base.labelSmall),
)

@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PocketTheme(mode: String, dynamic: Boolean, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val platform = LocalGraph.current.platform
    var scheme = (if (dynamic) platform.dynamicColors(dark) else null) ?: if (dark) Dark else Light
    if (dark && pureBlack) scheme = scheme.pureBlack()
    val code = if (dark) CodeColors(Color(0xFFC0C1FF), Color(0xFF8FD8B0), Color(0xFF918F9A), Color(0xFFFFB0CD))
    else CodeColors(Color(0xFF4A4FC4), Color(0xFF1B6E4B), Color(0xFF777680), Color(0xFF8A4A65))
    val motion = remember { platform.animationsEnabled() }
    CompositionLocalProvider(LocalCodeColors provides code, LocalDark provides dark, LocalMotion provides motion) {
        MaterialExpressiveTheme(
            colorScheme = scheme.animated(),
            motionScheme = MotionScheme.expressive(),
            shapes = AppShapes,
            typography = AppTypography,
        ) {
            // Text and icons that do not name a colour take this one. Without it they fall back to black,
            // which disappears on a dark background.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}
