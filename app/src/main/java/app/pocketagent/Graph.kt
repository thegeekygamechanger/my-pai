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
    val tools = Tools(workspace, memory, toolHost, integrations, media)

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

    /** Removes every chat, attachment, workspace file and memory note. The API key and settings stay. */
    suspend fun deleteEverything() {
        runs.stopAll()
        dao.deleteAllChats()
        attach.dir.deleteRecursively()
        blobs.deleteAll()
        workspace.root.deleteRecursively(); workspace.root.mkdirs()
        memory.root.deleteRecursively(); memory.root.mkdirs()
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
            sync.start()
            scheduler.start()
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
