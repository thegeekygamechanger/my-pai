package app.pocketagent.ai

import app.pocketagent.data.ToolError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Where the mailbox lives. Gmail by default; tests point this at a local stand-in without TLS. */
data class MailHosts(val imap: String = "imap.gmail.com", val imapPort: Int = 993, val smtp: String = "smtp.gmail.com", val smtpPort: Int = 465, val tls: Boolean = true)

data class MailSummary(val id: String, val from: String, val to: String, val subject: String, val date: String)

/**
 * A small mail client for the agent: search and read over IMAP, send over SMTP, both inside TLS. It signs in
 * with the address and an app password the user created for this purpose, and talks only to the mail service.
 * It is deliberately minimal: no folders to manage, no attachments, nothing stored.
 */
class Mail(private val user: String, private val pass: String, private val hosts: MailHosts = MailHosts()) {

    private fun open(host: String, port: Int): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 15_000)
        plain.soTimeout = 30_000
        if (!hosts.tls) return plain
        // TLS is layered on with the server's name given explicitly. That name is what tells a shared server
        // (Google's) which certificate to present, and what the certificate is then checked against.
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        return tls
    }

    // ---- reading (IMAP)

    private class Imap(private val input: InputStream, private val out: OutputStream) {
        private val LITERAL = Regex("\\{(\\d+)\\}$")
        private var n = 0

        private fun line(): ByteArray {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw ToolError("The mail server closed the connection.")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toByteArray()
        }

        fun greeting() { line() }

        /**
         * Sends one command and collects the untagged answers. A piece of mail arrives as a "literal": the line
         * ends with {n} and exactly n bytes follow. Each answer is returned as its text with the literals beside it.
         */
        fun command(text: String, secret: Boolean = false): List<Pair<String, List<ByteArray>>> {
            val tag = "a" + (++n)
            out.write("$tag $text\r\n".toByteArray()); out.flush()
            val answers = ArrayList<Pair<String, List<ByteArray>>>()
            while (true) {
                val sb = StringBuilder()
                val literals = ArrayList<ByteArray>()
                while (true) {
                    val l = String(line(), Charsets.ISO_8859_1)
                    sb.append(l)
                    // Both braces are escaped: Android's pattern engine refuses a bare closing brace that a computer's accepts.
                    val m = LITERAL.find(l) ?: break
                    val size = m.groupValues[1].toInt()
                    if (size > 8_000_000) throw ToolError("That message is too large to read.")
                    val data = ByteArray(size)
                    var got = 0
                    while (got < size) { val r = input.read(data, got, size - got); if (r < 0) throw ToolError("The mail server closed the connection."); got += r }
                    literals += data
                }
                val l = sb.toString()
                if (l.startsWith("$tag ")) {
                    if (!l.startsWith("$tag OK", ignoreCase = true)) throw ToolError(if (secret) "The mail server refused the sign-in. Check the address and the app password." else "The mail server answered: " + l.removePrefix("$tag ").take(200))
                    return answers
                }
                answers += l to literals
            }
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ") + "\""

    private fun <T> imap(block: (Imap) -> T): T = open(hosts.imap, hosts.imapPort).use { s ->
        val c = Imap(s.getInputStream(), s.getOutputStream())
        c.greeting()
        c.command("LOGIN ${quote(user)} ${quote(pass)}", secret = true)
        // "All Mail" covers every label; a mailbox without it (not Gmail) falls back to the inbox.
        runCatching { c.command("SELECT \"[Gmail]/All Mail\"") }.getOrElse { c.command("SELECT INBOX") }
        val result = block(c)
        runCatching { c.command("LOGOUT") }
        result
    }

    /** Signs in and out again: used to check an address and app password when the user saves them. */
    fun check() { imap { } }

    /** The newest messages matching a Gmail search (the same words that work in Gmail's search box). */
    fun search(query: String, max: Int): List<MailSummary> = imap { c ->
        val found = runCatching { c.command("UID SEARCH X-GM-RAW ${quote(query)}") }.getOrElse { c.command("UID SEARCH TEXT ${quote(query)}") }
        val uids = found.filter { it.first.startsWith("* SEARCH", true) }.flatMap { it.first.removePrefix("* SEARCH").trim().split(' ') }.mapNotNull { it.toLongOrNull() }.sortedDescending().take(max.coerceIn(1, 30))
        if (uids.isEmpty()) return@imap emptyList()
        val rows = c.command("UID FETCH ${uids.joinToString(",")} (UID BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE)])")
        rows.mapNotNull { (text, literals) ->
            val uid = Regex("UID (\\d+)").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val h = Mime.headers(String(literals.firstOrNull() ?: return@mapNotNull null, Charsets.ISO_8859_1))
            MailSummary(uid, h["from"].orEmpty(), h["to"].orEmpty(), h["subject"].orEmpty(), h["date"].orEmpty())
        }.sortedByDescending { it.id.toLongOrNull() ?: 0 }
    }

    /** One message as readable text: who, when, subject, then the body. */
    fun read(id: String): String = imap { c ->
        val uid = id.trim().toLongOrNull() ?: throw ToolError("That is not a message id from gmail_search.")
        val rows = c.command("UID FETCH $uid (UID BODY.PEEK[]<0.400000>)")
        val raw = rows.firstNotNullOfOrNull { it.second.firstOrNull() } ?: throw ToolError("No message with id $id.")
        Mime.readable(raw)
    }

    // ---- sending (SMTP)

    fun send(to: List<String>, cc: List<String>, subject: String, body: String) {
        val all = (to + cc).map { it.trim() }.filter { it.isNotEmpty() }
        if (to.isEmpty()) throw ToolError("Give at least one address to send to.")
        all.forEach { if (!ADDRESS.matches(it)) throw ToolError("\"$it\" is not an email address.") }
        if (subject.any { it == '\r' || it == '\n' }) throw ToolError("The subject must be one line.")
        open(hosts.smtp, hosts.smtpPort).use { s ->
            val input = s.getInputStream()
            val out = s.getOutputStream()
            fun reply(): String {
                // An answer can span several lines; the last one has a space after the code.
                val sb = StringBuilder()
                while (true) {
                    val line = StringBuilder()
                    while (true) { val b = input.read(); if (b < 0) throw ToolError("The mail server closed the connection."); if (b == '\n'.code) break; if (b != '\r'.code) line.append(b.toChar()) }
                    sb.append(line).append('\n')
                    if (line.length < 4 || line[3] != '-') break
                }
                return sb.toString()
            }
            fun say(text: String, expect: String, what: String) {
                out.write("$text\r\n".toByteArray()); out.flush()
                val r = reply()
                if (!r.startsWith(expect)) throw ToolError("$what: " + r.trim().take(200))
            }
            if (!reply().startsWith("220")) throw ToolError("The mail server is not accepting mail right now.")
            say("EHLO pocketagent.local", "250", "The mail server refused the connection")
            val auth = Base64.getEncoder().encodeToString("\u0000$user\u0000$pass".toByteArray())
            say("AUTH PLAIN $auth", "235", "The mail server refused the sign-in. Check the address and the app password")
            say("MAIL FROM:<$user>", "250", "The sender was refused")
            all.forEach { say("RCPT TO:<$it>", "25", "The address $it was refused") }
            say("DATA", "354", "The mail server refused the message")
            val enc = Base64.getMimeEncoder(76, "\r\n".toByteArray())
            val message = buildString {
                append("From: <").append(user).append(">\r\n")
                append("To: ").append(to.joinToString(", ")).append("\r\n")
                if (cc.isNotEmpty()) append("Cc: ").append(cc.joinToString(", ")).append("\r\n")
                append("Subject: ").append(Mime.encodeWord(subject)).append("\r\n")
                append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(ZonedDateTime.now())).append("\r\n")
                append("Message-ID: <").append(UUID.randomUUID()).append("@pocketagent>\r\n")
                append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                append(enc.encodeToString(body.toByteArray())).append("\r\n")
            }
            out.write(message.toByteArray()); out.flush()
            say(".", "250", "The message was not accepted")
            runCatching { out.write("QUIT\r\n".toByteArray()); out.flush() }
        }
    }

    private companion object { val ADDRESS = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$") }
}

/** Just enough of the mail format to turn a raw message into text a person (or a model) can read. */
object Mime {
    /** Header names in lower case to their values, with folded lines joined and encoded words decoded. */
    fun headers(block: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var name = ""
        for (line in block.replace("\r\n", "\n").split('\n')) {
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') { if (name.isNotEmpty()) out[name] = out[name] + " " + line.trim(); continue }
            val i = line.indexOf(':')
            if (i <= 0) continue
            name = line.substring(0, i).trim().lowercase()
            out[name] = line.substring(i + 1).trim()
        }
        return out.mapValues { decodeWords(it.value) }
    }

    private val WORD = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")

    fun decodeWords(s: String): String = WORD.replace(s.replace(Regex("\\?=\\s+=\\?"), "?==?")) { m ->
        runCatching {
            val cs = charset(m.groupValues[1])
            if (m.groupValues[2].equals("b", true)) String(Base64.getMimeDecoder().decode(m.groupValues[3]), cs)
            else String(quotedPrintable(m.groupValues[3].replace('_', ' ').toByteArray(Charsets.ISO_8859_1)), cs)
        }.getOrDefault(m.value)
    }

    fun encodeWord(s: String): String = if (s.all { it.code in 32..126 }) s else "=?utf-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray()) + "?="

    private fun charset(name: String): Charset = runCatching { Charset.forName(name.trim().trim('"')) }.getOrDefault(Charsets.UTF_8)

    private fun quotedPrintable(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt().toChar()
            if (c == '=' && i + 2 < b.size + 0 && i + 1 < b.size) {
                if (b[i + 1].toInt().toChar() == '\n') { i += 2; continue }
                if (b[i + 1].toInt().toChar() == '\r') { i += 3; continue }
                val hex = String(b, i + 1, minOf(2, b.size - i - 1), Charsets.ISO_8859_1)
                val v = hex.toIntOrNull(16)
                if (v != null && hex.length == 2) { out.write(v); i += 3; continue }
            }
            out.write(b[i].toInt()); i++
        }
        return out.toByteArray()
    }

    private fun param(headerValue: String, name: String): String? =
        Regex("$name\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))", RegexOption.IGNORE_CASE).find(headerValue)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /** The text of one part: the plain-text version if there is one, otherwise the HTML with its tags removed. */
    private fun text(raw: String, depth: Int = 0): String? {
        val split = raw.indexOf("\n\n")
        val head = headers(if (split < 0) raw else raw.substring(0, split))
        val body = if (split < 0) "" else raw.substring(split + 2)
        val type = head["content-type"] ?: "text/plain"
        if (type.startsWith("multipart/", true) && depth < 6) {
            val boundary = param(type, "boundary") ?: return null
            val parts = body.split("--$boundary").drop(1).map { it.trimStart('\n') }.filter { !it.startsWith("--") }
            val texts = parts.mapNotNull { p -> text(p, depth + 1)?.let { (headers(p.substringBefore("\n\n"))["content-type"] ?: "text/plain") to it } }
            return texts.firstOrNull { it.first.startsWith("text/plain", true) || it.first.startsWith("multipart/", true) }?.second ?: texts.firstOrNull()?.second
        }
        if (!type.startsWith("text/", true)) return null
        val bytes = when (head["content-transfer-encoding"]?.lowercase()) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(body) }.getOrDefault(body.toByteArray(Charsets.ISO_8859_1))
            "quoted-printable" -> quotedPrintable(body.toByteArray(Charsets.ISO_8859_1))
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }
        val decoded = String(bytes, charset(param(type, "charset") ?: "utf-8"))
        return if (type.startsWith("text/html", true)) decoded
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h\\d>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
        else decoded.trim()
    }

    fun readable(raw: ByteArray): String {
        val s = String(raw, Charsets.ISO_8859_1).replace("\r\n", "\n")
        val h = headers(s.substringBefore("\n\n"))
        val body = text(s) ?: "(This message has no readable text.)"
        return buildString {
            append("From: ").append(h["from"].orEmpty()).append('\n')
            append("To: ").append(h["to"].orEmpty()).append('\n')
            h["cc"]?.let { append("Cc: ").append(it).append('\n') }
            append("Date: ").append(h["date"].orEmpty()).append('\n')
            append("Subject: ").append(h["subject"].orEmpty()).append("\n\n")
            append(body.take(60_000))
        }
    }
}
