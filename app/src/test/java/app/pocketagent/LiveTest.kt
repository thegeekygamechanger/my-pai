package app.pocketagent

import app.pocketagent.ai.ClaudeApi
import app.pocketagent.ai.ModelInfo
import app.pocketagent.ai.RunHost
import app.pocketagent.ai.RunManager
import app.pocketagent.ai.ToolHost
import app.pocketagent.ai.Tools
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.str
import app.pocketagent.data.Blobs
import app.pocketagent.data.Chat
import app.pocketagent.data.Settings
import app.pocketagent.data.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Talks to the real Claude API. Skipped unless a git-ignored `live.properties` with a line `key=...`
 * sits in the project root, so it never runs by accident and no key is ever part of the source.
 * Every request here is tiny; a full run costs a few cents.
 */
class LiveTest {
    private val key: String? = File("../live.properties").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("key=") }?.removePrefix("key=")?.trim()?.takeIf { it.isNotEmpty() }
    private val tmp: File = Files.createTempDirectory("pa-live").toFile()
    private val dao: app.pocketagent.data.ChatDao = app.pocketagent.data.TrackedDao(RunLoopTest.FakeDao()) {}
    private val workspace = Workspace(File(tmp, "ws"))
    private var models: List<ModelInfo> = emptyList()
    private var image: ByteArray? = null
    private val api by lazy { ClaudeApi { ClaudeApi.PRODUCTION } }
    private val runs by lazy {
        val toolHost = object : ToolHost {
            override fun copyToClipboard(text: String) {}
            override fun tasksChanged(chatId: Long, tasksJson: String) = runBlocking { dao.setTasks(chatId, tasksJson) }
        }
        val host = object : RunHost {
            override suspend fun settings() = Settings(maxSteps = 8, budgetK = 0, maxOutput = 8000, name = "Sam", style = "short")
            override fun provider(id: String) = app.pocketagent.ai.Providers.PRESETS.firstOrNull { it.id == id }
            override fun key(providerId: String): String? = key
            override fun modelInfo(id: String) = models.firstOrNull { it.id == id }
            override fun attachment(path: String): ByteArray? = image
            override fun runsChanged(active: Boolean) {}
            override fun finished(chat: Chat, replyPreview: String, failed: Boolean) {}
            override fun approvalNeeded(chat: Chat, title: String) {}
            override fun approvalResolved(chatId: Long) {}
        }
        RunManager(dao, api, app.pocketagent.ai.OpenAiApi(), Tools(workspace, Workspace(File(tmp, "mem")), toolHost), Blobs(File(tmp, "blobs")), host)
    }

    private fun text(t: String) = parseArray("""[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(t)}}]""")
    private fun await(id: Long) { val until = System.currentTimeMillis() + 180_000; while (runs.isRunning(id)) { check(System.currentTimeMillis() < until) { "timed out" }; Thread.sleep(50) } }
    private suspend fun chat(model: String, agent: Boolean = false, web: Boolean = false, fast: Boolean = false, effort: String = "low") =
        dao.insert(Chat(createdAt = 1, updatedAt = 1, agent = agent, model = model, effort = effort, web = web, fast = fast, permission = "full_auto"))

    /** Prints what came back and fails if the run ended with an error note. */
    private suspend fun report(label: String, id: Long): String {
        val rows = dao.messages(id)
        val notes = rows.filter { it.role == "note" }
        val last = rows.lastOrNull { it.role == "assistant" }
        val types = rows.filter { it.role == "assistant" }.flatMap { m -> parseArray(m.content).map { (it as JsonObject).str("type") } }
        val reply = last?.let { m -> parseArray(m.content).mapNotNull { (it as JsonObject).takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString(" ") }.orEmpty()
        val c = dao.chat(id)!!
        println("LIVE $label | model=${last?.model} stop=${last?.stopReason} blocks=$types in=${c.inTok} out=${c.outTok} costMicros=${c.costMicros} rejected=${runs.rejectedFeatures()} notes=${notes.map { it.stopReason + ":" + it.content }} reply=${reply.take(160).replace('\n', ' ')}")
        assertTrue("$label ended with an error: ${notes.map { it.content }}", notes.none { it.stopReason == "error" })
        assertTrue("$label produced no reply", reply.isNotBlank())
        return reply
    }

    @Test fun realApi() = runBlocking {
        Assume.assumeTrue("no live.properties, skipping the live test", key != null)
        models = api.listModels(key!!)
        println("LIVE models | " + models.joinToString { "${it.id}(out=${it.maxOutput})" })
        assertTrue(models.isNotEmpty())
        val ids = models.map { it.id }.toSet()

        // 1. A plain chat on each current model family, with every optional extra switched on.
        for (m in listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-2").filter { it in ids }) {
            val id = chat(m)
            runs.send(id, text("Reply with the single word OK."))
            await(id)
            report("chat $m", id)
        }
        val cheap = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5").first { it in ids }

        // 2. Agent mode: the model has to call the phone's file tool, read the result and finish.
        val agent = chat(cheap, agent = true)
        runs.send(agent, text("Create a file named hello.txt in the workspace containing exactly the text hi. Then tell me it is done."))
        await(agent)
        report("agent $cheap", agent)
        assertEquals("hi", File(workspace.root, "hello.txt").readText().trim())

        // 3. Same chat switched back to normal mode: tools change, so older reasoning has to be dropped cleanly.
        dao.update(dao.chat(agent)!!.copy(agent = false))
        runs.send(agent, text("In five words or fewer, what did you just do?"))
        await(agent)
        report("mode switch $cheap", agent)

        // 4. Web search runs on Anthropic's side; the reply must come back with search blocks the app can keep.
        val web = chat(cheap, web = true)
        runs.send(web, text("Search the web for the latest stable Kotlin release and answer with just the version number."))
        await(web)
        report("web $cheap", web)
        val webTypes = dao.messages(web).filter { it.role == "assistant" }.flatMap { m -> parseArray(m.content).map { (it as JsonObject).str("type") } }
        assertTrue("expected a server tool call, got $webTypes", "server_tool_use" in webTypes)
        // A follow-up proves the stored search blocks are accepted when sent back.
        runs.send(web, text("Thanks. Reply with the single word OK."))
        await(web)
        report("web follow-up $cheap", web)

        // 5. An image attachment stored on the "phone".
        val bmp = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.RED; fillRect(0, 0, 64, 64); dispose() } }
        image = ByteArrayOutputStream().also { ImageIO.write(bmp, "jpg", it) }.toByteArray()
        val vision = chat(cheap)
        runs.send(vision, parseArray("""[{"type":"image","source":{"type":"local","path":"att/red.jpg","media_type":"image/jpeg"}},{"type":"text","text":"What single colour fills this image? One word."}]"""))
        await(vision)
        assertTrue(report("image $cheap", vision).contains("red", ignoreCase = true))

        // 6. Fast mode, where the model offers it.
        if ("claude-opus-5-5" in ids) {
            val fast = chat("claude-opus-5-5", fast = true)
            runs.send(fast, text("Reply with the single word OK."))
            await(fast)
            report("fast claude-opus-5-5", fast)
        }
        println("LIVE done | rejected extras this session: ${runs.rejectedFeatures()}")
    }
}
