package app.pocketagent.ai

import app.pocketagent.data.ToolError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One tool an MCP server offers, as it was last listed. [schema] is its input schema, kept as JSON text. */
@Serializable
data class McpTool(val name: String, val description: String = "", val schema: String = "{}", val on: Boolean = true)

/**
 * An MCP server the person added. It is somebody else's service: it receives the arguments of every call made
 * to it. A remote one has a [url]; on a computer one can instead be a [command] that is started on that computer
 * and spoken to over its input and output. Its token, if it has one, is in the vault, never here.
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String = "",
    val command: String = "",
    val hasToken: Boolean = false,
    val on: Boolean = true,
    val tools: List<McpTool> = emptyList(),
    /** When the list of tools was last fetched. */
    val checkedAt: Long = 0,
    /** When the name or the address was last changed, on whichever device: the newer copy wins between linked devices. */
    val changedAt: Long = 0,
    /** On a computer: linked devices may use this server's tools through the pairing. */
    val lend: Boolean = false,
    /** It arrived from a linked device, where it has a token. The token itself is never sent, so it has to be typed in here. */
    val wantsToken: Boolean = false,
) {
    val local: Boolean get() = command.isNotBlank()
}

/**
 * How the list of MCP servers travels between a person's linked devices: the name and the address of each
 * server that is reached by an address, and when each was changed or removed. Never a token, never the list of
 * tools, never a program's command line: a token is typed in again on each device, and a program belongs to
 * the computer it is installed on.
 */
object McpSync {
    @Serializable
    class Entry(val id: String, val name: String, val url: String, val token: Boolean = false, val at: Long = 0)
    @Serializable
    class Wire(val servers: List<Entry> = emptyList(), val gone: Map<String, Long> = emptyMap())

    class Merged(val servers: List<McpServer>, val gone: Map<String, Long>, val removed: List<String>, val changed: Boolean, /** This device holds something the sender does not. */ val ahead: Boolean)

    fun pack(servers: List<McpServer>, gone: Map<String, Long>): String =
        AppJson.encodeToString(Wire.serializer(), Wire(servers.filter { !it.local }.map { Entry(it.id, it.name, it.url, it.hasToken || it.wantsToken, it.changedAt) }, gone))

    /** Each server and each removal is settled by itself: the newer wins. Returns null when [packed] cannot be read. */
    fun merge(mine: List<McpServer>, myGone: Map<String, Long>, packed: String, now: Long = System.currentTimeMillis()): Merged? {
        val theirs = runCatching { AppJson.decodeFromString(Wire.serializer(), packed) }.getOrNull() ?: return null
        val gone = HashMap(myGone)
        theirs.gone.forEach { (id, at) -> if (id.length <= 64 && at > (gone[id] ?: 0)) gone[id] = at }
        gone.entries.removeAll { now - it.value > FORGET_MS }
        val incoming = theirs.servers.filter { it.id.isNotBlank() && it.id.length <= 64 && it.url.length <= 2000 }.take(MAX_SYNCED).associateBy { it.id }
        val removed = ArrayList<String>()
        var changed = false
        val out = ArrayList<McpServer>()
        for (s in mine) {
            if (s.local) { out += s; continue }
            if ((gone[s.id] ?: -1) >= s.changedAt) { removed += s.id; changed = true; continue }
            val e = incoming[s.id]
            if (e != null && e.at > s.changedAt) {
                val moved = e.url.trim() != s.url
                // A new address is a new place: it is switched off until the person connects it here.
                out += s.copy(name = e.name.take(40), url = e.url.trim(), changedAt = e.at, on = s.on && !moved, tools = if (moved) emptyList() else s.tools, wantsToken = e.token && !s.hasToken)
                changed = true
            } else out += s
        }
        val have = mine.map { it.id }.toSet()
        for (e in incoming.values) if (e.id !in have && (gone[e.id] ?: -1) < e.at) {
            // A server that arrives is listed but off: nothing is contacted until the person connects it on this device.
            out += McpServer(id = e.id, name = e.name.take(40), url = e.url.trim(), on = false, changedAt = e.at, wantsToken = e.token)
            changed = true
        }
        val ahead = out.any { !it.local && (incoming[it.id]?.at ?: -1) < it.changedAt } || gone.any { (id, at) -> (theirs.gone[id] ?: -1) < at }
        return Merged(out, gone, removed, changed || gone != myGone, ahead)
    }

    const val MAX_SYNCED = 50
    private const val FORGET_MS = 180L * 24 * 3600 * 1000
}

