package app.pocketagent.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A finished reply: `content` is exactly what must be sent back on the next request. */
class ApiMessage(
    val id: String,
    val model: String,
    val content: JsonArray,
    val stopReason: String,
    val stopDetails: JsonObject?,
    val usage: Usage,
    /** tool_use id -> the raw text the model sent, for calls whose input was not valid JSON. */
    val invalidInputs: Map<String, String>,
    /** The code sandbox this reply ran in, if any; sending it back keeps the sandbox's files for the next step. */
    val container: String = "",
)

/** One block of a reply while it is still arriving, for drawing on screen. */
data class LiveBlock(
    val type: String,
    val text: String = "",
    val name: String = "",
    val id: String = "",
    val inputPreview: String = "",
    val done: Boolean = false,
    val raw: JsonObject? = null,
    /** For a file that is being written right now: its path and the text that has arrived so far. */
    val draftPath: String = "",
    val draft: String = "",
)

/**
 * What a tool call that is still arriving is going to write, read out of its unfinished JSON. This is what lets a
 * file be read while it is being written, and kept if the writing is stopped half-way.
 */
object Draft {
    private val KEYS = mapOf("write_file" to "content", "edit_file" to "new_text", Tools.MEMORY to "file_text")

    /** The path and the text so far, or null if this tool writes no text or none has arrived yet. */
    fun of(tool: String, partialJson: CharSequence): Pair<String, String>? {
        val key = KEYS[tool] ?: return null
        val text = string(partialJson, key)?.takeIf { it.isNotEmpty() } ?: return null
        return string(partialJson, "path").orEmpty() to text
    }

    /** The value of a string field, as far as it has arrived: escapes are undone, and a half-arrived escape at the end is left off. */
    fun string(json: CharSequence, key: String): String? {
        val start = Regex("(?<!\\\\)\"" + Regex.escape(key) + "\"\\s*:\\s*\"").find(json)?.range?.last?.plus(1) ?: return null
        val out = StringBuilder()
        var i = start
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' -> return out.toString()
                c != '\\' -> out.append(c)
                i + 1 >= json.length -> return out.toString()
                else -> {
                    i++
                    when (val e = json[i]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); 'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 >= json.length) return out.toString()
                            json.substring(i + 1, i + 5).toIntOrNull(16)?.let { out.append(it.toChar()) }
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
            }
            i++
        }
        return out.toString()
    }
}

/**
 * Rebuilds the reply from server-sent events. Every block is kept as the JSON the service sent
 * (thinking signatures, server tool results, citations and block types this app has never heard of),
 * because the next request has to return the reply unchanged.
 */
class StreamAssembler : LiveReply {
    private class Block(val base: JsonObject) {
        val type: String = base.str("type").orEmpty()
        val strings = LinkedHashMap<String, StringBuilder>()
        val extras = LinkedHashMap<String, JsonElement>()
        val partialJson = StringBuilder()
        var citations: MutableList<JsonElement>? = null
        var done = false
    }

    override var id = ""; private set
    override var model = ""; private set
    var stopReason: String? = null; private set
    var finished = false; private set
    private var stopDetails: JsonObject? = null
    private var container = ""
    private val usage = LinkedHashMap<String, Long>()
    private val blocks = ArrayList<Block?>()
    private val invalid = LinkedHashMap<String, String>()

