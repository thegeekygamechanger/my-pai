package app.pocketagent.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The "chat completions" shape that OpenAI, OpenRouter, Groq, Gemini, Mistral, DeepSeek and many others share.
 *
 * Conversations are stored in one format (Anthropic-style content blocks). This file translates that format
 * to and from the chat-completions shape on every request, so a chat can even switch service half-way.
 */
object OpenAiCompat {

    /** The phone's tools as "function" tools. The memory tool, which Claude knows natively, is spelled out here. */
    fun tools(cfg: RunConfig, clientTools: Tools?, focus: ToolFocus? = null): JsonArray = buildJsonArray {
        if (clientTools == null) return@buildJsonArray
        for (t in clientTools.definitions(eager = false, mediaOnly = !cfg.agent, focus = focus)) {
            val o = t as JsonObject
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") { put("name", o.str("name").orEmpty()); put("description", o.str("description").orEmpty()); put("parameters", o["input_schema"] ?: JsonObject(emptyMap())) }
            })
        }
        if (cfg.memory) add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", Tools.MEMORY)
                put("description", "Notes that persist between chats, kept as files under /memories on the phone. Commands: view (a folder or a file), create (path, file_text), str_replace (path, old_str, new_str), insert (path, insert_line, insert_text), delete (path), rename (old_path, new_path). Look at /memories before starting work, and save what will be useful later.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("command") { put("type", "string"); putJsonArray("enum") { listOf("view", "create", "str_replace", "insert", "delete", "rename").forEach { add(it) } } }
                        for (k in listOf("path", "file_text", "old_str", "new_str", "insert_text", "old_path", "new_path")) putJsonObject(k) { put("type", "string") }
                        putJsonObject("insert_line") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("command") }
                }
            }
        })
    }

    fun build(cfg: RunConfig, messages: List<JsonObject>, clientTools: Tools?, disabled: Set<Feature>, loadFile: (String) -> ByteArray?): JsonObject = buildJsonObject {
        put("model", cfg.model)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        val toolList = if (Feature.TOOLS in disabled) JsonArray(emptyList()) else tools(cfg, clientTools, RequestBuilder.focus(messages))
        if (toolList.isNotEmpty()) put("tools", toolList)
        if (Feature.EFFORT !in disabled) {
            val effort = when (cfg.effort) { "xhigh", "max" -> "high"; else -> cfg.effort }
            if (effort in Providers.efforts(cfg.provider, cfg.model)) {
                if (cfg.provider.id == "openrouter") putJsonObject("reasoning") { put("effort", effort) } else put("reasoning_effort", effort)
            }
        }
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", RequestBuilder.system(cfg)) })
            for (m in messages) convert(m, loadFile).forEach { add(it) }
        }
    }

    private fun convert(msg: JsonObject, loadFile: (String) -> ByteArray?): List<JsonObject> {
        val blocks = msg.arr("content").orEmpty().mapNotNull { it.asObj() }
        if (msg.str("role") == "assistant") {
            val text = blocks.filter { it.str("type") == "text" }.joinToString("\n\n") { it.str("text").orEmpty() }
            val calls = RequestBuilder.effective(blocks).mapNotNull { it.asObj() }.filter { it.str("type") == "tool_use" }
            if (text.isBlank() && calls.isEmpty()) return emptyList()
            return listOf(buildJsonObject {
                put("role", "assistant")
                put("content", text)
                if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                    calls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.str("id").orEmpty()); put("type", "function")
                            putJsonObject("function") { put("name", c.str("name").orEmpty()); put("arguments", (c.obj("input") ?: JsonObject(emptyMap())).toString()) }
                        })
                    }
                }
            })
        }
        val out = ArrayList<JsonObject>()
        // Tool results first: they answer the assistant turn just before.
        for (b in blocks) if (b.str("type") == "tool_result") out += buildJsonObject {
            put("role", "tool"); put("tool_call_id", b.str("tool_use_id").orEmpty())
            put("content", when (val c = b["content"]) {
                is JsonPrimitive -> c.content
                is JsonArray -> c.mapNotNull { it.asObj()?.str("text") }.joinToString("\n")
                else -> ""
            })
        }
        val parts = ArrayList<JsonObject>()
        for (b in blocks) when (b.str("type")) {
            "text" -> parts += buildJsonObject { put("type", "text"); put("text", b.str("text").orEmpty()) }
            "image" -> {
                val src = b.obj("source")
                val data = when (src?.str("type")) {
                    "local" -> src.str("path")?.let(loadFile)?.let { Base64.getEncoder().encodeToString(it) }
                    "base64" -> src.str("data")
                    else -> null
                }
                if (data != null) parts += buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:${src?.str("media_type") ?: "image/jpeg"};base64,$data") } }
                else parts += buildJsonObject { put("type", "text"); put("text", "[An image that is no longer on this device.]") }
            }
            "document" -> {
                val src = b.obj("source")
                val title = b.str("title") ?: "document"
                parts += buildJsonObject {
                    put("type", "text")
                    put("text", if (src?.str("type") == "text") "File \"$title\":\n\n${src.str("data").orEmpty()}" else "[A PDF named \"$title\" is attached, but this model cannot read PDFs through this service.]")
                }
            }
        }
        if (parts.isNotEmpty()) out += buildJsonObject {
            put("role", "user")
            // Plain text goes as a string: some services accept nothing else.
            if (parts.all { it.str("type") == "text" }) put("content", parts.joinToString("\n\n") { it.str("text").orEmpty() }) else put("content", JsonArray(parts))
        }
        return out
    }
}

