package app.pocketagent.sync

import app.pocketagent.Graph
import app.pocketagent.ai.AppJson
import app.pocketagent.ai.Decision
import app.pocketagent.ai.Integrations
import app.pocketagent.ai.LiveBlock
import app.pocketagent.ai.PendingApproval
import app.pocketagent.ai.Phase
import app.pocketagent.ai.Provider
import app.pocketagent.ai.Providers
import app.pocketagent.ai.Risk
import app.pocketagent.ai.RunState
import app.pocketagent.ai.ToolOutcome
import app.pocketagent.ai.arr
import app.pocketagent.ai.asObj
import app.pocketagent.ai.bool
import app.pocketagent.ai.long
import app.pocketagent.ai.obj
import app.pocketagent.ai.str
import app.pocketagent.data.incognito
import app.pocketagent.data.Change
import app.pocketagent.data.Chat
import app.pocketagent.data.Device
import app.pocketagent.data.Msg
import app.pocketagent.data.Tomb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SyncStatus(val enabled: Boolean = false, val online: Boolean = false, val peers: List<String> = emptyList(), val error: String = "")

/**
 * Keeps this device and the ones linked with it the same, live. Devices talk to each other through the relay;
 * nothing is kept there, so what a device missed while it was away it gets from the others when it is back.
 *
 * How it stays simple: every chat and message has a global id and a "last changed" time. A change is sent to
 * the others the moment it happens. When a device connects it also sends a list of what it has, and the others
 * answer with what it lacks and ask for what they lack. The newer copy of a row always wins; a delete is
 * remembered so that it wins over older copies still held elsewhere.
 */
