package app.pocketagent.ai

import app.pocketagent.data.ToolError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The outside accounts the agent can work with: GitHub (with a personal access token) and Gmail (with an
 * app password). Each exists only once the user has connected it, and every action that changes something
 * outside the phone (sending mail, writing to GitHub) is shown to the user for a yes or no first.
 */
class Integrations(private val secret: (String) -> String?, private val githubUrl: String = "https://api.github.com", private val mailHosts: MailHosts = MailHosts()) {
    private val http by lazy { OkHttpClient.Builder().guarded().followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build() }

    fun githubOn() = !secret(GITHUB).isNullOrBlank()
    fun gmailOn() = !secret(GMAIL_USER).isNullOrBlank() && !secret(GMAIL_PASS).isNullOrBlank()

    private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

    fun specs(): List<ToolSpec> = buildList {
        if (githubOn()) add(ToolSpec(
            "github", "Call the GitHub REST API as the connected account. Use it to look at repositories, files, issues and pull requests, and to create or change them. Anything other than GET is shown to the person for approval first.",
            Risk.READ, listOf("method", "path"), buildJsonObject {
                put("method", buildJsonObject { put("type", "string"); putJsonArray("enum") { listOf("GET", "POST", "PATCH", "PUT", "DELETE").forEach { add(it) } } })
                put("path", prop("string", "The API path with any query string, starting with /, for example /user/repos?per_page=20 or /repos/OWNER/REPO/issues."))
                put("body", prop("object", "The JSON body for POST, PATCH and PUT."))
                put("raw", prop("boolean", "For a file's contents: return the file itself instead of base64 inside JSON."))
            },
        ))
        if (gmailOn()) {
            add(ToolSpec("gmail_search", "Search the connected Gmail mailbox. The query uses Gmail's own search words (from:, subject:, newer_than:7d, is:unread and so on). Returns the newest matches with an id for gmail_read.", Risk.READ, listOf("query"), buildJsonObject {
                put("query", prop("string", "What to search for."))
                put("max", prop("integer", "How many to return, up to 30. Default 10."))
            }))
            add(ToolSpec("gmail_read", "Read one message from the connected Gmail mailbox, by the id gmail_search returned.", Risk.READ, listOf("id"), buildJsonObject { put("id", prop("string", "The message id.")) }))
            add(ToolSpec("gmail_send", "Send an email from the connected Gmail address. The person sees the message and must approve it before it is sent.", Risk.OUTWARD, listOf("to", "subject", "body"), buildJsonObject {
                put("to", prop("string", "Who to send to. Several addresses are separated by commas."))
                put("cc", prop("string", "Addresses to copy, separated by commas."))
                put("subject", prop("string", "The subject line."))
                put("body", prop("string", "The message, as plain text."))
            }))
        }
    }

    fun risk(name: String, input: JsonObject): Risk? = when (name) {
        "github" -> if (input.str("method").orEmpty().uppercase() == "GET") Risk.READ else Risk.OUTWARD
        "gmail_search", "gmail_read" -> Risk.READ
        "gmail_send" -> Risk.OUTWARD
        else -> null
    }

    fun describe(name: String, input: JsonObject): String? = when (name) {
        "github" -> "GitHub: " + input.str("method").orEmpty().uppercase() + " " + input.str("path").orEmpty().substringBefore('?').take(80)
        "gmail_search" -> "Search mail: " + input.str("query").orEmpty().take(60)
        "gmail_read" -> "Read an email"
        "gmail_send" -> "Send email to " + input.str("to").orEmpty().take(60)
        else -> null
    }

    /** What the approval card shows for an action, so the person sees exactly what would go out. */
    fun preview(name: String, input: JsonObject): String = when (name) {
        "gmail_send" -> "To: ${input.str("to").orEmpty()}" + (input.str("cc")?.takeIf { it.isNotBlank() }?.let { "\nCc: $it" } ?: "") + "\nSubject: ${input.str("subject").orEmpty()}\n\n${input.str("body").orEmpty()}"
        "github" -> input.obj("body")?.let { PrettyJson.encodeToString(JsonObject.serializer(), it) }.orEmpty()
        else -> ""
    }

