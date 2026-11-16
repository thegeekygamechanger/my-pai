package app.pocketagent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** On a computer "back" is the Esc key (and the back arrow pushed screens already show). The newest handler on screen answers it. */
object BackStack {
    private val handlers = ArrayDeque<() -> Unit>()
    fun push(h: () -> Unit) { handlers.addLast(h) }
    fun remove(h: () -> Unit) { handlers.remove(h) }

    /** Runs the newest handler; false when nothing wants the key. */
    fun back(): Boolean = handlers.lastOrNull()?.let { it(); true } ?: false
}

/** Runs [onBack] when the user goes back while [enabled]. */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val handler = { current() }
        if (enabled) BackStack.push(handler)
        onDispose { BackStack.remove(handler) }
    }
}

/** A computer has no back gesture to follow with the finger, so this is plain [OnBack]. */
@Composable
fun OnBackDrag(enabled: Boolean, drag: Animatable<Float, AnimationVector1D>, onBack: () -> Unit) = OnBack(enabled, onBack)
