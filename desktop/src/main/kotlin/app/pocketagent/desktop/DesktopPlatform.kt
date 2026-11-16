package app.pocketagent.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.pocketagent.Graph
import app.pocketagent.Platform
import app.pocketagent.Speech
import app.pocketagent.ai.Transcribe
import app.pocketagent.Vault
import app.pocketagent.data.AppDb
import app.pocketagent.data.AttachmentError
import app.pocketagent.data.Attachments
import app.pocketagent.data.Chat
import app.pocketagent.data.Picked
import app.pocketagent.update.DesktopUpdates
import app.pocketagent.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** The computer's side of [Platform] (Linux and Windows). */
class DesktopPlatform(override val filesDir: File) : Platform {
    /** The app's window, once it exists: the parent for file choosers. */
    var frame: Frame? = null
    private val attachments by lazy { Attachments(File(filesDir, "att")) }
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    override val isDesktop = true
    override val versionName: String = BuildInfo.VERSION
    override val debug: Boolean = System.getProperty("pocketagent.debug") == "1"
    override val deviceName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" } ?: if (windows) "Windows PC" else "Linux PC"
    override val system = if (windows) "windows" else "linux"

    override val hasDynamicColors = false
    override fun dynamicColors(dark: Boolean): ColorScheme? = null
    override fun animationsEnabled() = true

    override fun copyToClipboard(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
    // A computer has no share sheet; the text goes on the clipboard, ready to paste anywhere.
    override fun shareText(text: String) = copyToClipboard(text)
    override fun shareFile(file: File) {
        runCatching { Desktop.getDesktop().open(file.parentFile) }.onFailure { runCatching { ProcessBuilder("xdg-open", file.parentFile.path).start() } }
    }

    // A computer does not suspend an app that is merely in the background, so nothing has to keep a run alive.
    override fun inForeground() = true
    override fun runsChanged(active: Boolean) {}
    override fun notifyFinished(chat: Chat, preview: String, failed: Boolean) {}
    override fun notifyApproval(chat: Chat, title: String) {}
    override fun clearApprovalNotice(chatId: Long) {}
    override fun clearFinishedNotice(chatId: Long) {}

    override fun importAttachment(source: Any): Picked {
        val f = source as? File ?: throw AttachmentError("Could not attach that file.")
        val isImage = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        return attachments.import(
            name = f.name, isImage = isImage, size = f.length(), read = { f.readBytes() },
            toJpeg = {
                val src = ImageIO.read(f) ?: return@import null
                val longest = maxOf(src.width, src.height)
                val k = if (longest > Attachments.MAX_EDGE) Attachments.MAX_EDGE.toDouble() / longest else 1.0
                val w = (src.width * k).toInt().coerceAtLeast(1)
                val h = (src.height * k).toInt().coerceAtLeast(1)
                // JPEG has no transparency: draw on white.
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                out.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    color = java.awt.Color.WHITE; fillRect(0, 0, w, h)
                    drawImage(src, 0, 0, w, h, null); dispose()
                }
                ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
            },
        )
    }

