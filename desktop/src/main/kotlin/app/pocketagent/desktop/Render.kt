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
    // Everything runs on the same interface thread the real app uses, so effects started while a frame is being
    // drawn wait their turn, and anything the app sends to the main thread lands on this one too.
    val ui = kotlinx.coroutines.Dispatchers.Main
    runBlocking(ui) {
        val data = File(args.getOrElse(0) { "build/render-data" }).apply { mkdirs() }
        val out = File(args.getOrElse(1) { "build/render" }).apply { mkdirs() }
        val platform = DesktopPlatform(data)
        val graph = desktopGraph(platform)
        graph.start()
        val host = DesktopHost(graph)
        val (width, height, scale) = System.getProperty("render.size", "1600x1000x1").split('x').let { Triple(it[0].toInt(), it[1].toInt(), it.getOrElse(2) { "1" }.toFloat()) }
        val scene = ImageComposeScene(width, height, Density(scale), coroutineContext = ui) { Box(Modifier.fillMaxSize()) { AppRoot(host) } }
        var clock = 0L
        val keySource = object : java.awt.Component() {}
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
                val (service, given) = s.removePrefix("key=").split(':', limit = 2)
                // "env:NAME" takes the key from an environment variable, so a real one never appears on a command line.
                val value = if (given.startsWith("env:")) System.getenv(given.removePrefix("env:")).orEmpty() else given
                graph.vault.write(app.pocketagent.ai.Providers.vaultKey(service), value)
                graph.settingsStore.update { it.copy(onboarded = true, provider = service, name = "Sam") }
                settle(600)
            }
            s == "dark" || s == "light" -> { graph.settingsStore.update { it.copy(theme = s) }; settle(700) }
            s.startsWith("type:") -> {
                // Typed characters reach a text field the way a keyboard's do on a computer: as "key typed" events.
                for (ch in s.removePrefix("type:").replace('_', ' ')) {
                    scene.sendKeyEvent(KeyEvent(key = Key.Unknown, type = androidx.compose.ui.input.key.KeyEventType.Unknown, codePoint = ch.code, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch)))
                    settle(40)
                }
                settle(300)
            }
            s.startsWith("hold:") -> {
                // Keeps the app running for a stretch of real time, for tests where another device talks to this one.
                val end = System.currentTimeMillis() + s.removePrefix("hold:").toLong()
                while (System.currentTimeMillis() < end) { scene.render(clock * 1_000_000); clock += 32; delay(30) }
            }
            s == "enter" -> {
                scene.sendKeyEvent(KeyEvent(key = Key.Enter, type = androidx.compose.ui.input.key.KeyEventType.KeyDown, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n')))
                scene.sendKeyEvent(KeyEvent(key = Key.Enter, type = androidx.compose.ui.input.key.KeyEventType.KeyUp, nativeEvent = java.awt.event.KeyEvent(keySource, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n')))
                settle(500)
            }
            s.startsWith("secret=") -> {
                // "secret=<vault name>:env:NAME" stores a connection's secret for the test, taken from the environment.
                val (name, given) = s.removePrefix("secret=").split(':', limit = 2)
                graph.vault.write(name, if (given.startsWith("env:")) System.getenv(given.removePrefix("env:")).orEmpty() else given)
                graph.updateShared { it }
                settle(400)
            }
            s.contains(',') -> s.split(',').let { click(it[0].toFloat(), it[1].toFloat()) }
            else -> shot(s)
        }
        scene.close()
    }
    kotlin.system.exitProcess(0)
}
