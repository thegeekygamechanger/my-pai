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

class ToolSpec(val name: String, val description: String, val risk: Risk, val required: List<String>, val props: JsonObject)

/** The parts of the phone a tool may need; kept behind an interface so the tools run in plain unit tests. */
interface ToolHost {
    fun copyToClipboard(text: String)
    fun tasksChanged(chatId: Long, tasksJson: String)
}

data class ToolOutcome(val text: String, val isError: Boolean = false)

/**
 * The tools that run on the phone. Web search, page fetch and the code sandbox are not here:
 * those run on Anthropic's side and never touch the device.
 */
class Tools(private val workspace: Workspace, private val memory: Workspace, private val host: ToolHost, val integrations: Integrations? = null, val media: Media? = null) {

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
    /** @param mediaOnly just the picture and video tools, which an ordinary chat has too; everything else belongs to Work */
    fun definitions(eager: Boolean, mediaOnly: Boolean = false): JsonArray = buildJsonArray {
        for (s in (if (mediaOnly) emptyList() else specs + integrations?.specs().orEmpty()) + media?.specs().orEmpty()) add(buildJsonObject {
            put("name", s.name)
            put("description", s.description)
            if (eager) put("eager_input_streaming", true)
            putJsonObject("input_schema") {
                put("type", "object")
                put("properties", s.props)
                putJsonArray("required") { s.required.forEach { add(it) } }
            }
        })
    }

    fun risk(name: String, input: JsonObject): Risk = integrations?.risk(name, input) ?: media?.risk(name) ?: when (name) {
        MEMORY -> if (input.str("command") == "view") Risk.READ else Risk.WRITE
        else -> byName[name]?.risk ?: Risk.WRITE
    }

    /** One line for the timeline and the approval sheet. */
    fun describe(name: String, input: JsonObject): String = integrations?.describe(name, input) ?: media?.describe(name, input) ?: when (name) {
        "list_files" -> "List " + (input.str("path")?.ifBlank { null } ?: "workspace")
        "read_file" -> "Read " + input.str("path").orEmpty()
        "write_file" -> "Write " + input.str("path").orEmpty()
        "edit_file" -> "Edit " + input.str("path").orEmpty()
        "delete_file" -> "Delete " + input.str("path").orEmpty()
        "update_tasks" -> "Update the task list"
        "get_time" -> "Check the time"
        "copy_to_clipboard" -> "Copy to clipboard"
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
    fun run(chatId: Long, name: String, input: JsonObject): ToolOutcome = try {
        val spec = byName[name] ?: integrations?.specs()?.firstOrNull { it.name == name } ?: media?.specs()?.firstOrNull { it.name == name }
        if (spec != null) {
            val missing = spec.required.filter { input[it] == null }
            if (missing.isNotEmpty()) throw ToolError("Missing required field: " + missing.joinToString())
        }
        ToolOutcome(clip(execute(chatId, name, input)))
    } catch (e: ToolError) {
        ToolOutcome(e.message ?: "The tool failed.", isError = true)
    } catch (e: Exception) {
        ToolOutcome("The tool failed: ${e.javaClass.simpleName}: ${e.message}", isError = true)
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

    companion object {
        const val MEMORY = "memory"
        const val MAX_RESULT = 150_000
    }
}
