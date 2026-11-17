package app.pocketagent.sync

import app.pocketagent.Graph
import app.pocketagent.ai.ActivityLog
import app.pocketagent.ai.Mcp
import app.pocketagent.ai.OutsideTools
import app.pocketagent.ai.Risk
import app.pocketagent.ai.ToolCall
import app.pocketagent.ai.ToolSpec
import app.pocketagent.ai.arr
import app.pocketagent.ai.asObj
import app.pocketagent.ai.bool
import app.pocketagent.ai.long
import app.pocketagent.ai.obj
import app.pocketagent.ai.parseObject
import app.pocketagent.ai.str
import app.pocketagent.data.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The bridge between a person's own linked devices: a phone can use tools that only exist on their computer
 * (a program run as an MCP server, or a server whose token is kept there).
 *
 * It rides on the pairing that sync already uses: the same room, the same end-to-end encryption, so the relay
 * sees nothing of it and there is no new way in. On top of that:
 *  - a computer lends nothing unless the person turns the bridge on there, and then only the servers they mark;
 *  - the asking device shows every call to the person first, like any tool that leaves the device;
 *  - a call names a server and a tool; it cannot run a command, read a file or reach anything that was not lent;
 *  - calls are counted (no more than [MAX_PER_MINUTE] a minute), each is used once, an old one is refused,
 *    and every one is written to the lending computer's activity log.
 * See design/phone-to-computer-bridge.md for the reasoning and the risks that remain.
 */
class Bridge(private val g: Graph) : OutsideTools {
    /** One tool a linked computer lends. */
    class Lent(val device: String, val deviceName: String, val serverId: String, val server: String, val tool: String, val description: String, val schema: String)

    private val _lent = MutableStateFlow<Map<String, List<Lent>>>(emptyMap())
    /** What each linked computer lends, by its device id. */
    val lent: StateFlow<Map<String, List<Lent>>> = _lent

