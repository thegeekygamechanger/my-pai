package app.pocketagent

import app.pocketagent.ai.Integrations
import app.pocketagent.ai.MailHosts
import app.pocketagent.ai.Mime
import app.pocketagent.ai.Risk
import app.pocketagent.ai.Scheduler
import app.pocketagent.ai.parseObject
import app.pocketagent.data.Schedule
import app.pocketagent.data.ToolError
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** GitHub, Gmail and scheduled tasks, against local stand-ins for the real services. */
class IntegrationsTest {
    private lateinit var github: HttpServer
    private lateinit var imap: ServerSocket
    private lateinit var smtp: ServerSocket
    private val githubSeen = CopyOnWriteArrayList<String>()
    private val smtpSeen = CopyOnWriteArrayList<String>()
    private val message = ("From: =?utf-8?B?" + Base64.getEncoder().encodeToString("Asha Rao".toByteArray()) + "?= <asha@example.com>\r\nTo: me@example.com\r\nSubject: =?utf-8?Q?Caf=C3=A9_plans?=\r\nDate: Mon, 16 Nov 2026 09:00:00 +0530\r\n" +
        "MIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n--b1\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nSee you at the caf=C3=A9 at 5.=\r\n Bring the notes.\r\n--b1\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>See you</p>\r\n--b1--\r\n")

    @Before fun setUp() {
        github = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        github.createContext("/") { ex ->
            githubSeen += ex.requestMethod + " " + ex.requestURI + " auth=" + ex.requestHeaders.getFirst("authorization") + " body=" + ex.requestBody.readBytes().decodeToString()
            val (code, body) = when {
                ex.requestURI.path == "/user" -> 200 to """{"login":"octo"}"""
                ex.requestURI.path == "/missing" -> 404 to """{"message":"Not Found"}"""
                else -> 200 to """[{"full_name":"octo/app"}]"""
            }
            val b = body.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        github.start()

        imap = ServerSocket(0)
        thread(isDaemon = true) {
            while (!imap.isClosed) runCatching {
                imap.accept().use { s ->
                    val out = s.getOutputStream(); val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    fun send(t: String) { out.write(t.toByteArray(Charsets.ISO_8859_1)); out.flush() }
                    send("* OK ready\r\n")
                    while (true) {
                        val line = reader.readLine() ?: break
                        val tag = line.substringBefore(' ')
                        val cmd = line.substringAfter(' ')
                        when {
                            cmd.startsWith("LOGIN") -> send(if (cmd.contains("\"goodpass\"")) "$tag OK done\r\n" else "$tag NO bad credentials\r\n")
                            cmd.startsWith("SELECT \"[Gmail]") -> send("$tag NO no such mailbox\r\n")
                            cmd.startsWith("SELECT") -> send("* 2 EXISTS\r\n$tag OK done\r\n")
                            cmd.startsWith("UID SEARCH X-GM-RAW") -> send("$tag BAD unknown search key\r\n")
                            cmd.startsWith("UID SEARCH") -> send("* SEARCH 7 12\r\n$tag OK done\r\n")
                            cmd.startsWith("UID FETCH") && cmd.contains("HEADER.FIELDS") -> {
                                val head = message.substringBefore("MIME-Version")
                                send("* 1 FETCH (UID 12 BODY[HEADER.FIELDS (FROM TO SUBJECT DATE)] {${head.length}}\r\n$head)\r\n* 2 FETCH (BODY[HEADER.FIELDS (FROM TO SUBJECT DATE)] {${head.length}}\r\n$head UID 7)\r\n$tag OK done\r\n")
                            }
                            cmd.startsWith("UID FETCH") -> send("* 1 FETCH (UID 12 BODY[]<0> {${message.length}}\r\n$message)\r\n$tag OK done\r\n")
                            cmd.startsWith("LOGOUT") -> { send("* BYE\r\n$tag OK done\r\n"); break }
                            else -> send("$tag BAD what\r\n")
                        }
                    }
                }
            }
        }
        smtp = ServerSocket(0)
        thread(isDaemon = true) {
            while (!smtp.isClosed) runCatching {
                smtp.accept().use { s ->
                    val out = s.getOutputStream(); val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    fun send(t: String) { out.write(t.toByteArray()); out.flush() }
                    send("220 hello\r\n")
                    var data = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        smtpSeen += line
                        when {
                            data -> if (line == ".") { data = false; send("250 queued\r\n") }
                            line.startsWith("EHLO") -> send("250-local\r\n250 AUTH PLAIN\r\n")
                            line.startsWith("AUTH PLAIN") -> send("235 ok\r\n")
                            line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> send("250 ok\r\n")
                            line == "DATA" -> { data = true; send("354 go\r\n") }
                            line == "QUIT" -> { send("221 bye\r\n"); break }
                        }
                    }
                }
            }
        }
    }

