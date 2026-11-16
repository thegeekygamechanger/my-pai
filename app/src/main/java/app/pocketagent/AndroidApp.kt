package app.pocketagent

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings.Global
import android.security.keystore.KeyGenParameterSpec
import android.speech.RecognizerIntent
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import app.pocketagent.ai.Notifier
import app.pocketagent.ai.RunService
import app.pocketagent.ai.ScheduleWorker
import app.pocketagent.data.AppDb
import app.pocketagent.data.Attachments
import app.pocketagent.data.AttachmentError
import app.pocketagent.data.Chat
import app.pocketagent.data.Picked
import app.pocketagent.data.SettingsStore
import app.pocketagent.update.AndroidUpdates
import app.pocketagent.update.Updates
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets on Android. Each value is encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where the phone has it), so what is stored is useless when copied off the device.
 */
class AndroidVault(ctx: Context) : Vault {
    private val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val cache = HashMap<String, String?>()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    override fun write(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove("$name.iv").remove("$name.data").apply()
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val sealed = cipher.doFinal(value.toByteArray())
            prefs.edit()
                .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("$name.data", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .apply()
        }
        cache[name] = value
    }

    @Synchronized
    override fun read(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val iv = prefs.getString("$name.iv", null)
        val data = prefs.getString("$name.data", null)
        val value = if (iv == null || data == null) null else try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        } catch (e: Exception) {
            // The keystore key is gone (for example after a restore to another phone): the stored value cannot be read.
            null
        }
        cache[name] = value
        return value
    }

    @Synchronized
    override fun clearAll() {
        prefs.edit().clear().apply()
        cache.clear()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object { const val ALIAS = "pocketagent_vault_v1" }
}

/** Android's side of [Platform]. */
class AndroidPlatform(private val app: Application) : Platform {
    val notifier = Notifier(app)
    private val attachments by lazy { Attachments(File(app.filesDir, "att")) }
    /** The activity on screen, for the few things that need one (the unlock prompt, window flags). */
    var activity: WeakReference<FragmentActivity> = WeakReference(null)

    override val isDesktop = false
    override val versionName: String = BuildConfig.VERSION_NAME
    override val debug: Boolean = BuildConfig.DEBUG
    override val filesDir: File = app.filesDir
    override val deviceName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android phone" }
    override val system = "android"

