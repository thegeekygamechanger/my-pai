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

/** A task to run at a set time. [repeat] is "once", "daily", "weekdays" or "weekly"; [weekday] is 1 (Monday) to 7. */
@Serializable
data class Schedule(
    val id: String, val title: String, val prompt: String, val agent: Boolean = false, val hour: Int = 9, val minute: Int = 0,
    val repeat: String = "daily", val weekday: Int = 1, val enabled: Boolean = true, val lastRun: Long = 0, val createdAt: Long = 0,
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
    val fallbacks: Boolean = true,
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