    @After fun tearDown() { github.stop(0); imap.close(); smtp.close() }

    private fun integrations(pass: String = "goodpass") = Integrations(
        secret = { n -> mapOf(Integrations.GITHUB to "tok_123", Integrations.GMAIL_USER to "me@example.com", Integrations.GMAIL_PASS to pass)[n] },
        githubUrl = "http://127.0.0.1:${github.address.port}",
        mailHosts = MailHosts("127.0.0.1", imap.localPort, "127.0.0.1", smtp.localPort, tls = false),
    )
    private fun input(json: String) = parseObject(json)!!

    @Test fun toolsAppearOnlyForConnectedAccounts() {
        assertTrue(Integrations({ null }).specs().isEmpty())
        assertEquals(listOf("github"), Integrations({ if (it == Integrations.GITHUB) "t" else null }).specs().map { it.name })
        assertEquals(listOf("github", "gmail_search", "gmail_read", "gmail_send"), integrations().specs().map { it.name })
    }

    @Test fun githubReadsFreelyAndAsksBeforeAnyChange() {
        val i = integrations()
        assertEquals("octo", i.checkGithub("tok_123"))
        assertTrue(i.run("github", input("""{"method":"GET","path":"/user/repos?per_page=5"}""")).contains("octo/app"))
        assertTrue(githubSeen.last().startsWith("GET /user/repos?per_page=5 auth=Bearer tok_123"))
        assertEquals(Risk.READ, i.risk("github", input("""{"method":"GET","path":"/user"}""")))
        for (m in listOf("POST", "PATCH", "PUT", "DELETE")) assertEquals(Risk.OUTWARD, i.risk("github", input("""{"method":"$m","path":"/x"}""")))
        assertTrue(i.run("github", input("""{"method":"POST","path":"/repos/octo/app/issues","body":{"title":"Bug"}}""")).contains("octo/app"))
        assertTrue(githubSeen.last().contains("""body={"title":"Bug"}"""))
        assertTrue(assertThrows(ToolError::class.java) { i.run("github", input("""{"method":"GET","path":"/missing"}""")) }.message!!.contains("404"))
    }

    @Test fun githubTokenCannotBeSentToAnotherHost() {
        val i = integrations()
        val before = githubSeen.size
        for (path in listOf("//evil.example/steal", "https://evil.example/x", "/x@evil.example/", "user/repos", "/a b", "/x\r\nHost: evil"))
            assertThrows(path, ToolError::class.java) { i.run("github", input("""{"method":"GET","path":${kotlinx.serialization.json.JsonPrimitive(path)}}""")) }
        assertEquals("nothing was sent", before, githubSeen.size)
    }

    @Test fun gmailSearchAndReadDecodeRealWorldMail() {
        val i = integrations()
        i.checkGmail("me@example.com", "good pass") // app passwords are shown in groups; the spaces are dropped
        val found = i.run("gmail_search", input("""{"query":"from:asha café"}"""))
        assertTrue(found, found.lines().size == 2 && found.lines()[0].startsWith("id 12 ") && found.contains("Asha Rao <asha@example.com>") && found.contains("Café plans"))
        val read = i.run("gmail_read", input("""{"id":"12"}"""))
        assertTrue(read, read.contains("Subject: Café plans") && read.contains("See you at the café at 5. Bring the notes."))
        assertFalse(read.contains("<p>"))
        assertTrue(assertThrows(ToolError::class.java) { integrations("wrong").checkGmail("me@example.com", "wrong") }.message!!.contains("app password"))
    }

