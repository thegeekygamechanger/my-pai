package app.pocketagent.ui

import app.pocketagent.ai.Phase
import app.pocketagent.ai.PrettyJson
import app.pocketagent.ai.RequestBuilder
import app.pocketagent.ai.RunState
import app.pocketagent.ai.ToolOutcome
import app.pocketagent.ai.arr
import app.pocketagent.ai.asObj
import app.pocketagent.ai.bool
import app.pocketagent.ai.long
import app.pocketagent.ai.obj
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.parseObject
import app.pocketagent.ai.str
import app.pocketagent.data.Msg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Attachment(val kind: String, val path: String, val title: String)
data class Source(val title: String, val url: String)
enum class ToolState { RUNNING, WAITING, DONE, ERROR }

/** One item in the chat list on screen. A reply is split into many rows so a long one scrolls smoothly. */
sealed interface UiRow {
    val key: String

    data class User(override val key: String, val msgId: Long, val text: String, val attachments: List<Attachment>) : UiRow
    data class Md(override val key: String, val block: MdBlock, val live: Boolean) : UiRow
    data class Thinking(override val key: String, val text: String, val active: Boolean) : UiRow
    data class Tool(
        override val key: String, val id: String, val name: String, val title: String, val server: Boolean,
        val state: ToolState, val input: String, val result: String, val sources: List<Source> = emptyList(),
    ) : UiRow
    data class Note(override val key: String, val text: String, val error: Boolean) : UiRow
    data class Sources(override val key: String, val sources: List<Source>) : UiRow
    /** Copy / retry actions under the last reply of a turn. */
    data class Footer(override val key: String, val text: String, val model: String, val tokens: Long, val last: Boolean) : UiRow
    data class Status(override val key: String, val text: String, val retrying: Boolean) : UiRow
}

/** A stored message, already parsed. Parsing is cached per message because rows are rebuilt on every change. */
class ParsedMsg(val msg: Msg, val content: List<JsonObject>)

