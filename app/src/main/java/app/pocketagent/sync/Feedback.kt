package app.pocketagent.sync

import app.pocketagent.ai.bool
import app.pocketagent.ai.parseObject
import app.pocketagent.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A note from the person to the developer. It goes to the relay, which hands it to an email service; the app never
 * learns where it ends up. Only what is passed here is sent: the words, an address for a reply if one was given,
 * and which version of the app wrote it.
 */
object Feedback {
    enum class Result { SENT, TOO_MANY, FAILED }

    const val MAX = 4000
    private val EMAIL = Regex("^[^\\s@<>\"',;]{1,64}@[A-Za-z0-9.-]{1,180}\\.[A-Za-z]{2,24}$")

    fun validEmail(text: String) = EMAIL.matches(text.trim())

    private fun relay(): String = System.getProperty("pocketagent.relay") ?: SyncEngine.RELAY

    /** Whether the relay passes notes on at the moment. The form is only offered when it does. */
    suspend fun available(relay: String = relay()): Boolean = withContext(Dispatchers.IO) {
        try {
            Updates.http.newCall(Request.Builder().url(relay.trimEnd('/') + "/healthz").build()).execute().use { resp ->
                resp.isSuccessful && parseObject(resp.body.string())?.bool("feedback") == true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun send(message: String, email: String, app: String, relay: String = relay()): Result = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("message", message.trim().take(MAX))
            if (email.isNotBlank()) put("email", email.trim())
            put("app", app)
        }.toString().toRequestBody("application/json".toMediaType())
        try {
            Updates.http.newCall(Request.Builder().url(relay.trimEnd('/') + "/v1/feedback").post(body).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.SENT
                    resp.code == 429 -> Result.TOO_MANY
                    else -> Result.FAILED
                }
            }
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