    @Synchronized
    fun accept(ev: JsonObject) {
        when (ev.str("type")) {
            "message_start" -> ev.obj("message")?.let { m ->
                id = m.str("id").orEmpty()
                model = m.str("model").orEmpty()
                m.obj("usage")?.let(::mergeUsage)
                m.obj("container")?.str("id")?.let { container = it }
            }
            "content_block_start" -> {
                val i = ev.long("index")?.toInt() ?: return
                while (blocks.size <= i) blocks.add(null)
                blocks[i] = Block(ev.obj("content_block") ?: return)
            }
            "content_block_delta" -> {
                val b = blocks.getOrNull(ev.long("index")?.toInt() ?: return) ?: return
                val d = ev.obj("delta") ?: return
                for ((k, v) in d) {
                    if (k == "type") continue
                    val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    when {
                        k == "partial_json" -> b.partialJson.append(s.orEmpty())
                        k == "citation" -> (b.citations ?: (b.base.arr("citations")?.toMutableList() ?: mutableListOf()).also { b.citations = it }).add(v)
                        s != null -> b.strings.getOrPut(k) { StringBuilder(b.base.str(k).orEmpty()) }.append(s)
                        else -> b.extras[k] = v
                    }
                }
            }
            "content_block_stop" -> blocks.getOrNull(ev.long("index")?.toInt() ?: return)?.done = true
            "message_delta" -> {
                ev.obj("delta")?.let { d ->
                    d.str("stop_reason")?.let { stopReason = it }
                    d.obj("stop_details")?.let { stopDetails = it }
                    d.obj("container")?.str("id")?.let { container = it }
                }
                ev.obj("container")?.str("id")?.let { container = it }
                ev.obj("usage")?.let(::mergeUsage)
            }
            "message_stop" -> finished = true
            "error" -> {
                val e = ev.obj("error")
                throw ApiException(ApiException.kindFor(0, e?.str("type")), e?.str("message") ?: "The service reported an error.")
            }
        }
    }

    private fun mergeUsage(u: JsonObject) {
        for ((k, v) in u) (v as? JsonPrimitive)?.let { p -> if (!p.isString) p.content.toLongOrNull()?.let { usage[k] = it } }
    }

    private fun isToolCall(type: String) = type == "tool_use" || type == "server_tool_use" || type == "mcp_tool_use"

    private fun Block.build(): JsonObject = buildJsonObject {
        for ((k, v) in base) put(k, v)
        for ((k, sb) in strings) put(k, JsonPrimitive(sb.toString()))
        for ((k, v) in extras) put(k, v)
        citations?.let { put("citations", JsonArray(it)) }
        if (isToolCall(type)) {
            val rawInput = partialJson.toString()
            val parsed = if (rawInput.isBlank()) base.obj("input") ?: JsonObject(emptyMap()) else parseObject(rawInput)
            if (parsed == null) base.str("id")?.let { invalid[it] = rawInput }
            put("input", parsed ?: JsonObject(emptyMap()))
        }
    }

    @Synchronized
    fun usage() = Usage(
        input = usage["input_tokens"] ?: 0,
        output = usage["output_tokens"] ?: 0,
        cacheRead = usage["cache_read_input_tokens"] ?: 0,
        cacheWrite = usage["cache_creation_input_tokens"] ?: 0,
    )

    @Synchronized
    fun toMessage(): ApiMessage {
        invalid.clear()
        val content = JsonArray(blocks.filterNotNull().map { it.build() })
        return ApiMessage(id, model, content, stopReason ?: "end_turn", stopDetails, usage(), invalid.toMap(), container)
    }

    /** A cheap picture of the reply so far. */
    @Synchronized
    override fun live(): List<LiveBlock> = blocks.filterNotNull().map { b ->
        when {
            b.type == "text" -> LiveBlock("text", text = b.strings["text"]?.toString() ?: b.base.str("text").orEmpty(), done = b.done)
            b.type == "thinking" -> LiveBlock("thinking", text = b.strings["thinking"]?.toString() ?: b.base.str("thinking").orEmpty(), done = b.done)
            isToolCall(b.type) -> {
                val name = b.base.str("name").orEmpty()
                val draft = if (b.done) null else Draft.of(name, b.partialJson)
                LiveBlock(b.type, name = name, id = b.base.str("id").orEmpty(), inputPreview = b.partialJson.toString().take(600), done = b.done, draftPath = draft?.first.orEmpty(), draft = draft?.second.orEmpty())
            }
            else -> LiveBlock(b.type, done = b.done, raw = if (b.done) b.base else null, id = b.base.str("tool_use_id").orEmpty())
        }
    }

    /** Only the visible text, for keeping what arrived when the user stops a reply half-way. */
    @Synchronized
    override fun partialText(): String = blocks.filterNotNull().filter { it.type == "text" }
        .joinToString("\n\n") { it.strings["text"]?.toString() ?: it.base.str("text").orEmpty() }.trim()
}
