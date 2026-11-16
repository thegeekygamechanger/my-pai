package app.pocketagent.desktop

import app.pocketagent.Graph
import app.pocketagent.ai.Providers
import app.pocketagent.data.Chat
import app.pocketagent.data.Msg
import app.pocketagent.sync.ShareClient
import app.pocketagent.sync.ShareCode
import app.pocketagent.sync.ShareHost
import app.pocketagent.sync.SyncCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Simulated devices link, sync and share through a real relay, and every step is checked. Run it against a
 * local relay: `PORT=18790 node server/relay.mjs` then `./gradlew :desktop:syncCheck`.
 */
fun main(args: Array<String>) {
    val base = File(args.getOrElse(0) { "build/sync-check" }).apply { deleteRecursively(); mkdirs() }
    var failed = 0
    fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failed++ }
    runBlocking(Dispatchers.Default) {
        suspend fun until(ms: Long = 10_000, cond: suspend () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (runCatching { cond() }.getOrDefault(false)) return true; delay(60) }
            return false
        }
        suspend fun device(name: String): Graph {
            val g = desktopGraph(DesktopPlatform(File(base, name)))
            g.start()
            until { g.settings.value?.deviceId?.isNotEmpty() == true }
            return g
        }
        fun text(t: String) = """[{"type":"text","text":"$t"}]"""
        suspend fun chatWith(g: Graph, title: String, vararg lines: Pair<String, String>): Long {
            val now = System.currentTimeMillis()
            val id = g.dao.insert(Chat(title = title, createdAt = now, updatedAt = now, model = "claude-opus-5-5", effort = "medium"))
            for ((role, t) in lines) g.dao.insert(Msg(chatId = id, role = role, content = text(t), createdAt = System.currentTimeMillis()))
            return id
        }
        suspend fun titles(g: Graph) = g.dao.allChats().map { it.title }.filter { it.isNotBlank() }.sorted()
        suspend fun texts(g: Graph, title: String): List<String> {
            val c = g.dao.allChats().firstOrNull { it.title == title } ?: return emptyList()
            return g.dao.messages(c.id).map { it.role + ":" + it.content }
        }

        val a = device("a")
        val b = device("b")

        // --- A has data before any sync exists.
        val first = chatWith(a, "Trip plan", "user" to "Plan a trip", "assistant" to "Day one: MARKER-PLAINTEXT museum")
        a.workspace.write("notes/plan.md", "# Plan\nMARKER-PLAINTEXT")
        a.memory.write("me.md", "likes tea")
        a.vault.write(Providers.vaultKey(Providers.ANTHROPIC), "sk-ant-test-key-000000000000")
        a.updateShared { it.copy(customInstructions = "Be brief", name = "Sam", onboarded = true) }
        val att = File(a.attach.dir, "pic.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        a.dao.insert(Msg(chatId = first, role = "user", content = """[{"type":"image","source":{"type":"local","path":"att/pic.jpg","media_type":"image/jpeg"}},{"type":"text","text":"and this photo"}]""", createdAt = System.currentTimeMillis()))

        val code = a.sync.enable()
        check("turning sync on gives a 25-character code", code.length == 25)
        check("first device is online with nobody else", until { a.sync.status.value.online } && a.sync.status.value.peers.isEmpty())

        // --- An eavesdropper in the same room (it knows the room name, as the relay does, but not the code).
        val overheard = StringBuilder()
        val relay = System.getProperty("pocketagent.relay")
        val spyClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val spyCall = spyClient.newCall(Request.Builder().url("$relay/v1/r/${SyncCode.room(code, SyncCode.SYNC)}/s?d=spy-device-0001").build())
        thread(isDaemon = true) {
            runCatching {
                spyCall.execute().use { resp ->
                    val src = resp.body.source()
                    while (true) { val line = src.readUtf8Line() ?: break; if (line.startsWith("data:") && !line.contains("ids")) synchronized(overheard) { overheard.append(String(Base64.getDecoder().decode(line.substring(5).trim()), Charsets.ISO_8859_1)) } }
                }
            }
        }
        delay(400)

        // --- B links with the code and catches up on everything.
        check("a typed code is accepted", b.sync.join(SyncCode.pretty(code)))
        check("both devices see each other", until { a.sync.status.value.peers.size == 2 && b.sync.status.value.peers.size == 2 })
        check("the chat arrives with all three messages", until { texts(b, "Trip plan").size == 3 })
        check("messages arrive in the same order", texts(a, "Trip plan") == texts(b, "Trip plan"))
        check("the attachment file arrives intact", until { File(b.attach.dir, "pic.jpg").let { it.isFile && it.readBytes().contentEquals(att.readBytes()) } })
        check("workspace and memory files arrive", until { runCatching { b.workspace.read("notes/plan.md").contains("# Plan") && b.memory.read("me.md").contains("likes tea") }.getOrDefault(false) })
        check("the API key, name and instructions arrive", until { b.keyFor(Providers.ANTHROPIC) == "sk-ant-test-key-000000000000" && b.settings.value?.customInstructions == "Be brief" && b.settings.value?.name == "Sam" })
        check("the new device is ready to use without typing a key", until { b.settings.value?.onboarded == true })
        check("each device lists both", until { a.settings.value?.devices?.count { !it.removed } == 2 && b.settings.value?.devices?.count { !it.removed } == 2 })

        // --- Live changes, both ways.
        val bChat = b.dao.allChats().first { it.title == "Trip plan" }
        b.dao.insert(Msg(chatId = bChat.id, role = "user", content = text("Add a second day"), createdAt = System.currentTimeMillis()))
        check("a message written on B appears on A", until { texts(a, "Trip plan").size == 4 })
        val t0 = System.currentTimeMillis()
        chatWith(a, "Shopping", "user" to "milk")
        check("a new chat on A appears on B", until { "Shopping" in titles(b) })
        println("      (took ${System.currentTimeMillis() - t0} ms)")
        b.dao.rename(b.dao.allChats().first { it.title == "Shopping" }.id, "Groceries")
        check("a rename on B appears on A", until { "Groceries" in titles(a) && "Shopping" !in titles(a) })
        b.workspace.write("notes/plan.md", "# Plan v2"); b.sync.filesMayHaveChanged()
        check("a file edited on B updates on A", until(12_000) { a.workspace.read("notes/plan.md").contains("v2") })
        a.workspace.delete("notes/plan.md"); a.sync.filesMayHaveChanged()
        check("a file deleted on A disappears on B", until(12_000) { !File(b.workspace.root, "notes/plan.md").exists() })
        a.deleteChat(a.dao.allChats().first { it.title == "Groceries" }.id)
        check("a chat deleted on A disappears on B", until { "Groceries" !in titles(b) })
        b.updateShared { it.copy(customInstructions = "Be thorough") }
        check("changed instructions reach A", until { a.settings.value?.customInstructions == "Be thorough" })

        // --- Incognito: a chat that stays on the device it was written on, and is gone when it is left.
        val secret = a.newIncognitoChat()
        a.dao.rename(secret, "Secret plans")
        a.dao.insert(Msg(chatId = secret, role = "user", content = text("only here"), createdAt = System.currentTimeMillis()))
        chatWith(a, "After the secret", "user" to "hello")
        check("an ordinary chat made after an incognito one arrives", until { "After the secret" in titles(b) })
        check("the incognito chat was not sent", "Secret plans" !in titles(b))
        check("it is not in its own device's list or search", a.dao.chats().first().none { it.title == "Secret plans" } && a.dao.search("%Secret plans%").first().isEmpty() && a.dao.search("%only here%").first().isEmpty())
        a.leftChat(secret)
        check("leaving it erases it", a.dao.chat(secret) == null && a.dao.messages(secret).isEmpty())
        check("and nobody is told it ever existed", a.dao.tombs().none { it.kind == "chat" && it.uid.startsWith("incognito-") })

        // --- Catching up after being away.
        b.sync.leave()
        check("an unlinked device goes quiet", until { !b.sync.status.value.enabled && a.sync.status.value.peers.size == 1 })
        chatWith(a, "While you were away", "user" to "hello", "assistant" to "hi")
        chatWith(b, "Written offline", "user" to "note to self")
        a.dao.insert(Msg(chatId = first, role = "assistant", content = text("Day two: the coast"), createdAt = System.currentTimeMillis()))
        check("it links again", b.sync.join(code))
        check("B gets what A did meanwhile", until { "While you were away" in titles(b) && texts(b, "Trip plan").size == 5 })
        check("A gets what B did meanwhile", until { "Written offline" in titles(a) })
        check("both ends hold the same chats", until { titles(a) == titles(b) })
        check("a chat deleted earlier stays deleted", "Groceries" !in titles(a) && "Groceries" !in titles(b))

        // --- The relay and anyone listening see only ciphertext.
        spyCall.cancel()
        val heard = synchronized(overheard) { overheard.toString() }
        check("the eavesdropper received traffic", heard.length > 1000)
        check("none of it is readable (no chat text, file text or key)", !heard.contains("MARKER-PLAINTEXT") && !heard.contains("Trip plan") && !heard.contains("sk-ant-test"))

        // --- Up to five devices.
        val more = listOf("c", "d", "e").map { device(it) }
        more.forEach { it.sync.join(code) }
        check("five devices are linked and live", until(15_000) { a.sync.status.value.peers.size == 4 && more.all { m -> titles(m) == titles(a) } })
        check("every device lists five", until { (more + a + b).all { g -> g.settings.value?.devices?.count { !it.removed } == 5 } })
        val sixth = device("f")
        sixth.sync.join(code)
        check("a sixth device is turned away", until(15_000) { !sixth.sync.status.value.enabled && sixth.sync.status.value.error.isNotEmpty() })
        check("the sixth device received nothing", titles(sixth).isEmpty() && sixth.keyFor(Providers.ANTHROPIC) == null)

        // --- Removing a device.
        val gone = more.last()
        a.sync.removeDevice(gone.settings.value!!.deviceId)
        check("a removed device unlinks itself", until { !gone.sync.status.value.enabled })
        check("the others drop it from their lists", until { b.settings.value?.devices?.count { !it.removed } == 4 })
        check("the freed place can be taken", sixth.sync.join(code) && until(15_000) { sixth.sync.status.value.online && titles(sixth) == titles(a) })

        // --- Voice typing on a computer: the recording goes to the connected service's speech model.
        val heardForm = StringBuilder()
        val stt = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        stt.createContext("/audio/transcriptions") { ex ->
            heardForm.append(ex.requestHeaders.getFirst("authorization")).append('|').append(String(ex.requestBody.readBytes(), Charsets.ISO_8859_1))
            val body = """{"text":" नमस्ते, how are you? "}""".toByteArray(); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        stt.start()
        check("voice typing is off until a speech-capable service is connected", !app.pocketagent.ai.Transcribe.available(a))
        a.vault.write(Providers.vaultKey("groq"), "gsk_test_000000")
        val said = app.pocketagent.ai.Transcribe.transcribe(a, app.pocketagent.ai.Transcribe.wav(ByteArray(32000), 16000), "hi-IN") { "http://127.0.0.1:${stt.address.port}" }
        stt.stop(0)
        check("speech comes back as text, in any script", said == "नमस्ते, how are you?")
        check("the recording was sent as a WAV file with the model and language", heardForm.startsWith("Bearer gsk_test_000000|") && heardForm.contains("whisper-large-v3") && heardForm.contains("name=\"language\"") && heardForm.contains("RIFF"))
        a.vault.write(Providers.vaultKey("groq"), null)

        // --- Sharing one chat with someone who is not linked.
        val stranger = device("stranger")
        val host = ShareHost(a, first).also { it.start() }
        delay(600)
        check("a share code is four letters and digits, with both", host.code.length == 4 && host.code.any { it.isLetter() } && host.code.any { it.isDigit() } && host.code.all { it.isLetterOrDigit() })
        check("it is found inside a pasted message, and alone", ShareCode.find("here you go mypai-share:${host.code.lowercase()} thanks")?.groupValues?.get(1)?.uppercase() == host.code && ShareCode.parse(" ${host.code} ") == host.code)
        check("an ordinary word or number is not a code", ShareCode.parse("GOOD") == null && ShareCode.parse("2026") == null && ShareCode.parse("AB2") == null && ShareCode.find("pocketagent-share:ABCD2") == null)
        check("a share code is not a sync code", !stranger.sync.join(ShareCode.text(host.code)))
        val wrong = generateSequence { ShareCode.new() }.first { it != host.code }
        check("a wrong code reaches nobody", kotlinx.coroutines.withTimeoutOrNull(4000) { ShareClient.fetch(device("guesser"), wrong) } == null)
        // Someone who learns the code afterwards and has a recording still cannot read the chat: the key was agreed afresh and is gone.
        val k1 = app.pocketagent.sync.ShareSession.keyPair(); val k2 = app.pocketagent.sync.ShareSession.keyPair(); val wrap = ShareCode.derive(host.code).key
        val s1 = app.pocketagent.sync.ShareSession.key(k1, k2.public.encoded, wrap, k1.public.encoded, k2.public.encoded, "r")
        val s2 = app.pocketagent.sync.ShareSession.key(k2, k1.public.encoded, wrap, k1.public.encoded, k2.public.encoded, "r")
        check("both sides agree a fresh key, different every time", s1 != null && s1.contentEquals(s2) && !s1.contentEquals(app.pocketagent.sync.ShareSession.key(app.pocketagent.sync.ShareSession.keyPair(), k2.public.encoded, wrap, k1.public.encoded, k2.public.encoded, "r")))
        check("a broken public key is refused", app.pocketagent.sync.ShareSession.key(k1, ByteArray(40) { 7 }, wrap, k1.public.encoded, k2.public.encoded, "r") == null)
        a.settingsStore.update { it.copy(name = "Asha") }
        delay(200)
        val fetched = ShareClient.fetch(stranger, host.code)
        check("the chat arrives with its title and the sharer's name", fetched != null && fetched.title == "Trip plan" && fetched.by == "Asha" && fetched.messages.size == 5)
        check("and nothing is saved until the person agrees", titles(stranger).isEmpty() && !File(stranger.attach.dir, "pic.jpg").exists())
        val got = fetched?.let { ShareClient.save(stranger, it) }
        check("the stranger receives the chat", got != null && texts(stranger, "Trip plan").size == 5)
        check("with its attachment", File(stranger.attach.dir, "pic.jpg").isFile)
        check("and nothing else: one chat, no key, no files", titles(stranger) == listOf("Trip plan") && stranger.keyFor(Providers.ANTHROPIC) == null && !File(stranger.memory.root, "me.md").exists())
        check("the sharer counted the hand-over", host.served.value >= 1)
        host.stop()
        check("a closed share can no longer be opened", ShareClient.open(device("late"), host.code) == null)
    }
    println(if (failed == 0) "ALL CHECKS PASSED" else "$failed CHECK(S) FAILED")
    exitProcess(if (failed == 0) 0 else 1)
}
