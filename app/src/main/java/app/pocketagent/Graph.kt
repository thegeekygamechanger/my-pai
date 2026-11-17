package app.pocketagent

import app.pocketagent.ai.ApiException
import app.pocketagent.ai.ClaudeApi
import app.pocketagent.ai.Decision
import app.pocketagent.ai.Integrations
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.ModelInfo
import app.pocketagent.ai.OpenAiApi
import app.pocketagent.ai.Provider
import app.pocketagent.ai.Providers
import app.pocketagent.ai.RunHost
import app.pocketagent.ai.RunManager
import app.pocketagent.ai.RunState
import app.pocketagent.ai.Scheduler
import app.pocketagent.ai.ToolHost
import app.pocketagent.ai.Weather
import app.pocketagent.ai.WeatherNow
import app.pocketagent.ai.Tools
import app.pocketagent.ai.arr
import app.pocketagent.ai.asObj
import app.pocketagent.ai.bool
import app.pocketagent.ai.str
import app.pocketagent.data.Schedule
import app.pocketagent.data.AppDb
import app.pocketagent.data.Attachments
import app.pocketagent.data.Blobs
import app.pocketagent.data.Chat
import app.pocketagent.data.ChatDao
import app.pocketagent.data.SavedModel
import app.pocketagent.data.Settings
import app.pocketagent.data.SettingsStore
import app.pocketagent.data.TrackedDao
import app.pocketagent.data.Workspace
import app.pocketagent.sync.SyncEngine
import app.pocketagent.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** Everything long-lived, built once. Small enough that a dependency-injection library would only add weight. */
class Graph(val platform: Platform, val vault: Vault, val settingsStore: SettingsStore, val db: AppDb) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: StateFlow<Settings?> = settingsStore.flow.stateIn(scope, SharingStarted.Eagerly, null)
    private val filesDir = platform.filesDir
    val workspace = Workspace(File(filesDir, "workspace"))
    val memory = Workspace(File(filesDir, "memory"))
    val attach = Attachments(File(filesDir, "att"))
    val blobs = Blobs(File(filesDir, "blobs"))

    /** The database with change tracking: what the screens and the run loop write through. */
    val dao: ChatDao = TrackedDao(db.dao()) { change -> sync.localChange(change) }

    val claude = ClaudeApi(baseUrl = { settings.value?.debugBaseUrl?.takeIf { platform.debug && it.isNotBlank() } ?: ClaudeApi.PRODUCTION })
    val openai = OpenAiApi()

    private val toolHost = object : ToolHost {
        override fun copyToClipboard(text: String) = platform.copyToClipboard(text)
        override fun tasksChanged(chatId: Long, tasksJson: String) = runBlocking { dao.setTasks(chatId, tasksJson) }
    }
    val integrations = Integrations({ name -> vault.read(name) })
    private val shares = HashMap<Long, app.pocketagent.sync.ShareHost>()

    /**
     * The share that is open for a chat, started if there is none. It stays up for its ten minutes even after
     * its window is closed, so the person can leave the app's share window to send the code to someone.
     * @param fresh ends the current one and starts again with a new code
     */
    fun share(chatId: Long, fresh: Boolean = false): app.pocketagent.sync.ShareHost = synchronized(shares) {
        val open = shares[chatId]
        if (open != null && !open.expired.value && !fresh) return open
        if (open != null) scope.launch { open.stop() }
        app.pocketagent.sync.ShareHost(this, chatId).also { it.start(); shares[chatId] = it }
    }

    val media = app.pocketagent.ai.Media({ name -> vault.read(name) }, attach.dir)
    /** The MCP servers the person added. Their tools join the others; their tokens stay in the vault on this device. */
    val mcp = app.pocketagent.ai.Mcp(
        servers = { settings.value?.mcpServers.orEmpty() },
        token = { id -> vault.read(app.pocketagent.ai.Mcp.tokenKey(id)) },
        pipe = { server -> platform.mcpProgram(server.command) },
        allowHttp = { platform.debug },
        appVersion = platform.versionName,
    )
    /** Every tool call made on this device. Kept here only: it is in no backup and is never synced. */
    val activity = app.pocketagent.ai.ActivityLog(File(filesDir, "activity.jsonl"))
    /** Tools a linked computer lends, and what this computer lends to linked devices. */
    val bridge: app.pocketagent.sync.Bridge by lazy { app.pocketagent.sync.Bridge(this) }
    private val lentTools = object : app.pocketagent.ai.OutsideTools {
        override fun specs() = bridge.specs()
        override fun handles(name: String) = bridge.handles(name)
        override fun describe(name: String) = bridge.describe(name)
        override fun origin(name: String) = bridge.origin(name)
        override fun run(name: String, input: kotlinx.serialization.json.JsonObject) = bridge.run(name, input)
    }
    val tools = Tools(workspace, memory, toolHost, integrations, media, mcp, lentTools, app.pocketagent.data.UndoStore(File(filesDir, "undo")), activity) { settings.value?.toolsOff.orEmpty().toSet() }

    /** A file to show inside the app, over whatever screen is open: what "Open" on a file leads to. */
    val fileOnScreen = MutableStateFlow<File?>(null)
    fun viewFile(file: File) { fileOnScreen.value = file }

    /** Takes back the change one tool call made to a file, and lets linked devices know the file changed. */
    fun undoTool(callId: String): String? = tools.undo(callId)?.also { sync.filesMayHaveChanged() }
    init { mcp.onAnswer = { id, ok -> mcpStatus.update { it + (id to if (ok) app.pocketagent.ai.McpStatus.CONNECTED else app.pocketagent.ai.McpStatus.FAILED) } } }

    private val runHost = object : RunHost {
        override suspend fun settings(): Settings = settingsStore.flow.first()
        override fun provider(id: String): Provider? = this@Graph.provider(id)
        override fun key(providerId: String): String? = keyFor(providerId)
        override fun modelInfo(id: String): ModelInfo? = allModels().firstOrNull { it.id == id }
        override fun models(providerId: String): List<ModelInfo> = this@Graph.models(providerId)
        override fun saveProduced(name: String, bytes: ByteArray): String {
            // Only the name counts, never a path, so nothing can be written outside the workspace.
            val safe = File(name).name.ifBlank { "file" }
            workspace.resolve(safe).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            sync.filesMayHaveChanged()
            // A picture is also kept where the chat can draw it.
            val ext = safe.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("png", "jpg", "jpeg", "webp", "gif")) return safe
            attach.dir.mkdirs()
            val shown = "gen-" + java.util.UUID.randomUUID() + "." + (if (ext == "jpeg") "jpg" else ext)
            File(attach.dir, shown).writeBytes(bytes)
            return safe + "\natt/" + shown
        }
        override fun attachment(path: String): ByteArray? = attach.file(path).takeIf { it.isFile }?.readBytes()
        override fun runsChanged(active: Boolean) = platform.runsChanged(active)
        override fun finished(chat: Chat, replyPreview: String, failed: Boolean) {
            sync.filesMayHaveChanged()
            val s = settings.value
            // A scheduled task announces its result with a sound wherever the person is; an ordinary reply only speaks up when they have gone to another app.
            // An incognito chat announces only that a reply is there, never what it or the chat says.
            val hidden = chat.uid.startsWith(app.pocketagent.data.INCOGNITO)
            // Unread until it is opened, unless the person is looking at this very chat right now.
            if (!hidden && (viewing.value != chat.id || !platform.inForeground())) scope.launch { markUnread(chat.id) }
            val shown = if (hidden) chat.copy(title = "Incognito chat") else chat
            val words = if (hidden) "" else replyPreview
            if (scheduler.wasScheduled(chat.id)) { if (s?.notifyTasks != false) platform.notifyFinished(shown, words, failed, sound = true) }
            else if (!platform.inForeground() && s?.notifyReplies != false) platform.notifyFinished(shown, words, failed)
        }
        override fun approvalNeeded(chat: Chat, title: String) { if (!platform.inForeground()) platform.notifyApproval(chat, title) }
        override fun approvalResolved(chatId: Long) = platform.clearApprovalNotice(chatId)
    }
    val runs = RunManager(dao, claude, openai, tools, blobs, runHost, scope)

    val sync: SyncEngine by lazy { SyncEngine(this) }
    val updates: Updates by lazy { platform.makeUpdates(this) }
    val scheduler: Scheduler by lazy { Scheduler(this) }
    /** The weather in the user's city, when they have set one; refreshed every half hour while the app is open. */
    val weather = MutableStateFlow<WeatherNow?>(null)

    /** Runs on this device and runs on linked devices, as one map for the screens. */
    val allRuns: StateFlow<Map<Long, RunState>> by lazy {
        combine(runs.runs, sync.remoteRuns) { local, remote -> remote + local }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Stops a run wherever it is running. */
    fun stopRun(chatId: Long) { if (runs.isRunning(chatId)) runs.stop(chatId) else sync.sendStop(chatId) }
    fun decide(chatId: Long, decision: Decision) { if (runs.isRunning(chatId)) runs.decide(chatId, decision) else sync.sendDecision(chatId, decision) }

    // ---- services and keys

    fun providers(): List<Provider> = Providers.PRESETS + settings.value?.customProviders.orEmpty()
    fun provider(id: String): Provider? = providers().firstOrNull { it.id == id }
    fun keyFor(providerId: String): String? = vault.read(Providers.vaultKey(providerId))?.takeIf { it.isNotBlank() }
    /** The services that have a key, in the order they are listed. */
    fun connected(): List<Provider> = providers().filter { keyFor(it.id) != null }

    /** The models of one service: the list last fetched with the user's key, or a built-in one for Claude before that. */
    fun models(providerId: String): List<ModelInfo> {
        val saved = settings.value?.models?.get(providerId).orEmpty().map { ModelInfo(it.id, it.name.ifBlank { it.id }, it.maxInput, it.maxOutput, providerId) }
        return saved.ifEmpty { if (providerId == Providers.ANTHROPIC) ModelCaps.BUILT_IN else emptyList() }
    }
    fun allModels(): List<ModelInfo> = connected().flatMap { models(it.id) }

    private suspend fun fetchModels(provider: Provider, key: String): List<ModelInfo> =
        if (provider.kind == "anthropic") claude.listModels(key) else openai.listModels(provider, key)

    /** Fetches a service's model list with its saved key and remembers it. Throws if the key does not work. */
    suspend fun refreshModels(provider: Provider): List<ModelInfo> {
        val key = keyFor(provider.id) ?: throw ApiException(ApiException.Kind.NO_KEY, "No key")
        return fetchModels(provider, key).also { saveModels(provider, it) }
    }

    private suspend fun saveModels(provider: Provider, list: List<ModelInfo>) {
        if (list.isEmpty()) return
        settingsStore.update { it.copy(models = it.models + (provider.id to list.take(600).map { m -> SavedModel(m.id, m.name, m.maxInput, m.maxOutput) })) }
    }

    /**
     * Checks a pasted key against its service and, if it works, stores it.
     * @return a message to show when the key is refused, or null when it is saved
     */
    suspend fun connect(provider: Provider, rawKey: String): String? {
        // A service on the person's own computer needs no key; a word stands in for one so the rest of the app has something to send.
        val key = rawKey.trim().ifEmpty { if (provider.local) Providers.NO_KEY else "" }
        if (!provider.local && (key.length < 8 || key.any { it.isWhitespace() })) return "That does not look like an API key."
        if (provider.local && !provider.baseUrl.startsWith("http")) return "Enter the address ${provider.name} listens on, starting with http://"
        val models = try {
            fetchModels(provider, key)
        } catch (e: ApiException) {
            if (provider.local) return "Could not reach ${provider.name} at ${provider.baseUrl}. Check that it is running (ollama serve) and that the address is right."
            // A wrong key is refused outright. Any other answer (some services have no model list) is not the key's fault.
            if (e.kind == ApiException.Kind.AUTH || e.kind == ApiException.Kind.PERMISSION || e.kind == ApiException.Kind.NETWORK || provider.kind == "anthropic") return e.friendly
            emptyList()
        } catch (e: Exception) {
            return "Could not check the key. Try again."
        }
        if (provider.local && models.isEmpty()) return "${provider.name} answered, but it has no models yet. Download one first, for example: ollama pull llama3.2"
        // Kept with its address. Connecting again under the same name replaces the address it had.
        if (provider.custom) settingsStore.update { s -> s.copy(customProviders = s.customProviders.filter { it.id != provider.id } + provider) }
        vault.write(Providers.vaultKey(provider.id), key)
        saveModels(provider, models)
        settingsStore.update { s ->
            // The first service connected becomes the one new chats use.
            val first = s.provider.isEmpty() || keyFor(s.provider) == null || !s.onboarded
            val model = models.firstOrNull { it.id == ModelCaps.DEFAULT_MODEL }?.id ?: models.firstOrNull()?.id ?: (if (provider.kind == "anthropic") ModelCaps.DEFAULT_MODEL else "")
            if (first) s.copy(provider = provider.id, model = model, sharedRev = System.currentTimeMillis()) else s.copy(sharedRev = System.currentTimeMillis())
        }
        sync.sharedSettingsChanged()
        return null
    }

    suspend fun removeKey(providerId: String) {
        vault.write(Providers.vaultKey(providerId), null)
        settingsStore.update { s -> s.copy(customProviders = s.customProviders.filter { it.id != providerId }, models = s.models - providerId, sharedRev = System.currentTimeMillis()) }
        sync.sharedSettingsChanged()
    }

    /** Stores (or with null, removes) a connection's secret and tells linked devices. */
    /**
     * Adds an MCP server or changes one. The server is asked for its tools first; if it cannot be reached or does
     * not answer as an MCP server does, nothing is saved and the reason comes back.
     * @param newToken null keeps the token it has, "" removes it, anything else replaces it
     * @return null when saved, otherwise what went wrong
     */
    /** Which MCP servers answered when last asked. Kept only while the app runs: it is a fact about now, not a setting. */
    val mcpStatus = MutableStateFlow<Map<String, app.pocketagent.ai.McpStatus>>(emptyMap())
    private fun mcpIs(id: String, status: app.pocketagent.ai.McpStatus) = mcpStatus.update { it + (id to status) }

    /** Connects a server again: switches it on and asks for its tools. Returns what went wrong, or null. */
    suspend fun connectMcp(id: String): String? {
        val server = settingsStore.flow.first().mcpServers.firstOrNull { it.id == id } ?: return "That server was removed."
        if (mcpStatus.value[id] == app.pocketagent.ai.McpStatus.CHECKING) return null
        mcpIs(id, app.pocketagent.ai.McpStatus.CHECKING)
        val problem = saveMcpServer(server.copy(on = true), null)
        mcpIs(id, if (problem == null) app.pocketagent.ai.McpStatus.CONNECTED else app.pocketagent.ai.McpStatus.FAILED)
        return problem
    }

    /** Asks every switched-on server that has not been asked since the app started, so the screens can say which are connected. */
    fun checkMcp() {
        // A server the app came with is not contacted just because a list was opened: only when the person uses it or connects it.
        val waiting = settings.value?.mcpServers.orEmpty().filter { it.on && !it.untouched && (mcpStatus.value[it.id] ?: app.pocketagent.ai.McpStatus.UNKNOWN) == app.pocketagent.ai.McpStatus.UNKNOWN }
        waiting.forEach { s -> scope.launch { connectMcp(s.id) } }
    }

    suspend fun saveMcpServer(server: app.pocketagent.ai.McpServer, newToken: String?): String? {
        val name = server.name.trim()
        if (name.isEmpty()) return "Give the server a name."
        if (!server.local) app.pocketagent.ai.Mcp.urlProblem(server.url, platform.debug)?.let { return it }
        if (server.local && !platform.isDesktop) return "A local server can only run on a computer."
        val token = newToken?.trim() ?: vault.read(app.pocketagent.ai.Mcp.tokenKey(server.id)).orEmpty()
        val clean = server.copy(name = name.take(40), url = server.url.trim(), command = server.command.trim(), hasToken = token.isNotEmpty())
        val tools = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mcp.list(clean, tokenOverride = token) }
        } catch (e: app.pocketagent.ai.McpError) {
            return e.message
        } catch (e: Exception) {
            return "Could not reach the server (${e.message ?: e.javaClass.simpleName})."
        }
        vault.write(app.pocketagent.ai.Mcp.tokenKey(clean.id), token.ifEmpty { null })
        mcp.forget(clean.id)
        val saved = clean.copy(tools = tools, checkedAt = System.currentTimeMillis())
        mcpIs(saved.id, app.pocketagent.ai.McpStatus.CONNECTED)
        val now = System.currentTimeMillis()
        var listChanged = false
        settingsStore.update { s ->
            val old = s.mcpServers.firstOrNull { it.id == saved.id }
            // A new server, a new name or a new address is news for the linked devices; a refreshed list of tools is not.
            listChanged = !saved.local && (old == null || old.name != saved.name || old.url != saved.url || old.hasToken != saved.hasToken)
            val kept = saved.copy(changedAt = if (listChanged) now else old?.changedAt ?: now, wantsToken = false)
            s.copy(mcpServers = if (old != null) s.mcpServers.map { if (it.id == kept.id) kept else it } else s.mcpServers + kept, sharedRev = if (listChanged) now else s.sharedRev)
        }
        if (listChanged) sync.sharedSettingsChanged()
        bridge.advertise()
        return null
    }

    /** Switches a server, or one of its tools, on or off. Nothing is sent to the server. */
    suspend fun changeMcpServer(id: String, change: (app.pocketagent.ai.McpServer) -> app.pocketagent.ai.McpServer) {
        settingsStore.update { s -> s.copy(mcpServers = s.mcpServers.map { if (it.id == id) change(it) else it }) }
        bridge.advertise()
    }

    suspend fun removeMcpServer(id: String) {
        mcp.forget(id)
        mcpStatus.update { it - id }
        vault.write(app.pocketagent.ai.Mcp.tokenKey(id), null)
        val now = System.currentTimeMillis()
        var remote = false
        settingsStore.update { s ->
            remote = s.mcpServers.any { it.id == id && !it.local }
            s.copy(mcpServers = s.mcpServers.filter { it.id != id }, mcpGone = if (remote) s.mcpGone + (id to now) else s.mcpGone, sharedRev = if (remote) now else s.sharedRev)
        }
        if (remote) sync.sharedSettingsChanged()
        bridge.advertise()
    }

    /** Puts back one of the public servers the app knows of, as it is when first listed: switched on, and not contacted. */
    suspend fun addPreset(id: String) {
        val p = app.pocketagent.ai.McpPresets.ALL.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        settingsStore.update { s ->
            if (s.mcpServers.any { it.id == id }) s
            else s.copy(mcpServers = s.mcpServers + app.pocketagent.ai.McpPresets.server(p).copy(changedAt = now), mcpGone = s.mcpGone - id, sharedRev = now)
        }
        sync.sharedSettingsChanged()
    }

    /**
     * Takes in the list of MCP servers a linked device sent: names and addresses, no tokens.
     * @return true when this device holds a server or a removal the sender did not know of
     */
    suspend fun mergeMcp(packed: String): Boolean {
        var ahead = false
        var removed = emptyList<String>()
        settingsStore.update { s ->
            val m = app.pocketagent.ai.McpSync.merge(s.mcpServers, s.mcpGone, packed) ?: return@update s
            ahead = m.ahead; removed = m.removed
            if (m.changed) s.copy(mcpServers = m.servers, mcpGone = m.gone) else s
        }
        removed.forEach { id -> mcp.forget(id); mcpStatus.update { it - id }; vault.write(app.pocketagent.ai.Mcp.tokenKey(id), null) }
        return ahead
    }

    // ---- My PAI as an MCP server for other programs on this computer

    /** A program's request waiting for the person's yes or no. */
    class ServeAsk(val what: String, val answer: kotlinx.coroutines.CompletableDeferred<Int>)
    val serveAsk = MutableStateFlow<ServeAsk?>(null)
    @Volatile private var serveTrustedUntil = 0L
    private val serveLock = Any()
    /** One question at a time; apart from [serveLock], so a question waiting for its answer holds nothing else up. */
    private val askLock = Any()
    private var server: app.pocketagent.ai.LocalServer? = null
    /** What went wrong starting the local server, for the settings screen. */
    val serveProblem = MutableStateFlow("")

    /** Shows the request in the app and waits for the answer; no answer in a minute counts as no. */
    private fun askToServe(what: String): Boolean = synchronized(askLock) {
        if (System.currentTimeMillis() < serveTrustedUntil) return true
        val ask = ServeAsk(what, kotlinx.coroutines.CompletableDeferred())
        serveAsk.value = ask
        val choice = runBlocking { kotlinx.coroutines.withTimeoutOrNull(SERVE_WAIT_MS) { ask.answer.await() } } ?: 0
        serveAsk.value = null
        if (choice == 2) serveTrustedUntil = System.currentTimeMillis() + SERVE_TRUST_MS
        choice > 0
    }

    companion object {
        /** How long a program's request waits for the person before it counts as refused. */
        const val SERVE_WAIT_MS = 60_000L
        /** "Allow for ten minutes". */
        const val SERVE_TRUST_MS = 10 * 60_000L
    }

    val serveToken: String? get() = vault.read(app.pocketagent.ai.McpHost.TOKEN)

    /** Starts or stops the local server to match the setting. Only a computer serves. */
    private fun applyServe(on: Boolean, port: Int) = synchronized(serveLock) {
        if (!platform.isDesktop || !on) { server?.close(); server = null; serveProblem.value = ""; return@synchronized }
        if (server?.port == port) return@synchronized
        server?.close(); server = null
        if (serveToken == null) vault.write(app.pocketagent.ai.McpHost.TOKEN, app.pocketagent.ai.McpHost.newToken())
        val host = app.pocketagent.ai.McpHost(workspace, memory, { settings.value?.schedules.orEmpty() }, ::askToServe, activity, platform.versionName)
        server = runCatching { app.pocketagent.ai.LocalServer(port) { auth, origin, body -> host.handle(serveToken.orEmpty(), auth, origin, body) } }
            .onFailure { serveProblem.value = "Could not listen on port $port. Another program may be using it." }.onSuccess { serveProblem.value = "" }.getOrNull()
    }

    /** A new token: every program that had the old one has to be given the new one. */
    fun newServeToken() { vault.write(app.pocketagent.ai.McpHost.TOKEN, app.pocketagent.ai.McpHost.newToken()); serveTrustedUntil = 0 }

    // ---- sharing a setup: MCP servers, routines and instructions, never a token

    /** What this device would hand over: names and addresses of servers, routines and standing instructions. No token, no key, no program path. */
    fun setupBundle(servers: Boolean, routines: Boolean, instructions: Boolean): kotlinx.serialization.json.JsonObject {
        val s = settings.value ?: Settings()
        return kotlinx.serialization.json.buildJsonObject {
            put("v", kotlinx.serialization.json.JsonPrimitive(1))
            if (servers) put("servers", kotlinx.serialization.json.JsonArray(s.mcpServers.filter { !it.local }.map { m ->
                kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(m.name)); put("url", kotlinx.serialization.json.JsonPrimitive(m.url)); put("token", kotlinx.serialization.json.JsonPrimitive(m.hasToken || m.wantsToken)) }
            }))
            if (routines) put("routines", kotlinx.serialization.json.JsonArray(s.schedules.map { r ->
                kotlinx.serialization.json.buildJsonObject {
                    put("title", kotlinx.serialization.json.JsonPrimitive(r.title)); put("prompt", kotlinx.serialization.json.JsonPrimitive(r.prompt)); put("agent", kotlinx.serialization.json.JsonPrimitive(r.agent))
                    put("steps", kotlinx.serialization.json.JsonArray(r.steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
            }))
            if (instructions && s.customInstructions.isNotBlank()) put("instructions", kotlinx.serialization.json.JsonPrimitive(s.customInstructions))
        }
    }

    private var setupShare: app.pocketagent.sync.ShareHost? = null

    /** A fresh code under which the bundle can be fetched for ten minutes, exactly as a shared chat is. */
    fun shareSetup(bundle: kotlinx.serialization.json.JsonObject): app.pocketagent.sync.ShareHost = synchronized(shares) {
        setupShare?.let { old -> scope.launch { old.stop() } }
        app.pocketagent.sync.ShareHost(this, -1, bundle).also { it.start(); setupShare = it }
    }

    /**
     * Adds what a shared setup holds. Servers arrive switched off and without a token; routines arrive as ones
     * to run by hand; instructions are added under the person's own, never in place of them.
     * @return a line saying what was added
     */
    suspend fun addSetup(setup: kotlinx.serialization.json.JsonObject, instructions: Boolean): String {
        val now = System.currentTimeMillis()
        var servers = 0; var routines = 0; var instr = false
        settingsStore.update { s ->
            val known = s.mcpServers.map { it.url }.toSet()
            val newServers = setup.arr("servers").orEmpty().mapNotNull { it.asObj() }.take(20).mapNotNull { o ->
                val url = o.str("url")?.trim().orEmpty()
                if (url in known || app.pocketagent.ai.Mcp.urlProblem(url, false) != null) null
                else app.pocketagent.ai.McpServer(id = java.util.UUID.randomUUID().toString().take(12), name = o.str("name").orEmpty().take(40).ifBlank { "Server" }, url = url, on = false, changedAt = now, wantsToken = o.bool("token") == true)
            }
            val newRoutines = setup.arr("routines").orEmpty().mapNotNull { it.asObj() }.take(20).mapNotNull { o ->
                val prompt = o.str("prompt")?.take(4000).orEmpty()
                if (prompt.isBlank() || s.schedules.any { it.prompt == prompt }) null
                else Schedule(
                    id = java.util.UUID.randomUUID().toString(), title = o.str("title").orEmpty().take(80), prompt = prompt, agent = o.bool("agent") == true, repeat = "never", createdAt = now,
                    steps = o.arr("steps").orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.take(4000) }.take(12),
                )
            }
            val more = setup.str("instructions")?.trim()?.take(4000).orEmpty()
            instr = instructions && more.isNotEmpty() && !s.customInstructions.contains(more)
            servers = newServers.size; routines = newRoutines.size
            s.copy(
                mcpServers = s.mcpServers + newServers, schedules = s.schedules + newRoutines,
                customInstructions = if (instr) listOf(s.customInstructions.trim(), more).filter { it.isNotEmpty() }.joinToString("\n") else s.customInstructions,
                sharedRev = if (servers > 0 || instr) now else s.sharedRev,
            )
        }
        if (servers > 0 || instr) sync.sharedSettingsChanged()
        val parts = listOfNotNull(
            "$servers MCP server${if (servers == 1) "" else "s"}".takeIf { servers > 0 }, "$routines routine${if (routines == 1) "" else "s"}".takeIf { routines > 0 }, "the instructions".takeIf { instr },
        )
        return if (parts.isEmpty()) "Nothing new: you already have all of it." else "Added " + parts.joinToString(", ") + "." + (if (servers > 0) " Connect each server in Settings, Tools." else "")
    }

    suspend fun setConnection(values: Map<String, String?>) {
        values.forEach { (name, value) -> vault.write(name, value?.takeIf { it.isNotBlank() }) }
        settingsStore.update { it.copy(sharedRev = System.currentTimeMillis()) }
        sync.sharedSettingsChanged()
    }

    /** Saves something every linked device shares (name, instructions and so on) and tells them. */
    suspend fun updateShared(change: (Settings) -> Settings) {
        settingsStore.update { change(it).copy(sharedRev = System.currentTimeMillis()) }
        sync.sharedSettingsChanged()
    }

    suspend fun newChat(agent: Boolean = false): Long {
        val s = settingsStore.flow.first()
        val on = connected()
        val provider = on.firstOrNull { it.id == s.provider } ?: on.firstOrNull() ?: Providers.PRESETS.first()
        val list = models(provider.id)
        val model = app.pocketagent.ai.AutoModel.ID.takeIf { !s.modelChosen || s.model == it } ?: s.model.takeIf { id -> provider.id == s.provider && (list.isEmpty() || list.any { it.id == id }) } ?: list.firstOrNull { it.id == ModelCaps.DEFAULT_MODEL }?.id ?: list.firstOrNull()?.id ?: s.model
        val now = System.currentTimeMillis()
        return dao.insert(Chat(createdAt = now, updatedAt = now, agent = agent, provider = provider.id, model = model, effort = s.effort, web = s.web, permission = s.permission))
    }

    /**
     * A chat that is kept out of everything: it is in no list, is never sent to linked devices, cannot be shared,
     * writes no memory notes, and is deleted the moment the person moves on from it.
     */
    suspend fun newIncognitoChat(): Long {
        val id = newChat()
        val chat = dao.chat(id) ?: return id
        db.dao().update(chat.copy(uid = app.pocketagent.data.INCOGNITO + java.util.UUID.randomUUID(), memory = false))
        return id
    }

    /** The person has gone on to something else. An incognito chat does not outlive that. */
    suspend fun leftChat(id: Long) {
        if (db.dao().chat(id)?.let { it.uid.startsWith(app.pocketagent.data.INCOGNITO) } == true) deleteChat(id)
    }

    /** The chat on screen right now, if any. A reply that lands there while the app is in front counts as read. */
    val viewing = MutableStateFlow<Long?>(null)

    /** A reply arrived in a chat nobody was looking at: it gets a small dot in the lists until it is opened. */
    suspend fun markUnread(id: Long) {
        settingsStore.update { if (id in it.unreadChats) it else it.copy(unreadChats = (it.unreadChats + id).takeLast(300)) }
    }

    suspend fun markRead(id: Long) {
        if (settings.value?.unreadChats?.contains(id) == true) settingsStore.update { it.copy(unreadChats = it.unreadChats - id) }
    }

    suspend fun deleteChat(id: Long) {
        runs.stop(id)
        markRead(id)
        val paths = Regex("\"path\":\"(att/[^\"]+)\"")
        dao.messages(id).forEach { m -> paths.findAll(blobs.unpack(m.content)).forEach { attach.file(it.groupValues[1]).delete() } }
        blobs.deleteFor(id)
        dao.deleteChat(id)
    }

    /**
     * Clears History: every chat and its attachments, here and on linked devices. Files in the workspace, memory notes,
     * tasks and settings stay. Chats that are being answered right now are stopped first.
     * @return how many chats were removed
     */
    suspend fun clearHistory(): Int {
        val all = db.dao().allChats()
        all.forEach { deleteChat(it.id) }
        return all.size
    }

    /** Hands memory the app is not using back to the system: called when the app has settled after starting, and when it goes to the background. */
    fun trimMemory() {
        platform.trimMemory()
    }

    /** Removes every chat, attachment, workspace file and memory note. The API key and settings stay. */
    suspend fun deleteEverything() {
        runs.stopAll()
        dao.deleteAllChats()
        attach.dir.deleteRecursively()
        blobs.deleteAll()
        workspace.root.deleteRecursively(); workspace.root.mkdirs()
        memory.root.deleteRecursively(); memory.root.mkdirs()
        // The record of what tools did, and the copies kept for undoing, go with the rest.
        activity.clear()
        File(filesDir, "undo").deleteRecursively()
        sync.filesMayHaveChanged()
    }

    /** Called once at start. */
    fun start() {
        scope.launch {
            // An incognito chat that was open when the app was closed goes now.
            db.dao().incognitoChats().forEach { deleteChat(it.id) }
            // Empty chats that were opened and never used are not worth keeping.
            db.dao().allChats().filter { it.title.isBlank() && it.updatedAt == it.createdAt }.forEach { c ->
                if (db.dao().messages(c.id).isEmpty() && !runs.isRunning(c.id)) db.dao().deleteChat(c.id)
            }
            // The public servers the app comes with are listed once; one the person removed is not brought back.
            settingsStore.update { s ->
                if (s.presets >= app.pocketagent.ai.McpPresets.VERSION) s
                else s.copy(mcpServers = s.mcpServers + app.pocketagent.ai.McpPresets.missing(s.mcpServers, s.mcpGone), presets = app.pocketagent.ai.McpPresets.VERSION)
            }
            sync.start()
            scheduler.start()
        }
        // Offline mode and the local server follow their settings from the first moment and whenever they change.
        scope.launch {
            settingsStore.flow.collect { s ->
                app.pocketagent.ai.Net.offline = s.offline
                runCatching { applyServe(s.serve, s.servePort) }
            }
        }
        scope.launch {
            var last: Pair<Boolean, Boolean>? = null
            settingsStore.flow.collect { s -> val now = s.bridge to s.offline; if (now != last) { last = now; bridge.advertise() } }
        }
        scope.launch {
            var lastCity = ""
            var lastAt = 0L
            settingsStore.flow.collect { s ->
                val city = s.city.trim()
                val now = System.currentTimeMillis()
                if (city.isEmpty()) { weather.value = null; lastCity = "" }
                else if (city != lastCity || now - lastAt > 30 * 60_000) { lastCity = city; lastAt = now; weather.value = Weather.fetch(city) ?: weather.value }
            }
        }
        // Looking for a newer version has a coroutine to itself: at start, then again every half hour while the app
        // stays open. (It used to sit after the loop above, which never ends, so it never ran.)
        scope.launch {
            while (true) {
                runCatching { updates.check() }
                kotlinx.coroutines.delay(app.pocketagent.update.Updates.AUTO_CHECK_MS)
            }
        }
    }
}