/** A program started on this computer that speaks MCP over its input and output, one JSON message a line. */
/** Whether a server answered the last time it was asked. UNKNOWN is a server not asked since the app started. */
enum class McpStatus { UNKNOWN, CHECKING, CONNECTED, FAILED }

interface McpPipe {
    fun send(line: String)
    /** The next line the program wrote, or null if none came in time or it has ended. */
    fun receive(timeoutMs: Long): String?
    fun close()
}

/** A problem talking to an MCP server, in words fit to show. */
class McpError(message: String) : Exception(message)

/**
 * The tools of the MCP servers the person added, offered to the model like any other tool of the app.
 * Every call is shown to the person first, with the server, the tool and the arguments. What a server answers
 * is information for the model, never an instruction. Only servers the person added are ever contacted, and
 * only when a tool of theirs is called or their list is refreshed.
 *
 * It speaks the 2026-07-28 revision of the protocol (no handshake, no sessions) and falls back to the
 * handshake of the 2025 revisions for servers that have not moved on.
 */
class Mcp(
    private val servers: () -> List<McpServer>,
    private val token: (String) -> String?,
    /** Starts a local server's program; null where that is not possible (a phone). */
    private val pipe: ((McpServer) -> McpPipe?)? = null,
    /** Plain http is only for development builds. */
    private val allowHttp: () -> Boolean = { false },
    private val appVersion: String = "1",
    private val http: OkHttpClient = OkHttpClient.Builder().guarded().followSslRedirects(false).followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(CALL_SECONDS, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build(),
) : OutsideTools {
    private val links = ConcurrentHashMap<String, McpLink>()

    private class Named(val server: McpServer, val tool: McpTool)

    /** The model's name for each switched-on tool of each switched-on server. Stable while the list is. */
    private fun named(): Map<String, Named> {
        val out = LinkedHashMap<String, Named>()
        for (s in servers()) if (s.on && (!Net.offline || s.local || Net.localUrl(s.url))) for (t in s.tools) if (t.on) {
            val base = ("mcp_" + slug(s.name).take(20) + "_" + slug(t.name)).take(60)
            var name = base
            var n = 2
            while (name in out) name = base.take(57) + "_" + n++
            out[name] = Named(s, t)
        }
        return out
    }

    override fun specs(): List<ToolSpec> = named().map { (name, n) ->
        ToolSpec(
            name,
            ("From the MCP server \"${n.server.name}\" (a third party the person connected). " + n.tool.description).take(MAX_DESCRIPTION),
            Risk.OUTWARD, emptyList(), JsonObject(emptyMap()), schema = forModel(parseObject(n.tool.schema)),
        )
    }

    override fun handles(name: String) = name.startsWith("mcp_")
    fun risk(name: String): Risk? = if (handles(name)) Risk.OUTWARD else null

    /** The server and tool behind one of the model's tool names, for the approval card and the timeline. */
    /** The model's name for one tool of one server, when both are switched on. */
    fun nameOf(serverId: String, tool: String): String? = named().entries.firstOrNull { it.value.server.id == serverId && it.value.tool.name == tool }?.key

    override fun origin(name: String): Pair<String, String>? = named()[name]?.let { it.server.name to it.tool.name }
    override fun describe(name: String): String? = if (!handles(name)) null else origin(name)?.let { "${it.first}: ${it.second}" } ?: "A tool that is no longer connected"

    /** Calls the tool and returns what it answered as text. */
    /** Told after every call whether the server answered, so the screens can show which servers are connected. */
    @Volatile var onAnswer: ((serverId: String, ok: Boolean) -> Unit)? = null

    override fun run(name: String, input: JsonObject): String {
        val n = named()[name] ?: throw ToolError("That tool is not available: its server was removed or switched off.")
        val result = try {
            link(n.server).request("tools/call", buildJsonObject { put("name", n.tool.name); put("arguments", input) }, n.tool.name, parseObject(n.tool.schema), CALL_SECONDS)
        } catch (e: McpError) {
            onAnswer?.invoke(n.server.id, false)
            throw ToolError("${n.server.name}: ${e.message}")
        }
        // An answer, even one that reports a mistake in the call, means the server is there.
        onAnswer?.invoke(n.server.id, true)
        val text = text(result)
        if (result.bool("isError") == true) throw ToolError(text.ifBlank { "${n.server.name} reported an error." })
        return text.ifBlank { "(The tool returned nothing.)" }
    }

    /** Asks a server for its tools. Used when one is added, tested or refreshed. Switches chosen earlier are kept. */
    fun list(server: McpServer, tokenOverride: String? = null): List<McpTool> {
        links.remove(server.id)?.close()
        val link = McpLink(server, tokenOverride ?: token(server.id), http, pipe, allowHttp(), appVersion)
        val before = server.tools.associateBy { it.name }
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        try {
            for (page in 0 until 10) {
                val result = link.request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } }, null, null, LIST_SECONDS)
                for (t in result.arr("tools").orEmpty()) {
                    val o = t.asObj() ?: continue
                    val name = o.str("name")?.takeIf { it.isNotBlank() } ?: continue
                    val schema = o.obj("inputSchema") ?: JsonObject(emptyMap())
                    // A tool whose header annotations break the rules cannot be called correctly, so it is left out.
                    if (!server.local && McpHeaders.params(schema) == null) continue
                    val text = schema.toString()
                    if (text.length > MAX_SCHEMA) continue
                    out += McpTool(name.take(200), o.str("description").orEmpty().take(MAX_DESCRIPTION), text, before[name]?.on ?: true)
                    if (out.size >= MAX_TOOLS) break
                }
                cursor = result.str("nextCursor")?.takeIf { it.isNotBlank() }
                if (cursor == null || out.size >= MAX_TOOLS) break
            }
        } finally {
            if (tokenOverride != null) link.close() else links[server.id] = link
        }
        return out
    }

    /** Lets go of a server's connection: its program is stopped, its session forgotten. */
    fun forget(id: String) { links.remove(id)?.close() }
    fun close() { links.keys.toList().forEach(::forget) }

    private fun link(server: McpServer): McpLink = links.getOrPut(server.id) { McpLink(server, token(server.id), http, pipe, allowHttp(), appVersion) }

    private fun text(result: JsonObject): String {
        val parts = result.arr("content").orEmpty().mapNotNull { it.asObj() }.map { b ->
            when (b.str("type")) {
                "text" -> b.str("text").orEmpty()
                "resource" -> b.obj("resource")?.let { r -> r.str("text") ?: "[a file: ${r.str("uri").orEmpty()}]" }.orEmpty()
                "resource_link" -> "[a link: ${b.str("uri").orEmpty()}]"
                "image" -> "[a picture, not shown]"
                "audio" -> "[a sound, not shown]"
                else -> ""
            }
        }.filter { it.isNotBlank() }
        if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        return result["structuredContent"]?.takeIf { it !is JsonNull }?.toString().orEmpty()
    }

    companion object {
        const val MODERN = "2026-07-28"
        val LEGACY = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val CALL_SECONDS = 120L
        const val LIST_SECONDS = 30L
        const val MAX_TOOLS = 200
        const val MAX_DESCRIPTION = 1200
        const val MAX_SCHEMA = 20_000
        const val MAX_RESPONSE = 4 * 1024 * 1024
        fun tokenKey(id: String) = "mcp.token.$id"

        private fun slug(s: String) = s.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_').replace(Regex("_+"), "_").ifEmpty { "x" }

        /** A server address the app will talk to: https, or plain http in a development build only. */
        fun urlProblem(url: String, allowHttp: Boolean): String? {
            val u = url.trim()
            return when {
                u.startsWith("https://") && u.length > 10 -> null
                u.startsWith("http://") && allowHttp -> null
                u.startsWith("http://") -> "The address must start with https:// so the calls are encrypted."
                else -> "Enter the server's address, starting with https://"
            }
        }

        /** The schema as the model is shown it: without the transport's own annotations, and always an object. */
        fun forModel(schema: JsonObject?): JsonObject {
            fun clean(e: JsonElement): JsonElement = when (e) {
                // Only the annotations themselves go (they are plain values); a property that happens to carry one of these names stays.
                is JsonObject -> JsonObject(e.filterNot { (k, v) -> v is JsonPrimitive && (k == "x-mcp-header" || k == "\$schema" || k == "\$id") }.mapValues { clean(it.value) })
                is JsonArray -> JsonArray(e.map(::clean))
                else -> e
            }
            val s = (schema?.let(::clean) as? JsonObject) ?: JsonObject(emptyMap())
            return if (s.str("type") == "object" && s["properties"] is JsonObject) s else JsonObject(s + mapOf("type" to JsonPrimitive("object"), "properties" to (s["properties"] as? JsonObject ?: JsonObject(emptyMap()))))
        }
    }
}

