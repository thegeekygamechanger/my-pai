package app.pocketagent.desktop

import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Notices on a computer: the system's own notification (through `notify-send` on Linux, the tray on Windows)
 * and, for scheduled tasks, a short two-note chime the app makes itself, so it sounds the same everywhere.
 */
object DesktopNotify {
    private var tray: TrayIcon? = null

    fun show(dataDir: File, title: String, text: String, sound: Boolean) {
        thread(isDaemon = true, name = "notify") {
            val shown = runCatching { if (System.getProperty("os.name").orEmpty().contains("linux", true)) linux(dataDir, title, text) else false }.getOrDefault(false)
            if (!shown) runCatching { viaTray(title, text) }
            if (sound) runCatching { chime() }
        }
    }

    private fun icon(dataDir: File): File? = runCatching {
        val f = File(dataDir, "icon.png")
        val bytes = DesktopNotify::class.java.getResourceAsStream("/icon.png")?.use { it.readBytes() } ?: return null
        if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        f
    }.getOrNull()

    private fun linux(dataDir: File, title: String, text: String): Boolean {
        // The body is read as simple markup, so the three characters that mean something there are spelled out.
        val body = text.take(400).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val args = buildList {
            add("notify-send"); add("-a"); add("My PAI")
            icon(dataDir)?.let { add("-i"); add(it.path) }
            add("--"); add(title.take(120)); add(body)
        }
        // No shell is involved: every word reaches the program exactly as written.
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor() == 0
    }

    private fun viaTray(title: String, text: String) {
        if (!SystemTray.isSupported()) return
        val icon = tray ?: run {
            val image = DesktopNotify::class.java.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) } ?: return
            TrayIcon(image, "My PAI").apply { isImageAutoSize = true }.also { SystemTray.getSystemTray().add(it); tray = it }
        }
        icon.displayMessage(title.take(120), text.take(400), TrayIcon.MessageType.NONE)
    }

    /** Two soft rising notes, about a third of a second. */
    fun chime() {
        val rate = 44_100f
        val notes = listOf(659.25 to 0.14, 880.0 to 0.24)
        val out = java.io.ByteArrayOutputStream()
        for ((freq, seconds) in notes) {
            val n = (rate * seconds).toInt()
            for (i in 0 until n) {
                // A quick rise and a slow fall keep it from clicking.
                val env = minOf(1.0, i / (rate * 0.01)) * (1.0 - i.toDouble() / n).let { it * it }
                val v = (sin(2 * PI * freq * i / rate) * env * 0.32 * Short.MAX_VALUE).toInt()
                out.write(v and 0xff); out.write((v shr 8) and 0xff)
            }
        }
        val pcm = out.toByteArray()
        val format = AudioFormat(rate, 16, 1, true, false)
        AudioSystem.getSourceDataLine(format).apply { open(format); start(); write(pcm, 0, pcm.size); drain(); stop(); close() }
    }
}

/** Shows one notice and plays the chime, to check them from a terminal: NotifyCheck <data dir>. */
object NotifyCheck {
    @JvmStatic fun main(args: Array<String>) {
        DesktopNotify.show(File(args.getOrElse(0) { "build/notify-data" }).apply { mkdirs() }, "Morning news brief", "Your scheduled task has finished. This is a test of the notice and its sound.", sound = true)
        Thread.sleep(2500)
        println("NOTIFY SENT")
    }
}
