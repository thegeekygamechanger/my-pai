package app.pocketagent.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The one place that talks to the Claude API. Plain HTTPS with OkHttp: there is no official SDK for
 * Android, and the JVM one would add tens of megabytes to the app.
 */
class ClaudeApi(private val baseUrl: () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // A hard problem can think for minutes between events; the service sends pings in between.
        .readTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(path: String, key: String, betas: List<String> = emptyList()): Request.Builder =
        Request.Builder().url(baseUrl().trimEnd('/') + path)
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .apply { if (betas.isNotEmpty()) header("anthropic-beta", betas.joinToString(",")) }

    /**
     * Fetches a file that code in the sandbox produced.
     * @return its name and contents, or null if it is gone or too large to keep on the device
     */
    suspend fun sandboxFile(key: String, fileId: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        val name = http.newCall(request("/v1/files/$fileId", key).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            parseObject(resp.body.string())?.str("filename")?.takeIf { it.isNotBlank() } ?: "file-$fileId"
        }
        http.newCall(request("/v1/files/$fileId/content", key).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body.bytes()
            if (bytes.size > 60 * 1024 * 1024) null else name to bytes
        }
    }

    /**
     * Puts a file from the device where code in the sandbox can be given it. It is kept in the person's own
     * account at the service and removes itself when the sandbox it was for is gone.
     * @return the id to hand to the sandbox
     */
    suspend fun sandboxUpload(key: String, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val safe = name.map { if (it in "<>:\"|?*\\/" || it.code < 32) '_' else it }.joinToString("").take(200).ifBlank { "file" }
        val type = runCatching { java.net.URLConnection.guessContentTypeFromName(safe) }.getOrNull() ?: "application/octet-stream"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("expires_in_seconds", UPLOAD_SECONDS.toString())
            .addFormDataPart("file", safe, bytes.toRequestBody(type.toMediaType()))
            .build()
        try {
            http.newCall(request("/v1/files", key).post(body).build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw ApiException.fromHttp(resp.code, text, 0)
                parseObject(text)?.str("id")?.takeIf { it.isNotBlank() } ?: throw ApiException(ApiException.Kind.SERVER, "Unexpected answer from the service.")
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "The file could not be sent: " + (e.message ?: "no connection"))
        }
    }

    /** Every Claude model this key can use, newest first. Also the way a pasted key is checked. */
    suspend fun listModels(key: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<ModelInfo>()
        var after: String? = null
        try {
            for (page in 0 until 5) {
                val url = "/v1/models?limit=100" + (after?.let { "&after_id=$it" } ?: "")
                http.newCall(request(url, key).get().build()).execute().use { resp ->
                    val text = resp.body.string()
                    if (!resp.isSuccessful) throw ApiException.fromHttp(resp.code, text, 0)
                    val json = parseObject(text) ?: throw ApiException(ApiException.Kind.SERVER, "Unexpected answer from the service.")
                    json.arr("data")?.forEach { e ->
                        val m = e.asObj() ?: return@forEach
                        val id = m.str("id") ?: return@forEach
                        if (id.startsWith("claude")) out += ModelInfo(id, m.str("display_name") ?: id, m.long("max_input_tokens") ?: 0, m.long("max_tokens") ?: 0)
                    }
                    after = if (json.bool("has_more") == true) json.str("last_id") else null
                }
                if (after == null) break
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "No connection. Check the internet and try again.")
        }
        out
    }

    /**
     * Sends one request and reads the reply as it streams. [onUpdate] runs on a background thread
     * after every event. Cancelling the coroutine closes the connection at once.
     */
    suspend fun stream(key: String, body: JsonObject, betas: List<String>, onUpdate: (StreamAssembler) -> Unit): ApiMessage = withContext(Dispatchers.IO) {
        val req = request("/v1/messages", key, betas)
            .header("accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = http.newCall(req)
        // The read below blocks its thread, so it cannot notice cancellation by itself. This small helper
        // coroutine can: the moment the run is cancelled it closes the connection, which ends the read.
        val onCancel = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val retryAfter = resp.header("retry-after")?.toDoubleOrNull()?.toInt() ?: 0
                    throw ApiException.fromHttp(resp.code, resp.body.string(), retryAfter)
                }
                val source = resp.body.source()
                val asm = StreamAssembler()
                while (!asm.finished) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val ev = parseObject(line.substring(5).trim()) ?: continue
                    asm.accept(ev)
                    onUpdate(asm)
                }
                if (!asm.finished) throw ApiException(ApiException.Kind.NETWORK, "The connection dropped before the reply finished.")
                asm.toMessage()
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw ApiException(ApiException.Kind.NETWORK, "No connection. Check the internet and try again.")
        } finally {
            onCancel.cancel()
        }
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        /** How long a file sent to the sandbox is kept at the service: as long as a sandbox can live. */
        const val UPLOAD_SECONDS = 30L * 24 * 3600
        const val PRODUCTION = "https://api.anthropic.com"
        private val JSON = "application/json".toMediaType()
    }
}
