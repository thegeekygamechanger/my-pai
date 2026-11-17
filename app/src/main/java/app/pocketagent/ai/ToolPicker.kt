package app.pocketagent.ai

/** What the person last asked, and the tools this conversation has already used: what the picker goes by. */
class ToolFocus(val text: String, val used: Set<String>)

/**
 * Chooses which tools of outside servers (MCP servers, a linked computer) go to the model with a request.
 * A few servers can bring hundreds of tools; sending them all costs tokens on every message and makes the model
 * worse at choosing. So only the few that fit are sent: first the servers the person named with "@mcp", then tools
 * this conversation already used (so its history stays valid), then the ones whose name or description shares
 * words with what was asked.
 */
object ToolPicker {
    /** The most tools from outside servers sent with one request. */
    const val MAX_OUTSIDE_TOOLS = 12

    private val MENTION = Regex("^@mcp[ \\t]+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val WORD = Regex("[a-z0-9]{3,}")
    private val COMMON = setOf(
        "the", "and", "for", "with", "that", "this", "from", "have", "has", "are", "was", "you", "your", "can", "could", "would", "should", "please", "what", "when", "where",
        "which", "who", "how", "why", "does", "about", "into", "them", "then", "than", "there", "their", "all", "any", "get", "use", "using", "tool", "tools", "mcp", "server", "want", "need", "make",
    )

    fun words(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value.removeSuffix("s") }.filter { it.length >= 3 && it !in COMMON }.toSet()

    /**
     * @param server the name of the server a tool comes from
     * @return at most [cap] of [all], in their original order (so the request stays the same from one message to the next where it can)
     */
    fun pick(all: List<ToolSpec>, focus: ToolFocus?, server: (String) -> String?, cap: Int = MAX_OUTSIDE_TOOLS): List<ToolSpec> {
        if (all.size <= cap) return all
        val text = focus?.text.orEmpty()
        val chosen = MENTION.findAll(text).flatMap { it.groupValues[1].split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val asked = words(MENTION.replace(text, " "))
        val scored = all.mapIndexed { i, t ->
            val from = server(t.name).orEmpty()
            var score = 0
            if (focus != null && t.name in focus.used) score += 10_000
            if (from.lowercase() in chosen) score += 1_000
            if (asked.isNotEmpty()) {
                val name = words(t.name.replace('_', ' ').replace('-', ' '))
                score += 6 * asked.count { it in name } + 3 * asked.count { it in words(from) } + asked.count { it in words(t.description) }
            }
            Triple(i, t, score)
        }
        val keep = scored.sortedWith(compareByDescending<Triple<Int, ToolSpec, Int>> { it.third }.thenBy { it.first }).take(cap).map { it.first }.toSet()
        return scored.filter { it.first in keep }.map { it.second }
    }
}