    fun handles(name: String) = name == "github" || name.startsWith("gmail_")

    fun run(name: String, input: JsonObject): String = when (name) {
        "github" -> github(input)
        "gmail_search" -> {
            val max = (input["max"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10
            val found = mail().search(input.str("query") ?: throw ToolError("query must be a string."), max)
            if (found.isEmpty()) "No messages match." else found.joinToString("\n") { "id ${it.id} | ${it.date} | from ${it.from} | ${it.subject}" }
        }
        "gmail_read" -> mail().read(input.str("id") ?: (input["id"] as? JsonPrimitive)?.content ?: throw ToolError("id is required."))
        "gmail_send" -> {
            fun list(k: String) = input.str(k).orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            mail().send(list("to"), list("cc"), input.str("subject").orEmpty(), input.str("body").orEmpty())
            "Sent to " + list("to").joinToString(", ") + "."
        }
        else -> throw ToolError("Unknown tool: $name")
    }

    private fun mail() = Mail(secret(GMAIL_USER) ?: throw ToolError("Gmail is not connected."), secret(GMAIL_PASS) ?: throw ToolError("Gmail is not connected."), mailHosts)

    /** Checks saved Gmail details by signing in. Throws with a readable message if they do not work. */
    fun checkGmail(user: String, pass: String) = Mail(user.trim(), pass.replace(" ", ""), mailHosts).check()

    /** Checks a GitHub token and returns the account's login name. */
    fun checkGithub(token: String): String {
        val req = Request.Builder().url("$githubUrl/user").header("authorization", "Bearer ${token.trim()}").header("accept", "application/vnd.github+json").header("user-agent", "MyPAI").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw ToolError("GitHub did not accept that token.")
            if (!resp.isSuccessful) throw ToolError("GitHub answered ${resp.code}.")
            return parseObject(resp.body.string())?.str("login") ?: "your account"
        }
    }

    private fun github(input: JsonObject): String {
        val token = secret(GITHUB) ?: throw ToolError("GitHub is not connected.")
        val method = input.str("method").orEmpty().uppercase().ifEmpty { "GET" }
        if (method !in setOf("GET", "POST", "PATCH", "PUT", "DELETE")) throw ToolError("method must be GET, POST, PATCH, PUT or DELETE.")
        val path = input.str("path") ?: throw ToolError("path is required.")
        // The path is appended to GitHub's own address and nothing else: the token must never be sent to another host.
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://") || path.contains('@') || path.any { it.isWhitespace() }) throw ToolError("path must start with / and be a GitHub API path.")
        val url = runCatching { (githubUrl + path).toHttpUrl() }.getOrNull() ?: throw ToolError("That is not a valid path.")
        if (url.host != githubUrl.toHttpUrl().host) throw ToolError("path must be a GitHub API path.")
        val body = input.obj("body")
        val req = Request.Builder().url(url)
            .header("authorization", "Bearer $token")
            .header("accept", if (input.bool("raw") == true) "application/vnd.github.raw+json" else "application/vnd.github+json")
            .header("x-github-api-version", "2022-11-28")
            .header("user-agent", "MyPAI")
            .method(method, if (method == "GET") null else (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            val shown = if (text.length > MAX) text.take(MAX) + "\n[cut at $MAX characters; ask for less with per_page or a narrower path]" else text
            if (!resp.isSuccessful) throw ToolError("GitHub answered ${resp.code}: " + (parseObject(text)?.str("message") ?: shown.take(300)))
            return shown.ifBlank { "Done (${resp.code})." }
        }
    }

    companion object {
        const val GITHUB = "conn.github"
        const val GMAIL_USER = "conn.gmail.user"
        const val GMAIL_PASS = "conn.gmail.pass"
        /** Every secret a connection keeps, for sync and for removal. */
        val SECRETS = listOf(GITHUB, GMAIL_USER, GMAIL_PASS)
        const val MAX = 60_000
        private val JSON = "application/json".toMediaType()
    }
}