/** Rebuilds a chat-completions reply from its stream, into the same blocks a Claude reply has. */
class OpenAiAssembler : LiveReply {
    private class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    override var id = ""; private set
    override var model = ""; private set
    var finished = false; private set
    private var finish: String? = null
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = sortedMapOf<Int, Call>()
    private var promptTokens = 0L
    private var outTokens = 0L
    private var cachedTokens = 0L

    @Synchronized
    fun accept(chunk: JsonObject) {
        chunk.obj("error")?.let { e -> throw ApiException(ApiException.Kind.SERVER, e.str("message") ?: "The service reported an error.") }
        chunk.str("id")?.let { if (id.isEmpty()) id = it }
        chunk.str("model")?.let { if (model.isEmpty()) model = it }
        chunk.obj("usage")?.let { u ->
            promptTokens = u.long("prompt_tokens") ?: promptTokens
            outTokens = u.long("completion_tokens") ?: outTokens
            cachedTokens = u.obj("prompt_tokens_details")?.long("cached_tokens") ?: cachedTokens
        }
        val choice = chunk.arr("choices")?.firstOrNull()?.asObj() ?: return
        choice.str("finish_reason")?.let { finish = it }
        val delta = choice.obj("delta") ?: return
        delta.str("content")?.let { text.append(it) }
        (delta.str("reasoning_content") ?: delta.str("reasoning"))?.let { reasoning.append(it) }
        delta.arr("tool_calls")?.forEach { el ->
            val c = el.asObj() ?: return@forEach
            val call = calls.getOrPut((c.long("index") ?: calls.size.toLong()).toInt()) { Call() }
            c.str("id")?.let { if (it.isNotEmpty()) call.id = it }
            c.obj("function")?.let { f ->
                f.str("name")?.let { if (it.isNotEmpty()) call.name = it }
                f.str("arguments")?.let { call.args.append(it) }
            }
        }
    }

    @Synchronized fun done() { finished = true }

    @Synchronized
    override fun live(): List<LiveBlock> = buildList {
        if (reasoning.isNotBlank()) add(LiveBlock("thinking", text = reasoning.toString(), done = text.isNotEmpty() || calls.isNotEmpty() || finished))
        if (text.isNotEmpty()) add(LiveBlock("text", text = text.toString(), done = calls.isNotEmpty() || finished))
        calls.values.forEach { c ->
            val draft = if (finished) null else Draft.of(c.name, c.args)
            add(LiveBlock("tool_use", name = c.name, id = c.id, inputPreview = c.args.toString().take(600), done = finished, draftPath = draft?.first.orEmpty(), draft = draft?.second.orEmpty()))
        }
    }

