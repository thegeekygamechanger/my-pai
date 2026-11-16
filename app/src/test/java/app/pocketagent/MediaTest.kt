package app.pocketagent

import app.pocketagent.ai.Media
import app.pocketagent.ai.Providers
import app.pocketagent.ai.Risk
import app.pocketagent.ai.parseObject
import app.pocketagent.data.ToolError
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Making pictures and videos, against a local stand-in for Gemini and OpenAI. */
class MediaTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val seen = CopyOnWriteArrayList<String>()
    private val keys = HashMap<String, String>()
    private val png = ByteArray(400) { (it % 251).toByte() }
    private val mp4 = ByteArray(900) { (it % 13).toByte() }
    private var fileTries = 0
    /** Makes the stand-in refuse the usual Gemini model, as happens when a model is retired. */
    private var retired = false

    private fun base() = "http://127.0.0.1:${server.address.port}"
    private var freeOn = false
    private var freeTries = 0
    private fun media() = Media({ keys[it] }, dir, base() + "/openai", base() + "/gemini", pollMs = 10, free = { freeOn }, freeUrl = base() + "/free", pollinationsUrl = base() + "/poll")
    private fun input(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
    private fun created(text: String): File = dir.resolve(Media.REF.find(text)!!.groupValues[1])

    @Before fun setUp() {
        dir = Files.createTempDirectory("media").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            seen += ex.requestMethod + " " + ex.requestURI + " key=" + (ex.requestHeaders.getFirst("x-goog-api-key") ?: ex.requestHeaders.getFirst("authorization")) + " body=" + body
            val path = ex.requestURI.path
            val asked = parseObject(body)
            var bytes: ByteArray? = null
            var type = "video/mp4"
            val (code, reply) = when {
                path == "/gemini/interactions" && retired && asked?.get("model") == JsonPrimitive(Media.GEMINI_IMAGE) -> 404 to """{"error":{"message":"model not found"}}"""
                path == "/gemini/interactions" && body.contains("\"video\"") && body.contains("big") ->
                    200 to """{"id":"v2","status":"completed","steps":[{"type":"model_output","content":[{"type":"video","mime_type":"video/mp4","uri":"${base()}/gemini/files/f1:download?alt=media"}]}]}"""
                path == "/gemini/interactions" && body.contains("\"video\"") -> 200 to """{"id":"v1","status":"in_progress"}"""
                path == "/gemini/interactions/v1" -> 200 to """{"id":"v1","status":"completed","steps":[{"type":"model_output","content":[{"type":"video","mime_type":"video/mp4","data":"${Base64.getEncoder().encodeToString(mp4)}"}]}]}"""
                path == "/gemini/interactions" && body.contains("refuse me") -> 200 to """{"id":"i9","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"I can't make that."}]}]}"""
                path == "/gemini/interactions" -> 200 to """{"id":"i1","status":"completed","steps":[{"type":"thought"},{"type":"model_output","content":[{"type":"text","text":"Here it is"},{"type":"image","mime_type":"image/jpeg","data":"${Base64.getEncoder().encodeToString(png)}"}]}]}"""
                path == "/gemini/models" -> 200 to """{"models":[{"name":"models/gemini-9-flash"},{"name":"models/gemini-nano-banana-3"},{"name":"models/gemini-nano-banana-2.1"}]}"""
                // The free service: it asks for a short pause the first time, then draws.
                path.startsWith("/free/prompt/") -> { freeTries++; if (freeTries == 1) { ex.responseHeaders.add("Retry-After", "1"); 402 to "{}" } else { bytes = png; type = "image/jpeg"; 200 to "" } }
                path.startsWith("/poll/") && ex.requestHeaders.getFirst("authorization") != "Bearer pk_test" -> 401 to """{"error":{"message":"Authentication required"}}"""
                path.startsWith("/poll/image/") -> { bytes = png; type = "image/png"; 200 to "" }
                path.startsWith("/poll/video/") -> { bytes = mp4; 200 to "" }
                path.startsWith("/gemini/files/") -> { fileTries++; if (fileTries < 3) 404 to "{}" else { bytes = mp4; 200 to "" } }
                path == "/openai/models" -> 200 to """{"data":[{"id":"gpt-9"},{"id":"gpt-image-3-mini"},{"id":"gpt-image-3"}]}"""
                path == "/openai/images/generations" && ex.requestHeaders.getFirst("authorization") == "Bearer bad" -> 401 to """{"error":{"message":"Incorrect API key"}}"""
                path == "/openai/images/generations" -> 200 to """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
                else -> 404 to """{"error":{"message":"nothing here"}}"""
            }
            val out = bytes ?: reply.toByteArray()
            if (bytes != null) ex.responseHeaders.add("content-type", type)
            ex.sendResponseHeaders(code, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @After fun tearDown() { server.stop(0); dir.deleteRecursively() }

    @Test fun toolsAppearOnlyWithAKeyThatCanDoTheJob() {
        assertTrue(media().specs().isEmpty())
        keys[Providers.vaultKey("openai")] = "sk-test"
        assertEquals(listOf(Media.IMAGE), media().specs().map { it.name })
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        assertEquals(listOf(Media.IMAGE, Media.VIDEO), media().specs().map { it.name })
        // A picture is made without asking; a video costs more and takes longer, so it is asked about first.
        assertEquals(Risk.READ, media().risk(Media.IMAGE))
        assertEquals(Risk.WRITE, media().risk(Media.VIDEO))
    }

    @Test fun geminiMakesAPictureAndItIsSavedOnTheDevice() {
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        val text = media().run(Media.IMAGE, input("prompt" to "a red kite over a green hill", "shape" to "wide"))
        val file = created(text)
        assertTrue(file.name.endsWith(".jpg"))
        assertTrue(file.readBytes().contentEquals(png))
        val call = seen.single()
        assertTrue(call.startsWith("POST /gemini/interactions key=AIza-test"))
        assertTrue(call.contains("a red kite over a green hill") && call.contains("\"aspect_ratio\":\"16:9\"") && call.contains(Media.GEMINI_IMAGE))
        // The key travels in a header, never in the address.
        assertFalse(call.substringBefore(" key=").contains("AIza"))
    }

    @Test fun aRetiredModelIsReplacedByTheNewestOfItsKind() {
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        retired = true
        val text = media().run(Media.IMAGE, input("prompt" to "a lighthouse"))
        assertTrue(created(text).isFile)
        assertTrue(seen.any { it.startsWith("GET /gemini/models") })
        assertTrue(seen.last().contains("gemini-nano-banana-3"))
    }

    @Test fun openAiMakesAPictureWithTheNewestImageModelOnTheAccount() {
        keys[Providers.vaultKey("openai")] = "sk-test"
        val text = media().run(Media.IMAGE, input("prompt" to "a paper boat", "shape" to "tall"))
        assertTrue(created(text).readBytes().contentEquals(png))
        val call = seen.last()
        assertTrue(call.startsWith("POST /openai/images/generations key=Bearer sk-test"))
        assertTrue(call.contains("\"model\":\"gpt-image-3\"") && call.contains("1024x1536"))
    }

    @Test fun whenOneServiceFailsTheOtherIsUsed() {
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        keys[Providers.vaultKey("openai")] = "sk-test"
        val text = media().run(Media.IMAGE, input("prompt" to "refuse me"))
        assertTrue(created(text).isFile)
        assertTrue(seen.any { it.startsWith("POST /openai/images/generations") })
    }

    @Test fun aVideoThatTakesTimeIsWaitedFor() {
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        val text = media().run(Media.VIDEO, input("prompt" to "waves at dusk", "seconds" to 40))
        val file = created(text)
        assertTrue(file.name.endsWith(".mp4") && file.readBytes().contentEquals(mp4))
        // Longer than the service allows is brought down to its limit.
        assertTrue(seen.first().contains("\"duration\":\"15s\"") && seen.first().contains(Media.GEMINI_VIDEO))
        assertTrue(seen.any { it.startsWith("GET /gemini/interactions/v1") })
    }

    @Test fun aLargeVideoIsFetchedFromItsLinkOnceReady() {
        keys[Providers.vaultKey("gemini")] = "AIza-test"
        val text = media().run(Media.VIDEO, input("prompt" to "a big parade"))
        assertTrue(created(text).readBytes().contentEquals(mp4))
        assertEquals(3, fileTries)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun withNoKeyAtAllTheFreeServiceDrawsThePicture() {
        // Switched off, there is no picture tool; switched on, there is, and still no video tool.
        assertTrue(media().specs().isEmpty())
        freeOn = true
        assertEquals(listOf(Media.IMAGE), media().specs().map { it.name })
        val text = media().run(Media.IMAGE, input("prompt" to "a red kite & a green hill", "shape" to "wide"))
        assertTrue(created(text).readBytes().contentEquals(png))
        // It was asked to wait once, waited, and asked again; the request carries the description and no key.
        assertEquals(2, freeTries)
        val call = seen.last()
        assertTrue(call.startsWith("GET /free/prompt/a%20red%20kite%20%26%20a%20green%20hill?width=1024&height=576"))
        assertTrue(call.contains("key=null"))
    }

    @Test fun aKeyOfThePersonsOwnIsUsedBeforeTheFreeService() {
        freeOn = true
        keys[Providers.vaultKey("pollinations")] = "pk_test"
        val picture = media().run(Media.IMAGE, input("prompt" to "a paper boat"))
        assertTrue(created(picture).name.endsWith(".png"))
        assertTrue(seen.last().startsWith("GET /poll/image/a%20paper%20boat?model=${Media.POLLINATIONS_IMAGE}"))
        assertEquals(0, freeTries)
        // The same key opens an open-source video model, five seconds long.
        assertEquals(listOf(Media.IMAGE, Media.VIDEO), media().specs().map { it.name })
        val clip = media().run(Media.VIDEO, input("prompt" to "waves at dusk", "shape" to "tall"))
        assertTrue(created(clip).readBytes().contentEquals(mp4))
        assertTrue(seen.last().contains("/poll/video/waves%20at%20dusk?model=${Media.POLLINATIONS_VIDEO}&duration=5&aspectRatio=9:16"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun problemsComeBackInPlainWords() {
        assertThrows(ToolError::class.java) { media().run(Media.IMAGE, input("prompt" to "a cat")) }
        keys[Providers.vaultKey("openai")] = "bad"
        val e = assertThrows(ToolError::class.java) { media().run(Media.IMAGE, input("prompt" to "a cat")) }
        assertTrue(e.message!!.contains("refused the key"))
        assertThrows(ToolError::class.java) { media().run(Media.IMAGE, input("prompt" to "  ")) }
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
