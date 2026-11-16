package app.pocketagent.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.pocketagent.Graph
import app.pocketagent.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Updates on Android. The new release's APK is streamed from GitHub straight into Android's package
 * installer: the app never keeps an APK file of its own. Android only accepts the update when it is
 * signed with the same key as the installed app, so a tampered download cannot be installed.
 */
class AndroidUpdates(private val g: Graph, private val app: Application) : Updates {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    @Volatile private var pending: UpdateInfo? = null

    override suspend fun check(force: Boolean) {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Confirming) return
        val s = g.settingsStore.flow.first()
        if (!force && (!s.autoUpdate || System.currentTimeMillis() - s.lastUpdateCheck < Updates.AUTO_CHECK_MS - 60_000)) return
        state.value = UpdateState.Checking
        try {
            val info = Updates.latest(g.platform.versionName) { names -> names.firstOrNull { it.endsWith(".apk") } }
            g.settingsStore.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            // A silent daily check that fails is not worth an error on screen; it is tried again the next time the app is opened.
            state.value = if (force) UpdateState.Failed("Could not reach GitHub to check for updates (${e.message ?: e.javaClass.simpleName}). Try again in a while.") else UpdateState.Idle
        }
    }

    /** True when Android will let the app hand it an update. */
    private fun mayInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun openPermission() {
        runCatching {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Every time the app comes to the front: pick up where an update was left, and look for a new one. */
    override fun onAppForeground() {
        val s = state.value
        if (s is UpdateState.NeedsPermission && mayInstall()) install(s.info) else g.scope.launch { check() }
    }

    /** Downloads the update into an installer session and hands it to Android to install. */
    override fun install(info: UpdateInfo) {
        if (state.value is UpdateState.Downloading) return
        // Ask for the one-time permission first, in the app's own words, rather than downloading and then being refused.
        if (!mayInstall()) { state.value = UpdateState.NeedsPermission(info); return }
        state.value = UpdateState.Downloading(info, 0)
        g.scope.launch {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(app.packageName)
                        if (info.sizeBytes > 0) setSize(info.sizeBytes)
                    }
                    sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        Updates.http.newCall(Request.Builder().url(info.url).header("User-Agent", "MyPAI").build()).execute().use { res ->
                            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                            val total = res.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                            session.openWrite("MyPAI.apk", 0, if (total > 0) total else -1).use { out ->
                                val input = res.body.byteStream()
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                var shown = -1
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                                    if (pct != shown) { shown = pct; state.value = UpdateState.Downloading(info, pct) }
                                }
                                session.fsync(out)
                            }
                        }
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val result = PendingIntent.getBroadcast(app, sessionId, Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION), flags)
                        pending = info
                        session.commit(result.intentSender)
                    }
                }
                state.value = UpdateState.Confirming(info)
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                state.value = UpdateState.Failed("The update could not be downloaded. Try again.", info)
            }
        }
    }

    /** Called by [UpdateReceiver] with what Android decided. */
    fun onInstallResult(status: Int, message: String?) {
        val info = pending
        state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> info?.let { UpdateState.Confirming(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space to install the update."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update does not fit this phone."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INVALID -> "Android refused the update because it does not match the installed app."
                    else -> "The update was not installed" + (message?.let { ": $it" } ?: ".")
                },
                info,
            )
        }
    }

    /** Removes anything a broken-off update left with the system installer. */
    fun cleanUp() {
        runCatching {
            val installer = app.packageManager.packageInstaller
            installer.mySessions.forEach { if (!it.isActive) installer.abandonSession(it.sessionId) }
        }
    }
}

/** Receives Android's verdict on an update and, when asked, opens the system's confirmation screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        (context.graph.updates as? AndroidUpdates)?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object { const val ACTION = "app.pocketagent.action.UPDATE_RESULT" }
}
