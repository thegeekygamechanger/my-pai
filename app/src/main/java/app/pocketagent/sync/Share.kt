package app.pocketagent.sync

import app.pocketagent.Graph
import app.pocketagent.ai.RequestBuilder
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.parseObject
import app.pocketagent.ai.asObj
import app.pocketagent.ai.long
import app.pocketagent.ai.obj
import app.pocketagent.ai.str
import app.pocketagent.data.Chat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyPair
import java.util.Base64
import java.util.UUID

/**
 * Sharing one chat. The sharer gets a fresh code that leads to a room of its own; whoever opens the code
 * receives a copy of that single chat and nothing else. The chat is sent device to device while the sharer
 * keeps the share open, because the relay holds nothing to pick up later.
 */
class ShareHost(private val g: Graph, private val chatId: Long) {
    val code: String = ShareCode.new()
    private val _served = MutableStateFlow(0)
    /** How many times the chat has been handed over. */
    val served: StateFlow<Int> = _served
    private val _expired = MutableStateFlow(false)
    /** True once the code has stopped working: a short code is only safe for a short time. */
    val expired: StateFlow<Boolean> = _expired
    private var job: Job? = null

    fun start() {
        val id = "host-" + UUID.randomUUID()
        job = g.scope.launch {
            val keys = withContext(Dispatchers.Default) { ShareCode.derive(code) }
            val link = RelayLink({ relayUrl(g) }, keys.room, id, Sealer(keys.key, keys.room))
            val serving = launch {
                runCatching {
                    link.run(onOnline = {}, onOpen = {}, onPeers = {}, onMessage = { m ->
                        if (m.str("t") == "sreq" && answer(link, id, keys.key, m)) _served.value++
                    })
                }
            }
            delay(LIFETIME_MS)
            serving.cancelAndJoin()
            _expired.value = true
        }
    }

    /** Agrees a fresh key with the device that asked, then sends the chat under that key. */
    private suspend fun answer(link: RelayLink, id: String, wrap: ByteArray, req: JsonObject): Boolean {
        val rid = req.str("rid").orEmpty()
        val guestPublic = runCatching { Base64.getDecoder().decode(req.str("pub").orEmpty()) }.getOrNull() ?: return false
        if (rid.isEmpty() || rid.length > 64 || guestPublic.size !in 1..256) return false
        val pair = ShareSession.keyPair()
        val hostPublic = pair.public.encoded
        val key = ShareSession.key(pair, guestPublic, wrap, guestPublic, hostPublic, rid) ?: return false
        val box = Sealer(key, "share/$rid")
        val dao = g.db.dao()
        val chat = dao.chat(chatId) ?: return false
        val msgs = dao.messages(chatId)
        link.send(buildJsonObject { put("from", id); put("rid", rid); put("t", "skey"); put("pub", Base64.getEncoder().encodeToString(hostPublic)) })
        // Every part is numbered inside its sealed box, so none can be dropped, repeated or reordered on the way without the receiver noticing.
        var n = 0
        fun boxed(part: JsonObject) {
            val plain = JsonObject(part + ("n" to JsonPrimitive(n++))).toString().toByteArray()
            link.send(buildJsonObject { put("from", id); put("rid", rid); put("t", "sbox"); put("data", Base64.getEncoder().encodeToString(box.seal(plain))) })
        }
        boxed(buildJsonObject {
            put("t", "schat"); put("count", msgs.size); put("by", g.settings.value?.name.orEmpty())
            put("title", chat.title); put("agent", chat.agent); put("model", chat.model); put("effort", chat.effort); put("preview", chat.preview)
        })
        val sent = HashSet<String>()
        for (m in msgs) {
            ATT.findAll(g.blobs.unpack(m.content)).forEach { hit ->
                val name = File(hit.groupValues[1]).name
                val f = g.attach.file(name)
                if (sent.add(name) && f.isFile) boxed(buildJsonObject { put("t", "sfile"); put("name", name); put("data", Base64.getEncoder().encodeToString(f.readBytes())) })
            }
            boxed(buildJsonObject { put("t", "smsg"); put("m", g.sync.msgJson(m)) })
        }
        boxed(buildJsonObject { put("t", "sdone") })
        return true
    }

    suspend fun stop() { job?.cancelAndJoin(); _expired.value = true }

    companion object {
        /** How long a share code works. */
        const val LIFETIME_MS = 10 * 60_000L
        private val ATT = Regex("(att/[A-Za-z0-9._-]+)")
    }
}

object ShareClient {
    /** Fetches and saves in one go. */
    suspend fun open(g: Graph, code: String): Long? = fetch(g, code)?.let { save(g, it) }

