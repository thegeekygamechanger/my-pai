package app.pocketagent.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

val AppJson = Json { ignoreUnknownKeys = true }
val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun parseArray(s: String): JsonArray = runCatching { AppJson.parseToJsonElement(s) as JsonArray }.getOrDefault(JsonArray(emptyList()))
fun parseObject(s: String): JsonObject? = runCatching { AppJson.parseToJsonElement(s) as JsonObject }.getOrNull()

class ApiException(val kind: Kind, message: String, val status: Int = 0, val retryAfterSec: Int = 0) : Exception(message) {
    enum class Kind { NO_KEY, AUTH, BILLING, PERMISSION, NOT_FOUND, RATE_LIMIT, OVERLOADED, BAD_REQUEST, TOO_LARGE, SERVER, NETWORK }

    val retryable: Boolean get() = kind == Kind.RATE_LIMIT || kind == Kind.OVERLOADED || kind == Kind.SERVER || kind == Kind.NETWORK

    /** Plain words for the UI. */
    val friendly: String
        get() = when (kind) {
            Kind.NO_KEY -> "No API key is set. Add one in Settings."
            Kind.AUTH -> "The API key was rejected. Check that it is correct and still active."
            Kind.BILLING -> "The API account has a billing problem (credits or payment)."
            Kind.PERMISSION -> "This API key is not allowed to do that."
            Kind.NOT_FOUND -> "That model is not available for this API key. Pick another model."
            Kind.RATE_LIMIT -> "Rate limit reached. Wait a little and try again."
            Kind.OVERLOADED -> "The service is busy right now. Try again shortly."
            Kind.TOO_LARGE -> "This conversation or attachment is too large to send."
            Kind.SERVER -> "The service had a problem. Try again."
            Kind.NETWORK -> message ?: "No connection. Check the internet and try again."
            Kind.BAD_REQUEST -> message ?: "The request was not accepted."
        }

    companion object {
        fun fromHttp(status: Int, body: String, retryAfter: Int): ApiException {
            val err = parseObject(body)?.obj("error")
            val msg = err?.str("message") ?: "HTTP $status"
            return ApiException(kindFor(status, err?.str("type")), msg, status, retryAfter)
        }

        fun kindFor(status: Int, type: String?): Kind = when {
            status == 401 || type == "authentication_error" -> Kind.AUTH
            status == 402 || type == "billing_error" -> Kind.BILLING
            status == 403 || type == "permission_error" -> Kind.PERMISSION
            status == 404 || type == "not_found_error" -> Kind.NOT_FOUND
            status == 413 || type == "request_too_large" -> Kind.TOO_LARGE
            status == 429 || type == "rate_limit_error" -> Kind.RATE_LIMIT
            status == 529 || type == "overloaded_error" -> Kind.OVERLOADED
            status >= 500 || type == "api_error" -> Kind.SERVER
            else -> Kind.BAD_REQUEST
        }
    }
}

data class ModelInfo(val id: String, val name: String, val maxInput: Long = 0, val maxOutput: Long = 0, val provider: String = Providers.ANTHROPIC)

/** What each model family accepts. Kept in one place so a request never carries a field its model rejects. */
object ModelCaps {
    const val DEFAULT_MODEL = "claude-opus-5-5"

    /** Shown until the live list has been fetched with the user's key, and if that fetch fails. */
    val BUILT_IN = listOf(
        ModelInfo("claude-fable-5-2", "Claude Fable 5.2", 1_000_000, 128_000),
        ModelInfo("claude-opus-5-5", "Claude Opus 5.5", 1_000_000, 128_000),
        ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", 1_000_000, 128_000),
        ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", 1_000_000, 128_000),
    )

    val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

    private val ADAPTIVE = Regex("fable|mythos|opus-(4-[6-9]|[5-9])|sonnet-(4-[6-9]|[5-9])|haiku-[5-9]")
    private val FAST = setOf("claude-opus-5-5", "claude-fable-5-2", "claude-mythos-5-2")
    private val FALLBACKS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    /** Models with adaptive thinking and the effort control; older ones get neither field. */
    fun adaptive(id: String) = ADAPTIVE.containsMatchIn(id)

    fun efforts(id: String): List<String> = when {
        !adaptive(id) -> emptyList()
        id.contains("-4-6") -> EFFORTS - "xhigh"
        else -> EFFORTS
    }

    /** The nearest effort this model accepts, or null when it has no effort control. */
    fun effortFor(id: String, wanted: String): String? {
        val ok = efforts(id)
        if (ok.isEmpty()) return null
        if (wanted in ok) return wanted
        return if (wanted == "xhigh") "high" else "medium"
    }

    fun fast(id: String) = id in FAST
    fun fallbacks(id: String) = id in FALLBACKS

    fun tagline(id: String): String = when {
        id.contains("fable") || id.contains("mythos") -> "Most capable, for the hardest work"
        id.contains("opus") -> "Strong all-rounder"
        id.contains("sonnet") -> "Fast and capable for everyday work"
        id.contains("haiku") -> "Fastest and cheapest"
        else -> ""
    }

    /** US dollars per million tokens (input, output); null when the price is not known to the app. */
    fun price(id: String): Pair<Double, Double>? = when {
        id.startsWith("claude-fable-5-2") || id.startsWith("claude-mythos-5-2") -> 8.0 to 40.0
        id.startsWith("claude-fable-5") || id.startsWith("claude-mythos-5") -> 10.0 to 50.0
        id.startsWith("claude-opus-5-5") -> 4.0 to 20.0
        id.startsWith("claude-opus-5") || id.startsWith("claude-opus-4-8") || id.startsWith("claude-opus-4-7") || id.startsWith("claude-opus-4-6") -> 5.0 to 25.0
        id.startsWith("claude-sonnet-5") -> 2.0 to 10.0
        id.startsWith("claude-sonnet-4-6") -> 3.0 to 15.0
        id.startsWith("claude-haiku-5-5") -> 0.10 to 0.50
        id.startsWith("claude-haiku-4-5") -> 1.0 to 5.0
        else -> null
    }
}

data class Usage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    val total: Long get() = input + output + cacheRead + cacheWrite

    /** Estimated cost in millionths of a dollar. Cache reads bill at a tenth of input, cache writes at 1.25x. */
    fun costMicros(model: String, fast: Boolean): Long {
        val (pin, pout) = ModelCaps.price(model) ?: return 0
        val mult = if (fast) 2.0 else 1.0
        val dollars = (input * pin + output * pout + cacheRead * pin * 0.1 + cacheWrite * pin * 1.25) / 1_000_000.0 * mult
        return (dollars * 1_000_000).toLong()
    }
}

fun formatCost(micros: Long): String = when {
    micros <= 0 -> ""
    micros < 10_000 -> "under $0.01"
    else -> "$" + String.format(java.util.Locale.US, "%.2f", micros / 1_000_000.0)
}

fun formatTokens(n: Long): String = when {
    n < 1_000 -> "$n"
    n < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> String.format(java.util.Locale.US, "%.2fM", n / 1_000_000.0)
}