    private val waiting = ConcurrentHashMap<String, CompletableFuture<Pair<Boolean, String>>>()
    private val seen = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Long>() { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 300 })
    private val recent = java.util.ArrayDeque<Long>()

    // ---- the asking side

    private fun named(): Map<String, Lent> {
        val here = g.sync.status.value.peers.toSet()
        val out = LinkedHashMap<String, Lent>()
        for ((device, tools) in _lent.value.toSortedMap()) if (device in here) for (t in tools) {
            val base = ("pc_" + slug(t.server).take(18) + "_" + slug(t.tool)).take(60)
            var name = base
            var n = 2
            while (name in out) name = base.take(57) + "_" + n++
            out[name] = t
        }
        return out
    }

    override fun specs(): List<ToolSpec> = if (app.pocketagent.ai.Net.offline) emptyList() else named().map { (name, t) ->
        ToolSpec(
            name, ("Runs on the person's own computer \"${t.deviceName}\", through the server \"${t.server}\" there. " + t.description).take(1024),
            Risk.OUTWARD, emptyList(), JsonObject(emptyMap()), schema = Mcp.forModel(parseObject(t.schema)),
        )
    }

    override fun handles(name: String) = name.startsWith("pc_")
    override fun origin(name: String): Pair<String, String>? = named()[name]?.let { "${it.deviceName} (${it.server})" to it.tool }
    override fun describe(name: String): String? = if (!handles(name)) null else named()[name]?.let { "${it.deviceName}: ${it.tool}" } ?: "A tool on a computer that is not connected"

    /** Sends the call to the computer and waits for its answer. Runs on a worker thread; the person has already said yes. */
    override fun run(name: String, input: JsonObject): String {
        val t = named()[name] ?: throw ToolError("That computer is not connected right now. Open My PAI on it and try again.")
        val id = UUID.randomUUID().toString()
        val answer = CompletableFuture<Pair<Boolean, String>>()
        waiting[id] = answer
        try {
            g.sync.sendTo { put("t", "bc"); put("to", t.device); put("id", id); put("at", System.currentTimeMillis()); put("server", t.serverId); put("tool", t.tool); put("args", input) }
            val (ok, text) = try {
                answer.get(CALL_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw ToolError("${t.deviceName} did not answer in time. Check that My PAI is open there.")
            }
            if (!ok) throw ToolError(text)
            return text
        } finally {
            waiting.remove(id)
        }
    }

    // ---- the lending side (a computer)

    /** Tells the linked devices what this computer lends: nothing at all unless the bridge is on. */
    fun advertise(to: String? = null) {
        if (!g.platform.isDesktop) return
        g.scope.launch { announce(to) }
    }

    private suspend fun announce(to: String?) {
        // Read from the store itself, not from the copy the screens watch: that one can be a moment behind a change just made.
        val now = g.settingsStore.flow.first()
        val servers = if (now.bridge) now.mcpServers.filter { it.on && it.lend } else emptyList()
        g.sync.sendTo {
            put("t", "bt"); if (to != null) put("to", to); put("name", g.platform.deviceName)
            putJsonArray("tools") {
                servers.forEach { s -> s.tools.filter { it.on }.take(MAX_TOOLS).forEach { t ->
                    add(buildJsonObject { put("sid", s.id); put("server", s.name); put("tool", t.name); put("d", t.description.take(600)); put("schema", t.schema.take(20_000)) })
                } }
            }
        }
    }

    /** Asks the linked computers what they lend. */
    fun ask() { g.sync.sendTo { put("t", "bq") } }

    /** A message of the bridge, already decrypted by sync. [from] is the device that sent it. */
    suspend fun handle(m: JsonObject, from: String) {
        when (m.str("t")) {
            "bq" -> if (g.platform.isDesktop) announce(from)
            "bt" -> {
                val name = m.str("name").orEmpty().take(60).ifBlank { "Computer" }
                val tools = m.arr("tools").orEmpty().mapNotNull { it.asObj() }.take(MAX_TOOLS * 4).mapNotNull { o ->
                    val sid = o.str("sid") ?: return@mapNotNull null
                    val tool = o.str("tool") ?: return@mapNotNull null
                    Lent(from, name, sid, o.str("server").orEmpty().take(40), tool.take(120), o.str("d").orEmpty(), o.str("schema") ?: "{}")
                }
                _lent.update { if (tools.isEmpty()) it - from else it + (from to tools) }
            }
            "br" -> waiting[m.str("id").orEmpty()]?.complete((m.bool("ok") == true) to m.str("text").orEmpty())
            "bc" -> {
                val id = m.str("id").orEmpty()
                fun reply(ok: Boolean, text: String) = g.sync.sendTo { put("t", "br"); put("to", from); put("id", id); put("ok", ok); put("text", text.take(MAX_RESULT)) }
                val now = g.settingsStore.flow.first()
                val refusal = refuse(now, m, id)
                if (refusal != null) { reply(false, refusal); return }
                val server = now.mcpServers.first { it.id == m.str("server") }
                val tool = m.str("tool").orEmpty()
                val args = m.obj("args") ?: JsonObject(emptyMap())
                val who = now.devices.firstOrNull { it.id == from }?.name ?: "a linked device"
                g.scope.launch(Dispatchers.IO) {
                    val began = System.currentTimeMillis()
                    val result = runCatching { g.mcp.run(g.mcp.nameOf(server.id, tool) ?: throw ToolError("That tool is switched off on the computer."), args) }
                    val text = result.getOrElse { it.message ?: "The tool failed." }
                    g.activity.add(ToolCall(at = began, server = server.name, tool = tool, args = ActivityLog.args(args), ok = result.isSuccess, summary = ActivityLog.summary(text), tokens = ActivityLog.tokens(text), ms = System.currentTimeMillis() - began, from = who))
                    reply(result.isSuccess, text)
                }
            }
        }
    }

    /** Why a call is not run, or null when it may be. */
    private fun refuse(now: app.pocketagent.data.Settings, m: JsonObject, id: String): String? {
        if (!g.platform.isDesktop || !now.bridge) return "The bridge is switched off on that computer."
        if (id.length !in 8..64 || seen.put(id, System.currentTimeMillis()) != null) return "That call was already used."
        if (kotlin.math.abs(System.currentTimeMillis() - (m.long("at") ?: 0)) > MAX_AGE_MS) return "That call is too old. Check that both devices have the right time."
        val server = now.mcpServers.firstOrNull { it.id == m.str("server") }
        if (server == null || !server.on || !server.lend) return "That server is not lent to linked devices."
        if (server.tools.none { it.name == m.str("tool") && it.on }) return "That tool is switched off on the computer."
        synchronized(recent) {
            val now = System.currentTimeMillis()
            while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
            if (recent.size >= MAX_PER_MINUTE) return "Too many calls in a minute. Wait a little."
            recent.addLast(now)
        }
        return null
    }

    companion object {
        const val CALL_SECONDS = 120L
        const val MAX_PER_MINUTE = 30
        const val MAX_TOOLS = 60
        const val MAX_RESULT = 100_000
        const val MAX_AGE_MS = 120_000L
        private fun slug(s: String) = s.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_').replace(Regex("_+"), "_").ifEmpty { "x" }
    }
}
