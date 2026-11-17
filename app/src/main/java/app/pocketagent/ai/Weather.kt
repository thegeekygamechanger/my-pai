package app.pocketagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The weather right now, as far as the greeting and the background care. [kind] is clear, cloudy, fog, rain, snow or storm. */
data class WeatherNow(val tempC: Int, val kind: String, val day: Boolean)

/**
 * Current weather for the city the user typed, from Open-Meteo (free, no account, no key). It is used only to
 * shape the greeting and the colours of the first screen. Nothing is asked for when no city is set.
 */
object Weather {
    private val http by lazy { appClient().followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    fun kind(code: Int): String = when (code) {
        0, 1 -> "clear"
        2, 3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloudy"
    }

    private fun get(url: okhttp3.HttpUrl) = http.newCall(Request.Builder().url(url).header("User-Agent", "MyPAI").build()).execute().use { if (it.isSuccessful) parseObject(it.body.string()) else null }

    suspend fun fetch(city: String): WeatherNow? = withContext(Dispatchers.IO) {
        runCatching {
            val place = get("https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder().addQueryParameter("name", city.trim()).addQueryParameter("count", "1").addQueryParameter("format", "json").build())
                ?.arr("results")?.firstOrNull()?.asObj() ?: return@runCatching null
            val lat = (place["latitude"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@runCatching null
            val lon = (place["longitude"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@runCatching null
            val now = get("https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder().addQueryParameter("latitude", lat).addQueryParameter("longitude", lon).addQueryParameter("current", "temperature_2m,weather_code,is_day").build())
                ?.obj("current") ?: return@runCatching null
            val temp = (now["temperature_2m"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@runCatching null
            WeatherNow(Math.round(temp).toInt(), kind((now.long("weather_code") ?: 2).toInt()), now.long("is_day") != 0L)
        }.getOrNull()
    }
}
