package app.pocketagent.ai

/**
 * "Auto": instead of one fixed model, each message goes to the model that suits it. A greeting or a quick
 * fact goes to the service's fast model, ordinary questions to its everyday model, and long, technical or
 * multi-step requests (and Work) to its strongest. The choice is made on the device from the words of the
 * message; nothing extra is sent anywhere to make it.
 */
object AutoModel {
    const val ID = "auto"
    const val NAME = "Auto"

    const val EASY = 0
    const val NORMAL = 1
    const val HARD = 2

    private val GREETING = Regex("^(hi+|hello+|hey+|yo|namaste|thanks?( you)?( so much| a lot)?|thank u|ty|ok(ay)?|k|yes|yep|no|nope|cool|nice|great|good (morning|afternoon|evening|night)|bye|goodbye|see you|got it|perfect|awesome|lol|haha+)[ !.,?]*$")
    private val QUICK = Regex("\\b(translate|define|definition of|meaning of|synonym|antonym|spell(ing)?|rephrase|reword|capital of|convert|how many|how much is|what time|what day|weather|joke|who is|who was|what is|what's|when is|when was|where is)\\b")
    private val DEMANDING = Regex(
        "\\b(analy[sz]e|analysis|debug|refactor|architect(ure)?|prove|proof|derive|optimi[sz]e|strategy|research|in depth|in detail|detailed|step by step|compare|evaluate|critique|review|design|algorithm|" +
            "business plan|legal|contract|diagnos\\w*|essay|report|thesis|trade-?offs?|root cause|implement|migrat\\w*|security|vulnerab\\w*|complex|comprehensive|thorough(ly)?|plan)\\b"
    )
    private val CODE = Regex("```|\\b(function|class|def|import|select .* from|exception|stack ?trace|null ?pointer|compile|regex|sql|api|json|kotlin|python|javascript|typescript|java|rust|golang|c\\+\\+)\\b")

    /** Words in a model's name that mark it as the service's quick one or its strongest one. */
    private val FAST_WORDS = listOf("haiku", "mini", "flash", "lite", "nano", "small", "instant", "8b", "tiny", "fast")
    private val STRONG_WORDS = listOf("opus", "pro", "large", "max", "ultra", "reasoner", "o3", "o1", "405b", "deep", "thinking")
    /** Models that are not for chatting at all, or that a person should only get by asking for them by name. */
    private val NOT_FOR_AUTO = listOf(
        "embed", "whisper", "tts", "audio", "speech", "transcri", "image", "imagen", "banana", "dall", "veo", "omni", "sora", "video", "guard", "moderation",
        "realtime", "rerank", "search", "computer-use", "fable", "mythos", "preview", "instruct", "codex",
    )

    fun isGreeting(text: String) = text.length < 40 && GREETING.matches(text.trim().lowercase())

    /** How demanding a message is. */
    fun level(text: String, agent: Boolean, attachments: Int): Int {
        val t = text.trim().lowercase()
        // Work means tools and several steps: never the quick model, and the strongest unless the request is small.
        if (agent) return if (t.length < 80 && !DEMANDING.containsMatchIn(t) && attachments == 0) NORMAL else HARD
        if (isGreeting(t)) return EASY
        var score = 0
        if (t.length > 1500) score += 2 else if (t.length > 400) score += 1
        if (CODE.containsMatchIn(t)) score += 1
        // One demanding word is a hint; two different ones settle it.
        score += minOf(2, DEMANDING.findAll(t).map { it.value }.distinct().count())
        if (attachments > 0) score += 1
        if (t.length < 200 && QUICK.containsMatchIn(t)) score -= 1
        return when {
            score >= 2 -> HARD
            score < 0 || (score == 0 && t.length < 60) -> EASY
            else -> NORMAL
        }
    }

    /** Which of the three kinds a model is, from its name. */
    fun tier(id: String): Int {
        val m = id.lowercase()
        return when {
            FAST_WORDS.any { it in m } -> EASY
            STRONG_WORDS.any { it in m } -> HARD
            else -> NORMAL
        }
    }

    /** The numbers in a model's name, so "5-5" ranks above "4-8" and above a dated older name. */
    private fun version(id: String): List<Long> = Regex("\\d+").findAll(id).map { it.value.toLongOrNull() ?: 0 }.toList()
    private val newestFirst = Comparator<ModelInfo> { a, b ->
        val x = version(a.id); val y = version(b.id)
        // A date stamp (eight digits) says nothing about which generation is newer; the leading numbers do.
        val xs = x.filter { it < 10_000 }; val ys = y.filter { it < 10_000 }
        for (i in 0 until maxOf(xs.size, ys.size)) { val c = (ys.getOrElse(i) { 0 }).compareTo(xs.getOrElse(i) { 0 }); if (c != 0) return@Comparator c }
        0
    }

    /**
     * @param models the service's models
     * @param previous the model that wrote the last reply in this chat, if any
     * @return the model to use, or null when the service has no model that can be chosen automatically
     */
    fun pick(models: List<ModelInfo>, text: String, agent: Boolean, attachments: Int, previous: String? = null): String? {
        // OpenRouter has its own router, which knows its hundreds of models far better than a name can tell.
        models.firstOrNull { it.id == "openrouter/auto" }?.let { return it.id }
        val usable = models.filter { m -> NOT_FOR_AUTO.none { it in m.id.lowercase() } }.ifEmpty { return models.firstOrNull()?.id }
        var want = level(text, agent, attachments)
        // A short follow-up ("and the second one?") leans on what came before, so it stays with a model at least as
        // strong as the one that has been answering. Only a plain greeting or thanks steps down.
        if (previous != null && !isGreeting(text) && text.trim().length < 200) want = maxOf(want, tier(previous))
        val claude = usable.filter { it.id.startsWith("claude-") }
        if (claude.isNotEmpty()) {
            val family = when (want) { EASY -> listOf("haiku", "sonnet", "opus"); NORMAL -> listOf("sonnet", "opus", "haiku"); else -> listOf("opus", "sonnet", "haiku") }
            for (f in family) claude.filter { f in it.id }.sortedWith(newestFirst).firstOrNull()?.let { return it.id }
        }
        // Elsewhere: the nearest kind the service has, keeping the order of its own list within a kind.
        val order = when (want) { EASY -> listOf(EASY, NORMAL, HARD); NORMAL -> listOf(NORMAL, HARD, EASY); else -> listOf(HARD, NORMAL, EASY) }
        for (kind in order) usable.firstOrNull { tier(it.id) == kind }?.let { return it.id }
        return usable.first().id
    }
}
