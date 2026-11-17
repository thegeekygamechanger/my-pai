package app.pocketagent.ai

import app.pocketagent.Graph
import app.pocketagent.data.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** The languages offered for voice typing: a tag the speech services understand, and its name. "" follows the device. */
val SPEECH_LANGUAGES = listOf(
    "" to "Automatic", "en-IN" to "English (India)", "hi-IN" to "Hindi", "bn-IN" to "Bengali", "mr-IN" to "Marathi", "ta-IN" to "Tamil",
    "te-IN" to "Telugu", "gu-IN" to "Gujarati", "kn-IN" to "Kannada", "ml-IN" to "Malayalam", "pa-IN" to "Punjabi", "ur-IN" to "Urdu",
    "or-IN" to "Odia", "as-IN" to "Assamese", "ne-NP" to "Nepali", "en-US" to "English (US)", "en-GB" to "English (UK)", "es-ES" to "Spanish",
    "fr-FR" to "French", "de-DE" to "German", "pt-BR" to "Portuguese", "ar-SA" to "Arabic", "ru-RU" to "Russian", "id-ID" to "Indonesian",
    "ja-JP" to "Japanese", "ko-KR" to "Korean", "zh-CN" to "Chinese",
)

/**
 * Speech to text through a connected service's transcription model (Whisper). It recognises the language
 * by itself and copes well with accents, which is why the computer app uses it for voice typing.
 */
object Transcribe {
    private val http by lazy { appClient().followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build() }
    /** Services with a transcription model, best first. */
    private val MODELS = listOf("groq" to "whisper-large-v3", "openai" to "whisper-1")

    fun available(g: Graph): Boolean = MODELS.any { g.keyFor(it.first) != null }

    /** Wraps raw 16-bit mono samples in a WAV file. */
    fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return ByteArrayOutputStream().also { it.write(header.array()); it.write(pcm) }.toByteArray()
    }

    suspend fun transcribe(g: Graph, wav: ByteArray, lang: String, baseUrlFor: (Provider) -> String = { it.baseUrl }): String = withContext(Dispatchers.IO) {
        val (providerId, model) = MODELS.firstOrNull { g.keyFor(it.first) != null } ?: throw ToolError("Voice typing here needs a Groq or OpenAI key. Add one in Settings, AI services.")
        val provider = g.provider(providerId)!!
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { lang.substringBefore('-').takeIf { it.length == 2 }?.let { addFormDataPart("language", it) } }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(baseUrlFor(provider).trimEnd('/') + "/audio/transcriptions").header("authorization", "Bearer " + g.keyFor(providerId)).post(body).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw ToolError(ApiException.fromHttp(resp.code, text, 0).friendly)
                parseObject(text)?.str("text")?.trim().orEmpty()
            }
        } catch (e: java.io.IOException) {
            throw ToolError("No connection. Check the internet and try again.")
        }
    }
}