/** The HTTP headers the 2026-07-28 revision mirrors from a call's arguments (the `x-mcp-header` annotation). */
internal object McpHeaders {
    private val NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    private fun count(e: JsonElement): Int = when (e) {
        is JsonObject -> (if (e["x-mcp-header"] is JsonPrimitive) 1 else 0) + e.values.sumOf(::count)
        is JsonArray -> e.sumOf(::count)
        else -> 0
    }

    /** Where each annotated argument is (a chain of property names) and the header name it goes under; null if the schema breaks the rules. */
    fun params(schema: JsonObject): Map<List<String>, String>? {
        val out = LinkedHashMap<List<String>, String>()
        fun walk(node: JsonObject, path: List<String>): Boolean {
            for ((key, value) in node.obj("properties").orEmpty()) {
                val prop = value as? JsonObject ?: continue
                val here = path + key
                prop["x-mcp-header"]?.let { h ->
                    val name = (h as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                    if (!NAME.matches(name) || out.values.any { it.equals(name, ignoreCase = true) }) return false
                    if (prop.str("type") !in setOf("string", "integer", "boolean")) return false
                    out[here] = name
                }
                if (!walk(prop, here)) return false
            }
            return true
        }
        if (!walk(schema, emptyList())) return null
        // An annotation anywhere the walk above could not reach (inside a list, a choice, a reference) is not allowed.
        return if (count(schema) == out.size) out else null
    }

    fun encode(value: String): String {
        val plain = value.isNotEmpty() && value.all { it.code in 0x20..0x7E } && value == value.trim() && !(value.startsWith("=?base64?") && value.endsWith("?="))
        return if (plain) value else "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray()) + "?="
    }

    /** The headers for one call: an annotated argument that is present and not null is mirrored, the rest are left out. */
    fun forCall(schema: JsonObject, arguments: JsonObject): List<Pair<String, String>> = params(schema).orEmpty().mapNotNull { (path, name) ->
        var at: JsonElement? = arguments
        for (step in path) at = (at as? JsonObject)?.get(step)
        val p = at as? JsonPrimitive ?: return@mapNotNull null
        if (p is JsonNull) return@mapNotNull null
        val text = when {
            p.isString -> p.content
            p.booleanOrNull != null -> p.content.lowercase()
            p.longOrNull != null -> p.content
            else -> return@mapNotNull null
        }
        "Mcp-Param-$name" to encode(text)
    }
}

/**
 * One server, and what has been learnt about how to talk to it: whether it takes the 2026 form of a request or
 * needs the older handshake first, and, for a local one, its running program.
 */
internal class McpLink(
    private val server: McpServer, private val token: String?, private val http: OkHttpClient,
    private val pipe: ((McpServer) -> McpPipe?)?, private val allowHttp: Boolean, private val appVersion: String,
) {
    private val ids = AtomicLong(1)
    /** Set once the server has shown it wants the handshake of an earlier revision: the version agreed with it. */
    private var legacy: String? = null
    private var session: String? = null
    private var program: McpPipe? = null
    private var probed = false
    private var modernWorks = false

    private fun meta(): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", Mcp.MODERN)
        putJsonObject("io.modelcontextprotocol/clientInfo") { put("name", "My PAI"); put("version", appVersion) }
        putJsonObject("io.modelcontextprotocol/clientCapabilities") {}
    }

