package app.pocketagent.ai

import app.pocketagent.data.Blobs
import app.pocketagent.data.Chat
import app.pocketagent.data.ChatDao
import app.pocketagent.data.Msg
import app.pocketagent.data.Settings
import app.pocketagent.data.ToolError
import app.pocketagent.data.instructions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Phase { CONNECTING, THINKING, WRITING, TOOL, APPROVAL, RETRYING }
enum class Decision { ALLOW, ALLOW_ALWAYS, DENY }

data class PendingApproval(val toolUseId: String, val name: String, val title: String, val input: JsonObject, val risk: Risk)

/** What the screen needs to draw a run in progress. */
data class RunState(
    val chatId: Long,
    val phase: Phase = Phase.CONNECTING,
    /** The service's id for the reply being streamed; empty until its first event arrives. */
    val apiId: String = "",
    val live: List<LiveBlock> = emptyList(),
    val step: Int = 1,
    val tokens: Long = 0,
    val status: String = "",
    val pending: PendingApproval? = null,
    val runningTool: String = "",
    /** Results of this step's tool calls that are finished but not saved yet. */
    val done: Map<String, ToolOutcome> = emptyMap(),
    /** Set when the run is happening on a linked device and only being shown here: that device's name. */
    val remote: String = "",
)

/** The outside world a run needs, so the loop itself has no Android in it and runs in unit tests. */
interface RunHost {
    suspend fun settings(): Settings
    fun provider(id: String): Provider?
    fun key(providerId: String): String?
    fun modelInfo(id: String): ModelInfo?
    /** Keeps a file the code sandbox produced: in the workspace, and for a picture also where the chat can show it. Returns the lines of the note about it. */
    fun saveProduced(name: String, bytes: ByteArray): String = name
    /** The models of a service, for choosing one when the chat is set to Auto. */
    fun models(providerId: String): List<ModelInfo> = emptyList()
    fun attachment(path: String): ByteArray?
    fun runsChanged(active: Boolean)
    fun finished(chat: Chat, replyPreview: String, failed: Boolean)
    fun approvalNeeded(chat: Chat, title: String)
    fun approvalResolved(chatId: Long)
}

/**
 * Owns every reply and agent run. Runs live in an app-wide scope, not in a screen, so they carry on
 * when the user leaves the chat or switches to another app (a foreground service keeps the process alive).
 */
