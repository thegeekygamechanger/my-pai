package app.pocketagent.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException

/** Runs [onBack] when the user goes back (the system back gesture or button) while [enabled]. */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** Like [OnBack], and [drag] follows the finger (0 to 1) while the back gesture is in progress. */
@Composable
fun OnBackDrag(enabled: Boolean, drag: Animatable<Float, AnimationVector1D>, onBack: () -> Unit) {
    PredictiveBackHandler(enabled) { progress ->
        try {
            progress.collect { drag.snapTo(it.progress) }
            onBack()
            drag.snapTo(0f)
        } catch (e: CancellationException) {
            drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
        }
    }
}