    private fun message(method: String, params: JsonObject, modern: Boolean, id: Long?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        put("method", method)
        put("params", if (modern) JsonObject(params + ("_meta" to meta())) else params)
    }

    private fun initParams() = buildJsonObject {
        put("protocolVersion", Mcp.LEGACY.first())
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") { put("name", "My PAI"); put("version", appVersion) }
    }

    /** One request and its result. [name] and [schema] are the tool's, for a call. */
    @Synchronized
    fun request(method: String, params: JsonObject, name: String?, schema: JsonObject?, seconds: Long): JsonObject {
        val result = if (server.local) overPipe(method, params, seconds) else overHttp(method, params, name, schema, seconds)
        if (result.str("resultType") == "input_required") throw McpError("The server asked for more input in a way this app does not support yet.")
        return result
    }

    @Synchronized
    fun close() { runCatching { program?.close() }; program = null; probed = false; legacy = null; session = null; modernWorks = false }

    // ---- Remote servers: one POST for every request.

    private class Answer(val status: Int, val result: JsonObject?, val errorCode: Int?, val errorText: String, val errorData: JsonObject?, val sessionId: String?)

    private fun overHttp(method: String, params: JsonObject, name: String?, schema: JsonObject?, seconds: Long): JsonObject {
        Mcp.urlProblem(server.url, allowHttp)?.let { throw McpError(it) }
        if (legacy == null) {
            val a = post(method, params, true, name, schema, seconds)
            a.result?.let { modernWorks = true; return it }
            when {
                // It has answered a 2026-style request before, or it understood this one and found fault with it: that is the answer.
                modernWorks || (a.status in 200..299 && a.errorCode != null && !Regex("initiali[sz]|session", RegexOption.IGNORE_CASE).containsMatchIn(a.errorText)) -> throw McpError(a.errorText.ifBlank { "The server reported an error." })
                a.status == 401 || a.status == 403 -> throw McpError(refused(a.status))
                // A server of the 2026 kind that turned this request down says why; that is not a reason to try the old way,
                // unless what it says is that it only speaks older versions.
                a.errorCode == -32022 -> {
                    val supported = a.errorData?.arr("supported").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                    if (supported.none { it in Mcp.LEGACY }) throw McpError("The server uses a version of MCP this app does not speak" + (if (supported.isEmpty()) "." else " (${supported.joinToString()})."))
                }
                a.errorCode == -32020 || a.errorCode == -32021 -> throw McpError(a.errorText)
                a.errorCode == -32601 && a.status == 404 -> throw McpError("The server does not offer this (${method}).")
                a.status in 500..599 -> throw McpError("The server had a problem (HTTP ${a.status}). Try again later.")
            }
            // Anything else: a server from before the 2026 revision. Shake hands the way it expects.
            try { handshake() } catch (e: McpError) { throw McpError(a.errorText.ifBlank { e.message ?: "The server did not answer as an MCP server does." }) }
        }
        var a = post(method, params, false, name, schema, seconds)
        // A session the server has forgotten: start a new one, once.
        if (a.result == null && a.status == 404 && session != null) { session = null; handshake(); a = post(method, params, false, name, schema, seconds) }
        a.result?.let { return it }
        if (a.status == 401 || a.status == 403) throw McpError(refused(a.status))
        throw McpError(a.errorText.ifBlank { "The server answered HTTP ${a.status}." })
    }

