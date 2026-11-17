package app.pocketagent.ai

import app.pocketagent.data.Schedule
import app.pocketagent.data.ToolError
import app.pocketagent.data.Workspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * My PAI as an MCP server: other programs on the same computer (an editor, another agent) can read the person's
 * workspace files, memory notes and scheduled tasks. It is off unless the person turns it on, and then:
 *  - it only reads, there is no tool here that changes anything;
 *  - it listens on this computer only, never on the network;
 *  - a program must send the token shown in Settings, and a web page is always refused;
 *  - every call is shown to the person in the app, who allows or denies it.
 * There is no account in any of this: the token is a random secret made on this device.
 */
class McpHost(
    private val workspace: Workspace, private val memory: Workspace, private val tasks: () -> List<Schedule>,
    /** Asks the person; blocks until they answer or the wait runs out. */
    private val approve: (what: String) -> Boolean,
    private val log: ActivityLog? = null, private val version: String = "1",
) {
    class Reply(val status: Int, val body: String)

    private class Offered(val name: String, val description: String, val props: Map<String, String>, val required: List<String>)

    private val offered = listOf(
        Offered("list_files", "List the files and folders in the person's My PAI workspace.", mapOf("path" to "Folder to list; leave out for the whole workspace."), emptyList()),
        Offered("read_file", "Read a text file from the person's My PAI workspace.", mapOf("path" to "File path relative to the workspace."), listOf("path")),
        Offered("list_memory", "List the memory notes My PAI keeps about the person's preferences and projects.", emptyMap(), emptyList()),
        Offered("read_memory", "Read one of My PAI's memory notes.", mapOf("path" to "The note's file name."), listOf("path")),
        Offered("list_tasks", "List the tasks and routines the person has scheduled in My PAI.", emptyMap(), emptyList()),
    )

    /** @param origin the Origin header, which only a web page sends */
    fun handle(token: String, auth: String?, origin: String?, body: String): Reply {
        if (origin != null) return Reply(403, error(null, -32000, "Requests from web pages are refused."))
        val given = auth.orEmpty().removePrefix("Bearer ").trim()
        if (token.length < 16 || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return Reply(401, error(null, -32001, "A token is needed. It is shown in My PAI under Settings, Connections."))
        val req = parseObject(body) ?: return Reply(400, error(null, -32700, "The request is not JSON."))
        val id = req["id"]
        val method = req.str("method").orEmpty()
        // A notification wants no answer.
        if (id == null) return Reply(202, "")
        val params = req.obj("params") ?: JsonObject(emptyMap())
        fun ok(result: JsonObject) = Reply(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", JsonObject(result + ("resultType" to JsonPrimitive("complete")))) }.toString())
        return when (method) {
            "initialize" -> ok(buildJsonObject {
                put("protocolVersion", params.str("protocolVersion")?.takeIf { it in OLDER } ?: OLDER.first())
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "My PAI"); put("version", version) }
                put("instructions", "Read-only access to one person's My PAI workspace files, memory notes and scheduled tasks. Each call is shown to the person, who may refuse it.")
            })
            "server/discover" -> ok(buildJsonObject {
                putJsonArray("supportedVersions") { add(Mcp.MODERN); OLDER.forEach { add(it) } }
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "My PAI"); put("version", version) }
            })
            "ping" -> ok(JsonObject(emptyMap()))
            "tools/list" -> ok(buildJsonObject {
                putJsonArray("tools") {
                    offered.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name); put("description", t.description)
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonObject("properties") { t.props.forEach { (k, d) -> putJsonObject(k) { put("type", "string"); put("description", d) } } }
                                putJsonArray("required") { t.required.forEach { add(it) } }
                            }
                            putJsonObject("annotations") { put("readOnlyHint", true) }
                        })
                    }
                }
            })
            "tools/call" -> {
                val name = params.str("name").orEmpty()
                val args = params.obj("arguments") ?: JsonObject(emptyMap())
                val began = System.currentTimeMillis()
                var failed = false
                val text = try {
                    if (offered.none { it.name == name }) throw ToolError("There is no tool called $name here.")
                    val what = when (name) {
                        "list_files" -> "list the files in " + (args.str("path")?.ifBlank { null } ?: "your workspace")
                        "read_file" -> "read the file " + args.str("path").orEmpty()
                        "list_memory" -> "list your memory notes"
                        "read_memory" -> "read the memory note " + args.str("path").orEmpty()
                        else -> "list your scheduled tasks"
                    }
                    if (!approve(what)) throw ToolError("The person did not allow this.")
                    when (name) {
                        "list_files" -> workspace.list(args.str("path").orEmpty())
                        "read_file" -> workspace.read(args.str("path") ?: throw ToolError("Missing required field: path"), null, null)
                        "list_memory" -> memory.list("")
                        "read_memory" -> memory.read((args.str("path") ?: throw ToolError("Missing required field: path")).removePrefix("/memories").trimStart('/'), null, null)
                        else -> tasks().joinToString("\n") { s -> "- ${s.title.ifBlank { "Task" }} (${Scheduler.describe(s)}${if (s.enabled) "" else ", switched off"}): ${(listOf(s.prompt) + s.steps).joinToString(" Then: ").take(600)}" }.ifEmpty { "(no scheduled tasks)" }
                    }.take(MAX_RESULT)
                } catch (e: ToolError) {
                    failed = true; e.message ?: "The tool failed."
                } catch (e: Exception) {
                    failed = true; "The tool failed."
                }
                log?.add(ToolCall(at = began, server = "This device", tool = name, args = ActivityLog.args(args), ok = !failed, summary = ActivityLog.summary(text), tokens = ActivityLog.tokens(text), ms = System.currentTimeMillis() - began, from = "Another program on this computer"))
                ok(buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }; put("isError", failed) })
            }
            else -> Reply(200, error(id, -32601, "Method not found: $method"))
        }
    }

    private fun error(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: kotlinx.serialization.json.JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }.toString()

    companion object {
        val OLDER = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val MAX_RESULT = 100_000
        const val TOKEN = "mcp.serve.token"
        const val PATH = "/mcp"

        fun newToken(): String = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The smallest web server that can carry MCP: one POST per connection, on this computer only.
 * Hand-written on a plain socket so the same code runs wherever the app does and nothing new is bundled.
 */
class LocalServer(port: Int, private val handler: (auth: String?, origin: String?, body: String) -> McpHost.Reply) : AutoCloseable {
    private val socket = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "mypai-mcp").apply { isDaemon = true } }
    val port: Int get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { pool.execute { client.use { runCatching { serve(it) } } } }.onFailure { runCatching { client.close() } }
            }
        }, "mypai-mcp-accept").apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val head = ByteArrayOutputStream()
        // Read up to the blank line that ends the headers.
        while (head.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            val a = head.toByteArray()
            if (a.size >= 4 && a[a.size - 4] == 13.toByte() && a[a.size - 3] == 10.toByte() && a[a.size - 2] == 13.toByte() && a[a.size - 1] == 10.toByte()) break
        }
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n")
        val first = lines.firstOrNull().orEmpty().split(' ')
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        fun answer(status: Int, body: String) {
            val bytes = body.toByteArray()
            val word = when (status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error" }
            client.getOutputStream().apply {
                write(("HTTP/1.1 $status $word\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                write(bytes); flush()
            }
        }
        // Only this computer may ask, by its own name: a page elsewhere cannot reach in by pointing a name at 127.0.0.1.
        val host = headers["host"].orEmpty().substringBefore(':').lowercase()
        if (host !in setOf("127.0.0.1", "localhost", "[::1]")) return answer(403, "")
        if (first.getOrNull(1)?.substringBefore('?') != McpHost.PATH) return answer(404, "")
        if (first.firstOrNull() != "POST") return answer(405, "")
        val length = headers["content-length"]?.toIntOrNull() ?: return answer(400, "")
        if (length > MAX_BODY) return answer(413, "")
        val body = ByteArray(length)
        var got = 0
        while (got < length) { val n = input.read(body, got, length - got); if (n < 0) return; got += n }
        // The person may take a while to answer the question on screen.
        client.soTimeout = 0
        val reply = handler(headers["authorization"], headers["origin"], String(body, Charsets.UTF_8))
        answer(reply.status, reply.body)
    }

    override fun close() {
        runCatching { socket.close() }
        pool.shutdownNow()
        runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
    }

    companion object {
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 1024 * 1024
    }
}
