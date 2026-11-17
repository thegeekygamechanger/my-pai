package app.pocketagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.update.UpdateState
import kotlinx.coroutines.launch

fun updateSummary(state: UpdateState): String = when (state) {
    is UpdateState.Available -> "Version ${state.info.version} is ready"
    is UpdateState.Downloading -> "Downloading ${state.percent}%"
    is UpdateState.Confirming -> "Installing ${state.info.version}"
    is UpdateState.NeedsPermission -> "Needs your permission"
    UpdateState.UpToDate -> "Up to date"
    else -> ""
}

/** The App update part of Settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state by g.updates.state.collectAsState()
    val settings by g.settings.collectAsState()
    val st = state
    Column {
        Text(
            when (st) {
                UpdateState.Idle -> "You have version ${g.platform.versionName}."
                UpdateState.Checking -> "Looking on GitHub…"
                UpdateState.UpToDate -> "Version ${g.platform.versionName} is the newest."
                is UpdateState.Available -> "Version ${st.info.version} is available." + st.info.notes.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { "\n$it" }.orEmpty()
                is UpdateState.Downloading -> "Downloading version ${st.info.version} from GitHub: ${st.percent}%"
                is UpdateState.NeedsPermission -> "Android needs your OK once before the app can update itself. Allow it on the next screen, then come back."
                is UpdateState.Confirming -> if (g.platform.isDesktop) "The update is starting. This app will close." else "Confirm the update in the window Android shows."
                is UpdateState.Failed -> st.message
            },
            Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium, color = if (st is UpdateState.Failed) cs.error else cs.onSurface,
        )
        if (st is UpdateState.Downloading) LinearWavyProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 10.dp))
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (st) {
                is UpdateState.Available -> FilledTonalButton({ g.updates.install(st.info) }) { Text("Update now") }
                is UpdateState.NeedsPermission -> FilledTonalButton({ g.updates.openPermission() }) { Text("Allow") }
                is UpdateState.Failed -> st.info?.let { info -> FilledTonalButton({ g.updates.install(info) }) { Text("Try again") } }
                else -> {}
            }
            if (st !is UpdateState.Downloading && st !is UpdateState.Checking) OutlinedButton({ scope.launch { g.updates.check(force = true) } }) { Text("Check now") }
        }
        SwitchRow("Check every day", "Updates come from the app's GitHub releases.", settings?.autoUpdate != false) { v -> scope.launch { g.settingsStore.update { it.copy(autoUpdate = v) } } }
    }
}

/** A slim line on the chat list when a newer version is waiting. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val state by g.updates.state.collectAsState()
    val st = state
    AnimatedVisibility(st is UpdateState.Available || st is UpdateState.Downloading, modifier, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 350f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            onClick = { (st as? UpdateState.Available)?.let { g.updates.install(it.info) } },
            shape = MaterialTheme.shapes.large, color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(20.dp))
                Text(
                    when (st) {
                        is UpdateState.Available -> "Version ${st.info.version} is ready. Tap to update."
                        is UpdateState.Downloading -> "Downloading the update: ${st.percent}%"
                        else -> ""
                    },
                    Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
