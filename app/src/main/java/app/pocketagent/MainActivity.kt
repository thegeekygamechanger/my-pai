package app.pocketagent

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import app.pocketagent.ai.Notifier
import app.pocketagent.ui.AppRoot
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** The Android window. Everything on screen is the shared [AppRoot]. */
class MainActivity : FragmentActivity(), Host {
    override val graph: Graph get() = (application as App).graph
    override val locked: MutableState<Boolean> = mutableStateOf(false)
    override val openChat: MutableState<Long?> = mutableStateOf(null)
    override val sharedText: MutableState<String?> = mutableStateOf(null)
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        (application as App).platform.activity = WeakReference(this)
        handleIntent(intent)
        setContent { AppRoot(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(Notifier.EXTRA_CHAT, -1L).takeIf { it > 0 }?.let { openChat.value = it }
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sharedText.value = it.take(20_000) }
        if (BuildConfig.DEBUG) {
            // Development only: lets an automated test point the app at local test servers and preload a key.
            val url = intent.getStringExtra("debugBaseUrl")
            val relay = intent.getStringExtra("debugSyncUrl")
            val key = intent.getStringExtra("debugKey")
            if (url != null || key != null || relay != null) graph.scope.launch {
                key?.let { graph.vault.write(app.pocketagent.ai.Providers.vaultKey(app.pocketagent.ai.Providers.ANTHROPIC), it) }
                graph.settingsStore.update { it.copy(debugBaseUrl = url ?: it.debugBaseUrl, debugSyncUrl = relay ?: it.debugSyncUrl, onboarded = if (key != null) true else it.onboarded) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val s = graph.settings.value
        if (s?.lock == true && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt >= s.lockDelaySec * 1000L) locked.value = true
    }
}
