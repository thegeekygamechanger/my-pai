package app.pocketagent

import app.pocketagent.ai.ApiException
import app.pocketagent.ai.Feature
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.OpenAiAssembler
import app.pocketagent.ai.OpenAiCompat
import app.pocketagent.ai.Providers
import app.pocketagent.sync.Sealer
import app.pocketagent.sync.SyncCode
import app.pocketagent.ai.RequestBuilder
import app.pocketagent.ai.RunConfig
import app.pocketagent.ai.StreamAssembler
import app.pocketagent.ai.ToolHost
import app.pocketagent.ai.Tools
import app.pocketagent.ai.Usage
import app.pocketagent.ai.arr
import app.pocketagent.ai.obj
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.parseObject
import app.pocketagent.ai.str
import app.pocketagent.data.ToolError
import app.pocketagent.data.Workspace
import app.pocketagent.ui.Inline
import app.pocketagent.ui.Markdown
import app.pocketagent.ui.Item
import app.pocketagent.ui.MdBlock
import app.pocketagent.ui.UiRow
import app.pocketagent.ui.WRITE_EDGE
import app.pocketagent.ui.turnWeight
import app.pocketagent.ui.writeOut
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoreTest {
    private fun asm(vararg events: String) = StreamAssembler().apply { events.forEach { accept(parseObject(it)!!) } }

    @Test fun assemblesTextThinkingAndToolUse() {
        val a = asm(
            """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","usage":{"input_tokens":12,"cache_read_input_tokens":3,"cache_creation_input_tokens":4}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"see."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG=="}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hel"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"lo"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"write_file","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\","}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"content\":\"hi\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}""",
            """{"type":"message_stop"}""",
        )
        assertTrue(a.finished)
        val m = a.toMessage()
        assertEquals("msg_1", m.id)
        assertEquals("tool_use", m.stopReason)
        assertEquals(Usage(12, 40, 3, 4), m.usage)
        val c = m.content.map { it as JsonObject }
        assertEquals("Let me see.", c[0].str("thinking"))
        assertEquals("SIG==", c[0].str("signature"))
        assertEquals("Hello", c[1].str("text"))
        assertEquals("a.txt", c[2].obj("input")!!.str("path"))
        assertTrue(m.invalidInputs.isEmpty())
    }

    @Test fun keepsServerToolBlocksAndCitationsUntouched() {
        val a = asm(
            """{"type":"message_start","message":{"id":"m","model":"x","usage":{"input_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"kotlin\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srv_1","content":[{"type":"web_search_result","title":"Kotlin","url":"https://kotlinlang.org","encrypted_content":"abc"}]}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"","citations":[]}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://kotlinlang.org","title":"Kotlin"}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Kotlin is a language."}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"some_future_block","payload":{"a":1}}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}""",
            """{"type":"message_stop"}""",
        )
        val c = a.toMessage().content.map { it as JsonObject }
        assertEquals("kotlin", c[0].obj("input")!!.str("query"))
        assertEquals("abc", (c[1].arr("content")!![0] as JsonObject).str("encrypted_content"))
        assertEquals(1, c[2].arr("citations")!!.size)
        assertEquals("Kotlin is a language.", c[2].str("text"))
        assertEquals("some_future_block", c[3].str("type"))
        assertEquals(JsonPrimitive(1), c[3].obj("payload")!!["a"])
    }

    @Test fun flagsInvalidToolJsonInsteadOfRunningIt() {
        val a = asm(
            """{"type":"message_start","message":{"id":"m","model":"x","usage":{}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"write_file","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\",\"content\":\"unterminated"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":1}}""",
            """{"type":"message_stop"}""",
        )
        val m = a.toMessage()
        assertTrue(m.invalidInputs.containsKey("toolu_9"))
        assertTrue((m.content[0] as JsonObject).obj("input")!!.isEmpty())
    }

    @Test fun streamErrorEventBecomesApiException() {
        val e = assertThrows(ApiException::class.java) { asm("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") }
        assertEquals(ApiException.Kind.OVERLOADED, e.kind)
        assertTrue(e.retryable)
    }

    @Test fun partialTextSurvivesAnUnfinishedReply() {
        val a = asm(
            """{"type":"message_start","message":{"id":"m","model":"x","usage":{}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Half an ans"}}""",
        )
        assertFalse(a.finished)
        assertEquals("Half an ans", a.partialText())
        assertEquals(listOf("thinking", "text"), a.live().map { it.type })
    }

    // ---- request building

    private val host = object : ToolHost {
        override fun copyToClipboard(text: String) {}
        override fun tasksChanged(chatId: Long, tasksJson: String) {}
    }
    private fun tmp() = Files.createTempDirectory("pa-test").toFile()
    private fun tools() = Tools(Workspace(tmp()), Workspace(tmp()), host)
    private fun cfg(model: String = "claude-opus-5-5", agent: Boolean = false, web: Boolean = false, fast: Boolean = false) =
        RunConfig(Providers.PRESETS.first(), model, "high", 32000, "", agent, web, code = false, memory = true, fast = fast, fallbacks = true, compaction = true)
    private fun user(text: String) = buildJsonObject { put("role", "user"); put("content", parseArray("""[{"type":"text","text":"$text"}]""")) }

    @Test fun modernModelGetsAdaptiveThinkingEffortAndExtras() {
        val (body, betas) = RequestBuilder.build(cfg(fast = true), listOf(user("hi")), tools(), emptySet()) { null }
        assertEquals("adaptive", body.obj("thinking")!!.str("type"))
        assertEquals("summarized", body.obj("thinking")!!.str("display"))
        assertEquals("high", body.obj("output_config")!!.str("effort"))
        assertEquals("fast", body.str("speed"))
        assertEquals("default", body.str("fallbacks"))
        assertTrue("compact-2026-01-12" in betas && "server-side-fallback-2026-07-01" in betas)
        assertEquals("a chat carries only the tools for saving a file", listOf("list_files", "read_file", "write_file"), body.arr("tools")!!.map { (it as JsonObject).str("name") })
        for (k in listOf("temperature", "top_p", "top_k", "budget_tokens")) assertNull(body[k])
    }

    @Test fun olderModelGetsNoThinkingEffortOrExtras() {
        val (body, betas) = RequestBuilder.build(cfg("claude-haiku-4-5", web = true, fast = true), listOf(user("hi")), tools(), emptySet()) { null }
        assertNull(body["thinking"]); assertNull(body["output_config"]); assertNull(body["speed"]); assertNull(body["fallbacks"]); assertNull(body["context_management"])
        assertTrue(betas.isEmpty())
        assertEquals("web_search_20250305", body.arr("tools")!!.mapNotNull { (it as JsonObject).str("type") }.first())
    }

    @Test fun effortIsMappedToWhatTheModelAccepts() {
        assertEquals("high", ModelCaps.effortFor("claude-sonnet-4-6", "xhigh"))
        assertEquals("xhigh", ModelCaps.effortFor("claude-opus-5-5", "xhigh"))
        assertNull(ModelCaps.effortFor("claude-haiku-4-5", "high"))
        assertTrue(ModelCaps.adaptive("claude-haiku-5-5") && ModelCaps.adaptive("claude-fable-5-2") && !ModelCaps.adaptive("claude-sonnet-4-5-20250929"))
    }

    @Test fun agentToolsAreStableAndDroppingAFeatureRemovesIt() {
        val t = tools()
        val (a, _) = RequestBuilder.build(cfg(agent = true, web = true), listOf(user("hi")), t, emptySet()) { null }
        val names = a.arr("tools")!!.map { (it as JsonObject).str("name") }
        assertEquals(listOf("list_files", "read_file", "write_file", "edit_file", "delete_file", "search_files", "move_file", "update_tasks", "get_time", "copy_to_clipboard", "memory", "web_search", "web_fetch"), names)
        assertEquals(JsonPrimitive(true), (a.arr("tools")!![2] as JsonObject)["eager_input_streaming"])
        val (b, betas) = RequestBuilder.build(cfg(agent = true), listOf(user("hi")), t, setOf(Feature.EAGER, Feature.COMPACTION, Feature.FALLBACKS, Feature.CACHE)) { null }
        assertNull((b.arr("tools")!![2] as JsonObject)["eager_input_streaming"])
        assertNull(b["context_management"]); assertNull(b["fallbacks"]); assertNull(b["cache_control"]); assertTrue(betas.isEmpty())
    }

    @Test fun fingerprintFollowsSystemAndToolsOnly() {
        val t = tools()
        val base = RequestBuilder.fingerprint(cfg(), t)
        assertEquals(base, RequestBuilder.fingerprint(cfg().copy(effort = "low", maxTokens = 8000, fast = true), t))
        assertNotEquals(base, RequestBuilder.fingerprint(cfg(agent = true), t))
        assertNotEquals(base, RequestBuilder.fingerprint(cfg(web = true), t))
        assertNotEquals(base, RequestBuilder.fingerprint(cfg().copy(customInstructions = "Be brief"), t))
    }

    @Test fun localAttachmentsBecomeBase64AndMissingOnesAreExplained() {
        val msg = buildJsonObject {
            put("role", "user")
            put("content", parseArray("""[{"type":"image","source":{"type":"local","path":"att/a.jpg","media_type":"image/jpeg"}},{"type":"image","source":{"type":"local","path":"att/gone.jpg","media_type":"image/jpeg"}}]"""))
        }
        val (body, _) = RequestBuilder.build(cfg(), listOf(msg), null, emptySet()) { if (it == "att/a.jpg") byteArrayOf(1, 2, 3) else null }
        val c = (body.arr("messages")!![0] as JsonObject).arr("content")!!.map { it as JsonObject }
        assertEquals("base64", c[0].obj("source")!!.str("type"))
        assertEquals("AQID", c[0].obj("source")!!.str("data"))
        assertEquals("text", c[1].str("type"))
    }

    @Test fun fallbackTurnsDropWhatCameBeforeTheHandOver() {
        val content = parseArray(
            """[{"type":"thinking","thinking":"a","signature":"s"},{"type":"text","text":"Start"},{"type":"tool_use","id":"t1","name":"read_file","input":{}},
               {"type":"server_tool_use","id":"s1","name":"web_search","input":{}},{"type":"web_search_tool_result","tool_use_id":"s1","content":[]},
               {"type":"server_tool_use","id":"s2","name":"web_search","input":{}},
               {"type":"fallback","from":{"model":"a"},"to":{"model":"b"}},{"type":"thinking","thinking":"b","signature":"s2"},{"type":"tool_use","id":"t2","name":"get_time","input":{}}]"""
        )
        val kept = RequestBuilder.effective(content).map { (it as JsonObject).let { b -> b.str("id") ?: b.str("tool_use_id") ?: b.str("type")!! } }
        assertEquals(listOf("text", "s1", "s1", "fallback", "thinking", "t2"), kept)
        assertEquals(listOf("t2"), RequestBuilder.pendingToolUses(content).map { it.str("id") })
    }

    @Test fun rejectedFeatureIsRecognisedFromTheErrorText() {
        val sent = setOf(Feature.COMPACTION, Feature.FALLBACKS, Feature.CACHE)
        assertEquals(Feature.COMPACTION, RequestBuilder.rejectedFeature("Unexpected value(s) `compact-2026-01-12` for the `anthropic-beta` header.", sent))
        assertEquals(Feature.FALLBACKS, RequestBuilder.rejectedFeature("fallbacks: Extra inputs are not permitted", sent))
        assertNull(RequestBuilder.rejectedFeature("messages: roles must alternate", sent))
        assertTrue(RequestBuilder.isBindingError("messages.5.content.0: Invalid `signature` in `thinking` block. The block is bound to a different conversation."))
    }

    // ---- other services (the OpenAI-compatible shape)

    private fun oaCfg(agent: Boolean = false) = cfg(model = "llama-3.3-70b", agent = agent).copy(provider = Providers.PRESETS.first { it.id == "groq" })

    @Test fun openAiRequestTranslatesTheStoredConversation() {
        val history = listOf(
            buildJsonObject { put("role", "user"); put("content", parseArray("""[{"type":"image","source":{"type":"local","path":"att/a.jpg","media_type":"image/jpeg"}},{"type":"text","text":"What is this?"}]""")) },
            buildJsonObject { put("role", "assistant"); put("content", parseArray("""[{"type":"thinking","thinking":"x","signature":"s"},{"type":"text","text":"Let me look."},{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}]""")) },
            buildJsonObject { put("role", "user"); put("content", parseArray("""[{"type":"tool_result","tool_use_id":"toolu_1","content":"hello"}]""")) },
        )
        val body = OpenAiCompat.build(oaCfg(agent = true), history, tools(), emptySet()) { byteArrayOf(1, 2, 3) }
        // A model that takes no tools is sent the same conversation without them.
        assertTrue(body["tools"] != null)
        assertNull(OpenAiCompat.build(oaCfg(agent = true), history, tools(), setOf(Feature.TOOLS)) { byteArrayOf(1, 2, 3) }["tools"])
        // Ollama is a service like the others, at an address of the person's own, with no key to ask for.
        assertTrue(Providers.ollama().local && Providers.ollama().baseUrl == "http://localhost:11434/v1")
        assertEquals("http://192.168.1.20:11434/v1", Providers.ollama(" http://192.168.1.20:11434/v1/ ").baseUrl)
        val m = body.arr("messages")!!.map { it as JsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), m.map { it.str("role") })
        val parts = m[1].arr("content")!!.map { it as JsonObject }
        assertEquals("data:image/jpeg;base64,AQID", parts[0].obj("image_url")!!.str("url"))
        val call = m[2].arr("tool_calls")!![0] as JsonObject
        assertEquals("toolu_1", call.str("id"))
        assertEquals("""{"path":"a.txt"}""", call.obj("function")!!.str("arguments"))
        assertEquals("toolu_1", m[3].str("tool_call_id"))
        val names = body.arr("tools")!!.map { (it as JsonObject).obj("function")!!.str("name") }
        assertTrue(names.containsAll(listOf("write_file", "memory")))
        for (k in listOf("thinking", "output_config", "cache_control", "fallbacks", "max_tokens")) assertNull(body[k])
    }

    @Test fun openAiStreamBecomesTheSameBlocksAsAClaudeReply() {
        val a = OpenAiAssembler()
        listOf(
            """{"id":"chatcmpl-1","model":"llama","choices":[{"delta":{"role":"assistant","reasoning_content":"thinking it over"}}]}""",
            """{"choices":[{"delta":{"content":"Sure, "}}]}""",
            """{"choices":[{"delta":{"content":"writing it."}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"write_file","arguments":"{\"path\":\"a.txt\","}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"content\":\"hi\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":12}}""",
        ).forEach { a.accept(parseObject(it)!!) }
        assertEquals(listOf("thinking", "text", "tool_use"), a.live().map { it.type })
        a.done()
        val msg = a.toMessage()
        assertEquals("tool_use", msg.stopReason)
        val c = msg.content.map { it as JsonObject }
        assertEquals(listOf("reasoning", "text", "tool_use"), c.map { it.str("type") })
        assertEquals("Sure, writing it.", c[1].str("text"))
        assertEquals("hi", c[2].obj("input")!!.str("content"))
        assertEquals(Usage(50, 12, 0, 0), msg.usage)
        // Sent on to Claude later, the display-only reasoning block is left out.
        val (body, _) = RequestBuilder.build(cfg(), listOf(buildJsonObject { put("role", "assistant"); put("content", msg.content) }), null, emptySet()) { null }
        assertEquals(listOf("text", "tool_use"), ((body.arr("messages")!![0] as JsonObject).arr("content")!!).map { (it as JsonObject).str("type") })
    }

    @Test fun recordedSpeechIsWrappedAsAValidWavFile() {
        val wav = app.pocketagent.ai.Transcribe.wav(ByteArray(32000) { (it % 7).toByte() }, 16000)
        assertEquals(32044, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        val le = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, le.getInt(24)); assertEquals(1, le.getShort(22).toInt()); assertEquals(16, le.getShort(34).toInt()); assertEquals(32000, le.getInt(40))
        assertTrue(app.pocketagent.ai.SPEECH_LANGUAGES.map { it.first }.containsAll(listOf("", "en-IN", "hi-IN", "ta-IN", "bn-IN")))
    }

    // ---- sync codes and encryption

    @Test fun pairingCodesParseAndDeriveDifferentRoomsAndKeys() {
        val code = SyncCode.new()
        assertEquals(25, code.length)
        assertEquals(code to SyncCode.SYNC, SyncCode.parse(SyncCode.qr(code, SyncCode.SYNC)))
        assertEquals(code to SyncCode.SHARE, SyncCode.parse("  " + SyncCode.qr(code, SyncCode.SHARE).lowercase()))
        assertEquals(code to null, SyncCode.parse(SyncCode.pretty(code)))
        assertNull(SyncCode.parse("hello"))
        assertNotEquals(SyncCode.room(code, SyncCode.SYNC), SyncCode.room(code, SyncCode.SHARE))
        assertEquals(64, SyncCode.room(code, SyncCode.SYNC).length)
        assertFalse(SyncCode.room(code, SyncCode.SYNC).contains(code, ignoreCase = true))
    }

    @Test fun sealedMessagesOpenOnlyWithTheSameCodeAndRoom() {
        val code = SyncCode.new()
        val room = SyncCode.room(code, SyncCode.SYNC)
        val a = Sealer(SyncCode.key(code, SyncCode.SYNC), room)
        val sealed = a.seal("secret chat".repeat(50).toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertEquals("secret chat".repeat(50), String(Sealer(SyncCode.key(code, SyncCode.SYNC), room).open(sealed)!!))
        assertNull(Sealer(SyncCode.key(SyncCode.new(), SyncCode.SYNC), room).open(sealed))
        assertNull(Sealer(SyncCode.key(code, SyncCode.SYNC), "another-room").open(sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertNull(a.open(sealed))
    }

    // ---- workspace sandbox and tools

    @Test fun workspaceRefusesEveryWayOut() {
        val root = tmp()
        val outside = File(root.parentFile, "outside-${System.nanoTime()}.txt").apply { writeText("secret") }
        val w = Workspace(File(root, "ws"))
        try {
            for (p in listOf("../${outside.name}", "a/../../${outside.name}", "..", "a/b/../../../x")) assertThrows(p, ToolError::class.java) { w.read(p) }
            assertThrows(ToolError::class.java) { w.write("../evil.txt", "x") }
            assertThrows(ToolError::class.java) { w.delete("") }
            // An absolute path is treated as relative to the workspace, never as the real filesystem.
            w.write(outside.absolutePath, "mine")
            assertEquals("secret", outside.readText())
            assertTrue(File(w.root, outside.absolutePath.trimStart('/')).isFile)
        } finally { outside.delete() }
    }

    @Test fun fileToolsRoundTrip() {
        val t = tools()
        fun run(name: String, json: String) = t.run(1, name, parseObject(json)!!)
        assertFalse(run("write_file", """{"path":"notes/a.md","content":"one\ntwo\nthree"}""").isError)
        assertTrue(run("read_file", """{"path":"notes/a.md"}""").text.contains("2\ttwo"))
        assertFalse(run("edit_file", """{"path":"notes/a.md","old_text":"two","new_text":"2"}""").isError)
        assertTrue(run("edit_file", """{"path":"notes/a.md","old_text":"missing","new_text":"x"}""").isError)
        assertTrue(run("list_files", "{}").text.contains("a.md"))
        assertTrue(run("write_file", """{"path":"x.txt"}""").isError)
        assertTrue(run("nope", "{}").isError)
        assertFalse(run("delete_file", """{"path":"notes"}""").isError)
        assertEquals("(empty)", run("list_files", "{}").text)
        // memory tool
        assertFalse(run("memory", """{"command":"create","path":"/memories/me.md","file_text":"likes tea"}""").isError)
        assertTrue(run("memory", """{"command":"view","path":"/memories"}""").text.contains("me.md"))
        assertFalse(run("memory", """{"command":"str_replace","path":"/memories/me.md","old_str":"tea","new_str":"coffee"}""").isError)
        assertTrue(run("memory", """{"command":"view","path":"/memories/me.md"}""").text.contains("coffee"))
        assertTrue(run("memory", """{"command":"view","path":"/memories/../../etc/passwd"}""").isError)
    }

    // ---- markdown

    @Test fun markdownBlocks() {
        val b = Markdown.parse("# Title\n\nSome *text*\nmore.\n\n- one\n- two\n  cont\n\n1. first\n2. second\n\n```kotlin\nval x = 1\n```\n\n> quote\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n---\n")
        assertEquals(listOf("Heading", "Para", "Items", "Items", "Code", "Quote", "Table", "Rule"), b.map { it::class.simpleName })
        assertEquals("Some *text*\nmore.", (b[1] as MdBlock.Para).text)
        assertEquals("two\ncont", (b[2] as MdBlock.Items).items[1].text)
        assertEquals("val x = 1", (b[4] as MdBlock.Code).code)
        assertEquals(listOf(listOf("1", "2")), (b[6] as MdBlock.Table).rows)
    }

    @Test fun markdownCopesWithAReplyThatIsStillArriving() {
        val b = Markdown.parse("Here:\n\n```python\nprint('hi')\nfor x in")
        assertEquals("print('hi')\nfor x in", (b[1] as MdBlock.Code).code)
        assertEquals(1, Markdown.parse("| a | b |\n|--").size)
    }

    @Test fun inlineStyles() {
        val s = Inline.parse("a **bold** and *it* and `code*x` [link](https://x.y) https://a.b/c. 2 * 3 * 4 snake_case_name")
        assertTrue(s.any { it.bold && it.text == "bold" })
        assertTrue(s.any { it.italic && it.text == "it" })
        assertTrue(s.any { it.code && it.text == "code*x" })
        assertTrue(s.any { it.link == "https://x.y" && it.text == "link" })
        assertTrue(s.any { it.link == "https://a.b/c" })
        assertTrue(s.none { it.italic && it.text.contains("3") })
        assertTrue(s.none { it.italic && it.text.contains("case") })
        assertEquals("a bold and it and code*x link https://a.b/c. 2 * 3 * 4 snake_case_name", s.joinToString("") { it.text })
    }

    // ---- writing a reply out

    @Test fun aReplyIsWrittenOutBlockByBlockAndOlderTurnsAreLeftAlone() {
        val old = UiRow.Md("a0:0:0", MdBlock.Para("An older answer."), false)
        val rows = listOf(
            UiRow.User("u1", 1, "first", emptyList()), old,
            UiRow.User("u2", 2, "second", emptyList()),
            UiRow.Md("a1:0:0", MdBlock.Para("0123456789"), false),
            UiRow.Note("n1", "Saved to Files: a.pdf", false),
            UiRow.Md("a1:0:1", MdBlock.Items(listOf(Item(0, "•", "one"), Item(0, "•", "two"))), false),
            UiRow.Footer("f1", "x", "m", 1, true),
        )
        assertEquals(10 + WRITE_EDGE + 24 + 6 + WRITE_EDGE, turnWeight(rows))
        // Nothing written yet: the question is there, the reply is not.
        assertEquals(listOf("u1", "a0:0:0", "u2"), writeOut(rows, 0).map { it.key })
        // Part of the first paragraph: it is cut there and nothing follows it.
        val part = writeOut(rows, 4)
        assertEquals(listOf("u1", "a0:0:0", "u2", "a1:0:0"), part.map { it.key })
        assertEquals(4, (part.last() as UiRow.Md).upTo)
        assertTrue((part.last() as UiRow.Md).live)
        assertTrue(part[1] === old)
        // The paragraph and its closing beat are done; the note waits for its own short beat.
        assertEquals("a1:0:0", writeOut(rows, 10 + WRITE_EDGE).last().key)
        assertEquals(-1, (writeOut(rows, 10 + WRITE_EDGE + 5).last() as UiRow.Md).upTo)
        assertEquals("n1", writeOut(rows, 10 + WRITE_EDGE + 24).last().key)
        // The footer comes only when every word is out.
        assertEquals("a1:0:1", writeOut(rows, turnWeight(rows) - 1).last().key)
        assertTrue(writeOut(rows, turnWeight(rows)) === rows)
    }

    @Test fun theSandboxIsOfferedForWorkAndItsContainerIsReused() {
        val work = RunConfig(Providers.PRESETS.first(), "claude-opus-5-5", "high", 32000, "", agent = true, web = true, code = true, memory = true, fast = false, fallbacks = true, compaction = true)
        val (body, _) = RequestBuilder.build(work, listOf(user("make a pdf")), tools(), emptySet(), "cnt_1") { null }
        assertEquals("cnt_1", body.str("container"))
        assertTrue(body.toString().contains("code_execution"))
        assertTrue(RequestBuilder.system(work).contains("OUTPUT_DIR"))
        // It can say who made the app and which model is answering, because it is told both.
        assertTrue(RequestBuilder.system(work).contains("developed by Harsh Kumar, a senior software engineer"))
        assertTrue(RequestBuilder.system(work).contains("Claude Opus 5.5, made by Anthropic"))
        // A plain chat has no sandbox, so it is told nothing about one and sends no container.
        val (chat, _) = RequestBuilder.build(cfg(), listOf(user("hi")), tools(), emptySet(), "cnt_1") { null }
        assertEquals(null, chat["container"])
        assertFalse(RequestBuilder.system(cfg()).contains("OUTPUT_DIR"))
        // Work can hand a workspace file to the sandbox; a file it sent goes in with the result that reports it, until it expires.
        assertTrue(body.toString().contains(Tools.SANDBOX_PUT))
        assertFalse(chat.toString().contains(Tools.SANDBOX_PUT))
        val sent = buildJsonObject { put("role", "user"); put("content", parseArray("""[{"type":"tool_result","tool_use_id":"t1","content":"a.docx is now in the sandbox. [sandbox file file_abc123 until 2000]"}]""")) }
        val (fresh, _) = RequestBuilder.build(work, listOf(user("convert"), sent), tools(), emptySet(), null, 1000) { null }
        assertTrue(fresh.toString().contains("""{"type":"container_upload","file_id":"file_abc123"}"""))
        val (late, _) = RequestBuilder.build(work, listOf(user("convert"), sent), tools(), emptySet(), null, 3000) { null }
        assertFalse(late.toString().contains("container_upload"))
        val (plain, _) = RequestBuilder.build(cfg(), listOf(user("convert"), sent), tools(), emptySet(), null, 1000) { null }
        assertFalse(plain.toString().contains("container_upload"))
        // The container the reply ran in is read from the stream.
        val a = asm("""{"type":"message_start","message":{"id":"m1","model":"x","container":{"id":"cnt_9"},"usage":{"input_tokens":1}}}""", """{"type":"message_stop"}""")
        assertEquals("cnt_9", a.toMessage().container)
    }

    // ---- feedback

    @Test fun feedbackGoesToTheRelayAndNowhereElse() = kotlinx.coroutines.runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        var on = true
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            seen += ex.requestMethod + " " + ex.requestURI.path + " " + body
            val (code, text) = when {
                ex.requestURI.path == "/healthz" -> 200 to """{"ok":true,"rooms":0,"feedback":$on}"""
                body.contains("flood") -> 429 to """{"error":"too many"}"""
                body.contains("broken") -> 502 to """{"error":"not sent"}"""
                else -> 200 to """{"ok":true}"""
            }
            val out = text.toByteArray()
            ex.sendResponseHeaders(code, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val relay = "http://127.0.0.1:${server.address.port}"
            assertTrue(app.pocketagent.sync.Feedback.available(relay))
            on = false
            assertFalse(app.pocketagent.sync.Feedback.available(relay))
            assertEquals(app.pocketagent.sync.Feedback.Result.SENT, app.pocketagent.sync.Feedback.send("  It works well.  ", " sam@example.org ", "android 1.1.8", relay))
            // Exactly what was written, the address for a reply and the version; nothing else is in the request.
            assertEquals("""POST /v1/feedback {"message":"It works well.","email":"sam@example.org","app":"android 1.1.8"}""", seen.last())
            assertEquals(app.pocketagent.sync.Feedback.Result.SENT, app.pocketagent.sync.Feedback.send("No address", "", "desktop 1.1.8", relay))
            assertFalse(seen.last().contains("email"))
            assertEquals(app.pocketagent.sync.Feedback.Result.TOO_MANY, app.pocketagent.sync.Feedback.send("flood", "", "x", relay))
            assertEquals(app.pocketagent.sync.Feedback.Result.FAILED, app.pocketagent.sync.Feedback.send("broken", "", "x", relay))
            assertEquals(app.pocketagent.sync.Feedback.Result.FAILED, app.pocketagent.sync.Feedback.send("nobody home", "", "x", "http://127.0.0.1:1"))
            assertTrue(app.pocketagent.sync.Feedback.validEmail("a.b+c@mail.example.in"))
            assertFalse(app.pocketagent.sync.Feedback.validEmail("not an email"))
        } finally {
            server.stop(0)
        }
    }
}