    private fun refused(status: Int) = if (token.isNullOrBlank()) "The server wants a token (HTTP $status). Add one for it in Settings, Connections." else "The server refused the token (HTTP $status)."

    private fun handshake() {
        val a = post("initialize", initParams(), false, null, null, Mcp.LIST_SECONDS, handshake = true)
        val r = a.result ?: throw McpError(if (a.status == 401 || a.status == 403) refused(a.status) else a.errorText.ifBlank { "The address did not answer as an MCP server does (HTTP ${a.status})." })
        legacy = r.str("protocolVersion")?.takeIf { it in Mcp.LEGACY } ?: Mcp.LEGACY.last()
        session = a.sessionId
        // The server is told the handshake is done; it answers nothing to this.
        runCatching { post("notifications/initialized", JsonObject(emptyMap()), false, null, null, Mcp.LIST_SECONDS, notify = true) }
    }

    private fun post(method: String, params: JsonObject, modern: Boolean, name: String?, schema: JsonObject?, seconds: Long, handshake: Boolean = false, notify: Boolean = false): Answer {
        val id = if (notify) null else ids.getAndIncrement()
        val body = message(method, params, modern, id).toString()
        val req = Request.Builder().url(server.url.trim()).post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "MyPAI")
        if (modern) {
            req.header("MCP-Protocol-Version", Mcp.MODERN).header("Mcp-Method", method)
            if (name != null) req.header("Mcp-Name", McpHeaders.encode(name))
            if (name != null && schema != null) McpHeaders.forCall(schema, params.obj("arguments") ?: JsonObject(emptyMap())).forEach { (h, v) -> req.header(h, v) }
        } else if (!handshake) {
            legacy?.let { req.header("MCP-Protocol-Version", it) }
            session?.let { req.header("Mcp-Session-Id", it) }
        }
        if (!token.isNullOrBlank()) req.header("Authorization", "Bearer " + token.trim())
        val call = http.newBuilder().readTimeout(seconds, TimeUnit.SECONDS).callTimeout(seconds + 20, TimeUnit.SECONDS).build().newCall(req.build())
        try {
            call.execute().use { resp ->
                val sid = resp.header("Mcp-Session-Id")
                if (notify) return Answer(resp.code, JsonObject(emptyMap()), null, "", null, sid)
                val type = resp.header("Content-Type").orEmpty()
                val reply: JsonObject? = if (type.startsWith("text/event-stream")) fromStream(resp.body.source(), id) else parseObject(readCapped(resp.body.source()))
                val err = reply?.obj("error")
                val result = reply?.obj("result").takeIf { resp.isSuccessful && err == null }
                val text = when {
                    err != null -> err.str("message").orEmpty().take(400).ifBlank { "The server reported an error." }
                    result == null && resp.isSuccessful -> "The server's answer could not be read."
                    else -> ""
                }
                return Answer(resp.code, result, (err?.get("code") as? JsonPrimitive)?.intOrNull, text, err?.obj("data"), sid)
            }
        } catch (e: IOException) {
            throw McpError("Could not reach the server (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun readCapped(source: okio.BufferedSource): String {
        val buffer = okio.Buffer()
        while (buffer.size <= Mcp.MAX_RESPONSE) { if (source.read(buffer, 64 * 1024) == -1L) return buffer.readUtf8() }
        throw McpError("The server's answer is too large.")
    }

    /** Reads a stream of events until the answer to request [id] arrives; progress notes before it are passed over. */
    private fun fromStream(source: okio.BufferedSource, id: Long?): JsonObject? {
        val data = StringBuilder()
        var total = 0L
        while (true) {
            val line = source.readUtf8Line() ?: return null
            total += line.length
            if (total > Mcp.MAX_RESPONSE) throw McpError("The server's answer is too large.")
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isEmpty() && data.isNotEmpty() -> {
                    val msg = parseObject(data.toString().trim()); data.setLength(0)
                    if (msg != null && (msg["result"] != null || msg["error"] != null) && ((msg["id"] as? JsonPrimitive)?.longOrNull == id || msg["id"] == null || msg["id"] is JsonNull)) return msg
                }
            }
        }
    }

