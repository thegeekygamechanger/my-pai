package app.pocketagent.ai

import app.pocketagent.data.ToolError
import app.pocketagent.data.Workspace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** How much a tool call can change, which decides whether the user is asked first. */
/** OUTWARD is something that leaves the phone for good (an email, a change on GitHub): it is always asked about. */
enum class Risk { READ, WRITE, DESTRUCTIVE, OUTWARD }

/** [schema], when given, is the whole input schema as it is (a tool from an MCP server brings its own); otherwise it is built from [props] and [required]. */
class ToolSpec(val name: String, val description: String, val risk: Risk, val required: List<String>, val props: JsonObject, val schema: JsonObject? = null)

/** The parts of the phone a tool may need; kept behind an interface so the tools run in plain unit tests. */
interface ToolHost {
    fun copyToClipboard(text: String)
    fun tasksChanged(chatId: Long, tasksJson: String)
}

data class ToolOutcome(val text: String, val isError: Boolean = false)

/** Tools that are not the app's own: those of MCP servers, and those a linked computer lends. They all leave the device, so each call is asked about. */
interface OutsideTools {
    fun specs(): List<ToolSpec>
    fun handles(name: String): Boolean
    /** One line for the timeline, or null when the tool is not one of these. */
    fun describe(name: String): String?
    /** Where the tool lives and what it is called there. */
    fun origin(name: String): Pair<String, String>?
    fun run(name: String, input: JsonObject): String
}

/** What a call would do, said before it runs, and whether it can be taken back afterwards. */
data class ToolPreview(val text: String, val undo: Boolean, val undoNote: String)

/**
 * The tools that run on the phone. Web search, page fetch and the code sandbox are not here:
 * those run on Anthropic's side and never touch the device.
 */
