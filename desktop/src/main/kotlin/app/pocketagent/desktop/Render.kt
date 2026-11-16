package app.pocketagent.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.pocketagent.ui.AppRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws the app off-screen and saves pictures of it: a way to check the interface on a machine with no
 * display (and in automated builds). Usage: Render <data dir> <output dir> steps...
 * Steps: "name" saves a picture, "x,y" clicks there (picture pixels), "wait:ms" waits, "type:text" types
 * (use _ for a space), "dark"/"light" switches the theme, "key=<service>:<value>" sets up a key first.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
fun main(args: Array<String>) {
    runBlocking(Dispatchers.Default) {
        val data = File(args.getOrElse(0) { "build/render-data" }).apply { mkdirs() }
        val out = File(args.getOrElse(1) { "build/render" }).apply { mkdirs() }
        val platform = DesktopPlatform(data)
        val graph = desktopGraph(platform)
        graph.start()
        val host = DesktopHost(graph)
        val (width, height, scale) = System.getProperty("render.size", "1600x1000x1").split('x').let { Triple(it[0].toInt(), it[1].toInt(), it.getOrElse(2) { "1" }.toFloat()) }
        val scene = ImageComposeScene(width, height, Density(scale)) { Box(Modifier.fillMaxSize()) { AppRoot(host) } }
        var clock = 0L
        suspend fun settle(ms: Long) { val end = clock + ms; while (clock < end) { scene.render(clock * 1_000_000); clock += 32; delay(8) } }
        fun shot(name: String) {
            val bytes = scene.render(clock * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(bytes); println("saved $name.png")
        }
        suspend fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)); scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            settle(60); scene.sendPointerEvent(PointerEventType.Release, Offset(x, y)); settle(900)
        }
        for (s in args.drop(2).ifEmpty { listOf("wait:2500", "first") }) when {
            s.startsWith("wait:") -> settle(s.removePrefix("wait:").toLong())
            s.startsWith("key=") -> {
                val (service, value) = s.removePrefix("key=").split(':', limit = 2)
                graph.vault.write(app.pocketagent.ai.Providers.vaultKey(service), value)
                graph.settingsStore.update { it.copy(onboarded = true, provider = service, name = "Sam") }
                settle(600)
            }
            s == "dark" || s == "light" -> { graph.settingsStore.update { it.copy(theme = s) }; settle(700) }
            s.startsWith("type:") -> {
                for (ch in s.removePrefix("type:").replace('_', ' ')) {
                    scene.sendKeyEvent(KeyEvent(key = Key.Unknown, type = androidx.compose.ui.input.key.KeyEventType.KeyDown, codePoint = ch.code))
                    scene.sendKeyEvent(KeyEvent(key = Key.Unknown, type = androidx.compose.ui.input.key.KeyEventType.KeyUp, codePoint = ch.code))
                }
                settle(300)
            }
            s.contains(',') -> s.split(',').let { click(it[0].toFloat(), it[1].toFloat()) }
            else -> shot(s)
        }
        scene.close()
    }
    kotlin.system.exitProcess(0)
}