    // ---- A local server: a program on this computer, one JSON message a line.

    private fun overPipe(method: String, params: JsonObject, seconds: Long): JsonObject {
        val p = program ?: (pipe?.invoke(server) ?: throw McpError("A local server can only run on a computer.")).also { program = it; probed = false; legacy = null }
        try {
            if (!probed) {
                // Ask a 2026-style question first; a program from before then does not know it and gets the handshake instead.
                val hello = exchange(p, message("server/discover", JsonObject(emptyMap()), true, ids.getAndIncrement()), Mcp.LIST_SECONDS)
                val versions = hello?.obj("result")?.arr("supportedVersions").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                if (hello?.obj("result") == null || (versions.isNotEmpty() && Mcp.MODERN !in versions)) {
                    val init = exchange(p, message("initialize", initParams(), false, ids.getAndIncrement()), Mcp.LIST_SECONDS)
                    val r = init?.obj("result") ?: throw McpError(init?.obj("error")?.str("message") ?: "The program did not answer as an MCP server does.")
                    legacy = r.str("protocolVersion") ?: Mcp.LEGACY.last()
                    p.send(message("notifications/initialized", JsonObject(emptyMap()), false, null).toString())
                }
                probed = true
            }
            val reply = exchange(p, message(method, params, legacy == null, ids.getAndIncrement()), seconds) ?: throw McpError("The program did not answer in time.")
            reply.obj("error")?.let { throw McpError(it.str("message").orEmpty().take(400).ifBlank { "The program reported an error." }) }
            return reply.obj("result") ?: throw McpError("The program's answer could not be read.")
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            close()
            throw McpError("The program stopped (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun exchange(p: McpPipe, msg: JsonObject, seconds: Long): JsonObject? {
        val id = (msg["id"] as JsonPrimitive).longOrNull
        p.send(msg.toString())
        val until = System.currentTimeMillis() + seconds * 1000
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return null
            val line = p.receive(left) ?: run { close(); throw McpError("The program ended or went quiet.") }
            if (line.length > Mcp.MAX_RESPONSE) throw McpError("The program's answer is too large.")
            val o = parseObject(line) ?: continue
            if ((o["result"] != null || o["error"] != null) && (o["id"] as? JsonPrimitive)?.longOrNull == id) return o
            // A question from the program to the app (older revisions allowed that): it is told the app does not do this.
            if (o["method"] != null && o["id"] != null && o["id"] !is JsonNull) p.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", o["id"]!!)
                putJsonObject("error") { put("code", -32601); put("message", "Not supported by this client") }
            }.toString())
        }
    }
}
