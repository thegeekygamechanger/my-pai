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
import app.pocketagent.ai.Media

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
        /** Pictures or videos this step created, shown in the chat. */
        val media: List<Attachment> = emptyList(),
    ) : UiRow
    /** Searches, pages and code behind a reply, folded into one line. */
    data class Steps(override val key: String, val summary: String, val active: Boolean, val items: List<Tool>) : UiRow
    data class Note(override val key: String, val text: String, val error: Boolean) : UiRow
    data class Sources(override val key: String, val sources: List<Source>) : UiRow
    /** Copy / retry actions under the last reply of a turn. */
    data class Footer(override val key: String, val text: String, val model: String, val tokens: Long, val last: Boolean) : UiRow
    /** The line shown while a reply is on its way. [mood] picks the words it cycles through: think, search, read, image, video, work. */
    data class Status(override val key: String, val text: String, val retrying: Boolean, val mood: String = "think") : UiRow
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
                    compact(rows, before, showThinking)
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
        compact(rows, 0, showThinking)
        val last = run.live.lastOrNull()
        val writing = !liveSaved && last != null && last.type == "text" && !last.done
        if (run.phase == Phase.RETRYING) rows += UiRow.Status("status", run.status, true)
        // Unless words are arriving right now, or the person is being asked something, one animated line says it is on its way.
        else if (!writing && run.pending == null && run.phase != Phase.APPROVAL) rows += UiRow.Status("status", "", false, mood(run))
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
                    val result = saved?.first ?: pending?.text.orEmpty()
                    rows += UiRow.Tool("$key:$i", id, name, describe(name, input), false, state, pretty(input), result, media = if (state == ToolState.DONE) mediaIn(result) else emptyList())
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
                    rows += UiRow.Tool("$key:$i", b.id, b.name, describe(b.name, input), false, state, if (b.done) pretty(input) else b.inputPreview, outcome?.text.orEmpty(), media = if (state == ToolState.DONE) mediaIn(outcome?.text.orEmpty()) else emptyList())
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

    /**
     * Tidies the rows of one reply from [from] on. Searching, opening pages and running code are how an
     * answer gets made, not the answer: every unbroken stretch of them becomes one quiet line that can be
     * opened, and the reasoning around them becomes one line too, instead of a card for every step.
     */
    /** Which family of words the waiting line should use, from what is happening at this moment. */
    private fun mood(run: RunState): String {
        val now = run.live.firstOrNull { it.id.isNotEmpty() && it.id == run.runningTool } ?: run.live.lastOrNull { it.type.endsWith("tool_use") && !it.done } ?: run.live.lastOrNull()
        return when (now?.name) {
            Media.IMAGE -> "image"
            Media.VIDEO -> "video"
            "web_search" -> "search"
            "web_fetch" -> "read"
            null, "", "code_execution", "bash_code_execution", "text_editor_code_execution" -> if (run.phase == Phase.TOOL) "work" else "think"
            else -> "work"
        }
    }

    /** @param show whether the person asked to see the working; otherwise the steps are left out altogether */
    private fun compact(rows: MutableList<UiRow>, from: Int, show: Boolean) {
        val out = ArrayList<UiRow>()
        var i = from
        while (i < rows.size) {
            val r = rows[i]
            // A picture or video that was made is shown by itself; the making of it is not.
            if (r is UiRow.Tool && (r.name == Media.IMAGE || r.name == Media.VIDEO)) { if (r.media.isNotEmpty() || (show && r.state == ToolState.ERROR)) out += r; i++; continue }
            // Looking something up in a connected account is background too; a change there is asked about and stays in view.
            if (!show && r is UiRow.Tool && r.state != ToolState.WAITING && (r.name == "gmail_search" || r.name == "gmail_read" || (r.name == "github" && r.title.startsWith("GitHub: GET")))) { i++; continue }
            if (!(r is UiRow.Thinking || (r is UiRow.Tool && r.server))) { out += r; i++; continue }
            var j = i
            while (j < rows.size && rows[j].let { it is UiRow.Thinking || (it is UiRow.Tool && it.server) }) j++
            val stretch = rows.subList(i, j)
            val thoughts = stretch.filterIsInstance<UiRow.Thinking>()
            val steps = stretch.filterIsInstance<UiRow.Tool>()
            if (thoughts.isNotEmpty()) out += UiRow.Thinking(thoughts.first().key, thoughts.joinToString("\n\n") { it.text.trim() }, thoughts.last().active && steps.none { it.state == ToolState.RUNNING })
            if (show && steps.isNotEmpty()) out += UiRow.Steps(steps.first().key + "s", stepsSummary(steps), steps.any { it.state == ToolState.RUNNING }, steps)
            i = j
        }
        while (rows.size > from) rows.removeAt(rows.size - 1)
        rows += out
    }

    private fun stepsSummary(steps: List<UiRow.Tool>): String {
        steps.lastOrNull { it.state == ToolState.RUNNING }?.let { now ->
            return when (now.name) { "web_search" -> "Searching the web"; "web_fetch" -> "Reading a page"; else -> "Working it out" }
        }
        val searches = steps.count { it.name == "web_search" }
        val pages = steps.count { it.name == "web_fetch" && it.state == ToolState.DONE }
        val parts = listOfNotNull(
            when { searches == 1 -> "searched the web"; searches > 1 -> "searched the web $searches times"; else -> null },
            when { pages == 1 -> "read 1 page"; pages > 1 -> "read $pages pages"; else -> null },
        )
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "Worked it out" }
    }

    private fun mediaIn(result: String): List<Attachment> = Media.REF.findAll(result).map { hit ->
        val name = hit.groupValues[1]
        Attachment(if (name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mov")) "video" else "image", "att/$name", name)
    }.toList()

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
