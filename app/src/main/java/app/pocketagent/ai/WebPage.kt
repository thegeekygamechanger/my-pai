package app.pocketagent.ai

import app.pocketagent.data.ToolError
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads a public web page and returns its text, for AI services that have no web tools of their own.
 * It only fetches: no cookies, no sign-in, no forms. Addresses on the person's own device or network are refused,
 * so a page cannot make the assistant fetch something private and hand it on; every redirect is checked the same way.
 * What it returns is somebody else's text, and is treated as such (see [Shield]).
 */
object WebPage {
    private val http by lazy { appClient().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build() }

    fun read(address: String): String {
        var url = address.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) url = "https://$url"
        repeat(MAX_HOPS) {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: throw ToolError("That is not a web address: $address")
            if (Net.local(host)) throw ToolError("That address is on this device or your own network, not on the web, so it is not opened.")
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; MyPAI page reader)").header("Accept", "text/html,text/plain,application/xhtml+xml,application/json;q=0.8").build()
            val (next, text) = try {
                http.newCall(request).execute().use { r ->
                    when {
                        r.isRedirect -> (r.header("Location")?.let { r.request.url.resolve(it)?.toString() } ?: throw ToolError("The page sent us elsewhere without saying where.")) to ""
                        !r.isSuccessful -> throw ToolError("The site answered with an error (${r.code}).")
                        else -> {
                            val type = r.header("Content-Type").orEmpty().lowercase()
                            if (type.isNotEmpty() && !type.startsWith("text/") && !type.contains("json") && !type.contains("xml")) throw ToolError("That address is not a page of text (it is $type).")
                            val bytes = r.body!!.source().let { src -> src.request(MAX_BYTES); src.buffer.snapshot(minOf(src.buffer.size, MAX_BYTES).toInt()).toByteArray() }
                            null to text(String(bytes, Charsets.UTF_8), type.contains("html") || type.isEmpty())
                        }
                    }
                }
            } catch (e: ToolError) {
                throw e
            } catch (e: Exception) {
                throw ToolError(if (e.message == Net.REFUSED) Net.REFUSED else "The page could not be opened (${e.message ?: e.javaClass.simpleName}).")
            }
            if (next == null) return text.ifBlank { "(The page has no text.)" }
            url = next
        }
        throw ToolError("The page kept sending us elsewhere.")
    }

    private val DROP = Regex("(?is)<(script|style|noscript|svg|head|template|iframe)\\b.*?</\\1>")
    private val BREAK = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|ul|ol|table|blockquote|pre)\\b[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&mdash;" to "-", "&ndash;" to "-", "&hellip;" to "...", "&rsquo;" to "'", "&lsquo;" to "'", "&ldquo;" to "\"", "&rdquo;" to "\"")

    /** The words of a page without its markup. Plain text and JSON pass through as they are. */
    fun text(raw: String, html: Boolean): String {
        if (!html || !raw.contains('<')) return raw.trim().take(MAX_TEXT)
        val title = TITLE.find(raw)?.groupValues?.get(1)?.let(::plain)?.trim().orEmpty()
        val body = plain(TAG.replace(BREAK.replace(DROP.replace(raw, " "), "\n"), " "))
            .lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return ((if (title.isEmpty()) "" else "$title\n\n") + body).take(MAX_TEXT) + (if (body.length > MAX_TEXT) "\n[the page goes on; this is its first part]" else "")
    }

    private fun plain(s: String): String {
        var out = s
        for ((k, v) in ENTITIES) out = out.replace(k, v)
        return Regex("&#(\\d{2,6});").replace(out) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: " " }
    }

    private const val MAX_HOPS = 5
    private const val MAX_BYTES = 2L * 1024 * 1024
    const val MAX_TEXT = 24_000
}
