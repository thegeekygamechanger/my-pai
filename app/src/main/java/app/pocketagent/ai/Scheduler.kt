package app.pocketagent.ai

import app.pocketagent.Graph
import app.pocketagent.data.Schedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tasks the user asked to have done at a set time: "every morning at 8, summarise my unread mail".
 * When one is due, a new chat is started with its prompt, exactly as if the user had typed it.
 * Schedules belong to the device they were made on, so a task runs once, not once per linked device.
 */
class Scheduler(private val g: Graph) {

    /** Called once at start: keeps an eye on the clock while the app is alive, and arranges a wake-up for when it is not. */
    fun start() {
        g.scope.launch {
            delay(3000)
            while (true) {
                runCatching { runDue() }
                arm()
                delay(30_000)
            }
        }
    }

    private suspend fun schedules() = g.settingsStore.flow.first().schedules

    /** Starts every task whose time has come. Returns the chats it started. */
    suspend fun runDue(now: Long = System.currentTimeMillis()): List<Long> {
        val due = schedules().filter { s -> s.enabled && (next(s, maxOf(s.lastRun, s.createdAt))?.let { it <= now } == true) }
        return due.mapNotNull { run(it, now) }
    }

    suspend fun runNow(id: String): Long? = schedules().firstOrNull { it.id == id }?.let { run(it, System.currentTimeMillis(), manual = true) }

    private suspend fun run(s: Schedule, now: Long, manual: Boolean = false): Long? {
        if (g.connected().isEmpty()) return null
        if (!manual) g.settingsStore.update { st -> st.copy(schedules = st.schedules.map { if (it.id == s.id) it.copy(lastRun = now, enabled = it.repeat != "once") else it }) }
        val chatId = g.newChat(agent = s.agent)
        g.dao.rename(chatId, s.title.ifBlank { "Scheduled task" })
        g.runs.send(chatId, JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", s.prompt) })))
        return chatId
    }

    /** Tells the system when the next task is due, so it can wake the app if it is closed by then. */
    suspend fun arm() {
        val now = System.currentTimeMillis()
        val soonest = schedules().filter { it.enabled }.mapNotNull { next(it, maxOf(it.lastRun, it.createdAt, now - 60_000)) }.minOrNull()
        g.platform.scheduleWake(soonest)
    }

    companion object {
        /** The first time [s] should run after [after] (both in epoch milliseconds), or null if it never will again. */
        fun next(s: Schedule, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), zone).withHour(s.hour.coerceIn(0, 23)).withMinute(s.minute.coerceIn(0, 59)).withSecond(0).withNano(0)
            if (t.toInstant().toEpochMilli() <= after) t = t.plusDays(1)
            if (s.repeat == "once") return if (s.lastRun > 0) null else t.toInstant().toEpochMilli()
            repeat(8) {
                val day = t.dayOfWeek
                val ok = when (s.repeat) {
                    "weekdays" -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                    "weekly" -> day.value == s.weekday.coerceIn(1, 7)
                    else -> true
                }
                if (ok) return t.toInstant().toEpochMilli()
                t = t.plusDays(1)
            }
            return null
        }

        fun describe(s: Schedule): String {
            val time = "%02d:%02d".format(s.hour, s.minute)
            return when (s.repeat) {
                "once" -> "Once at $time"
                "weekdays" -> "Weekdays at $time"
                "weekly" -> DayOfWeek.of(s.weekday.coerceIn(1, 7)).name.lowercase().replaceFirstChar { it.uppercase() } + "s at $time"
                else -> "Every day at $time"
            }
        }
    }
}