    override fun thumbnail(path: String, maxPx: Int): ImageBitmap? = runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(attachments.file(path).readBytes()).toComposeImageBitmap()
    }.getOrNull()

    @Composable
    override fun rememberFilePicker(onPicked: (List<Any>) -> Unit): () -> Unit {
        val picked by rememberUpdatedState(onPicked)
        val scope = rememberCoroutineScope()
        return {
            scope.launch {
                // The chooser blocks until it is closed, so it runs off the interface's thread.
                val files = withContext(Dispatchers.IO) {
                    FileDialog(frame, "Attach files (pictures, PDFs, text)", FileDialog.LOAD).run {
                        isMultipleMode = true
                        isVisible = true
                        files?.toList().orEmpty()
                    }
                }
                if (files.isNotEmpty()) picked(files)
            }
        }
    }

    @Composable override fun rememberNotificationAsk(): () -> Unit = {}
    // A computer shows the QR code; the phone is the one with a camera to read it.
    @Composable override fun rememberQrScanner(onResult: (String?) -> Unit): (() -> Unit)? = null

    /**
     * Voice typing on a computer: records from the microphone until the button is pressed again, then has a
     * connected service's transcription model turn it into text. That model recognises the language itself.
     */
    @Composable
    override fun rememberSpeech(g: Graph, onText: (String) -> Unit, onError: (String) -> Unit): Speech? {
        val text by rememberUpdatedState(onText)
        val error by rememberUpdatedState(onError)
        val scope = rememberCoroutineScope()
        val listening = remember { mutableStateOf(false) }
        val busy = remember { mutableStateOf(false) }
        val line = remember { arrayOfNulls<TargetDataLine>(1) }
        return remember {
            Speech(listening, busy) {
                if (listening.value) {
                    // Stopping the line ends the recording loop below, which then sends what it has.
                    listening.value = false
                    line[0]?.let { it.stop(); it.close() }
                } else if (!Transcribe.available(g)) {
                    error("Voice typing on a computer needs a Groq or OpenAI key. Add one in Settings, AI services.")
                } else scope.launch {
                    val format = AudioFormat(16000f, 16, 1, true, false)
                    val mic = try {
                        withContext(Dispatchers.IO) { (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).also { it.open(format); it.start() } }
                    } catch (e: Exception) { error("No microphone was found."); return@launch }
                    line[0] = mic
                    listening.value = true
                    val pcm = withContext(Dispatchers.IO) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(3200)
                        // At most two minutes; the loop also ends when the line is closed by a second press.
                        while (listening.value && out.size() < 16000 * 2 * 120) {
                            val n = runCatching { mic.read(buf, 0, buf.size) }.getOrDefault(-1)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        runCatching { mic.stop(); mic.close() }
                        out.toByteArray()
                    }
                    listening.value = false
                    if (pcm.size < 16000) return@launch
                    busy.value = true
                    try {
                        val said = Transcribe.transcribe(g, Transcribe.wav(pcm, 16000), g.settings.value?.speechLang.orEmpty())
                        if (said.isNotBlank()) text(said)
                    } catch (e: Exception) { error(e.message ?: "Could not turn that into text.") }
                    busy.value = false
                }
            }
        }
    }

    override val canLock = false
    override fun authenticate(title: String, onOk: () -> Unit) {}
    override fun setSecureScreen(on: Boolean) {}
    override fun systemBars(dark: Boolean) {}
    // Scheduled tasks run while the app is open; there is no system service to wake a closed app.
    override fun scheduleWake(atMillis: Long?) {}
    override fun makeUpdates(g: Graph): Updates = DesktopUpdates(g)
}

/**
 * A computer has no Keystore to lock a key into, so the encryption key is a random 256-bit value in a file
 * that only the user's own account can read (owner-only permissions where the system has them). Anyone who
 * can read that account's files can read the secrets; the encryption protects them from other accounts and
 * from casual copying, not from the account's owner or an administrator.
 */
class DesktopVault(private val dir: File) : Vault {
    private val keyFile = File(dir, "key.bin")
    private val file = File(dir, "vault.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    private val random = SecureRandom()

    private fun ownerOnly(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            .onFailure { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
    }

    private fun key(): SecretKeySpec {
        if (!keyFile.exists() || keyFile.length() != 32L) {
            dir.mkdirs()
            keyFile.writeBytes(ByteArray(32).also(random::nextBytes))
            ownerOnly(keyFile)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun save() { dir.mkdirs(); file.outputStream().use { props.store(it, null) }; ownerOnly(file) }

    @Synchronized
    override fun read(name: String): String? = props.getProperty(name)?.let { stored ->
        runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    @Synchronized
    override fun write(name: String, value: String?) {
        if (value == null) props.remove(name) else {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            props.setProperty(name, Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray())))
        }
        save()
    }

    @Synchronized override fun clearAll() { props.clear(); save(); keyFile.delete() }
}

/** The app's database on a computer: one SQLite file in the app's folder. */
fun openDesktopDb(dir: File): AppDb =
    Room.databaseBuilder<AppDb>(name = File(dir, "pocketagent.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

fun desktopSettings(dir: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath { File(dir, "settings.preferences_pb").toOkioPath() }

/** Where the app keeps its files: the usual per-user place on each system. */
fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
        os.contains("mac") -> File(home, "Library/Application Support")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
    }
    return File(base, "PocketAgent").apply { mkdirs() }
}