    @Synchronized override fun partialText(): String = text.toString().trim()

    @Synchronized
    fun toMessage(): ApiMessage {
        val invalid = LinkedHashMap<String, String>()
        val content = buildJsonArray {
            // Kept for display only. It is a different block type from Claude's signed "thinking", and is never sent back.
            if (reasoning.isNotBlank()) add(buildJsonObject { put("type", "reasoning"); put("thinking", reasoning.toString()) })
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text.toString()) })
            calls.values.forEachIndexed { i, c ->
                val callId = c.id.ifEmpty { "call_${id.takeLast(8)}_$i" }
                val raw = c.args.toString()
                val input = if (raw.isBlank()) JsonObject(emptyMap()) else parseObject(raw)
                if (input == null) invalid[callId] = raw
                add(buildJsonObject { put("type", "tool_use"); put("id", callId); put("name", c.name); put("input", input ?: JsonObject(emptyMap())) })
            }
        }
        val stop = when {
            calls.isNotEmpty() && finish != "length" -> "tool_use"
            finish == "length" -> "max_tokens"
            finish == "content_filter" -> "refusal"
            else -> "end_turn"
        }
        return ApiMessage(id.ifEmpty { "oa_" + System.nanoTime() }, model, content, stop, null, Usage(input = (promptTokens - cachedTokens).coerceAtLeast(0), output = outTokens, cacheRead = cachedTokens), invalid)
    }
}

class OpenAiApi {
    private val http = OkHttpClient.Builder().guarded()
        // Never carried from an https address to a plain http one, key and all.
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun request(provider: Provider, path: String, key: String): Request.Builder =
        Request.Builder().url(provider.baseUrl.trimEnd('/') + path)
            .header("authorization", "Bearer $key")
            // OpenRouter shows these on its dashboard; other services ignore them.
            .header("x-title", "My PAI")

    /** The models a key can use. Also the way a pasted key is checked. */
    suspend fun listModels(provider: Provider, key: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(provider, "/models", key).get().build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw ApiException.fromHttp(resp.code, text, 0)
                val json = parseObject(text)
                val list = (json?.arr("data") ?: runCatching { AppJson.parseToJsonElement(text) as? JsonArray }.getOrNull()).orEmpty()
                list.mapNotNull { e: JsonElement ->
                    val m = e.asObj() ?: return@mapNotNull null
                    val id = m.str("id") ?: return@mapNotNull null
                    ModelInfo(id, m.str("name") ?: m.str("display_name") ?: id, m.long("context_length") ?: m.long("context_window") ?: 0, 0, provider.id)
                }
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "No connection. Check the internet and try again.")
        }
    }

    suspend fun stream(provider: Provider, key: String, body: JsonObject, onUpdate: (OpenAiAssembler) -> Unit): ApiMessage = withContext(Dispatchers.IO) {
        val req = request(provider, "/chat/completions", key).header("accept", "text/event-stream").post(body.toString().toRequestBody(JSON)).build()
        val call = http.newCall(req)
        val onCancel = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()?.toInt() ?: 0
                    throw ApiException.fromHttp(resp.code, resp.body.string(), retryAfter)
                }
                val source = resp.body.source()
                val asm = OpenAiAssembler()
                var sawData = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") { asm.done(); break }
                    val chunk = parseObject(data) ?: continue
                    sawData = true
                    asm.accept(chunk)
                    onUpdate(asm)
                }
                if (!sawData) throw ApiException(ApiException.Kind.NETWORK, "The connection dropped before the reply started.")
                // Not every service ends with [DONE]; a stream that closed after a finish reason is complete too.
                asm.done()
                onUpdate(asm)
                asm.toMessage()
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw ApiException(ApiException.Kind.NETWORK, "No connection. Check the internet and try again.")
        } finally {
            onCancel.cancel()
        }
    }

    private companion object { val JSON = "application/json".toMediaType() }
}
