package app.pocketagent.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedModel(val id: String, val name: String = "", val maxInput: Long = 0, val maxOutput: Long = 0)

/**
 * A task to run at a set time. [repeat] is "once", "daily", "weekdays", "weekly", or "never" for a routine that only
 * runs when the person starts it; [weekday] is 1 (Monday) to 7. [steps] are further requests, sent one after another
 * in the same chat once [prompt] is done: with them the task is a routine.
 */
@Serializable
data class Schedule(
    val id: String, val title: String, val prompt: String, val agent: Boolean = false, val hour: Int = 9, val minute: Int = 0,
    val repeat: String = "daily", val weekday: Int = 1, val enabled: Boolean = true, val lastRun: Long = 0, val createdAt: Long = 0,
    val steps: List<String> = emptyList(),
)

/** A device this one is linked with. */
@Serializable
data class Device(val id: String, val name: String, val system: String, val addedAt: Long = 0, val removed: Boolean = false)

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    /** What the person told the app about themselves on the first screen, used to shape replies. */
    val name: String = "",
    val role: String = "",
    val style: String = "balanced",
    /** Where the person is, for the weather in the greeting. Optional. */
    val city: String = "",
    /** The language for voice typing, such as "hi-IN"; empty follows the device. */
    val speechLang: String = "",
    /** The service and model new chats start with. */
    val provider: String = "anthropic",
    val model: String = ModelCaps.DEFAULT_MODEL,
    /** Services the user added by hand (any OpenAI-compatible endpoint). */
    val customProviders: List<Provider> = emptyList(),
    val effort: String = "medium",
    /** Web search is on for new chats unless the user turns it off (it applies to Claude models). */
    val web: Boolean = true,
    val showThinking: Boolean = true,
    /** Pictures may be made by the free open-source service (Pollinations) when no key of the person's own can make them. */
    val freeImages: Boolean = true,
    /** Tell the person when a reply is ready while they are in another app. */
    val notifyReplies: Boolean = true,
    /** Announce the result of a scheduled task, with a sound. */
    val notifyTasks: Boolean = true,
    /** True once the person has picked a model for new chats themselves. Until then new chats use Auto. */
    val modelChosen: Boolean = false,
    /** Show the reasoning and the steps (searches, pages, code) behind a reply. Off: only an animated line while it works. */
    val showWork: Boolean = false,
    val customInstructions: String = "",
    /** When [customInstructions] or the API key last changed here; the newer copy wins between linked devices. */
    val sharedRev: Long = 0,
    val maxOutput: Int = 32_000,
    val permission: String = "auto_read",
    val maxSteps: Int = 30,
    /** Stop an agent run after this many thousand tokens; 0 = no limit. */
    val budgetK: Int = 400,
    val theme: String = "system",
    val pureBlack: Boolean = false,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val lock: Boolean = false,
    val lockDelaySec: Int = 60,
    val secureScreen: Boolean = false,
    val compaction: Boolean = true,
    /** Look for a newer release on GitHub once a day. */
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** Chats on this device with a reply the person has not looked at yet (a scheduled task's result, a reply that came while they were elsewhere). */
    val unreadChats: List<Long> = emptyList(),
    /** MCP servers the person added. Names and addresses reach linked devices; tokens are in each device's vault and never do. */
    val mcpServers: List<app.pocketagent.ai.McpServer> = emptyList(),
    /** MCP servers removed here or on a linked device, and when, so a removal is not undone by an older copy elsewhere. */
    val mcpGone: Map<String, Long> = emptyMap(),
    val fallbacks: Boolean = true,
    /** The service and model to carry on with when the chat's own fails or hits a limit. Empty: none. */
    val backupProvider: String = "",
    val backupModel: String = "",
    /** Offline mode: only models and tools on this device or the person's own network; nothing goes to the internet. */
    val offline: Boolean = false,
    /** On a computer: linked devices may use the MCP servers marked as lent. Off unless the person turns it on. */
    val bridge: Boolean = false,
    /** On a computer: other programs on it may read files, memory notes and tasks through a local MCP server. Off unless turned on. */
    val serve: Boolean = false,
    val servePort: Int = 18791,
    /** The model lists last fetched with the user's keys, by service, so the picker is instant and works offline. */
    val models: Map<String, List<SavedModel>> = emptyMap(),
    /** Tasks to run at set times, on this device. */
    val schedules: List<Schedule> = emptyList(),
    /** This device's own id for syncing; made once. */
    val deviceId: String = "",
    /** Every device linked through sync, this one included. */
    val devices: List<Device> = emptyList(),
    /** Development builds only: point the app at a local test server. */
    val debugBaseUrl: String = "",
    val debugSyncUrl: String = "",
)

/** The person's standing instructions, with what they said about themselves on the first screen in front. */
fun Settings.instructions(): String = buildString {
    if (name.isNotBlank()) append("The person's name is ").append(name.trim()).append(". ")
    if (role.isNotBlank()) append("They describe themselves as: ").append(role.trim()).append(". ")
    when (style) {
        "short" -> append("They prefer short, direct answers. ")
        "detailed" -> append("They prefer thorough, detailed answers. ")
    }
    if (customInstructions.isNotBlank()) { if (isNotEmpty()) append("\n"); append(customInstructions.trim()) }
}.trim()

class SettingsStore(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun read(p: Preferences): Settings = p[key]?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    val flow: Flow<Settings> = store.data.map(::read)

    suspend fun update(change: (Settings) -> Settings) {
        store.edit { p -> p[key] = json.encodeToString(Settings.serializer(), change(read(p))) }
    }
}