class RunManager(
    private val dao: ChatDao,
    private val claude: ClaudeApi,
    private val openai: OpenAiApi,
    private val tools: Tools,
    private val blobs: Blobs,
    private val host: RunHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Length of one countdown second while waiting to retry; tests shorten it. */
    private val tickMs: Long = 1000L,
) {
    private val _runs = MutableStateFlow<Map<Long, RunState>>(emptyMap())
    val runs: StateFlow<Map<Long, RunState>> = _runs

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val approvals = ConcurrentHashMap<Long, CompletableDeferred<Decision>>()
    /** Tools the person allowed "for this task": gone the moment the task ends, whether it finished, failed or was stopped. */
    private val taskAllowed = ConcurrentHashMap<Long, MutableSet<String>>()
    /** Chats whose running task has read something worded as instructions: until it ends, nothing that changes anything runs unasked. */
    private val shielded: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val assemblers = ConcurrentHashMap<Long, LiveReply>()
    /** Optional request extras the service has rejected; left off for the rest of this app session. */
    private val disabled: MutableSet<Feature> = Collections.synchronizedSet(LinkedHashSet())
    /** Models ("service/model") that answered that they take no tools. */
    private val noTools: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun isRunning(chatId: Long) = jobs.containsKey(chatId)

    /** For diagnostics: the optional extras the service has turned down in this session. */
    fun rejectedFeatures(): Set<Feature> = disabled.toSet()

    /** Adds the user's message and starts replying. */
    fun send(chatId: Long, content: JsonArray) = launchRun(chatId) { ask(chatId, content) }

    /**
     * A routine: several requests sent one after another in the same chat, each when the one before has finished.
     * Every step is an ordinary request, so every step asks before it changes anything. A step that fails or is
     * stopped ends the routine there.
     */
    fun sendSteps(chatId: Long, steps: List<String>) {
        fun block(text: String) = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) }))
        val all = steps.filter { it.isNotBlank() }
        if (all.isEmpty()) return
        launchRun(chatId, more = all.drop(1).map(::block)) { ask(chatId, block(all.first())) }
    }

    private suspend fun ask(chatId: Long, content: JsonArray) {
        repairDangling(chatId, "Interrupted before this ran.")
        val now = System.currentTimeMillis()
        dao.insert(Msg(chatId = chatId, role = "user", content = blobs.pack(chatId, content.toString()), createdAt = now))
        val text = content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString(" ").trim()
        val chat = dao.chat(chatId)
        if (chat != null && chat.title.isBlank()) dao.rename(chatId, titleFrom(text))
        dao.touch(chatId, now, text.take(140))
    }

    /** Drops the last reply (and any tool steps in it) and answers the last user message again. */
    fun regenerate(chatId: Long) = launchRun(chatId) {
        val msgs = dao.messages(chatId)
        val lastUser = msgs.lastOrNull { it.role == "user" && !isToolResults(it) } ?: return@launchRun
        dao.deleteFrom(chatId, lastUser.createdAt + 1)
    }

    /** Replaces a user message and everything after it, then replies. */
    fun editAndResend(chatId: Long, msgId: Long, content: JsonArray) {
        if (isRunning(chatId)) return
        launchRun(chatId) {
            val from = dao.messages(chatId).firstOrNull { it.id == msgId } ?: return@launchRun
            dao.deleteFrom(chatId, from.createdAt)
            val now = System.currentTimeMillis()
            dao.insert(Msg(chatId = chatId, role = "user", content = blobs.pack(chatId, content.toString()), createdAt = now))
        }
    }

    fun stop(chatId: Long) { jobs[chatId]?.cancel() }
    fun stopAll() { jobs.values.forEach { it.cancel() } }
    fun decide(chatId: Long, decision: Decision) { approvals[chatId]?.complete(decision) }

    private fun isToolResults(m: Msg): Boolean {
        val c = parseArray(blobs.unpack(m.content))
        return c.isNotEmpty() && c.all { (it as? JsonObject)?.str("type") == "tool_result" }
    }

    private val MENTION_LINE = Regex("^@(github|gmail|mcp)\\b", RegexOption.IGNORE_CASE)

    private fun titleFrom(text: String): String {
        // A chat is named after what was asked, not after the "@GitHub" or "@mcp" line that says where to look,
        // unless that line is all there is.
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val line = lines.firstOrNull { !MENTION_LINE.containsMatchIn(it) } ?: lines.firstOrNull().orEmpty()
        return when {
            line.isEmpty() -> "New chat"
            line.length <= 48 -> line
            else -> line.take(48).substringBeforeLast(' ', line.take(48)).trimEnd() + "…"
        }
    }

    private fun launchRun(chatId: Long, more: List<JsonArray> = emptyList(), before: suspend () -> Unit) {
        if (jobs.containsKey(chatId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                before()
                var ok = loop(chatId, last = more.isEmpty())
                for ((i, step) in more.withIndex()) {
                    if (!ok) { note(chatId, "info", "The routine stopped at step ${i + 1} of ${more.size + 1}, so the later steps were not run."); break }
                    ask(chatId, step)
                    ok = loop(chatId, last = i == more.lastIndex)
                }
            } finally {
                jobs.remove(chatId)
                approvals.remove(chatId)
                // What was allowed "for this task" ends with the task.
                taskAllowed.remove(chatId)
                shielded.remove(chatId)
                assemblers.remove(chatId)
                _runs.update { it - chatId }
                host.runsChanged(jobs.isNotEmpty())
            }
        }
        if (jobs.putIfAbsent(chatId, job) != null) { job.cancel(); return }
        _runs.update { it + (chatId to RunState(chatId)) }
        host.runsChanged(true)
        job.start()
    }

    private fun setState(chatId: Long, change: (RunState) -> RunState) {
        _runs.update { m -> m[chatId]?.let { m + (chatId to change(it)) } ?: m }
    }

    /** The code sandbox each chat is using, so its files last from one step of the work to the next. */
    private val containers = java.util.concurrent.ConcurrentHashMap<Long, String>()
    /** Files the sandbox has handed over in the current run of each chat: name to a mark of the contents. */
    private val produced = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<String, Int>>()

    private suspend fun history(chatId: Long): List<JsonObject> =
        dao.messages(chatId).filter { it.role != "note" }.map { m ->
            buildJsonObject { put("role", m.role); put("content", parseArray(blobs.unpack(m.content))) }
        }

    private suspend fun note(chatId: Long, kind: String, text: String) {
        dao.insert(Msg(chatId = chatId, role = "note", content = text, createdAt = System.currentTimeMillis(), stopReason = kind))
    }

    /**
     * One request and everything it leads to, until the model has nothing more to do.
     * @param last false for a step of a routine that has more steps after it: the person is told when the whole routine is done, not after each step
     * @return true when it ended normally, so a routine may go on to its next step
     */
    private suspend fun loop(chatId: Long, last: Boolean = true): Boolean {
        var endNote: Pair<String, String>? = null
        var preview = ""
        var failed = false
        val chat = dao.chat(chatId) ?: return false
        try {
            repairDangling(chatId, "Interrupted before this ran.")
            produced.remove(chatId)
            val s = host.settings()
            val provider = host.provider(chat.provider) ?: throw ApiException(ApiException.Kind.NO_KEY, "The service this chat used is no longer set up. Pick another model.")
            // Offline mode: only a model on the person's own device or network may answer.
            if (s.offline && !(provider.local && Net.localUrl(provider.baseUrl))) throw ApiException(ApiException.Kind.BAD_REQUEST, "Offline mode is on, and ${provider.name} is on the internet. Pick an Ollama model on this device or your network for this chat, or turn Offline mode off in Settings.")
            // A chat set to Auto gets its model now, from what was just asked; it then stays the same for the whole run.
            val model = if (chat.model != AutoModel.ID) chat.model else {
                val history = dao.messages(chatId)
                val asked = history.lastOrNull { it.role == "user" && !blobs.unpack(it.content).contains("\"tool_result\"") }?.let { parseArray(blobs.unpack(it.content)).mapNotNull { b -> b as? JsonObject } }.orEmpty()
                AutoModel.pick(
                    host.models(provider.id), asked.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }, chat.agent,
                    asked.count { it.str("type") != "text" }, history.lastOrNull { it.role == "assistant" && it.model.isNotEmpty() }?.model,
                ) ?: throw ApiException(ApiException.Kind.NO_KEY, "This service has no model list to choose from. Pick a model for this chat.")
            }
            val cap = host.modelInfo(model)?.maxOutput?.takeIf { it > 0 }?.toInt() ?: if (ModelCaps.adaptive(model)) 128_000 else 8_192
            val server = Providers.serverTools(provider)
            var cfg = RunConfig(
                provider = provider, model = model, effort = chat.effort, maxTokens = minOf(s.maxOutput, cap), customInstructions = s.instructions(),
                // A Claude model always has the code sandbox, in Chat as in Work: it is how documents are converted and charts and office files are made.
                agent = chat.agent, web = chat.web && server, code = server, memory = chat.memory, fast = chat.fast && server,
                fallbacks = s.fallbacks, compaction = s.compaction,
            )
            // Reasoning blocks from earlier turns only stay valid while the system prompt and tools are unchanged.
            // If the user switched mode or tools since the last run, drop them once, up front.
            val fp = RequestBuilder.fingerprint(cfg, tools)
            if (chat.prefixFp.isNotEmpty() && chat.prefixFp != fp) stripThinking(chatId)
            if (chat.prefixFp != fp) dao.setFingerprint(chatId, fp)

            val budget = if (chat.agent) s.budgetK * 1000L else 0L
            var step = 0
            var spent = 0L
            // The backup the person chose, for when this service fails or has had enough for now. Used at most once in a task.
            var backup = backupFor(s, cfg)
            while (true) {
                step++
                setState(chatId) { it.copy(step = step, phase = Phase.CONNECTING, live = emptyList(), apiId = "", status = "", done = emptyMap(), runningTool = "") }
                val msg = try {
                    request(chatId, cfg, if (backup != null) 1 else MAX_RETRIES)
                } catch (e: ApiException) {
                    val next = backup?.takeIf { e.kind in BACKUP_KINDS } ?: throw e
                    backup = null
                    assemblers.remove(chatId)
                    // Reasoning written by one model is not handed to another.
                    stripThinking(chatId)
                    note(chatId, "info", "${cfg.provider.name} could not answer (${e.friendly.trimEnd('.')}). Carrying on with your backup, ${next.provider.name} ${next.model}.")
                    cfg = next
                    step--
                    continue
                }
                val text = msg.content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString(" ").trim()
                if (text.isNotEmpty()) preview = text.take(140)
                saveReply(chatId, cfg, msg, preview)
                spent += msg.usage.input + msg.usage.output + msg.usage.cacheWrite
                setState(chatId) { it.copy(tokens = spent) }
                val uses = RequestBuilder.pendingToolUses(msg.content)
                when (msg.stopReason) {
                    "pause_turn" -> continue
                    "tool_use" -> {
                        if (uses.isEmpty()) break
                        runTools(chatId, chat, uses, msg.invalidInputs)
                    }
                    "max_tokens" -> {
                        if (uses.isEmpty()) {
                            endNote = "info" to "The reply reached the length limit. Send \"continue\" to carry on."
                            break
                        }
                        answerAll(chatId, uses, "This call was cut off by the output limit, so nothing was run. Try again with a smaller input.")
                    }
                    "refusal" -> { endNote = "error" to "The model declined to continue with this request."; failed = true; break }
                    "model_context_window_exceeded" -> { endNote = "error" to "This conversation no longer fits in the model's context window. Start a new chat."; failed = true; break }
                    else -> break
                }
                if (step >= s.maxSteps) { endNote = "info" to "Paused after $step steps, the limit set in Settings. Send a message to continue."; break }
                if (budget > 0 && spent >= budget) { endNote = "info" to "Paused: this run used ${formatTokens(spent)} tokens, the limit set in Settings. Send a message to continue."; break }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                savePartial(chatId)
                repairDangling(chatId, "Stopped by the person before this ran.")
                note(chatId, "info", "Stopped.")
            }
            throw e
        } catch (e: ApiException) {
            failed = true
            savePartial(chatId)
            repairDangling(chatId, "Not run: the request failed.")
            note(chatId, "error", e.friendly)
        } catch (e: Exception) {
            failed = true
            repairDangling(chatId, "Not run: the app hit an error.")
            note(chatId, "error", "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        }
        endNote?.let { note(chatId, it.first, it.second) }
        if (last || failed) host.finished(dao.chat(chatId) ?: chat, preview.ifEmpty { endNote?.second.orEmpty() }, failed)
        return !failed
    }

    /** The settings for the backup model, or null when none is chosen, it is the model already in use, or it has no key. */
    private fun backupFor(s: Settings, cfg: RunConfig): RunConfig? {
        if (s.backupProvider.isBlank() || s.backupModel.isBlank()) return null
        if (s.backupProvider == cfg.provider.id && s.backupModel == cfg.model) return null
        val p = host.provider(s.backupProvider) ?: return null
        if (host.key(p.id) == null) return null
        if (s.offline && !(p.local && Net.localUrl(p.baseUrl))) return null
        val cap = host.modelInfo(s.backupModel)?.maxOutput?.takeIf { it > 0 }?.toInt() ?: if (ModelCaps.adaptive(s.backupModel)) 128_000 else 8_192
        val server = Providers.serverTools(p)
        return cfg.copy(provider = p, model = s.backupModel, maxTokens = minOf(s.maxOutput, cap), web = cfg.web && server, code = server, fast = cfg.fast && server)
    }

    private fun sentFeatures(cfg: RunConfig, off: Set<Feature>): Set<Feature> {
        if (cfg.provider.kind != "anthropic") return setOf(Feature.EFFORT) - off
        val modern = ModelCaps.adaptive(cfg.model)
        val sent = LinkedHashSet<Feature>()
        if (cfg.compaction && modern) sent += Feature.COMPACTION
        if (cfg.fallbacks && ModelCaps.fallbacks(cfg.model)) sent += Feature.FALLBACKS
        if (cfg.fast && ModelCaps.fast(cfg.model)) sent += Feature.FAST
        if (cfg.agent) sent += Feature.EAGER
        sent += Feature.CACHE
        return sent - off
    }

    /** One request, with the recoveries that should never reach the user: dropped extras, retries, backoff. */
    private suspend fun request(chatId: Long, cfg: RunConfig, maxRetries: Int = MAX_RETRIES): ApiMessage {
        var attempt = 0
        var stripped = false
        while (true) {
            // A model that turned tools down once is not offered them again.
            val toolsKey = cfg.provider.id + "/" + cfg.model
            val off = if (toolsKey in noTools) disabled.toSet() + Feature.TOOLS else disabled.toSet()
            val key = host.key(cfg.provider.id) ?: throw ApiException(ApiException.Kind.NO_KEY, "No API key is set for ${cfg.provider.name}. Add one in Settings.")
            val anthropic = cfg.provider.kind == "anthropic"
            // Events can arrive faster than a screen can draw. The newest state is published at most every
            // 40 ms, and always once more after the last event, so the screen is never left behind.
            val latest = AtomicReference<LiveReply?>(null)
            val dirty = AtomicBoolean(false)
            try {
                val msg = coroutineScope {
                    val ticker = launch {
                        while (true) {
                            delay(40)
                            if (dirty.getAndSet(false)) latest.get()?.let { publish(chatId, it) }
                        }
                    }
                    try {
                        val onUpdate = { asm: LiveReply ->
                            if (latest.getAndSet(asm) !== asm) assemblers[chatId] = asm
                            dirty.set(true)
                        }
                        if (anthropic) {
                            val (body, betas) = RequestBuilder.build(cfg, history(chatId), tools, off, containers[chatId], loadFile = host::attachment)
                            claude.stream(key, body, betas, onUpdate)
                        } else {
                            openai.stream(cfg.provider, key, OpenAiCompat.build(cfg, history(chatId), tools, off, host::attachment), onUpdate)
                        }
                    } finally {
                        ticker.cancel()
                    }
                }
                latest.get()?.let { publish(chatId, it) }
                assemblers.remove(chatId)
                if (msg.container.isNotEmpty()) containers[chatId] = msg.container
                return msg
            } catch (e: ApiException) {
                // "This model does not support tools": the same request goes again without them, as a plain conversation.
                if (!anthropic && toolsKey !in noTools && Feature.TOOLS.hints.any { e.message.orEmpty().lowercase().contains(it) }) {
                    noTools += toolsKey; assemblers.remove(chatId)
                    if (cfg.agent) note(chatId, "info", "This model cannot use tools, so it will answer here without files, memory or connected accounts.")
                    continue
                }
                if (e.kind == ApiException.Kind.BAD_REQUEST) {
                    val rejected = RequestBuilder.rejectedFeature(e.message.orEmpty(), sentFeatures(cfg, off))
                    if (rejected != null) { disabled += rejected; assemblers.remove(chatId); continue }
                    if (!stripped && RequestBuilder.isBindingError(e.message.orEmpty())) { stripThinking(chatId); stripped = true; assemblers.remove(chatId); continue }
                    // A sandbox that has expired cannot be reused; the request goes again and gets a fresh one.
                    if (containers[chatId] != null && e.message.orEmpty().contains("container", ignoreCase = true)) { containers.remove(chatId); assemblers.remove(chatId); continue }
                    throw e
                }
                if (!e.retryable || attempt >= maxRetries) throw e
                attempt++
                assemblers.remove(chatId)
                val wait = if (e.retryAfterSec > 0) minOf(e.retryAfterSec, 60) else minOf(2 shl attempt, 30)
                val why = when (e.kind) {
                    ApiException.Kind.RATE_LIMIT -> "Rate limit reached"
                    ApiException.Kind.OVERLOADED -> "The service is busy"
                    ApiException.Kind.NETWORK -> "Connection lost"
                    else -> "The service had a problem"
                }
                for (left in wait downTo 1) {
                    setState(chatId) { it.copy(phase = Phase.RETRYING, status = "$why. Trying again in $left s", live = emptyList(), apiId = "") }
                    delay(tickMs)
                }
                setState(chatId) { it.copy(phase = Phase.CONNECTING, status = "") }
            }
        }
    }

    private fun publish(chatId: Long, asm: LiveReply) {
        val live = asm.live()
        val phase = when (live.lastOrNull()?.type) {
            null -> Phase.CONNECTING
            "thinking", "redacted_thinking" -> Phase.THINKING
            "text" -> Phase.WRITING
            else -> Phase.TOOL
        }
        setState(chatId) { it.copy(live = live, apiId = asm.id, phase = phase, status = "") }
    }

    private suspend fun saveReply(chatId: Long, cfg: RunConfig, msg: ApiMessage, preview: String) {
        val now = System.currentTimeMillis()
        val u = msg.usage
        dao.insert(
            Msg(
                chatId = chatId, role = "assistant", content = blobs.pack(chatId, msg.content.toString()), createdAt = now,
                apiId = msg.id, model = msg.model.ifEmpty { cfg.model }, stopReason = msg.stopReason,
                inTok = u.input + u.cacheRead + u.cacheWrite, outTok = u.output,
            )
        )
        dao.addUsage(chatId, u.input, u.output, u.cacheRead, u.cacheWrite, if (cfg.provider.kind == "anthropic") u.costMicros(msg.model.ifEmpty { cfg.model }, cfg.fast && ModelCaps.fast(cfg.model)) else 0)
        if (preview.isNotEmpty()) dao.touch(chatId, now, preview)
        if (cfg.provider.kind == "anthropic") saveProduced(chatId, cfg, msg)
    }

    /**
     * Files that code in the sandbox left in its output folder come back as ids in the result of the command.
     * Each is fetched, kept in the workspace under its own name, and noted in the chat so the person can open it.
     */
    private suspend fun saveProduced(chatId: Long, cfg: RunConfig, msg: ApiMessage) {
        val ids = msg.content.mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "bash_code_execution_tool_result" || it.str("type") == "code_execution_tool_result" }
            .flatMap { r -> r.obj("content")?.arr("content").orEmpty().mapNotNull { f -> (f as? JsonObject)?.str("file_id") } }
            .distinct()
        if (ids.isEmpty()) return
        val key = host.key(cfg.provider.id) ?: return
        for (id in ids) {
            val got = runCatching { claude.sandboxFile(key, id) }.getOrNull()
            if (got == null) { note(chatId, "info", "A file that was made could not be fetched. Ask for it again."); continue }
            // The same file handed over twice in one run (made, then copied out again with another) is shown once.
            val mark = got.second.contentHashCode()
            val seen = produced.getOrPut(chatId) { java.util.concurrent.ConcurrentHashMap() }
            if (seen.put(got.first, mark) == mark) continue
            val lines = runCatching { host.saveProduced(got.first, got.second) }.getOrNull()
            if (lines == null) note(chatId, "info", "${got.first} was made but could not be saved on this device.") else note(chatId, "file", SAVED + lines)
        }
    }

    /** Keeps the text that had arrived when a reply was stopped or the connection failed. */
    private suspend fun savePartial(chatId: Long) {
        val asm = assemblers.remove(chatId) ?: return
        // A file that was being written when the reply was stopped is not thrown away: what had arrived is kept in the
        // chat, as text, so it can be read. Nothing is written to the workspace: a half-finished file is not saved over a whole one.
        val draft = asm.live().lastOrNull { it.type == "tool_use" && !it.done && it.draft.isNotBlank() }
        val kept = draft?.let { d ->
            val name = d.draftPath.ifBlank { "a file" }
            val plain = name.endsWith(".md", true) || name.endsWith(".txt", true) || !name.contains('.')
            "**Stopped while writing ${if (d.draftPath.isBlank()) name else "`$name`"}.** It was not saved. This is what had been written:\n\n" +
                (if (plain) d.draft else "```" + name.substringAfterLast('.', "") + "\n" + d.draft + "\n```")
        }
        val text = listOf(asm.partialText(), kept.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        if (text.isBlank()) return
        val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) }))
        dao.insert(Msg(chatId = chatId, role = "assistant", content = content.toString(), createdAt = System.currentTimeMillis(), apiId = asm.id, model = asm.model, stopReason = "stopped"))
    }

    private fun toolResult(id: String, outcome: ToolOutcome): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", id)
        put("content", outcome.text)
        if (outcome.isError) put("is_error", true)
    }

    private suspend fun answerAll(chatId: Long, uses: List<JsonObject>, text: String) {
        val results = uses.mapNotNull { u -> u.str("id")?.let { toolResult(it, ToolOutcome(text, true)) } }
        dao.insert(Msg(chatId = chatId, role = "user", content = JsonArray(results).toString(), createdAt = System.currentTimeMillis()))
    }

    /** If the last reply asked for tools that never got an answer, answer them, so the history stays valid. */
    private suspend fun repairDangling(chatId: Long, reason: String) {
        val last = dao.messages(chatId).lastOrNull { it.role != "note" } ?: return
        if (last.role != "assistant") return
        val uses = RequestBuilder.pendingToolUses(parseArray(blobs.unpack(last.content)))
        if (uses.isNotEmpty()) answerAll(chatId, uses, reason)
    }

    private suspend fun stripThinking(chatId: Long) {
        for (m in dao.messages(chatId)) {
            if (m.role != "assistant") continue
            val before = parseArray(blobs.unpack(m.content))
            val after = RequestBuilder.withoutThinking(before)
            if (after.size != before.size) dao.update(m.copy(content = blobs.pack(chatId, JsonArray(after).toString())))
        }
    }

    /** Runs every tool call of one step, in order, and saves all the results as one message. */
    private suspend fun runTools(chatId: Long, chat: Chat, uses: List<JsonObject>, invalid: Map<String, String>) {
        val results = LinkedHashMap<String, JsonObject>()
        try {
            for (u in uses) {
                val id = u.str("id") ?: continue
                val name = u.str("name").orEmpty()
                val input = u.obj("input") ?: JsonObject(emptyMap())
                val began = System.currentTimeMillis()
                var outcome = when {
                    id in invalid -> ToolOutcome(buildJsonObject { put("INVALID_JSON", invalid[id].orEmpty()) }.toString(), true)
                    !approved(chatId, chat, id, name, input) -> ToolOutcome("The person declined this action. Do not try it again; ask what they would like instead.", true)
                    name == Tools.SANDBOX_PUT -> {
                        setState(chatId) { it.copy(phase = Phase.TOOL, runningTool = id) }
                        sendToSandbox(chat, input)
                    }
                    else -> {
                        setState(chatId) { it.copy(phase = Phase.TOOL, runningTool = id) }
                        // Interruptible, so Stop also ends a long wait such as a video being made.
                        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { tools.run(chatId, name, input, id) }
                    }
                }
                // What a tool brings back from outside is data. If it is worded as orders to the model, it is marked,
                // the person is told, and nothing more runs unasked in this task.
                if (!outcome.isError && (name in READS_OUTSIDE || tools.origin(name) != null)) Shield.flag(outcome.text)?.let { sign ->
                    val from = tools.describe(name, input)
                    outcome = outcome.copy(text = Shield.wrap(from, outcome.text))
                    shielded += chatId
                    taskAllowed.remove(chatId)
                    note(chatId, "error", Shield.notice(from, sign))
                }
                // An incognito chat leaves no trace, so its calls are not written down either.
                if (!chat.uid.startsWith(app.pocketagent.data.INCOGNITO)) tools.log?.add(ToolCall(
                    at = began, chat = chatId, server = tools.home(name), tool = tools.origin(name)?.second ?: name, args = ActivityLog.args(input), ok = !outcome.isError,
                    summary = ActivityLog.summary(outcome.text), tokens = ActivityLog.tokens(input.toString(), outcome.text), ms = System.currentTimeMillis() - began,
                ))
                results[id] = toolResult(id, outcome)
                setState(chatId) { it.copy(done = it.done + (id to outcome), runningTool = "") }
            }
        } finally {
            withContext(NonCancellable) {
                for (u in uses) {
                    val id = u.str("id") ?: continue
                    if (id !in results) results[id] = toolResult(id, ToolOutcome("Stopped before this ran.", true))
                }
                dao.insert(Msg(chatId = chatId, role = "user", content = blobs.pack(chatId, JsonArray(results.values.toList()).toString()), createdAt = System.currentTimeMillis()))
            }
        }
    }

    /** Copies a workspace file to the service, where the code sandbox is given it with the next step. */
    private suspend fun sendToSandbox(chat: Chat, input: JsonObject): ToolOutcome {
        val key = host.key(chat.provider) ?: return ToolOutcome("No API key is set, so the file could not be sent.", true)
        return try {
            val (name, bytes) = withContext(Dispatchers.IO) { tools.sandboxFile(input.str("path") ?: throw ToolError("Missing required field: path")) }
            val id = claude.sandboxUpload(key, name, bytes)
            val until = System.currentTimeMillis() / 1000 + ClaudeApi.UPLOAD_SECONDS - 3600
            ToolOutcome(
                "$name (${bytes.size} bytes) is now in the sandbox. It is under /files/input/ and not in the working folder: find it with  find /files/input -name '${name.replace("'", "")}'  and work on a copy. " +
                    "[sandbox file $id until $until]",
            )
        } catch (e: ToolError) {
            ToolOutcome(e.message ?: "The file could not be read.", true)
        } catch (e: ApiException) {
            ToolOutcome("The file could not be sent to the sandbox: ${e.friendly}", true)
        }
    }

    private suspend fun approved(chatId: Long, chat: Chat, id: String, name: String, input: JsonObject): Boolean {
        val risk = tools.risk(name, input)
        val harmless = name == "update_tasks" || name == "get_time"
        // Something that leaves the phone for good (an email, a change on GitHub) is asked about every time, whatever the setting.
        val outside = tools.origin(name) != null
        val granted = taskAllowed[chatId]?.contains(name) == true
        val auto = when {
            // After a result that tried to give orders, only looking is done unasked.
            chatId in shielded -> harmless || (risk == Risk.READ && chat.permission != "ask")
            // An email or a change on GitHub is asked about every time. A tool of an outside server is too, unless the person allowed it for this task.
            risk == Risk.OUTWARD -> outside && granted
            else -> when (chat.permission) {
                "full_auto" -> true
                "ask" -> harmless
                else -> risk == Risk.READ
            } || (risk != Risk.DESTRUCTIVE && granted)
        }
        if (auto) return true
        val waiter = CompletableDeferred<Decision>()
        approvals[chatId] = waiter
        val title = tools.describe(name, input)
        setState(chatId) { it.copy(phase = Phase.APPROVAL, pending = PendingApproval(id, name, title, input, risk)) }
        host.approvalNeeded(chat, title)
        val decision = try {
            waiter.await()
        } finally {
            approvals.remove(chatId)
            setState(chatId) { it.copy(pending = null) }
            host.approvalResolved(chatId)
        }
        // "For this task": the name of the choice is from when it lasted longer; it now ends with the task.
        if (decision == Decision.ALLOW_ALWAYS && risk != Risk.DESTRUCTIVE && (risk != Risk.OUTWARD || outside)) taskAllowed.getOrPut(chatId) { Collections.synchronizedSet(HashSet()) }.add(name)
        return decision != Decision.DENY
    }

    companion object {
        private const val MAX_RETRIES = 4
        /** Failures a backup model is worth trying after: the service is down, busy, out of credit, or refuses the key or the model. */
        private val BACKUP_KINDS = setOf(
            ApiException.Kind.RATE_LIMIT, ApiException.Kind.OVERLOADED, ApiException.Kind.SERVER, ApiException.Kind.NETWORK,
            ApiException.Kind.BILLING, ApiException.Kind.AUTH, ApiException.Kind.NOT_FOUND, ApiException.Kind.PERMISSION,
        )
        /** The app's own tools that bring in text somebody else wrote. */
        private val READS_OUTSIDE = setOf("read_file", "github", "gmail_search", "gmail_read")
        /** How a note about a file the sandbox produced begins; the chat shows such a note as a file to open. */
        const val SAVED = "Saved to Files: "
    }
}
