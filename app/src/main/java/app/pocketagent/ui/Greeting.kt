package app.pocketagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.pocketagent.ai.WeatherNow
import app.pocketagent.ui.theme.LocalDark
import app.pocketagent.ui.theme.LocalMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Morning, afternoon, evening or night, from the hour on the clock. */
fun dayPhase(hour: Int): String = when (hour) {
    in 5..11 -> "morning"
    in 12..16 -> "afternoon"
    in 17..20 -> "evening"
    else -> "night"
}

/**
 * The two lines that welcome the person on an empty chat. They are put together from the time of day, the
 * weather where they are (when a city is set) and their name, and picked afresh each time, so the screen
 * does not say the same thing twice in a row.
 */
fun greeting(name: String, hour: Int, weather: WeatherNow?, work: Boolean, seed: Int): Pair<String, String> {
    val r = Random(seed)
    val who = name.trim().substringBefore(' ')
    fun line(with: List<String>, without: List<String>) = if (who.isEmpty()) without.random(r) else with.random(r).replace("{n}", who)
    val title = when (dayPhase(hour)) {
        "morning" -> line(listOf("Good morning, {n}", "Morning, {n}", "Rise and shine, {n}", "A fresh start, {n}"), listOf("Good morning", "A fresh start"))
        "afternoon" -> line(listOf("Good afternoon, {n}", "Hello, {n}", "Hope the day is going well, {n}", "Back at it, {n}"), listOf("Good afternoon", "Hello there"))
        "evening" -> line(listOf("Good evening, {n}", "Evening, {n}", "Winding down, {n}?", "Nice to see you, {n}"), listOf("Good evening", "Nice to see you"))
        else -> line(listOf("Still up, {n}?", "Burning the midnight oil, {n}?", "Late one, {n}", "Quiet hours, {n}"), listOf("Still up?", "Quiet hours"))
    }
    val ask = if (work) listOf("What should I work on?", "Hand me a task and I'll get going.", "What needs doing?", "Give me a goal and I'll take it from there.")
    else listOf("What's on your mind?", "Where shall we start?", "Ask me anything.", "What are you curious about?")
    val sky = weather?.let { w ->
        val t = "${w.tempC}°"
        when (w.kind) {
            "clear" -> if (w.day) listOf("Clear skies and $t outside.", "Bright out there, $t.", "Sunny and $t.") else listOf("A clear night, $t.", "Stars out, $t.")
            "cloudy" -> listOf("Cloudy and $t outside.", "Grey skies, $t.", "Overcast, $t.")
            "fog" -> listOf("Foggy out, $t.", "A misty one, $t.")
            "rain" -> listOf("Rain outside, $t.", "A wet one out there, $t.", "It's raining, $t.")
            "snow" -> listOf("Snow outside, $t.", "A white one out there, $t.")
            "storm" -> listOf("Stormy out, $t. Good to be indoors.", "Thunder about, $t.")
            else -> listOf("$t outside.")
        }.random(r)
    }
    return title to listOfNotNull(sky, ask.random(r)).joinToString(" ")
}

/** Three colours for the moving background, chosen by the weather and the time of day. */
fun backdropColors(hour: Int, weather: WeatherNow?): List<Color> {
    val phase = dayPhase(hour)
    val night = phase == "night" || weather?.day == false
    return when {
        weather?.kind == "storm" -> listOf(Color(0xFF6A5ACD), Color(0xFF3D5A9E), Color(0xFFB05FB5))
        weather?.kind == "rain" -> listOf(Color(0xFF6FA3D8), Color(0xFF5FC2C0), Color(0xFF8E97E8))
        weather?.kind == "snow" -> listOf(Color(0xFFBFDFFF), Color(0xFFDCD4FF), Color(0xFFAEE8EC))
        weather?.kind == "fog" -> listOf(Color(0xFFBFC6D2), Color(0xFFCFC4E0), Color(0xFFB4CDD3))
        night -> listOf(Color(0xFF5B63D6), Color(0xFF7A3FB8), Color(0xFF1E6FA8))
        weather?.kind == "cloudy" -> listOf(Color(0xFFA9B6D2), Color(0xFFC3B4E3), Color(0xFF9CC3DA))
        phase == "morning" -> listOf(Color(0xFFFFB98A), Color(0xFF9CCBFF), Color(0xFFFFE08A))
        phase == "evening" -> listOf(Color(0xFFFF9E80), Color(0xFFC98BFF), Color(0xFFFFC978))
        else -> listOf(Color(0xFF7DBBFF), Color(0xFF8FEBDB), Color(0xFFC8B4FF))
    }
}

/** The pause between frames of slow, ambient motion (the drifting colours, the turning mark). */
const val AMBIENT_FRAME_MS = 24L

/**
 * Soft patches of colour behind the chat. [speed] is how many times a minute they complete their slow circuit
 * (0 holds them still where they are); [strength] scales how much colour shows through. Light on the
 * processor: three gradients, redrawn only while they move.
 */
@Composable
fun Backdrop(colors: List<Color>, modifier: Modifier = Modifier, speed: Float = 2.7f, strength: Float = 1f) {
    val base = (if (LocalDark.current) 0.30f else 0.42f) * strength
    val motion = LocalMotion.current
    var t by remember { mutableFloatStateOf(0.2f) }
    // The slow drift is redrawn about thirty times a second, which the eye cannot tell from sixty at this pace and costs
    // half the work. While the window is behind another one the idle drift holds still; a reply being written still flows.
    val inFront = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(speed, motion, inFront) {
        if (speed <= 0f || !motion || (!inFront && speed < 5f)) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            kotlinx.coroutines.delay(AMBIENT_FRAME_MS)
            val now = withFrameNanos { it }
            t = (t + (now - last) / 1e9f * speed / 60f) % 1f
            last = now
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        colors.take(3).forEachIndexed { i, c ->
            val a = 2 * PI * (t + i / 3.0)
            val center = Offset((w * (0.5 + 0.36 * cos(a))).toFloat(), (h * (0.34 + 0.24 * sin(a + i))).toFloat())
            drawRect(Brush.radialGradient(listOf(c.copy(alpha = base), c.copy(alpha = 0f)), center, maxOf(w, h) * 0.62f))
        }
    }
}

/** The same colours behind the side menu and other screens: still while nothing is happening, flowing while work is running. */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier, strength: Float = 0.55f) {
    val g = app.pocketagent.LocalGraph.current
    val weather by g.weather.collectAsState()
    val runs by g.allRuns.collectAsState()
    val hour = remember { java.time.LocalTime.now().hour }
    Backdrop(backdropColors(hour, weather), modifier, speed = if (runs.isNotEmpty()) 9f else 0f, strength = strength)
}