class SyncEngine(private val g: Graph) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val _remoteRuns = MutableStateFlow<Map<Long, RunState>>(emptyMap())
    /** Runs happening on linked devices, keyed by this device's chat id. */
    val remoteRuns: StateFlow<Map<Long, RunState>> = _remoteRuns

    private val raw get() = g.db.dao()
    private var link: RelayLink? = null
    private var job: Job? = null
    private val lifecycle = Mutex()
    private val changes = Channel<Change>(Channel.UNLIMITED)
    private val sentFiles = ConcurrentHashMap.newKeySet<String>()
    private val remoteSeen = ConcurrentHashMap<Long, Long>()
    private var fileScan: Map<String, Pair<Long, Long>> = emptyMap()
    @Volatile private var filesDirty = true

    fun code(): String? = g.vault.read(CODE)
    private val me: String get() = g.settings.value?.deviceId.orEmpty()
    private suspend fun settings() = g.settingsStore.flow.first()
    private fun url(): String = g.settings.value?.debugSyncUrl?.takeIf { g.platform.debug && it.isNotBlank() } ?: System.getProperty("pocketagent.relay") ?: RELAY

    /** Called once when the app starts. */
    suspend fun start() {
        if (settings().deviceId.isEmpty()) g.settingsStore.update { it.copy(deviceId = UUID.randomUUID().toString()) }
        g.scope.launch {
            // One reply touches its chat several times in a row; gather for a moment and send each thing once.
            for (first in changes) {
                val batch = LinkedHashSet<Change>().apply { add(first) }
                delay(60)
                while (true) batch.add(changes.tryReceive().getOrNull() ?: break)
                for (c in batch) runCatching { pushChange(c) }
            }
        }
        g.scope.launch { mirrorRuns() }
        g.scope.launch {
            var ticks = 0
            while (true) {
                delay(4000); expireRemoteRuns()
                if (!peersOnline()) continue
                runCatching { pushFileChanges() }
                // Now and then the devices compare notes again, so anything that slipped through is put right.
                if (++ticks % 12 == 0) runCatching { sendInventory() }
            }
        }
        if (code() != null) connect()
    }

    private fun peersOnline() = _status.value.online && _status.value.peers.isNotEmpty()
    private fun self(s: app.pocketagent.data.Settings) = Device(s.deviceId, g.platform.deviceName, g.platform.system, System.currentTimeMillis())

    /** Turns sync on for the first device and returns the code the others join with. */
    suspend fun enable(): String {
        code()?.let { return it }
        val code = SyncCode.new()
        g.vault.write(CODE, code)
        g.settingsStore.update { s -> s.copy(devices = listOf(self(s))) }
        connect()
        return code
    }

    /** Links this device using a code typed or scanned from another one. False if it is not a sync code. */
    suspend fun join(input: String): Boolean {
        val (code, purpose) = SyncCode.parse(input) ?: return false
        if (purpose == SyncCode.SHARE) return false
        disconnect()
        g.vault.write(CODE, code)
        g.settingsStore.update { s -> s.copy(devices = listOf(self(s))) }
        connect()
        return true
    }

    /** Unlinks this device. Its own chats stay on it; it just stops exchanging them. */
    suspend fun leave(error: String = "") {
        disconnect()
        g.vault.write(CODE, null)
        g.settingsStore.update { it.copy(devices = emptyList()) }
        _status.value = SyncStatus(error = error)
    }

    private fun leaveLater(error: String) { g.scope.launch { leave(error) } }

    /** Takes another device off the list. It unlinks itself the next time it is online. */
    suspend fun removeDevice(id: String) {
        g.settingsStore.update { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(removed = true) else it }) }
        sendRoster()
    }

    private suspend fun disconnect() = lifecycle.withLock {
        job?.cancelAndJoin(); job = null; link = null
        _remoteRuns.value = emptyMap()
        _status.update { it.copy(online = false, peers = emptyList()) }
    }

    private suspend fun connect() = lifecycle.withLock {
        val code = code() ?: return@withLock
        job?.cancelAndJoin()
        val room = SyncCode.room(code, SyncCode.SYNC)
        val l = RelayLink(::url, room, me, Sealer(SyncCode.key(code, SyncCode.SYNC), room))
        link = l
        sentFiles.clear()
        _status.value = SyncStatus(enabled = true)
        job = g.scope.launch {
            try {
                l.run(
                    onOnline = { on -> _status.update { it.copy(online = on, peers = if (on) it.peers else emptyList(), error = "") } },
                    onOpen = { sendHello(); sendInventory(); g.bridge.advertise(); g.bridge.ask() },
                    onPeers = { ids ->
                        val before = _status.value.peers
                        _status.update { it.copy(peers = ids) }
                        // Someone new is here: tell them who we are and what we have.
                        if (ids.any { it !in before }) { sendHello(); sendInventory(); g.bridge.advertise(); g.bridge.ask() }
                    },
                    onMessage = { m -> runCatching { handle(m) } },
                )
            } catch (e: RelayLink.RoomFull) {
                // Leaving cancels this very job, so it is started from outside it.
                leaveLater("Five devices are already linked. Remove one on another device, then try again.")
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun send(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit, droppable: Boolean = false) {
        val l = link ?: return
        l.send(buildJsonObject { put("from", me); build() }, droppable)
    }

    /** For the bridge between linked devices: a message that travels in the same sealed room as everything else. */
    fun sendTo(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = send(build)

    // ---- who is linked

    private suspend fun sendHello() {
        val s = settings()
        send({ put("t", "hello"); put("dev", deviceJson(s.devices.firstOrNull { it.id == s.deviceId } ?: self(s))) })
    }

    private fun deviceJson(d: Device) = buildJsonObject { put("id", d.id); put("name", d.name); put("system", d.system); put("at", d.addedAt); put("removed", d.removed) }
    private fun deviceOf(o: JsonObject) = Device(o.str("id").orEmpty(), o.str("name").orEmpty(), o.str("system").orEmpty(), o.long("at") ?: 0, o.bool("removed") == true)

    private suspend fun sendRoster() {
        val s = settings()
        send({ put("t", "roster"); putJsonArray("devices") { s.devices.forEach { add(deviceJson(it)) } } })
    }

    /** Merges another device's list into ours. A removal sticks; beyond five, the most recently added lose their place. */
    private suspend fun mergeRoster(incoming: List<Device>) {
        var mine = false
        g.settingsStore.update { s ->
            val byId = LinkedHashMap<String, Device>()
            for (d in s.devices + incoming) {
                if (d.id.isEmpty()) continue
                val old = byId[d.id]
                byId[d.id] = if (old == null) d else old.copy(removed = old.removed || d.removed, addedAt = if (old.addedAt == 0L) d.addedAt else minOf(old.addedAt, d.addedAt.takeIf { it > 0 } ?: old.addedAt), name = d.name.ifEmpty { old.name })
            }
            val active = byId.values.filter { !it.removed }.sortedBy { it.addedAt }
            val over = active.drop(MAX_DEVICES).map { it.id }.toSet()
            val merged = byId.values.map { if (it.id in over) it.copy(removed = true) else it }
            mine = merged.firstOrNull { it.id == s.deviceId }?.removed == true
            s.copy(devices = merged)
        }
        if (mine) leaveLater("This device was removed from sync, or five devices are already linked.")
    }

    // ---- rows on the wire

    private fun chatJson(c: Chat) = buildJsonObject {
        put("uid", c.uid); put("rev", c.rev); put("title", c.title); put("preview", c.preview); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
        put("pinned", c.pinned); put("agent", c.agent); put("provider", c.provider); put("model", c.model); put("effort", c.effort); put("web", c.web); put("code", c.code)
        put("memory", c.memory); put("fast", c.fast); put("permission", c.permission); put("inTok", c.inTok); put("outTok", c.outTok)
        put("cacheReadTok", c.cacheReadTok); put("cacheWriteTok", c.cacheWriteTok); put("costMicros", c.costMicros); put("prefixFp", c.prefixFp); put("tasksJson", c.tasksJson)
    }

    private fun chatOf(o: JsonObject, id: Long = 0) = Chat(
        id = id, uid = o.str("uid").orEmpty(), rev = o.long("rev") ?: 0, title = o.str("title").orEmpty(), preview = o.str("preview").orEmpty(),
        createdAt = o.long("createdAt") ?: 0, updatedAt = o.long("updatedAt") ?: 0, pinned = o.bool("pinned") == true, agent = o.bool("agent") == true,
        provider = o.str("provider") ?: "anthropic", model = o.str("model").orEmpty(), effort = o.str("effort") ?: "medium", web = o.bool("web") == true, code = o.bool("code") == true,
        memory = o.bool("memory") != false, fast = o.bool("fast") == true, permission = o.str("permission") ?: "auto_read", inTok = o.long("inTok") ?: 0,
        outTok = o.long("outTok") ?: 0, cacheReadTok = o.long("cacheReadTok") ?: 0, cacheWriteTok = o.long("cacheWriteTok") ?: 0,
        costMicros = o.long("costMicros") ?: 0, prefixFp = o.str("prefixFp").orEmpty(), tasksJson = o.str("tasksJson").orEmpty(),
    )

    fun msgJson(m: Msg) = buildJsonObject {
        put("uid", m.uid); put("rev", m.rev); put("role", m.role); put("content", g.blobs.unpack(m.content)); put("createdAt", m.createdAt)
        put("apiId", m.apiId); put("model", m.model); put("stopReason", m.stopReason); put("inTok", m.inTok); put("outTok", m.outTok)
    }

    fun msgOf(o: JsonObject, chatId: Long, id: Long = 0) = Msg(
        id = id, uid = o.str("uid").orEmpty(), rev = o.long("rev") ?: 0, chatId = chatId, role = o.str("role") ?: "note",
        content = g.blobs.pack(chatId, o.str("content").orEmpty()), createdAt = o.long("createdAt") ?: 0, apiId = o.str("apiId").orEmpty(),
        model = o.str("model").orEmpty(), stopReason = o.str("stopReason").orEmpty(), inTok = o.long("inTok") ?: 0, outTok = o.long("outTok") ?: 0,
    )

    /** A chat that was opened and never used is not worth telling anyone about. */
    private fun worthSending(c: Chat) = c.uid.isNotEmpty() && c.title.isNotBlank() && !c.incognito

    private fun sendChat(c: Chat) { if (c.uid.isNotEmpty()) send({ put("t", "chat"); put("c", chatJson(c)) }) }

    private fun sendMsg(chat: Chat, m: Msg) {
        if (m.uid.isEmpty()) return
        // Attachments named in the message travel first, so the message can be shown as soon as it lands.
        ATT.findAll(g.blobs.unpack(m.content)).forEach { sendFile("att", File(it.groupValues[1]).name) }
        send({ put("t", "msg"); put("cu", chat.uid); put("m", msgJson(m)) })
    }

    private fun dirOf(scope: String): File? = when (scope) {
        "ws" -> g.workspace.root
        "mem" -> g.memory.root
        "att" -> g.attach.dir
        else -> null
    }

    /** A path that stays inside [root], or null. */
    private fun safe(root: File, path: String): File? {
        val f = File(root, path).canonicalFile
        val base = root.canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun sendFile(scope: String, path: String, force: Boolean = false) {
        val f = safe(dirOf(scope) ?: return, path) ?: return
        if (!f.isFile || f.length() > MAX_FILE) return
        val stamp = "$scope:$path:${f.length()}:${f.lastModified()}"
        if (!force && !sentFiles.add(stamp)) return
        send({ put("t", "file"); put("scope", scope); put("path", path); put("mtime", f.lastModified()); put("data", Base64.getEncoder().encodeToString(f.readBytes())) })
    }

    // ---- sending what changed here

    fun localChange(change: Change) { changes.trySend(change) }
    fun filesMayHaveChanged() { filesDirty = true }

    private suspend fun pushChange(change: Change) {
        if (!peersOnline()) return
        when (change) {
            is Change.ChatSaved -> raw.chat(change.id)?.takeIf(::worthSending)?.let(::sendChat)
            is Change.MsgSaved -> {
                val chat = raw.chat(change.chatId) ?: return
                if (chat.incognito) return
                val msg = raw.messages(change.chatId).firstOrNull { it.id == change.id } ?: return
                sendChat(chat)
                sendMsg(chat, msg)
                filesDirty = true
            }
            is Change.Deleted -> send({ put("t", "del"); put("kind", change.kind); put("at", System.currentTimeMillis()); putJsonArray("uids") { change.uids.forEach { add(it) } } })
        }
    }

    fun sharedSettingsChanged() { g.scope.launch { if (peersOnline()) sendShared() } }

    private suspend fun sendShared() {
        val s = settings()
        send({
            put("t", "set"); put("rev", s.sharedRev); put("instr", s.customInstructions); put("name", s.name); put("role", s.role); put("style", s.style); put("city", s.city)
            put("provider", s.provider); put("model", s.model)
            put("custom", AppJson.encodeToString(ListSerializer(Provider.serializer()), s.customProviders))
            putJsonObject("keys") { g.providers().forEach { p -> g.keyFor(p.id)?.let { put(p.id, it) } } }
            putJsonObject("conn") { Integrations.SECRETS.forEach { n -> g.vault.read(n)?.let { put(n, it) } } }
            // MCP servers: names and addresses only. Their tokens stay in each device's own vault and are typed in again there.
            put("mcp", app.pocketagent.ai.McpSync.pack(s.mcpServers, s.mcpGone))
        })
    }

    // ---- files the agent wrote, and its memory notes

    private fun scan(): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        for (scope in listOf("ws", "mem")) {
            val root = dirOf(scope) ?: continue
            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp~") }.take(MAX_FILES).forEach { f ->
                out["$scope:" + f.relativeTo(root).invariantSeparatorsPath] = f.length() to f.lastModified()
            }
        }
        return out
    }

    private suspend fun pushFileChanges() {
        if (!filesDirty && fileScan.isNotEmpty()) { /* still look: a file can change outside the app */ }
        val now = scan()
        val before = fileScan
        fileScan = now
        filesDirty = false
        if (before.isEmpty() && now.isNotEmpty() && sentFiles.isEmpty()) return // first look after connecting: the inventory covers it
        for ((key, stamp) in now) if (before[key] != stamp) sendFile(key.substringBefore(':'), key.substringAfter(':'))
        val gone = before.keys - now.keys
        if (gone.isNotEmpty()) {
            val at = System.currentTimeMillis()
            gone.forEach { raw.putTomb(Tomb("file:$it", "file", at)) }
            send({ put("t", "fdel"); put("at", at); putJsonArray("keys") { gone.forEach { add(it) } } })
        }
    }

    // ---- catching up after being away

    private suspend fun sendInventory(to: String? = null) {
        val s = settings()
        fileScan = scan()
        val chats = raw.allChats().filter(::worthSending)
        val stamps = chats.associate { it.id to raw.msgStamps(it.id) }
        val tombs = raw.tombs()
        send({
            put("t", "inv")
            if (to != null) put("to", to)
            put("sr", s.sharedRev)
            putJsonArray("chats") {
                chats.forEach { c ->
                    add(buildJsonObject {
                        put("u", c.uid); put("r", c.rev)
                        putJsonArray("m") { stamps[c.id].orEmpty().forEach { st -> add(buildJsonArray { add(st.uid); add(st.rev) }) } }
                    })
                }
            }
            putJsonArray("tombs") { tombs.forEach { t -> add(buildJsonArray { add(t.uid); add(t.kind); add(t.at) }) } }
            putJsonArray("files") { fileScan.forEach { (k, v) -> add(buildJsonArray { add(k); add(v.first); add(v.second) }) } }
        })
        if (to == null) sendRoster()
    }

    /** What was asked for or sent a moment ago, so that with several devices each thing still travels once. */
    private val recent = ConcurrentHashMap<String, Long>()
    private fun firstTime(key: String, withinMs: Long = 6000): Boolean {
        val now = System.currentTimeMillis()
        if (recent.size > 20_000) recent.entries.removeIf { now - it.value > 30_000 }
        val last = recent[key]
        if (last != null && now - last < withinMs) return false
        recent[key] = now
        return true
    }

    /**
     * Another device told us what it has. We ask it for what we lack. We do not push what it lacks: we answer
     * with our own list instead and let it ask, so that with several devices online it gets each thing once.
     */
    private suspend fun answerInventory(inv: JsonObject, from: String) {
        val theirTombs = inv.arr("tombs").orEmpty().mapNotNull { (it as? JsonArray)?.let { a -> Tomb(a.s(0), a.s(1), a.l(2)) } }
        theirTombs.forEach { applyTomb(it) }
        val theirTombIds = theirTombs.associate { it.uid to it.at }
        val theirs = inv.arr("chats").orEmpty().mapNotNull { it.asObj() }.associateBy { it.str("u").orEmpty() }
        val wantChats = ArrayList<String>()
        val wantMsgs = ArrayList<String>()
        for ((uid, t) in theirs) {
            if (raw.tomb(uid)?.let { it.at >= (t.long("r") ?: 0) } == true) continue
            val local = raw.chatByUid(uid)
            if (local == null) { if (firstTime("want:$uid")) wantChats += uid; continue }
            if (local.rev < (t.long("r") ?: 0) && firstTime("want:$uid:${t.long("r")}")) wantChats += uid
            val mine = raw.msgStamps(local.id).associate { it.uid to it.rev }
            for (m in t.arr("m").orEmpty().mapNotNull { it as? JsonArray }) {
                val uid2 = m.s(0); val rev = m.l(1)
                if ((mine[uid2] ?: -1) < rev && raw.tomb(uid2) == null && uid !in wantChats && firstTime("want:$uid2:$rev")) wantMsgs += uid2
            }
        }
        // Deletes they have not heard about.
        val myTombs = raw.tombs().filter { it.uid !in theirTombIds && firstTime("tomb:${it.uid}") }
        myTombs.filter { it.kind != "file" }.groupBy { it.kind }.forEach { (kind, list) ->
            send({ put("t", "del"); put("kind", kind); put("at", list.maxOf { it.at }); putJsonArray("uids") { list.forEach { add(it.uid) } } })
        }
        myTombs.filter { it.kind == "file" }.takeIf { it.isNotEmpty() }?.let { list ->
            send({ put("t", "fdel"); put("at", list.maxOf { it.at }); putJsonArray("keys") { list.forEach { add(it.uid.removePrefix("file:")) } } })
        }
        // Files.
        val theirFiles = inv.arr("files").orEmpty().mapNotNull { it as? JsonArray }.associate { it.s(0) to (it.l(1) to it.l(2)) }
        val mineFiles = scan()
        val wantFiles = ArrayList<String>()
        for ((key, stamp) in theirFiles) {
            val m = mineFiles[key]
            val tomb = raw.tomb("file:$key")
            if (tomb != null && tomb.at >= stamp.second) continue
            if ((m == null || m.second < stamp.second) && firstTime("want:file:$key:${stamp.second}")) wantFiles += key
        }
        // The keys and standing instructions.
        val s = settings()
        val theirShared = inv.long("sr") ?: 0
        if (s.sharedRev > theirShared && firstTime("set:${s.sharedRev}", 3000)) sendShared()
        val wantSet = theirShared > s.sharedRev && firstTime("want:set:$theirShared")
        if (wantChats.isNotEmpty() || wantMsgs.isNotEmpty() || wantFiles.isNotEmpty() || wantSet) send({
            put("t", "want"); put("to", from); put("set", wantSet)
            putJsonArray("chats") { wantChats.forEach { add(it) } }
            putJsonArray("msgs") { wantMsgs.forEach { add(it) } }
            putJsonArray("files") { wantFiles.forEach { add(it) } }
        })
        // A list that was sent to everyone (someone just came online) is answered with ours, to them alone.
        if (inv.str("to") == null) sendInventory(to = from)
    }

    private suspend fun answerWant(w: JsonObject, from: String) {
        // Remembered per asker: two devices that each lack something must both be served.

        for (uid in w.arr("chats").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }) {
            val c = raw.chatByUid(uid) ?: continue
            if (!firstTime("sent:$from:$uid:${c.rev}", 2000)) continue
            sendChat(c)
            raw.messages(c.id).forEach { sendMsg(c, it) }
        }
        for (uid in w.arr("msgs").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }) {
            val m = raw.msgByUid(uid) ?: continue
            if (firstTime("sent:$from:${m.uid}:${m.rev}", 2000)) raw.chat(m.chatId)?.let { sendMsg(it, m) }
        }
        for (key in w.arr("files").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }) {
            if (firstTime("sent:$from:file:$key", 2000)) sendFile(key.substringBefore(':'), key.substringAfter(':'), force = true)
        }
        if (w.bool("set") == true && firstTime("set:" + settings().sharedRev, 3000)) sendShared()
    }

    private fun JsonArray.s(i: Int): String = (getOrNull(i) as? JsonPrimitive)?.content.orEmpty()
    private fun JsonArray.l(i: Int): Long = (getOrNull(i) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0

    // ---- receiving

    private suspend fun handle(m: JsonObject) {
        val from = m.str("from").orEmpty()
        if (from == me) return
        m.str("to")?.let { if (it != me) return }
        when (m.str("t")) {
            "hello" -> { m.obj("dev")?.let { mergeRoster(listOf(deviceOf(it))) }; sendRoster() }
            "roster" -> mergeRoster(m.arr("devices").orEmpty().mapNotNull { it.asObj()?.let(::deviceOf) })
            "inv" -> answerInventory(m, from)
            "want" -> answerWant(m, from)
            "chat" -> m.obj("c")?.let { applyChat(it) }
            "msg" -> applyMsg(m.str("cu").orEmpty(), m.obj("m") ?: return, from)
            "del" -> { val at = m.long("at") ?: 0; val kind = m.str("kind") ?: "msg"; m.arr("uids").orEmpty().forEach { (it as? JsonPrimitive)?.content?.let { uid -> applyTomb(Tomb(uid, kind, at)) } } }
            "file" -> applyFile(m)
            "fdel" -> { val at = m.long("at") ?: 0; m.arr("keys").orEmpty().forEach { (it as? JsonPrimitive)?.content?.let { key -> applyTomb(Tomb("file:$key", "file", at)) } } }
            "set" -> applyShared(m)
            "live" -> applyLive(m)
            "liveend" -> raw.chatByUid(m.str("cu").orEmpty())?.let { c -> remoteSeen.remove(c.id); _remoteRuns.update { it - c.id } }
            "stop" -> raw.chatByUid(m.str("cu").orEmpty())?.let { g.runs.stop(it.id) }
            "bt", "bq", "bc", "br" -> g.bridge.handle(m, from)
            "decide" -> raw.chatByUid(m.str("cu").orEmpty())?.let { c -> runCatching { Decision.valueOf(m.str("d").orEmpty()) }.getOrNull()?.let { g.runs.decide(c.id, it) } }
        }
    }

    private suspend fun applyChat(o: JsonObject): Chat? {
        val uid = o.str("uid").orEmpty()
        if (uid.isEmpty()) return null
        val rev = o.long("rev") ?: 0
        raw.tomb(uid)?.let { if (it.at >= rev) return null }
        val local = raw.chatByUid(uid)
        return when {
            local == null -> raw.insert(chatOf(o)).let { raw.chat(it) }
            local.rev < rev -> chatOf(o, local.id).also { raw.update(it) }
            else -> local
        }
    }

    private suspend fun applyMsg(chatUid: String, o: JsonObject, from: String) {
        val uid = o.str("uid").orEmpty()
        if (uid.isEmpty()) return
        val rev = o.long("rev") ?: 0
        raw.tomb(uid)?.let { if (it.at >= rev) return }
        val chat = raw.chatByUid(chatUid)
        if (chat == null) {
            // The chat itself has not arrived (or was skipped): ask the sender for all of it.
            if (raw.tomb(chatUid) == null) send({ put("t", "want"); put("to", from); putJsonArray("chats") { add(chatUid) } })
            return
        }
        val local = raw.msgByUid(uid)
        when {
            local == null -> raw.insert(msgOf(o, chat.id))
            local.rev < rev -> raw.update(msgOf(o, chat.id, local.id))
        }
    }

    private suspend fun applyTomb(t: Tomb) {
        val known = raw.tomb(t.uid)
        if (known == null || known.at < t.at) raw.putTomb(t)
        when (t.kind) {
            "chat" -> raw.chatByUid(t.uid)?.let { c -> if (c.rev <= t.at) { g.runs.stop(c.id); g.blobs.deleteFor(c.id); raw.deleteChat(c.id) } }
            "msg" -> raw.msgByUid(t.uid)?.let { m -> if (m.rev <= t.at) raw.deleteMsg(m.id) }
            "file" -> {
                val key = t.uid.removePrefix("file:")
                val f = safe(dirOf(key.substringBefore(':')) ?: return, key.substringAfter(':')) ?: return
                if (f.isFile && f.lastModified() <= t.at) { f.delete(); fileScan = fileScan - key }
            }
        }
    }

    private fun applyFile(m: JsonObject) {
        val scope = m.str("scope").orEmpty()
        val path = m.str("path").orEmpty()
        val f = safe(dirOf(scope) ?: return, path) ?: return
        val mtime = m.long("mtime") ?: 0
        val bytes = runCatching { Base64.getDecoder().decode(m.str("data").orEmpty()) }.getOrNull() ?: return
        if (scope != "att" && f.isFile && f.lastModified() >= mtime) return
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        if (mtime > 0) f.setLastModified(mtime)
        if (scope != "att") {
            // Record it as already known, so the next look at the folder does not send it straight back.
            fileScan = fileScan + ("$scope:$path" to (f.length() to f.lastModified()))
            sentFiles.add("$scope:$path:${f.length()}:${f.lastModified()}")
        }
    }

    private suspend fun applyShared(m: JsonObject) {
        val rev = m.long("rev") ?: 0
        // The list of MCP servers is settled server by server, whichever device's other settings are the newer.
        val ahead = m.str("mcp")?.let { g.mergeMcp(it) } == true
        val s = settings()
        if (rev <= s.sharedRev) return
        val custom = m.str("custom")?.let { runCatching { AppJson.decodeFromString(ListSerializer(Provider.serializer()), it) }.getOrNull() } ?: s.customProviders
        val keys = m.obj("keys").orEmpty()
        // The sender's set of keys is the whole truth: a key removed there is removed here too.
        for (p in Providers.PRESETS + custom + s.customProviders) {
            val k = (keys[p.id] as? JsonPrimitive)?.content
            g.vault.write(Providers.vaultKey(p.id), k?.takeIf { it.isNotBlank() })
        }
        m.obj("conn")?.let { conn -> Integrations.SECRETS.forEach { n -> g.vault.write(n, (conn[n] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }) } }
        g.settingsStore.update {
            it.copy(
                customInstructions = m.str("instr") ?: it.customInstructions, name = m.str("name") ?: it.name, role = m.str("role") ?: it.role, style = m.str("style") ?: it.style, city = m.str("city") ?: it.city,
                provider = m.str("provider") ?: it.provider, model = m.str("model") ?: it.model, customProviders = custom, sharedRev = rev, onboarded = it.onboarded || keys.isNotEmpty(),
            )
        }
        // This device knew of a server, or of a removal, that the sender did not: send the settled list back.
        if (ahead) { g.settingsStore.update { it.copy(sharedRev = System.currentTimeMillis()) }; sendShared() }
        // Fetch model lists for services that just arrived, in the background.
        g.scope.launch { g.connected().filter { g.models(it.id).isEmpty() || it.kind != "anthropic" && g.settings.value?.models?.containsKey(it.id) != true }.forEach { p -> runCatching { g.refreshModels(p) } } }
    }

    // ---- showing a run that is happening on another device

    private suspend fun mirrorRuns() {
        var sent = emptyMap<Long, Pair<RunState, Long>>()
        while (true) {
            delay(350)
            val runs = g.runs.runs.value
            if (!peersOnline()) { sent = emptyMap(); continue }
            val now = System.currentTimeMillis()
            val next = HashMap<Long, Pair<RunState, Long>>()
            for ((id, run) in runs) {
                val last = sent[id]
                if (last != null && last.first == run && now - last.second < 4000) { next[id] = last; continue }
                val chat = raw.chat(id) ?: continue
                if (chat.uid.isEmpty()) continue
                send({ put("t", "live"); put("cu", chat.uid); put("dev", g.platform.deviceName); put("s", runJson(run)) }, droppable = true)
                next[id] = run to now
            }
            for (id in sent.keys - runs.keys) raw.chat(id)?.let { c -> send({ put("t", "liveend"); put("cu", c.uid) }) }
            sent = next
        }
    }

    private fun runJson(r: RunState) = buildJsonObject {
        put("phase", r.phase.name); put("apiId", r.apiId); put("step", r.step); put("tokens", r.tokens); put("status", r.status); put("runningTool", r.runningTool)
        putJsonArray("live") {
            r.live.forEach { b ->
                add(buildJsonObject {
                    put("type", b.type); put("text", b.text); put("name", b.name); put("id", b.id); put("input", b.inputPreview); put("done", b.done)
                    b.raw?.let { put("raw", it) }
                })
            }
        }
        r.pending?.let { p -> putJsonObject("pending") { put("id", p.toolUseId); put("name", p.name); put("title", p.title); put("input", p.input); put("risk", p.risk.name) } }
        putJsonObject("done") { r.done.forEach { (id, o) -> putJsonObject(id) { put("text", o.text.take(6000)); put("err", o.isError) } } }
    }

    private suspend fun applyLive(m: JsonObject) {
        val chat = raw.chatByUid(m.str("cu").orEmpty()) ?: return
        if (g.runs.isRunning(chat.id)) return
        val s = m.obj("s") ?: return
        val state = RunState(
            chatId = chat.id,
            phase = runCatching { Phase.valueOf(s.str("phase").orEmpty()) }.getOrDefault(Phase.CONNECTING),
            apiId = s.str("apiId").orEmpty(), step = (s.long("step") ?: 1).toInt(), tokens = s.long("tokens") ?: 0, status = s.str("status").orEmpty(),
            runningTool = s.str("runningTool").orEmpty(),
            live = s.arr("live").orEmpty().mapNotNull { it.asObj() }.map { b ->
                LiveBlock(b.str("type").orEmpty(), b.str("text").orEmpty(), b.str("name").orEmpty(), b.str("id").orEmpty(), b.str("input").orEmpty(), b.bool("done") == true, b.obj("raw"))
            },
            pending = s.obj("pending")?.let { p ->
                PendingApproval(p.str("id").orEmpty(), p.str("name").orEmpty(), p.str("title").orEmpty(), p.obj("input") ?: JsonObject(emptyMap()), runCatching { Risk.valueOf(p.str("risk").orEmpty()) }.getOrDefault(Risk.WRITE))
            },
            done = s.obj("done").orEmpty().mapNotNull { (id, v) -> (v as? JsonObject)?.let { id to ToolOutcome(it.str("text").orEmpty(), it.bool("err") == true) } }.toMap(),
            remote = m.str("dev") ?: "another device",
        )
        remoteSeen[chat.id] = System.currentTimeMillis()
        _remoteRuns.update { it + (chat.id to state) }
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    /** A device that goes away mid-run cannot say so; its run is dropped here after a short silence. */
    private fun expireRemoteRuns() {
        val cutoff = System.currentTimeMillis() - 12_000
        val stale = remoteSeen.filterValues { it < cutoff }.keys
        if (stale.isNotEmpty()) { stale.forEach(remoteSeen::remove); _remoteRuns.update { it - stale } }
        if (!_status.value.online && _remoteRuns.value.isNotEmpty()) { remoteSeen.clear(); _remoteRuns.value = emptyMap() }
    }

    fun sendStop(chatId: Long) { g.scope.launch { raw.chat(chatId)?.let { c -> send({ put("t", "stop"); put("cu", c.uid) }) } } }
    fun sendDecision(chatId: Long, d: Decision) { g.scope.launch { raw.chat(chatId)?.let { c -> send({ put("t", "decide"); put("cu", c.uid); put("d", d.name) }) } } }

    companion object {
        const val RELAY = "https://pocketagent.patienceai.in"
        const val MAX_DEVICES = 5
        const val MAX_FILE = 8L * 1024 * 1024
        const val MAX_FILES = 2000
        private const val CODE = "sync_code"
        private val ATT = Regex("(att/[A-Za-z0-9._-]+)")
    }
}
