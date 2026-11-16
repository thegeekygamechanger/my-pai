package app.pocketagent.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pocketagent.MainActivity
import app.pocketagent.R
import app.pocketagent.data.Chat
import app.pocketagent.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class Notifier(private val ctx: Context) {
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Working", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a reply or an agent run is in progress"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Replies", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A reply finished while the app was in the background" })
        // Scheduled tasks announce themselves: high importance gives the sound and the pop-up at the top of the screen.
        nm.createNotificationChannel(NotificationChannel(CH_TASK, "Scheduled tasks", NotificationManager.IMPORTANCE_HIGH).apply { description = "A scheduled task has finished"; enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply { description = "The agent is waiting for your permission" })
    }

    private fun open(chatId: Long?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (chatId != null) i.putExtra(EXTRA_CHAT, chatId)
        return PendingIntent.getActivity(ctx, (chatId ?: 0L).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun running(text: String, chatId: Long?): Notification {
        val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, RunActionReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("My PAI is working")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open(chatId))
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun post(id: Int, n: Notification) = runCatching { nm.notify(id, n) }

    /** @param task the chat came from a scheduled task, which is announced with a sound */
    fun finished(chat: Chat, preview: String, failed: Boolean, task: Boolean = false) {
        post(
            ID_DONE_BASE + (chat.id % 10_000).toInt(),
            NotificationCompat.Builder(ctx, if (task) CH_TASK else CH_DONE)
                .setPriority(if (task) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (task) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(if (failed) "Stopped: ${chat.title.ifBlank { "Chat" }}" else chat.title.ifBlank { "Reply ready" })
                .setContentText(preview.ifBlank { if (failed) "Open to see what happened." else "The reply is ready." })
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setContentIntent(open(chat.id))
                .build(),
        )
    }

    fun approval(chat: Chat, title: String) {
        post(
            ID_ASK_BASE + (chat.id % 10_000).toInt(),
            NotificationCompat.Builder(ctx, CH_ASK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Needs your OK")
                .setContentText(title)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open(chat.id))
                .build(),
        )
    }

    fun clearApproval(chatId: Long) = nm.cancel(ID_ASK_BASE + (chatId % 10_000).toInt())
    fun clearDone(chatId: Long) = nm.cancel(ID_DONE_BASE + (chatId % 10_000).toInt())

    companion object {
        const val CH_RUN = "run"
        const val CH_DONE = "done"
        const val CH_ASK = "ask"
        const val CH_TASK = "task"
        const val ID_RUN = 1
        const val ID_DONE_BASE = 10_000
        const val ID_ASK_BASE = 30_000
        const val EXTRA_CHAT = "chatId"
        const val ACTION_STOP = "app.pocketagent.STOP_RUNS"
    }
}

class RunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Notifier.ACTION_STOP) context.graph.runs.stopAll()
    }
}

/**
 * A foreground service that exists only while a reply or an agent run is in progress. It keeps the app's
 * process and its network connection alive when the user switches to another app or turns the screen off.
 * The work itself lives in RunManager; this just holds the door open, and leaves as soon as nothing is running.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = graph
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifier.ID_RUN, (application as app.pocketagent.App).platform.notifier.running("Starting…", null), type)
        } catch (e: Exception) {
            // Android refused (for example the daily background allowance is used up). The run still continues while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketagent:run").apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                g.runs.runs.collectLatest { runs ->
                    if (runs.isEmpty()) {
                        ServiceCompat.stopForeground(this@RunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val first = runs.values.first()
                        val title = g.dao.chat(first.chatId)?.title?.ifBlank { null } ?: "New chat"
                        val what = when (first.phase) {
                            Phase.APPROVAL -> "Waiting for your OK"
                            Phase.TOOL -> "Using a tool"
                            Phase.RETRYING -> "Reconnecting"
                            Phase.THINKING -> "Thinking"
                            Phase.WRITING -> "Writing"
                            Phase.CONNECTING -> "Working"
                        }
                        val more = if (runs.size > 1) " (+${runs.size - 1} more)" else ""
                        runCatching {
                            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                .notify(Notifier.ID_RUN, (application as app.pocketagent.App).platform.notifier.running("$what · $title$more", first.chatId))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 and later limit this kind of service to a few hours a day; when the limit hits, step down cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            // Starting from the background can be refused by Android; the run then simply lasts as long as the app does.
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java)) }
        }
    }
}