    override val hasDynamicColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    override fun dynamicColors(dark: Boolean): ColorScheme? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)) else null
    override fun animationsEnabled(): Boolean = runCatching { Global.getFloat(app.contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f

    override fun copyToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pocket Agent", text))
        }
    }

    private fun start(intent: Intent) { runCatching { app.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    override fun openFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
            val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
    override fun shareFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
            start(Intent(Intent.ACTION_SEND).setType(android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    override fun inForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    override fun runsChanged(active: Boolean) { if (active) RunService.start(app) }
    override fun notifyFinished(chat: Chat, preview: String, failed: Boolean) = notifier.finished(chat, preview, failed)
    override fun notifyApproval(chat: Chat, title: String) = notifier.approval(chat, title)
    override fun clearApprovalNotice(chatId: Long) = notifier.clearApproval(chatId)
    override fun clearFinishedNotice(chatId: Long) = notifier.clearDone(chatId)

    override fun importAttachment(source: Any): Picked {
        val uri = source as? Uri ?: throw AttachmentError("Could not attach that file.")
        val cr = app.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val type = cr.getType(uri).orEmpty()
        val pdfName = if (type == "application/pdf" && !name.endsWith(".pdf", true)) "$name.pdf" else name
        return attachments.import(
            name = pdfName, isImage = type.startsWith("image/"), size = size,
            read = { cr.openInputStream(uri)?.use { it.readBytes() } },
            toJpeg = {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return@import null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Attachments.MAX_EDGE) sample *= 2
                var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@import null
                val longest = maxOf(bmp.width, bmp.height)
                if (longest > Attachments.MAX_EDGE) {
                    val k = Attachments.MAX_EDGE.toFloat() / longest
                    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it); bmp.recycle() }.toByteArray()
            },
        )
    }

    /** A small preview, decoded at reduced size so a list of images stays light on memory. */
    override fun thumbnail(path: String, maxPx: Int): ImageBitmap? = runCatching {
        val f = attachments.file(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    @Composable
    override fun rememberFilePicker(onPicked: (List<Any>) -> Unit): () -> Unit {
        val picked by rememberUpdatedState(onPicked)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> if (uris.isNotEmpty()) picked(uris) }
        return { launcher.launch(arrayOf("image/*", "application/pdf", "text/*", "application/json")) }
    }

    @Composable
    override fun rememberNotificationAsk(): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        return {
            // Needed for the "reply ready" and "needs your OK" notices when the app is in the background.
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Reads a QR code from a photo taken with the phone's own camera app. This needs no camera permission
     * and no scanning service. The picture is deleted as soon as it has been read.
     */
    @Composable
    override fun rememberQrScanner(onResult: (String?) -> Unit): (() -> Unit)? {
        val result by rememberUpdatedState(onResult)
        val scope = rememberCoroutineScope()
        val file = remember { File(File(app.cacheDir, "share").apply { mkdirs() }, "qr.jpg") }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ ->
            scope.launch {
                val text = withContext(Dispatchers.Default) { runCatching { readQr(file) }.getOrNull() }
                file.delete()
                result(text)
            }
        }
        return {
            runCatching {
                file.delete(); file.createNewFile()
                launcher.launch(FileProvider.getUriForFile(app, app.packageName + ".files", file))
            }.onFailure { result(null) }
        }
    }

    /** Finds a QR code in a photograph. A photo of a screen can be large and soft, so it is tried at a few sizes. */
    private fun readQr(photo: File): String? {
        if (photo.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        for (target in listOf(1600, 1000, 2400, 640)) {
            var sample = 1
            while (longEdge / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: continue
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
            bmp.recycle()
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                runCatching { return MultiFormatReader().decode(bitmap, hints).text }
            }
        }
        return null
    }

    /**
     * Voice typing through the phone's own speech service. Its window does the listening, so the app itself
     * never asks for the microphone. The language comes from Settings, or the phone's language when unset.
     */
    @Composable
    override fun rememberSpeech(g: Graph, onText: (String) -> Unit, onError: (String) -> Unit): Speech? {
        val text by rememberUpdatedState(onText)
        val error by rememberUpdatedState(onError)
        val listening = remember { mutableStateOf(false) }
        val busy = remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            listening.value = false
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text(it) }
        }
        return remember {
            Speech(listening, busy) {
                val lang = g.settings.value?.speechLang.orEmpty()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (lang.isNotEmpty()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang).putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", lang)
                try { launcher.launch(intent); listening.value = true } catch (e: Exception) { error("This phone has no voice typing service switched on. Enable Google's speech services and try again.") }
            }
        }
    }

    override val canLock: Boolean get() = BiometricManager.from(app).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /** Asks for the fingerprint, face or screen lock. */
    override fun authenticate(title: String, onOk: () -> Unit) {
        val a = activity.get() ?: return
        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        })
        runCatching { prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build()) }
    }

    override fun setSecureScreen(on: Boolean) {
        val w = activity.get()?.window ?: return
        if (on) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun scheduleWake(atMillis: Long?) { runCatching { ScheduleWorker.arm(app, atMillis) } }

    override fun makeUpdates(g: Graph): Updates = AndroidUpdates(g, app).also { it.cleanUp() }

    /** Status and navigation bar icons follow the app's own theme, which can differ from the phone's. */
    override fun systemBars(dark: Boolean) {
        val a = activity.get() ?: return
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        a.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

class App : Application() {
    lateinit var graph: Graph
        private set
    lateinit var platform: AndroidPlatform
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        platform.notifier.createChannels()
        val settings = SettingsStore(PreferenceDataStoreFactory.create { preferencesDataStoreFile("settings") })
        graph = Graph(platform, AndroidVault(this), settings, Room.databaseBuilder(this, AppDb::class.java, "pocketagent.db").build())
        graph.start()
        // If the user leaves the app while the agent is waiting for a yes or no, tell them it is waiting.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) graph.updates.onAppForeground()
            if (event == Lifecycle.Event.ON_STOP) graph.runs.runs.value.values.forEach { run ->
                val pending = run.pending ?: return@forEach
                graph.scope.launch { graph.db.dao().chat(run.chatId)?.let { platform.notifyApproval(it, pending.title) } }
            }
        })
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
