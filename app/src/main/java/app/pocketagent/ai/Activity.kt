package app.pocketagent.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** One tool call, as the activity log keeps it. [tokens] is an estimate of what the call added to the conversation. */
@Serializable
data class ToolCall(
    val at: Long, val chat: Long = 0, val server: String = "", val tool: String = "", val args: String = "", val ok: Boolean = true,
    val summary: String = "", val tokens: Long = 0, val ms: Long = 0,
    /** Who asked, when it was not this device's own assistant: a linked device, or another program through the local server. */
    val from: String = "",
)

/**
 * Every tool call the app made: when, which server, which tool, with what, and how it ended. Kept in one file in the
 * app's private folder, on this device only: it is in no backup and is never synced. Values of fields that look like
 * secrets are left out, and long values are cut short.
 */
class ActivityLog(private val file: File) {
    private val lock = Any()
    private var lines = -1

    fun add(call: ToolCall) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            if (lines < 0) lines = if (file.isFile) file.useLines { it.count() } else 0
            file.appendText(AppJson.encodeToString(ToolCall.serializer(), call) + "\n")
            if (++lines > KEEP + KEEP / 4) {
                val kept = file.readLines().takeLast(KEEP)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
                lines = kept.size
            }
        }
        Unit
    }

    /** The newest calls first. */
    fun recent(limit: Int = 300): List<ToolCall> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        runCatching { file.readLines().takeLast(limit).mapNotNull { l -> runCatching { AppJson.decodeFromString(ToolCall.serializer(), l) }.getOrNull() }.reversed() }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(lock) { runCatching { file.delete() }; lines = 0 }

    /** How many times each tool was called and roughly how many tokens that came to, largest first. With [chat], for that chat only. */
    fun byTool(chat: Long? = null): List<ToolTotal> = recent(KEEP).filter { chat == null || it.chat == chat }
        .groupBy { (if (it.server.isEmpty()) "" else it.server + ": ") + it.tool }
        .map { (name, calls) -> ToolTotal(name, calls.size, calls.sumOf { it.tokens }) }.sortedByDescending { it.tokens }

    companion object {
        const val KEEP = 1000
        private const val MAX_ARGS = 600
        private const val MAX_SUMMARY = 240
        private val SECRET = Regex("token|password|passwd|secret|api[_-]?key|authorization|credential|bearer", RegexOption.IGNORE_CASE)

        /** The arguments of a call as the log keeps them: secrets blanked, long values cut. */
        fun args(input: JsonObject): String = clean(input).toString().take(MAX_ARGS)

        private fun clean(e: JsonElement, key: String = ""): JsonElement = when {
            SECRET.containsMatchIn(key) -> JsonPrimitive("(hidden)")
            e is JsonObject -> JsonObject(e.mapValues { (k, v) -> clean(v, k) })
            e is JsonArray -> JsonArray(e.take(20).map { clean(it) })
            e is JsonPrimitive && e.isString && e.content.length > 200 -> JsonPrimitive(e.content.take(200) + "…")
            else -> e
        }

        fun summary(result: String): String = result.replace(Regex("\\s+"), " ").trim().take(MAX_SUMMARY)

        /** A rough count: about four characters to a token. */
        fun tokens(vararg texts: String): Long = texts.sumOf { it.length.toLong() } / 4
    }
}

data class ToolTotal(val name: String, val calls: Int, val tokens: Long)
