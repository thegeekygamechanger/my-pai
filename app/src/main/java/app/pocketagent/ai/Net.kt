package app.pocketagent.ai

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Offline mode. When it is on, every connection the app makes is checked here, and one that would leave the
 * person's own device or network is refused before it starts. It is one gate for the whole app (AI services,
 * MCP servers, sync, weather, update checks, voice typing), so nothing can be forgotten.
 */
object Net {
    @Volatile var offline: Boolean = false

    const val REFUSED = "Offline mode is on, so nothing is sent to the internet. Turn it off in Settings to use this."

    /** True for this device itself and for addresses that only exist on a home or office network. No name is looked up. */
    fun local(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1") return true
        if (h.contains(':')) return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    fun localUrl(url: String): Boolean = runCatching { local(java.net.URI(url.trim()).host.orEmpty()) }.getOrDefault(false)

    internal val gate = Interceptor { chain: Interceptor.Chain ->
        if (offline && !local(chain.request().url.host)) throw IOException(REFUSED)
        chain.proceed(chain.request())
    }
}

/** Every client of the app is built through this, so every request passes the offline gate. */
fun OkHttpClient.Builder.guarded(): OkHttpClient.Builder = addInterceptor(Net.gate)

/**
 * The one client the others are cut from. They differ in their timeouts but share its connections and its few
 * threads, instead of each keeping a pool and a set of threads of its own.
 */
private val shared: OkHttpClient by lazy { OkHttpClient.Builder().guarded().build() }

/** A builder for a client of the app's: behind the offline gate, and sharing connections with all the others. */
fun appClient(): OkHttpClient.Builder = shared.newBuilder()
