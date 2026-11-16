package app.pocketagent

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import app.pocketagent.data.Chat
import app.pocketagent.data.Picked
import app.pocketagent.update.Updates
import java.io.File

/**
 * Everything that differs between a phone and a computer. The screens and the app's logic are the same
 * source files on Android, Linux and Windows; only this interface has one implementation per system.
 */
interface Platform {
    val isDesktop: Boolean
    val versionName: String
    val debug: Boolean
    /** Where the app keeps its files. */
    val filesDir: File
    /** A name a person would recognise this device by, for the list of linked devices. */
    val deviceName: String
    /** "android", "linux" or "windows". */
    val system: String

    fun dynamicColors(dark: Boolean): ColorScheme?
    val hasDynamicColors: Boolean
    /** False when the user turned animations off system-wide. */
    fun animationsEnabled(): Boolean

    fun copyToClipboard(text: String)
    /** Hands a file from the workspace to another app (Android) or shows it in the file manager (computer). */
    fun shareFile(file: File)
    /** Opens a file in whatever the device uses for that kind of file: a picture viewer, a video player. */
    fun openFile(file: File)
    fun inForeground(): Boolean

    /** Called when the first run starts and when the last one ends: Android keeps the process alive meanwhile. */
    fun runsChanged(active: Boolean)
    /** @param sound announce it with a sound even where a plain notice would be silent; used for scheduled tasks */
    fun notifyFinished(chat: Chat, preview: String, failed: Boolean, sound: Boolean = false)
    fun notifyApproval(chat: Chat, title: String)
    fun clearApprovalNotice(chatId: Long)
    fun clearFinishedNotice(chatId: Long)

    /** Turns what the file picker returned (a Uri on Android, a File on a computer) into an attachment. */
    fun importAttachment(source: Any): Picked
    fun thumbnail(path: String, maxPx: Int = 512): ImageBitmap?

    @Composable fun rememberFilePicker(onPicked: (List<Any>) -> Unit): () -> Unit
    @Composable fun rememberNotificationAsk(): () -> Unit
    /** Reads a QR code with the camera; null where there is no camera to use (a computer shows the code instead). */
    @Composable fun rememberQrScanner(onResult: (String?) -> Unit): (() -> Unit)?

    /** Voice typing for the message box, or null where this system cannot offer it. */
    @Composable fun rememberSpeech(g: Graph, onText: (String) -> Unit, onError: (String) -> Unit): Speech?

    val canLock: Boolean
    fun authenticate(title: String, onOk: () -> Unit)
    fun setSecureScreen(on: Boolean)
    fun systemBars(dark: Boolean)

    /** Asks the system to wake the app at this time for a scheduled task (null cancels). A computer runs tasks only while the app is open. */
    fun scheduleWake(atMillis: Long?)

    /** How this system finds and installs a newer release. */
    fun makeUpdates(g: Graph): Updates
}

/** Voice typing: [toggle] starts listening, or stops it; [busy] is true while what was said is being turned into text. */
class Speech(val listening: State<Boolean>, val busy: State<Boolean>, val toggle: () -> Unit)

/** Small encrypted store for secrets: the API keys and the sync code. */
interface Vault {
    fun read(name: String): String?
    fun write(name: String, value: String?)
    fun clearAll()
}

/** What the window or activity hands to the shared interface. */
interface Host {
    val graph: Graph
    val locked: MutableState<Boolean>
    /** A chat to open, from a tapped notification. */
    val openChat: MutableState<Long?>
    /** Text shared into the app from another app. */
    val sharedText: MutableState<String?>
}

val LocalGraph = staticCompositionLocalOf<Graph> { error("no Graph") }
/** True when the window is wide enough for the two-pane, landscape layout. */
val LocalWide = staticCompositionLocalOf { false }
