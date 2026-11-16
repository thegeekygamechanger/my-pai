package app.pocketagent.ai

import app.pocketagent.data.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Making pictures and videos. The chat model does not draw: it calls one of these tools, and the app asks a
 * service that can (Gemini or OpenAI for pictures, Gemini for video) with the person's own key for that
 * service. The result is saved on the device next to the chat's attachments and shown in the chat.
 *
 * The tools exist only when a key that can do the job is connected, and they work whichever model is chatting.
 */
class Media(
    private val secret: (String) -> String?,
    private val dir: File,
    private val openaiUrl: String = "https://api.openai.com/v1",
    private val geminiUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val pollMs: Long = 5000,
    /** Whether pictures may come from the free open-source service when no key of the person's own can make them. */
    private val free: () -> Boolean = { false },
    private val freeUrl: String = "https://image.pollinations.ai",
    private val pollinationsUrl: String = "https://gen.pollinations.ai",
) {
    // A video can take minutes to come back, so reads are allowed to wait far longer than for ordinary calls.
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).writeTimeout(60, TimeUnit.SECONDS).callTimeout(12, TimeUnit.MINUTES).build()
    }

    private fun key(provider: String): String? = secret(Providers.vaultKey(provider))?.takeIf { it.isNotBlank() }

    fun imageOn() = key("gemini") != null || key("openai") != null || key("pollinations") != null || free()
    fun videoOn() = key("gemini") != null || key("pollinations") != null

    private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
    private fun choice(description: String, vararg values: String) = buildJsonObject { put("type", "string"); put("description", description); putJsonArray("enum") { values.forEach { add(it) } } }

    fun specs(): List<ToolSpec> = buildList {
        if (imageOn()) add(ToolSpec(
            IMAGE, "Create a picture from a description and show it to the person in the chat. Use it when the person asks for an image, drawing, logo, poster, illustration or photo to be made. Write a specific prompt of a few sentences: subject, setting, style, lighting, and any text that must appear. It can take up to a minute; call it once for each picture wanted and wait for the result.",
            Risk.READ, listOf("prompt"), buildJsonObject {
                put("prompt", prop("string", "What the picture should show, in detail."))
                put("shape", choice("The shape of the picture. Default square.", "square", "wide", "tall"))
            },
        ))
        if (videoOn()) add(ToolSpec(
            VIDEO, "Create a short video clip (3 to 15 seconds) from a description and show it to the person in the chat. Use it only when the person asks for a video. It takes a minute or more and costs more than a picture. Write a full prompt: the scene, the action, the camera movement and the mood.",
            Risk.WRITE, listOf("prompt"), buildJsonObject {
                put("prompt", prop("string", "What happens in the video, in detail."))
                put("shape", choice("wide for a screen, tall for a phone. Default wide.", "wide", "tall"))
                put("seconds", prop("integer", "Length in seconds, from 3 to 15. Default 8."))
            },
        ))
    }

    fun handles(name: String) = name == IMAGE || name == VIDEO

    fun risk(name: String): Risk? = when (name) {
        IMAGE -> Risk.READ
        VIDEO -> Risk.WRITE
        else -> null
    }

    fun describe(name: String, input: JsonObject): String? = when (name) {
        IMAGE -> "Create a picture: " + input.str("prompt").orEmpty().take(70)
        VIDEO -> "Create a video: " + input.str("prompt").orEmpty().take(70)
        else -> null
    }

    /** Blocks until the picture or video is saved. Interrupting the thread stops the wait. */
    fun run(name: String, input: JsonObject): String {
        val prompt = input.str("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) throw ToolError("Field \"prompt\" must say what to create.")
        return try {
            when (name) {
                IMAGE -> image(prompt, input.str("shape") ?: "square")
                VIDEO -> video(prompt, input.str("shape") ?: "wide", (input.long("seconds") ?: 8).coerceIn(3, 15).toInt())
                else -> throw ToolError("Unknown tool: $name")
            }
        } catch (e: java.io.InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw ToolError("No connection, or the service took too long. Check the internet and try again.")
        }
    }

    private class Made(val bytes: ByteArray?, val file: File?, val mime: String)

    private fun image(prompt: String, shape: String): String {
        val problems = ArrayList<String>()
        // Whichever connected service works: the person's own keys first, then the free open-source service.
        for (service in listOf("gemini", "openai", "pollinations", "free")) {
            val k = if (service == "free") "".takeIf { free() } else key(service)
            k ?: continue
            try {
                return saved(when (service) {
                    "gemini" -> geminiImage(k, prompt, shape)
                    "openai" -> openaiImage(k, prompt, shape)
                    "pollinations" -> pollinations("image", k, prompt, shape, POLLINATIONS_IMAGE, 0)
                    else -> freeImage(prompt, shape)
                }, "picture")
            } catch (e: ToolError) {
                problems += e.message.orEmpty()
            }
        }
        throw ToolError(problems.joinToString(" ").ifBlank { "Making pictures is switched off. Turn on the free picture service in Settings, or add a Gemini, OpenAI or Pollinations key." })
    }

    private fun video(prompt: String, shape: String, seconds: Int): String {
        val k = key("gemini") ?: run {
            // No Gemini key: an open-source video model through the person's Pollinations key, where clips are five seconds.
            val p = key("pollinations") ?: throw ToolError("Making videos needs a Gemini or Pollinations key. Add one in Settings, AI services. There is no free service for video.")
            return saved(pollinations("video", p, prompt, shape, POLLINATIONS_VIDEO, 5), "video")
        }
        val format = buildJsonObject { put("type", "video"); put("aspect_ratio", if (shape == "tall") "9:16" else "16:9"); put("duration", "${seconds}s") }
        val made = gemini(k, GEMINI_VIDEO, listOf("omni", "veo"), "video", emptyMap()) { model, plain ->
            buildJsonObject { put("model", model); put("input", prompt); if (!plain) { put("response_format", format); put("store", false) } }
        }
        return saved(made, "video")
    }

    private fun geminiImage(key: String, prompt: String, shape: String): Made {
        val format = buildJsonObject { put("type", "image"); put("aspect_ratio", when (shape) { "wide" -> "16:9"; "tall" -> "9:16"; else -> "1:1" }) }
        return gemini(key, GEMINI_IMAGE, listOf("banana", "image"), "image", mapOf("Api-Revision" to "2026-05-20")) { model, plain ->
            buildJsonObject {
                put("model", model)
                putJsonArray("input") { add(buildJsonObject { put("type", "text"); put("text", prompt) }) }
                if (!plain) { put("response_format", format); put("store", false) }
            }
        }
    }

    /**
     * One request to Gemini's interactions endpoint, with two fall-backs so a change on their side does not
     * break the feature: if the usual model is gone, the newest one of the same family is looked up and
     * used; if an optional setting is refused, the request is sent again with the prompt alone.
     *
     * @param family words that mark a model of the right kind in Gemini's model list, best first
     * @param body builds the request for a model; its second argument asks for the prompt alone
     */
    private fun gemini(key: String, model: String, family: List<String>, kind: String, headers: Map<String, String>, body: (String, Boolean) -> JsonObject): Made {
        val auth = headers + ("x-goog-api-key" to key)
        fun ask(m: String, plain: Boolean) = post("$geminiUrl/interactions", auth, body(m, plain), "Gemini")
        var used = model
        var (code, reply) = ask(used, false)
        if (code == 404 || (code == 400 && message(reply).contains("model", true) && !message(reply).contains("response_format"))) {
            used = geminiModel(key, family) ?: throw ToolError("Gemini: " + message(reply))
            ask(used, false).let { code = it.first; reply = it.second }
        }
        if (code == 400) ask(used, true).let { code = it.first; reply = it.second }
        if (code !in 200..299) throw ToolError(friendly("Gemini", code, reply))
        var result = reply
        // Usually the answer is complete at once. If the work carries on at their end, ask again until it is done.
        var waited = 0L
        while (find(result, kind) == null && result.str("status") in WORKING && result.str("id") != null && waited < MAX_WAIT_MS) {
            Thread.sleep(pollMs); waited += pollMs
            val (c, next) = get("$geminiUrl/interactions/${result.str("id")}", auth)
            if (c !in 200..299) throw ToolError(friendly("Gemini", c, next))
            result = next
        }
        val found = find(result, kind) ?: throw ToolError(
            if (result.str("status") in WORKING) "Gemini is still working on it after several minutes. Try again later."
            else "Gemini did not return a $kind. It may have declined the request; try describing it differently."
        )
        val mime = found.str("mime_type") ?: if (kind == "video") "video/mp4" else "image/png"
        found.str("data")?.takeIf { it.isNotBlank() }?.let { return Made(decode(it), null, mime) }
        // A large result arrives as a link to a file that may need a moment before it can be fetched.
        val uri = found.str("uri") ?: throw ToolError("Gemini returned the $kind in a form this app does not understand.")
        val target = File(dir.also { it.mkdirs() }, "gen-" + UUID.randomUUID() + ".part")
        waited = 0
        while (true) {
            http.newCall(Request.Builder().url(uri).header("x-goog-api-key", key).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    target.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                    return Made(null, target, resp.header("content-type")?.substringBefore(';')?.takeIf { it.startsWith("video/") || it.startsWith("image/") } ?: mime)
                }
                if (resp.code in listOf(401, 403) || waited >= MAX_WAIT_MS) throw ToolError("Gemini made the $kind but it could not be downloaded (error ${resp.code}).")
            }
            Thread.sleep(pollMs); waited += pollMs
        }
    }

    /** The newest model of a family that the key can use, from Gemini's own list. */
    private fun geminiModel(key: String, family: List<String>): String? {
        val (code, reply) = get("$geminiUrl/models?pageSize=1000", mapOf("x-goog-api-key" to key))
        if (code !in 200..299) return null
        val names = reply.arr("models")?.mapNotNull { it.asObj()?.str("name")?.removePrefix("models/") }.orEmpty()
        for (word in family) names.filter { it.contains(word, true) && !it.contains("embedding", true) }.maxOrNull()?.let { return it }
        return null
    }

    private fun openaiImage(key: String, prompt: String, shape: String): Made {
        val auth = mapOf("authorization" to "Bearer $key")
        // The image models are renamed often, so the one to use is taken from the account's own list when it can be read.
        val listed = get("$openaiUrl/models", auth).takeIf { it.first in 200..299 }?.second?.arr("data")?.mapNotNull { it.asObj()?.str("id") }.orEmpty().filter { it.startsWith("gpt-image") }
        val model = when {
            OPENAI_IMAGE in listed || listed.isEmpty() -> OPENAI_IMAGE
            else -> listed.filter { !it.contains("mini") }.maxOrNull() ?: listed.max()
        }
        val (code, reply) = post("$openaiUrl/images/generations", auth, buildJsonObject {
            put("model", model); put("prompt", prompt)
            put("size", when (shape) { "wide" -> "1536x1024"; "tall" -> "1024x1536"; else -> "1024x1024" })
        }, "OpenAI")
        if (code !in 200..299) throw ToolError(friendly("OpenAI", code, reply))
        val first = reply.arr("data")?.firstOrNull()?.asObj() ?: throw ToolError("OpenAI did not return a picture.")
        first.str("b64_json")?.let { return Made(decode(it), null, "image/png") }
        val url = first.str("url") ?: throw ToolError("OpenAI did not return a picture.")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolError("OpenAI made the picture but it could not be downloaded.")
            return Made(resp.body.bytes(), null, resp.header("content-type")?.substringBefore(';') ?: "image/png")
        }
    }

    /**
     * The free picture service: Pollinations, an open-source project running open models, with no key and no
     * account. It allows one picture at a time and asks for a pause between them; when it says "not yet", the
     * wait is short enough to sit out once.
     */
    private fun freeImage(prompt: String, shape: String): Made {
        // The service draws up to 768 by 768 points in area, whatever the shape.
        val (w, h) = when (shape) { "wide" -> 1024 to 576; "tall" -> 576 to 1024; else -> 768 to 768 }
        val url = "$freeUrl/prompt/" + java.net.URLEncoder.encode(prompt.take(700), "UTF-8").replace("+", "%20") +
            "?width=$w&height=$h&nologo=true&private=true&seed=" + (1..999_999).random()
        // Two things are sat out rather than reported: "not yet" (its pause between pictures), and a passing fault at its end.
        var waited = false
        var faults = 0
        while (true) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "PocketAgent").build()).execute().use { resp ->
                if (resp.isSuccessful && resp.header("content-type").orEmpty().startsWith("image/")) return Made(resp.body.bytes(), null, resp.header("content-type")!!.substringBefore(';'))
                val limited = resp.code == 402 || resp.code == 429
                val pause = resp.header("Retry-After")?.toLongOrNull() ?: 46
                // One line for anyone looking into a failed picture: the service's answer, never what was asked for.
                System.err.println("free picture service: HTTP ${resp.code}, retry-after ${resp.header("Retry-After") ?: "none"}, ${resp.header("content-type") ?: "no type"}, body ${runCatching { resp.peekBody(160).string() }.getOrDefault("").take(160).replace('\n', ' ')}")
                when {
                    limited && !waited && pause <= 60 -> { waited = true; Thread.sleep(pause * 1000 + 1000) }
                    resp.code >= 500 && faults < 2 -> { faults++; Thread.sleep(3000L * faults) }
                    limited -> throw ToolError("The free picture service allows one picture every 45 seconds or so. Try again in a minute.")
                    else -> throw ToolError("The free picture service could not make it (error ${resp.code}). Try again, or describe it differently.")
                }
            }
        }
    }

    /** A picture or a video through the person's own Pollinations key, which opens its paid models: the answer is the file itself. */
    private fun pollinations(kind: String, key: String, prompt: String, shape: String, model: String, seconds: Int): Made {
        val (w, h) = when (shape) { "wide" -> 1280 to 720; "tall" -> 720 to 1280; else -> 1024 to 1024 }
        val url = "$pollinationsUrl/$kind/" + java.net.URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20") + "?model=$model" +
            if (kind == "video") "&duration=$seconds&aspectRatio=" + (if (shape == "tall") "9:16" else "16:9") else "&width=$w&height=$h&nologo=true&private=true"
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key").header("User-Agent", "PocketAgent").build()).execute().use { resp ->
            val type = resp.header("content-type").orEmpty().substringBefore(';')
            if (!resp.isSuccessful || !(type.startsWith("image/") || type.startsWith("video/"))) {
                val reply = runCatching { parseObject(resp.body.string()) }.getOrNull() ?: JsonObject(emptyMap())
                throw ToolError(friendly("Pollinations", resp.code, reply))
            }
            if (kind == "image") return Made(resp.body.bytes(), null, type)
            val target = File(dir.also { it.mkdirs() }, "gen-" + UUID.randomUUID() + ".part")
            target.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
            return Made(null, target, type)
        }
    }

    private fun saved(made: Made, what: String): String {
        val ext = when (made.mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"
            "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"
            else -> if (what == "video") "mp4" else "png"
        }
        dir.mkdirs()
        val file = File(dir, "gen-" + UUID.randomUUID() + "." + ext)
        if (made.file != null) { if (!made.file.renameTo(file)) { made.file.copyTo(file, true); made.file.delete() } } else file.writeBytes(made.bytes ?: ByteArray(0))
        if (file.length() < 100) { file.delete(); throw ToolError("The $what came back empty. Try again.") }
        return "Done. The $what is now showing to the person in the chat (att/${file.name}). Reply with one short line; do not describe it at length and do not mention the file name."
    }

    private fun decode(b64: String): ByteArray = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull() ?: throw ToolError("The result could not be read.")

    /** The last picture or video in an answer, wherever it sits: the layout of the answer has changed before. */
    private fun find(el: JsonElement?, kind: String): JsonObject? {
        var last: JsonObject? = null
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> {
                    if (e.str("type") == kind && (e.str("data") != null || e.str("uri") != null)) last = e
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(el)
        return last
    }

    private fun post(url: String, headers: Map<String, String>, body: JsonObject, service: String): Pair<Int, JsonObject> {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { resp -> resp.code to (parseObject(resp.body.string()) ?: JsonObject(emptyMap())) }
    }

    private fun get(url: String, headers: Map<String, String>): Pair<Int, JsonObject> {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use { resp -> resp.code to (parseObject(resp.body.string()) ?: JsonObject(emptyMap())) }
    }

    private fun message(reply: JsonObject): String = reply.obj("error")?.str("message") ?: reply.str("error") ?: reply.str("message") ?: ""

    private fun friendly(service: String, code: Int, reply: JsonObject): String {
        val said = message(reply).take(300)
        return when (code) {
            401, 403 -> "$service refused the key for this. Check the key in Settings, and that the account is allowed to create pictures or videos."
            429 -> "$service says the limit for this key is reached, or billing is not set up. " + said
            else -> "$service could not do it (error $code). $said"
        }.trim()
    }

    companion object {
        const val IMAGE = "generate_image"
        const val VIDEO = "generate_video"
        const val GEMINI_IMAGE = "gemini-nano-banana-2.1"
        const val GEMINI_VIDEO = "gemini-omni-1.2-flash"
        const val OPENAI_IMAGE = "gpt-image-2.5-sunburst"
        /** Open-source models on Pollinations: FLUX schnell for pictures, Wan for video. */
        const val POLLINATIONS_IMAGE = "black-forest-labs/flux.1-schnell"
        const val POLLINATIONS_VIDEO = "alibaba/wan-2.2-fast"
        private const val MAX_WAIT_MS = 10 * 60_000L
        private val WORKING = setOf("in_progress", "queued", "running", "pending", "requires_action")
        /** How a created file is named inside a tool's result, which is how the chat finds it to show it. */
        val REF = Regex("att/(gen-[A-Za-z0-9-]+\\.(png|jpg|webp|gif|mp4|webm|mov))")
    }
}
