package app.pocketagent.sync

import app.pocketagent.Graph
import app.pocketagent.ai.RequestBuilder
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.asObj
import app.pocketagent.ai.long
import app.pocketagent.ai.obj
import app.pocketagent.ai.str
import app.pocketagent.data.Chat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Sharing one chat. The sharer gets a fresh code that leads to a room of its own; whoever opens the code
 * receives a copy of that single chat and nothing else. The chat is sent device to device while the sharer
 * keeps the share open, because the relay holds nothing to pick up later.
 */
class ShareHost(private val g: Graph, private val chatId: Long) {
    val code: String = SyncCode.new()
    private val _served = MutableStateFlow(0)
    /** How many times the chat has been handed over. */
    val served: StateFlow<Int> = _served
    private var job: Job? = null

    fun start() {
        val room = SyncCode.room(code, SyncCode.SHARE)
        val id = "host-" + UUID.randomUUID()
        val link = RelayLink({ relayUrl(g) }, room, id, Sealer(SyncCode.key(code, SyncCode.SHARE), room))
        job = g.scope.launch {
            runCatching {
                link.run(onOnline = {}, onOpen = {}, onPeers = {}, onMessage = { m ->
                    if (m.str("t") == "sreq") { send(link, id, m.str("rid").orEmpty()); _served.value++ }
                })
            }
        }
    }

    private suspend fun send(link: RelayLink, id: String, rid: String) {
        val dao = g.db.dao()
        val chat = dao.chat(chatId) ?: return
        val msgs = dao.messages(chatId)
        link.send(buildJsonObject {
            put("from", id); put("rid", rid); put("t", "schat"); put("count", msgs.size)
            put("title", chat.title); put("agent", chat.agent); put("model", chat.model); put("effort", chat.effort); put("preview", chat.preview)
        })
        val sent = HashSet<String>()
        for (m in msgs) {
            ATT.findAll(g.blobs.unpack(m.content)).forEach { hit ->
                val name = File(hit.groupValues[1]).name
                val f = g.attach.file(name)
                if (sent.add(name) && f.isFile) link.send(buildJsonObject { put("from", id); put("rid", rid); put("t", "sfile"); put("name", name); put("data", Base64.getEncoder().encodeToString(f.readBytes())) })
            }
            link.send(buildJsonObject { put("from", id); put("rid", rid); put("t", "smsg"); put("m", g.sync.msgJson(m)) })
        }
        link.send(buildJsonObject { put("from", id); put("rid", rid); put("t", "sdone") })
    }

    suspend fun stop() { job?.cancelAndJoin() }

    private companion object { val ATT = Regex("\"path\":\"(att/[^\"]+)\"") }
}

object ShareClient {
    /**
     * Fetches the chat behind a share code and saves it as a new chat on this device.
     * @return the new chat's id, or null if the sharer could not be reached in time
     */
    suspend fun open(g: Graph, code: String): Long? {
        val room = SyncCode.room(code, SyncCode.SHARE)
        val id = "guest-" + UUID.randomUUID()
        val link = RelayLink({ relayUrl(g) }, room, id, Sealer(SyncCode.key(code, SyncCode.SHARE), room))
        var head: JsonObject? = null
        val msgs = ArrayList<JsonObject>()
        val done = CompletableDeferred<Unit>()
        // Each request carries its own id and only answers to the newest one count, so asking twice cannot mix two copies.
        var rid = ""
        fun ask() {
            rid = UUID.randomUUID().toString()
            head = null; msgs.clear()
            link.send(buildJsonObject { put("from", id); put("t", "sreq"); put("rid", rid) })
        }
        val job = g.scope.launch {
            runCatching {
                link.run(onOnline = {}, onOpen = {}, onPeers = { if (it.isNotEmpty() && head == null) ask() }, onMessage = { m ->
                    if (m.str("rid") == rid) when (m.str("t")) {
                        "schat" -> { head = m; msgs.clear() }
                        "sfile" -> {
                            val name = File(m.str("name").orEmpty()).name
                            val bytes = runCatching { Base64.getDecoder().decode(m.str("data").orEmpty()) }.getOrNull()
                            if (name.isNotEmpty() && bytes != null) { g.attach.dir.mkdirs(); g.attach.file(name).writeBytes(bytes) }
                        }
                        "smsg" -> m.obj("m")?.let { msgs += it }
                        "sdone" -> if (head != null) done.complete(Unit)
                    }
                })
            }
        }
        val got = withTimeoutOrNull(25_000) { done.await() }
        job.cancelAndJoin()
        val h = head
        if (got == null || h == null) return null
        // Saved through the tracked database with new ids: it is this person's own copy, and it reaches their linked devices like any chat.
        val now = System.currentTimeMillis()
        val settings = g.settings.value
        // Keep the sharer's model when this device has it; otherwise use this device's usual one.
        val theirs = g.allModels().firstOrNull { it.id == h.str("model") }
        val provider = theirs?.provider ?: settings?.provider ?: "anthropic"
        val model = theirs?.id ?: settings?.model.orEmpty()
        val chatId = g.dao.insert(Chat(
            title = h.str("title").orEmpty().ifBlank { "Shared chat" }, preview = h.str("preview").orEmpty(), createdAt = now, updatedAt = now,
            agent = h.asObj()?.get("agent")?.toString() == "true", provider = provider, model = model, effort = h.str("effort") ?: "medium", permission = settings?.permission ?: "auto_read",
        ))
        for (o in msgs.sortedBy { it.long("createdAt") ?: 0 }) {
            var m = g.sync.msgOf(o, chatId).copy(uid = "", rev = 0)
            // Reasoning blocks only work with the account that produced them, so the copy goes without.
            if (m.role == "assistant") m = m.copy(content = g.blobs.pack(chatId, JsonArray(RequestBuilder.withoutThinking(parseArray(g.blobs.unpack(m.content)))).toString()))
            g.dao.insert(m)
        }
        return chatId
    }
}

internal fun relayUrl(g: Graph): String =
    g.settings.value?.debugSyncUrl?.takeIf { g.platform.debug && it.isNotBlank() } ?: System.getProperty("pocketagent.relay") ?: SyncEngine.RELAY
