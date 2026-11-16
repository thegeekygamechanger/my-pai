package app.pocketagent.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.pocketagent.Graph
import app.pocketagent.Host
import app.pocketagent.data.SettingsStore
import app.pocketagent.ui.AppRoot
import app.pocketagent.ui.BackStack

/** Builds the app's long-lived objects for a computer. */
fun desktopGraph(platform: DesktopPlatform): Graph {
    val dir = platform.filesDir
    return Graph(platform, DesktopVault(dir), SettingsStore(desktopSettings(dir)), openDesktopDb(dir))
}

class DesktopHost(override val graph: Graph) : Host {
    override val locked: MutableState<Boolean> = mutableStateOf(false)
    override val openChat: MutableState<Long?> = mutableStateOf(null)
    override val sharedText: MutableState<String?> = mutableStateOf(null)
}

fun main() {
    val platform = DesktopPlatform(appDataDir())
    val graph = desktopGraph(platform)
    graph.start()
    val host = DesktopHost(graph)
    application {
        // Opens filling the screen in the landscape layout; made narrow, it turns into the phone's single column.
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1280.dp, 800.dp))
        Window(
            onCloseRequest = { graph.runs.stopAll(); exitApplication() },
            state = state,
            title = "Pocket Agent",
            onPreviewKeyEvent = { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && BackStack.back() },
        ) {
            LaunchedEffect(Unit) {
                platform.frame = window
                runCatching { object {}.javaClass.getResourceAsStream("/icon.png")?.use { window.iconImage = javax.imageio.ImageIO.read(it) } }
            }
            window.minimumSize = java.awt.Dimension(380, 640)
            AppRoot(host)
        }
    }
}