    @Test fun gmailSendWritesAProperMessageAndRefusesInjection() {
        val i = integrations()
        assertEquals(Risk.OUTWARD, i.risk("gmail_send", input("{}")))
        val preview = i.preview("gmail_send", input("""{"to":"a@example.com","subject":"Hi","body":"Hello there"}"""))
        assertTrue(preview.contains("To: a@example.com") && preview.contains("Hello there"))
        assertEquals("Sent to a@example.com, b@example.com.", i.run("gmail_send", input("""{"to":"a@example.com, b@example.com","cc":"c@example.com","subject":"Chai ☕","body":"Line one\nLine two"}""")))
        val sent = smtpSeen.joinToString("\n")
        assertTrue(sent.contains("MAIL FROM:<me@example.com>") && sent.contains("RCPT TO:<a@example.com>") && sent.contains("RCPT TO:<b@example.com>") && sent.contains("RCPT TO:<c@example.com>"))
        assertTrue(sent.contains("Subject: =?utf-8?B?"))
        assertTrue(sent.contains(Base64.getEncoder().encodeToString("Line one\nLine two".toByteArray())))
        val before = smtpSeen.size
        assertThrows(ToolError::class.java) { i.run("gmail_send", input("""{"to":"a@example.com","subject":"Hi\r\nBcc: evil@example.com","body":"x"}""")) }
        assertThrows(ToolError::class.java) { i.run("gmail_send", input("""{"to":"not an address","subject":"Hi","body":"x"}""")) }
        assertThrows(ToolError::class.java) { i.run("gmail_send", input("""{"to":"a@example.com>\r\nRCPT TO:<evil@example.com","subject":"Hi","body":"x"}""")) }
        assertEquals("nothing was sent for the refused messages", before, smtpSeen.size)
    }

    @Test fun mimeHandlesBase64BodiesAndHtmlOnlyMail() {
        val b64 = "From: a@b.co\r\nSubject: Hi\r\nContent-Type: text/plain; charset=\"utf-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + Base64.getEncoder().encodeToString("नमस्ते दुनिया".toByteArray())
        assertTrue(Mime.readable(b64.toByteArray(Charsets.ISO_8859_1)).contains("नमस्ते दुनिया"))
        val html = "From: a@b.co\r\nSubject: Hi\r\nContent-Type: text/html\r\n\r\n<html><style>p{}</style><p>Fish &amp; chips</p><br>Bye</html>"
        val text = Mime.readable(html.toByteArray())
        assertTrue(text, text.contains("Fish & chips") && text.contains("Bye") && !text.contains("<") && !text.contains("p{}"))
    }

    @Test fun scheduleTimesAreWorkedOutCorrectly() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
        val daily = Schedule("1", "t", "p", hour = 9, minute = 30, repeat = "daily")
        // Monday 16 Nov 2026
        assertEquals(at(2026, 11, 16, 9, 30), Scheduler.next(daily, at(2026, 11, 16, 8, 0), zone))
        assertEquals(at(2026, 11, 17, 9, 30), Scheduler.next(daily, at(2026, 11, 16, 9, 30), zone))
        val weekdays = daily.copy(repeat = "weekdays")
        assertEquals("Friday evening moves to Monday", at(2026, 11, 23, 9, 30), Scheduler.next(weekdays, at(2026, 11, 20, 18, 0), zone))
        val weekly = daily.copy(repeat = "weekly", weekday = 3)
        assertEquals("the next Wednesday", at(2026, 11, 18, 9, 30), Scheduler.next(weekly, at(2026, 11, 16, 10, 0), zone))
        assertEquals(at(2026, 11, 25, 9, 30), Scheduler.next(weekly, at(2026, 11, 18, 9, 30), zone))
        val once = daily.copy(repeat = "once")
        assertEquals(at(2026, 11, 16, 9, 30), Scheduler.next(once, at(2026, 11, 16, 9, 0), zone))
        assertNull("a one-off task that has run never runs again", Scheduler.next(once.copy(lastRun = 5), at(2026, 11, 16, 9, 0), zone))
        assertEquals("Weekdays at 09:30", Scheduler.describe(weekdays))
        assertEquals("Wednesdays at 09:30", Scheduler.describe(weekly))
    }
}