    /**
     * Fetches the chat behind a share code without saving it yet, so the person can be asked first.
     * @return the chat, or null if the sharer could not be reached in time
     */
    suspend fun fetch(g: Graph, code: String): SharedChat? {
        val keys = withContext(Dispatchers.Default) { ShareCode.derive(code) }
        val id = "guest-" + UUID.randomUUID()
        val link = RelayLink({ relayUrl(g) }, keys.room, id, Sealer(keys.key, keys.room))
        var head: JsonObject? = null
        val msgs = ArrayList<JsonObject>()
        // Attachments wait in memory and are written only if the person agrees to open the chat.
        val files = HashMap<String, ByteArray>()
        val done = CompletableDeferred<Unit>()
        // Each request carries its own id and its own key pair, and only answers to the newest one count, so asking twice cannot mix two copies.
        var rid = ""
        var pair: KeyPair? = null
        var box: Sealer? = null
        var next = 0L
        var peerSeen = false
        fun ask() {
            rid = UUID.randomUUID().toString()
            val mine = ShareSession.keyPair()
            pair = mine; box = null; next = 0
            head = null; msgs.clear(); files.clear()
            link.send(buildJsonObject { put("from", id); put("t", "sreq"); put("rid", rid); put("pub", Base64.getEncoder().encodeToString(mine.public.encoded)) })
        }
        val job = g.scope.launch {
            runCatching {
                link.run(onOnline = {}, onOpen = {}, onPeers = { if (it.isNotEmpty()) { peerSeen = true; if (head == null) ask() } }, onMessage = { m ->
                    if (m.str("rid") == rid) when (m.str("t")) {
                        "skey" -> {
                            val mine = pair
                            val hostPublic = runCatching { Base64.getDecoder().decode(m.str("pub").orEmpty()) }.getOrNull()
                            if (box == null && mine != null && hostPublic != null) {
                                ShareSession.key(mine, hostPublic, keys.key, mine.public.encoded, hostPublic, rid)?.let { box = Sealer(it, "share/$rid") }
                            }
                        }
                        "sbox" -> {
                            val plain = runCatching { Base64.getDecoder().decode(m.str("data").orEmpty()) }.getOrNull()?.let { sealed -> box?.open(sealed) }
                            val part = plain?.let { runCatching { parseObject(String(it)) }.getOrNull() }
                            if (part != null && part.long("n") == next) {
                                next++
                                when (part.str("t")) {
                                    "schat" -> { head = part; msgs.clear() }
                                    "sfile" -> {
                                        val name = File(part.str("name").orEmpty()).name
                                        val bytes = runCatching { Base64.getDecoder().decode(part.str("data").orEmpty()) }.getOrNull()
                                        if (name.isNotEmpty() && bytes != null) files[name] = bytes
                                    }
                                    "smsg" -> part.obj("m")?.let { msgs += it }
                                    "sdone" -> if (head != null) done.complete(Unit)
                                }
                            }
                        }
                    }
                })
            }
        }
        // The link is closed whatever happens, including when the person gives up waiting.
        val got = try {
            withTimeoutOrNull(25_000) {
                // If nobody else is in the room after a few seconds, there is no share under this code: say so without the full wait.
                val early = withTimeoutOrNull(7_000) { done.await() }
                if (early == null && !peerSeen) null else done.await()
            }
        } finally { job.cancel() }
        job.join()
        val h = head
        if (got == null || h == null) return null
        return SharedChat(h, msgs.toList(), files.toMap())
    }

    /** Saves a fetched chat as a new chat on this device and returns its id. */
    suspend fun save(g: Graph, shared: SharedChat): Long {
        val h = shared.head
        val msgs = shared.messages
        if (shared.files.isNotEmpty()) withContext(Dispatchers.IO) { g.attach.dir.mkdirs(); for ((name, bytes) in shared.files) g.attach.file(name).writeBytes(bytes) }
        // Saved through the tracked database with new ids: it is this person's own copy, and it reaches their linked devices like any chat.
        val now = System.currentTimeMillis()
        val settings = g.settings.value
        // Keep the sharer's model when this device has it; otherwise use this device's usual one.
        val theirs = g.allModels().firstOrNull { it.id == h.str("model") }
        val provider = theirs?.provider ?: settings?.provider ?: "anthropic"
        // A chat that was on Auto, or whose model this device does not have, opens on Auto unless this person has picked a model of their own.
        val auto = app.pocketagent.ai.AutoModel.ID
        val model = theirs?.id ?: if (h.str("model") == auto || settings?.modelChosen != true) auto else settings.model
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

/** A chat received through a share code, held in memory until the person agrees to open it. */
class SharedChat(val head: JsonObject, val messages: List<JsonObject>, val files: Map<String, ByteArray> = emptyMap()) {
    val title: String get() = head.str("title").orEmpty().ifBlank { "Shared chat" }
    /** The sharer's name as they entered it in their app, or empty. */
    val by: String get() = head.str("by").orEmpty().trim()
}

internal fun relayUrl(g: Graph): String =
    g.settings.value?.debugSyncUrl?.takeIf { g.platform.debug && it.isNotBlank() } ?: System.getProperty("pocketagent.relay") ?: SyncEngine.RELAY
