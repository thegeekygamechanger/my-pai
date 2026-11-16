package app.pocketagent.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * The same typefaces as the Android app, loaded from the app's own resources: TeX Gyre Heros (the freely
 * licensed typeface drawn to Helvetica's shapes and metrics) for headings, Inter for everything else.
 */
val Heros = FontFamily(
    Font(resource = "font/heros_regular.otf", weight = FontWeight.Normal),
    Font(resource = "font/heros_bold.otf", weight = FontWeight.Bold),
)

val Inter = FontFamily(
    Font(resource = "font/inter_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "font/inter_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "font/inter_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "font/inter_bold.ttf", weight = FontWeight.Bold),
)