class Tools(
    private val workspace: Workspace, private val memory: Workspace, private val host: ToolHost, val integrations: Integrations? = null, val media: Media? = null, val mcp: Mcp? = null,
    /** Tools a linked computer lends through the pairing. */
    val bridge: OutsideTools? = null,
    private val undo: app.pocketagent.data.UndoStore? = null,
    val log: ActivityLog? = null,
) {
    private val outside: List<OutsideTools> get() = listOfNotNull(mcp, bridge)
    private fun outside(name: String): OutsideTools? = outside.firstOrNull { it.handles(name) }

    /** The server and tool behind a name the model uses, when the tool is not the app's own. */
    fun origin(name: String): Pair<String, String>? = outside(name)?.origin(name)

    /** Where a tool runs, for the activity log. */
    fun home(name: String): String = origin(name)?.first ?: when {
        integrations?.handles(name) == true -> if (name.startsWith("gmail")) "Gmail" else "GitHub"
        media?.handles(name) == true -> "Pictures and videos"
        name in SERVER_SIDE -> "AI service"
        else -> "This device"
    }

    private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

    private val specs: List<ToolSpec> = listOf(
        ToolSpec("list_files", "List files and folders in the workspace, a private folder on the phone that keeps files between chats. Paths are relative to the workspace.", Risk.READ, emptyList(), buildJsonObject {
            put("path", prop("string", "Folder to list. Leave out for the whole workspace."))
        }),
        ToolSpec("read_file", "Read a text file from the workspace. Returns numbered lines.", Risk.READ, listOf("path"), buildJsonObject {
            put("path", prop("string", "File path relative to the workspace."))
            put("offset", prop("integer", "First line to return, starting at 1."))
            put("limit", prop("integer", "Number of lines to return."))
        }),
        ToolSpec("write_file", "Create a file in the workspace, or replace one completely. Folders are created as needed. For a small change to an existing file use edit_file instead.", Risk.WRITE, listOf("path", "content"), buildJsonObject {
            put("path", prop("string", "File path relative to the workspace."))
            put("content", prop("string", "The full text of the file."))
        }),
        ToolSpec("edit_file", "Replace an exact piece of text in a workspace file. old_text must match the file exactly and be unique unless replace_all is true.", Risk.WRITE, listOf("path", "old_text", "new_text"), buildJsonObject {
            put("path", prop("string", "File path relative to the workspace."))
            put("old_text", prop("string", "The exact text to replace."))
            put("new_text", prop("string", "The text to put in its place."))
            put("replace_all", prop("boolean", "Replace every occurrence instead of exactly one."))
        }),
        ToolSpec("delete_file", "Delete a file or folder from the workspace. This cannot be undone.", Risk.DESTRUCTIVE, listOf("path"), buildJsonObject {
            put("path", prop("string", "File or folder path relative to the workspace."))
        }),
        ToolSpec("update_tasks", "Show the person a short checklist of the work, and keep it current. Send the whole list each time. Use it for work with several steps; skip it for simple requests.", Risk.READ, listOf("tasks"), buildJsonObject {
            putJsonObject("tasks") {
                put("type", "array")
                put("description", "Every task, in order.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("title", prop("string", "A few words describing the task."))
                        putJsonObject("status") { put("type", "string"); putJsonArray("enum") { add("pending"); add("in_progress"); add("done") } }
                    }
                    putJsonArray("required") { add("title"); add("status") }
                }
            }
        }),
        ToolSpec("get_time", "Get the current date, time and time zone on the phone.", Risk.READ, emptyList(), JsonObject(emptyMap())),
        ToolSpec("copy_to_clipboard", "Put text on the phone's clipboard so the person can paste it in another app.", Risk.WRITE, listOf("text"), buildJsonObject {
            put("text", prop("string", "The text to copy."))
        }),
    )

    private val byName = specs.associateBy { it.name }

    /** Tool definitions for the request, in a fixed order so the cached prefix stays identical. */
    /**
     * @param mediaOnly the tools an ordinary chat has too: the connected accounts (GitHub, Gmail) and making pictures
     *   and videos. Files, memory and the task list belong to Work.
     */
    fun definitions(eager: Boolean, mediaOnly: Boolean = false, sandbox: Boolean = false, focus: ToolFocus? = null): JsonArray = buildJsonArray {
        // A plain chat has no task list, memory or editing, but it can still look into the workspace and save a file there when asked to.
        val own = if (mediaOnly) specs.filter { it.name in CHAT_FILE_TOOLS } else specs
        // In offline mode nothing that reaches the internet is offered: no accounts, no picture services, and only outside tools that are on this device or network.
        val accounts = if (Net.offline) emptyList() else integrations?.specs().orEmpty() + media?.specs().orEmpty()
        val lent = ToolPicker.pick(outside.flatMap { it.specs() }, focus, { origin(it)?.first })
        for (s in own + (if (sandbox) listOf(SANDBOX_SPEC) else emptyList()) + accounts + lent) add(buildJsonObject {
            put("name", s.name)
            put("description", s.description)
            if (eager) put("eager_input_streaming", true)
            if (s.schema != null) put("input_schema", s.schema) else putJsonObject("input_schema") {
                put("type", "object")
                put("properties", s.props)
                putJsonArray("required") { s.required.forEach { add(it) } }
            }
        })
    }

    fun risk(name: String, input: JsonObject): Risk = (if (outside(name) != null) Risk.OUTWARD else null) ?: integrations?.risk(name, input) ?: media?.risk(name) ?: when (name) {
        MEMORY -> if (input.str("command") == "view") Risk.READ else Risk.WRITE
        SANDBOX_PUT -> Risk.READ
        else -> byName[name]?.risk ?: Risk.WRITE
    }

    /** One line for the timeline and the approval sheet. */
    fun describe(name: String, input: JsonObject): String = outside(name)?.describe(name) ?: integrations?.describe(name, input) ?: media?.describe(name, input) ?: when (name) {
        "list_files" -> "List " + (input.str("path")?.ifBlank { null } ?: "workspace")
        "read_file" -> "Read " + input.str("path").orEmpty()
        "write_file" -> "Write " + input.str("path").orEmpty()
        "edit_file" -> "Edit " + input.str("path").orEmpty()
        "delete_file" -> "Delete " + input.str("path").orEmpty()
        "update_tasks" -> "Update the task list"
        "get_time" -> "Check the time"
        "copy_to_clipboard" -> "Copy to clipboard"
        SANDBOX_PUT -> "Send " + input.str("path").orEmpty() + " to the sandbox"
        MEMORY -> when (input.str("command")) {
            "view" -> "Read memory"
            "delete" -> "Delete a memory note"
            else -> "Update memory"
        }
        "web_search" -> "Search the web" + (input.str("query")?.let { ": $it" } ?: "")
        "web_fetch" -> "Open " + (input.str("url") ?: "a page")
        "code_execution", "bash_code_execution" -> "Run code"
        "text_editor_code_execution" -> "Edit a file in the sandbox"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Runs one call. Never throws: a failure comes back as an error result the model can read and react to. */
    /** @param callId the id of this call; with it, a change to a file is remembered so it can be undone */
    fun run(chatId: Long, name: String, input: JsonObject, callId: String = ""): ToolOutcome = try {
        val spec = byName[name] ?: integrations?.specs()?.firstOrNull { it.name == name } ?: media?.specs()?.firstOrNull { it.name == name }
        if (spec != null) {
            val missing = spec.required.filter { input[it] == null }
            if (missing.isNotEmpty()) throw ToolError("Missing required field: " + missing.joinToString())
        }
        val kept = callId.isNotEmpty() && touched(name, input)?.let { f -> undo?.remember(callId, f, describe(name, input)) } == true
        try {
            ToolOutcome(clip(execute(chatId, name, input)))
        } catch (e: Exception) {
            if (kept) undo?.forget(callId)
            throw e
        }
    } catch (e: ToolError) {
        ToolOutcome(e.message ?: "The tool failed.", isError = true)
    } catch (e: Exception) {
        ToolOutcome("The tool failed: ${e.javaClass.simpleName}: ${e.message}", isError = true)
    }

    /** The file a call would change, when it is one the app can put back. */
    private fun touched(name: String, input: JsonObject): java.io.File? = runCatching {
        when (name) {
            "write_file", "edit_file", "delete_file" -> input.str("path")?.let { workspace.resolve(it) }
            MEMORY -> when (input.str("command")) {
                "create", "str_replace", "insert", "delete" -> input.str("path")?.let { memory.resolve(memPath(it)) }
                else -> null
            }
            else -> null
        }
    }.getOrNull()

    fun canUndo(callId: String): Boolean = undo?.can(callId) == true

    /** Takes back the change one call made to a file. Returns what was undone, or null. */
    fun undo(callId: String): String? = undo?.undo(callId)

    /**
     * What a call would change, worked out without doing it: the dry run shown on the approval card.
     * It also says whether the change can be taken back afterwards, and when it cannot, why.
     */
    fun preview(name: String, input: JsonObject): ToolPreview {
        val from = origin(name)
        if (from != null) return ToolPreview("Sends this to ${from.first}. What the server does with it is up to the server.", false, "Cannot be undone from here: it happens on ${from.first}.")
        if (integrations?.handles(name) == true) return ToolPreview("", false, if (risk(name, input) == Risk.OUTWARD) "Cannot be undone from here once it is sent." else "Reads only; nothing changes.")
        val file = touched(name, input)
        val can = undo != null && file != null && !file.isDirectory && (!file.isFile || file.length() <= app.pocketagent.data.UndoStore.MAX_BYTES)
        val yes = "Can be undone afterwards, from this step in the chat."
        fun size(n: Int) = if (n == 1) "1 character" else "%,d characters".format(n)
        return when (name) {
            "write_file" -> {
                val new = input.str("content").orEmpty()
                val old = file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                val what = if (old == null) "Creates ${input.str("path").orEmpty()} (${size(new.length)}, ${new.lines().size} lines)."
                else if (old == new) "Leaves ${input.str("path").orEmpty()} exactly as it is."
                else {
                    val before = old.lines(); val after = new.lines()
                    val gone = before.toMutableList().apply { after.forEach { remove(it) } }.size
                    val added = after.toMutableList().apply { before.forEach { remove(it) } }.size
                    "Replaces ${input.str("path").orEmpty()}: ${size(old.length)} become ${size(new.length)}; $added lines added or changed, $gone removed."
                }
                ToolPreview(what, can, if (can) yes else "Too large to keep a copy of, so it cannot be undone.")
            }
            "edit_file" -> {
                val old = file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                val hits = old?.let { Regex(Regex.escape(input.str("old_text").orEmpty())).findAll(it).count() } ?: 0
                val what = when {
                    old == null -> "There is no file at ${input.str("path").orEmpty()}, so nothing will change."
                    hits == 0 -> "The text to replace is not in ${input.str("path").orEmpty()}, so nothing will change."
                    input.bool("replace_all") == true -> "Changes $hits place${if (hits == 1) "" else "s"} in ${input.str("path").orEmpty()}."
                    hits > 1 -> "The text appears $hits times in ${input.str("path").orEmpty()}, so the edit will be refused."
                    else -> "Changes one place in ${input.str("path").orEmpty()}."
                }
                ToolPreview(what, can, if (can) yes else "Cannot be undone.")
            }
            "delete_file" -> when {
                file == null || !file.exists() -> ToolPreview("There is nothing at ${input.str("path").orEmpty()}, so nothing will change.", false, "")
                file.isDirectory -> ToolPreview("Deletes the folder ${input.str("path").orEmpty()} and the ${file.walkTopDown().count { it.isFile }} files in it.", false, "A folder cannot be brought back.")
                else -> ToolPreview("Deletes ${input.str("path").orEmpty()} (${"%,d".format(file.length())} bytes).", can, if (can) yes else "Too large to keep a copy of, so it cannot be undone.")
            }
            MEMORY -> when (input.str("command")) {
                "view" -> ToolPreview("", false, "Reads only; nothing changes.")
                "rename" -> ToolPreview("Renames a memory note.", false, "Cannot be undone from here; rename it back by hand.")
                else -> ToolPreview(if (file?.isFile == true) "Changes the memory note ${file.name}." else "Creates a memory note.", can, if (can) yes else "Cannot be undone.")
            }
            "copy_to_clipboard" -> ToolPreview("Replaces what is on the clipboard.", false, "What was on the clipboard before is not kept.")
            SANDBOX_PUT -> ToolPreview("Sends a copy of ${input.str("path").orEmpty()} to the AI service's sandbox.", false, "The copy there is removed by the service after 30 days.")
            else -> ToolPreview("", false, if (risk(name, input) == Risk.READ) "Reads only; nothing changes." else "")
        }
    }

    private fun int(input: JsonObject, k: String): Int? = (input[k] as? JsonPrimitive)?.intOrNull

    private fun execute(chatId: Long, name: String, input: JsonObject): String = when (name) {
        "list_files" -> workspace.list(input.str("path").orEmpty())
        "read_file" -> workspace.read(need(input, "path"), int(input, "offset"), int(input, "limit"))
        "write_file" -> workspace.write(need(input, "path"), need(input, "content"))
        "edit_file" -> workspace.edit(need(input, "path"), need(input, "old_text"), need(input, "new_text"), input.bool("replace_all") == true)
        "delete_file" -> workspace.delete(need(input, "path"))
        "update_tasks" -> {
            val tasks = input.arr("tasks") ?: throw ToolError("tasks must be a list.")
            host.tasksChanged(chatId, tasks.toString())
            "Task list updated (${tasks.size} tasks)."
        }
        "get_time" -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss zzz (xxx)"))
        "copy_to_clipboard" -> { host.copyToClipboard(need(input, "text")); "Copied to the clipboard." }
        MEMORY -> memoryCommand(input)
        else -> when {
            outside(name) != null -> outside(name)!!.run(name, input)
            integrations?.handles(name) == true -> integrations.run(name, input)
            media?.handles(name) == true -> media.run(name, input)
            else -> throw ToolError("Unknown tool: $name")
        }
    }

    private fun need(input: JsonObject, k: String): String = input.str(k) ?: throw ToolError("Field \"$k\" must be a string.")

    /** The memory tool sends paths under /memories; they map onto the app's private memory folder. */
    private fun memPath(p: String): String = p.trim().removePrefix("/memories").trimStart('/')

    private fun memoryCommand(input: JsonObject): String = when (val cmd = input.str("command")) {
        "view" -> {
            val p = memPath(input.str("path") ?: "/memories")
            val f = memory.resolve(p)
            when {
                !f.exists() && p.isEmpty() -> "(no memory notes yet)"
                f.isDirectory -> memory.list(p).let { if (it == "(empty)") "(no memory notes yet)" else it }
                else -> memory.read(p)
            }
        }
        "create" -> memory.write(memPath(need(input, "path")), input.str("file_text") ?: input.str("content") ?: "")
        "str_replace" -> memory.edit(memPath(need(input, "path")), need(input, "old_str"), input.str("new_str").orEmpty(), false)
        "insert" -> {
            val p = memPath(need(input, "path"))
            val f = memory.resolve(p)
            if (!f.isFile) throw ToolError("No such file: $p")
            val lines = f.readText().split('\n').toMutableList()
            val at = (int(input, "insert_line") ?: lines.size).coerceIn(0, lines.size)
            lines.add(at, input.str("insert_text") ?: input.str("new_str") ?: "")
            memory.write(p, lines.joinToString("\n"))
        }
        "delete" -> memory.delete(memPath(need(input, "path")))
        "rename" -> memory.rename(memPath(need(input, "old_path")), memPath(need(input, "new_path")))
        else -> throw ToolError("Unknown memory command: $cmd")
    }

    private fun clip(s: String): String = if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "\n[output cut at $MAX_RESULT characters]"

    /** A workspace file exactly as it is on the device, to be handed to the code sandbox. */
    fun sandboxFile(path: String): Pair<String, ByteArray> {
        val f = workspace.resolve(path)
        if (!f.isFile) throw ToolError("There is no file at $path. Use list_files to see what is in the workspace.")
        if (f.length() > MAX_UPLOAD) throw ToolError("${f.name} is too large to send (the limit is ${MAX_UPLOAD / (1024 * 1024)} MB).")
        return f.name to f.readBytes()
    }

    companion object {
        const val MEMORY = "memory"
        /** Tools that run at the AI service, not on the device. */
        val SERVER_SIDE = setOf("web_search", "web_fetch", "code_execution", "bash_code_execution", "text_editor_code_execution")
        /** The workspace tools a plain chat has: enough to save what was asked for, and to find and read it again. */
        val CHAT_FILE_TOOLS = setOf("list_files", "read_file", "write_file")
        const val MAX_RESULT = 150_000
        /** Hands a workspace file to the code sandbox as it is. Run by the run loop, because it needs the service. */
        const val SANDBOX_PUT = "send_to_sandbox"
        const val MAX_UPLOAD = 40L * 1024 * 1024
        val SANDBOX_SPEC = ToolSpec(
            SANDBOX_PUT,
            "Copy a file from the workspace into the code sandbox exactly as it is, so code there can open it. " +
                "Use it for anything that is not plain text (a Word, Excel or PowerPoint file, a picture, a PDF, a zip, audio) and for text files too long to retype. " +
                "The file is in the sandbox from your next step on; the result says how to find it.",
            Risk.READ, listOf("path"),
            buildJsonObject { put("path", buildJsonObject { put("type", "string"); put("description", "The file in the workspace, relative to the workspace folder.") }) },
        )
        /** Written into the result of [SANDBOX_PUT]: the id of the file at the service, and when it stops being valid (seconds since 1970). */
        val SANDBOX_REF = Regex("\\[sandbox file (file_[A-Za-z0-9_-]+) until (\\d+)\\]")
    }
}