class RowBuilder(private val describe: (String, JsonObject) -> String, private val unpack: (String) -> String) {
    private val cache = HashMap<Long, Pair<String, ParsedMsg>>()
    private val mdCache = object : LinkedHashMap<String, List<MdBlock>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?) = size > 300
    }

    private fun parsed(m: Msg): ParsedMsg {
        cache[m.id]?.let { if (it.first == m.content) return it.second }
        val content = if (m.role == "note") emptyList() else parseArray(unpack(m.content)).mapNotNull { it.asObj() }
        return ParsedMsg(m, content).also { cache[m.id] = m.content to it }
    }

    private fun md(text: String): List<MdBlock> = mdCache.getOrPut(text) { Markdown.parse(text) }

    /** Rows for everything that is saved. [run] only supplies the state of tool calls that are in progress. */
    fun persisted(messages: List<Msg>, run: RunState?, showThinking: Boolean): List<UiRow> {
        val rows = ArrayList<UiRow>()
        val all = messages.map(::parsed)
        // Results of the phone's tools arrive in the message after the call; index them first.
        val results = HashMap<String, Pair<String, Boolean>>()
        for (p in all) if (p.msg.role == "user") for (b in p.content) if (b.str("type") == "tool_result") {
            results[b.str("tool_use_id").orEmpty()] = resultText(b["content"]) to (b.bool("is_error") == true)
        }
        val lastAssistant = all.lastOrNull { it.msg.role == "assistant" }?.msg?.id

        all.forEachIndexed { index, p ->
            val m = p.msg
            when (m.role) {
                "note" -> rows += UiRow.Note("n${m.id}", m.content, m.stopReason == "error")
                "user" -> {
                    val text = p.content.filter { it.str("type") == "text" }.joinToString("\n\n") { it.str("text").orEmpty() }
                    val atts = p.content.mapNotNull { b ->
                        when (b.str("type")) {
                            "image" -> Attachment("image", b.obj("source")?.str("path").orEmpty(), "Image")
                            "document" -> Attachment("document", b.obj("source")?.str("path").orEmpty(), b.str("title") ?: "Document")
                            else -> null
                        }
                    }
                    if (text.isNotBlank() || atts.isNotEmpty()) rows += UiRow.User("u${m.id}", m.id, text, atts)
                }
                "assistant" -> {
                    val key = if (m.apiId.isNotEmpty()) "a${m.apiId}" else "m${m.id}"
                    val before = rows.size
                    assistantRows(key, RequestBuilder.effective(p.content).mapNotNull { it.asObj() }, p.content, results, run, showThinking, rows)
                    // The footer goes under the last reply of a turn: the next stored message is a real user message, or there is none.
                    val next = all.getOrNull(index + 1)
                    val turnEnds = next == null || (next.msg.role == "user" && next.content.any { it.str("type") != "tool_result" }) || next.msg.role == "note"
                    val running = run != null && next == null
                    if (turnEnds && !running && rows.size > before) {
                        val text = p.content.filter { it.str("type") == "text" }.joinToString("\n\n") { it.str("text").orEmpty() }
                        if (text.isNotBlank()) rows += UiRow.Footer("f${m.id}", text, m.model, m.outTok, m.id == lastAssistant && all.drop(index + 1).none { it.msg.role == "user" && it.content.any { b -> b.str("type") != "tool_result" } })
                    }
                }
            }
        }

        return rows
    }

    /** Rows for the reply that is still arriving, plus the "connecting" or "retrying" line. */
    fun tail(messages: List<Msg>, run: RunState?, showThinking: Boolean): List<UiRow> {
        if (run == null) return emptyList()
        val rows = ArrayList<UiRow>()
        val liveId = run.apiId
        val liveSaved = liveId.isNotEmpty() && messages.any { it.apiId == liveId }
        if (!liveSaved && run.live.isNotEmpty()) liveRows(if (liveId.isNotEmpty()) "a$liveId" else "live", run, showThinking, rows)
        if (run.phase == Phase.RETRYING) rows += UiRow.Status("status", run.status, true)
        else if (run.phase == Phase.CONNECTING && (run.live.isEmpty() || liveSaved)) rows += UiRow.Status("status", "", false)
        return rows
    }

    private fun assistantRows(
        key: String, content: List<JsonObject>, raw: List<JsonObject>, results: Map<String, Pair<String, Boolean>>,
        run: RunState?, showThinking: Boolean, rows: MutableList<UiRow>,
    ) {
        val serverResults = raw.filter { it.str("tool_use_id") != null && it.str("type") != "tool_result" }.associateBy { it.str("tool_use_id").orEmpty() }
        val sources = LinkedHashMap<String, Source>()
        content.forEachIndexed { i, b ->
            when (b.str("type")) {
                "text" -> {
                    val text = b.str("text").orEmpty()
                    md(text).forEachIndexed { j, blk -> rows += UiRow.Md("$key:$i:$j", blk, false) }
                    b.arr("citations")?.forEach { c ->
                        val o = c.asObj() ?: return@forEach
                        val url = o.str("url") ?: return@forEach
                        sources.getOrPut(url) { Source(o.str("title") ?: url, url) }
                    }
                }
                "thinking" -> if (showThinking) b.str("thinking")?.takeIf { it.isNotBlank() }?.let { rows += UiRow.Thinking("$key:$i", it, false) }
                "tool_use" -> {
                    val id = b.str("id").orEmpty()
                    val name = b.str("name").orEmpty()
                    val input = b.obj("input") ?: JsonObject(emptyMap())
                    val saved = results[id]
                    val pending: ToolOutcome? = run?.done?.get(id)
                    val state = when {
                        saved != null -> if (saved.second) ToolState.ERROR else ToolState.DONE
                        pending != null -> if (pending.isError) ToolState.ERROR else ToolState.DONE
                        run?.pending?.toolUseId == id -> ToolState.WAITING
                        run != null -> ToolState.RUNNING
                        else -> ToolState.ERROR
                    }
                    rows += UiRow.Tool("$key:$i", id, name, describe(name, input), false, state, pretty(input), saved?.first ?: pending?.text.orEmpty())
                }
                "server_tool_use", "mcp_tool_use" -> {
                    val id = b.str("id").orEmpty()
                    val name = b.str("name").orEmpty()
                    val input = b.obj("input") ?: JsonObject(emptyMap())
                    val res = serverResults[id]
                    val (text, srcs, err) = serverResult(res)
                    rows += UiRow.Tool("$key:$i", id, name, describe(name, input), true, if (res == null) ToolState.DONE else if (err) ToolState.ERROR else ToolState.DONE, serverInput(name, input), text, srcs)
                }
                "fallback" -> rows += UiRow.Note("$key:$i", "Another model took over this reply" + (b.obj("to")?.str("model")?.let { " ($it)" } ?: "") + ".", false)
                "compaction" -> rows += UiRow.Note("$key:$i", "Earlier messages were summarised to keep the conversation within limits.", false)
            }
        }
        if (sources.isNotEmpty()) rows += UiRow.Sources("$key:src", sources.values.toList())
    }

    private fun liveRows(key: String, run: RunState, showThinking: Boolean, rows: MutableList<UiRow>) {
        val live = run.live
        val answered = live.filter { it.type.endsWith("_tool_result") }.associateBy { it.id }
        live.forEachIndexed { i, b ->
            val isLast = i == live.lastIndex
            when (b.type) {
                "text" -> {
                    val blocks = Markdown.parse(b.text)
                    blocks.forEachIndexed { j, blk -> rows += UiRow.Md("$key:$i:$j", blk, !b.done && isLast && j == blocks.lastIndex) }
                }
                "thinking" -> if (showThinking && (b.text.isNotBlank() || !b.done)) rows += UiRow.Thinking("$key:$i", b.text, !b.done)
                "tool_use" -> {
                    val input = parseObject(b.inputPreview) ?: JsonObject(emptyMap())
                    val outcome = run.done[b.id]
                    val state = when {
                        outcome != null -> if (outcome.isError) ToolState.ERROR else ToolState.DONE
                        run.pending?.toolUseId == b.id -> ToolState.WAITING
                        else -> ToolState.RUNNING
                    }
                    rows += UiRow.Tool("$key:$i", b.id, b.name, describe(b.name, input), false, state, if (b.done) pretty(input) else b.inputPreview, outcome?.text.orEmpty())
                }
                "server_tool_use", "mcp_tool_use" -> {
                    val input = parseObject(b.inputPreview) ?: JsonObject(emptyMap())
                    val res = answered[b.id]?.raw
                    val (text, srcs, err) = serverResult(res)
                    rows += UiRow.Tool("$key:$i", b.id, b.name, describe(b.name, input), true, if (res == null) ToolState.RUNNING else if (err) ToolState.ERROR else ToolState.DONE, serverInput(b.name, input), text, srcs)
                }
            }
        }
    }

    private fun pretty(o: JsonObject): String = if (o.isEmpty()) "" else runCatching { PrettyJson.encodeToString(JsonObject.serializer(), o) }.getOrDefault(o.toString()).take(4000)

    private fun serverInput(name: String, input: JsonObject): String =
        input.str("query") ?: input.str("url") ?: input.str("command") ?: input.str("code") ?: pretty(input)

    private fun resultText(el: JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.content
        is JsonArray -> el.mapNotNull { it.asObj()?.str("text") }.joinToString("\n")
        else -> el.toString()
    }.take(6000)

    /** Turns a server tool's result block into something worth reading: links for a search, output for code. */
    private fun serverResult(res: JsonObject?): Triple<String, List<Source>, Boolean> {
        if (res == null) return Triple("", emptyList(), false)
        val c = res["content"]
        if (c is JsonArray) {
            val srcs = c.mapNotNull { it.asObj() }.filter { it.str("url") != null }.map { Source(it.str("title") ?: it.str("url").orEmpty(), it.str("url").orEmpty()) }
            return Triple(if (srcs.isEmpty()) "No results." else "${srcs.size} result${if (srcs.size == 1) "" else "s"}", srcs.take(12), false)
        }
        val o = c.asObj() ?: return Triple("", emptyList(), false)
        o.str("error_code")?.let { return Triple("Failed: ${it.replace('_', ' ')}", emptyList(), true) }
        // A fetched page.
        o.str("url")?.let { url ->
            val title = o.obj("content")?.str("title")
            return Triple("Fetched", listOf(Source(title ?: url, url)), false)
        }
        // Code execution output.
        val out = StringBuilder()
        o.str("stdout")?.takeIf { it.isNotBlank() }?.let { out.append(it.trimEnd()) }
        o.str("stderr")?.takeIf { it.isNotBlank() }?.let { if (out.isNotEmpty()) out.append("\n"); out.append(it.trimEnd()) }
        val code = o.long("return_code")
        return Triple(out.toString().take(6000).ifEmpty { if (code == null) "Done." else "Finished with code $code." }, emptyList(), code != null && code != 0L)
    }

    fun forget(msgIds: Collection<Long>) { cache.keys.retainAll(msgIds.toSet()) }
}

/** The agent's checklist as it last sent it. */
data class Task(val title: String, val status: String)

fun parseTasks(json: String): List<Task> = parseArray(json).mapNotNull { e ->
    val o = e.asObj() ?: return@mapNotNull null
    Task(o.str("title") ?: return@mapNotNull null, o.str("status") ?: "pending")
}
