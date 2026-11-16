package app.pocketagent.ui

/** The pieces a reply is drawn from. Kept free of Android so the parser runs in plain unit tests. */
sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Items(val items: List<Item>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** [marker] is "•" for bullets, "1." style for numbers; [checked] is set for task-list items. */
data class Item(val indent: Int, val marker: String, val text: String, val checked: Boolean? = null)

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBER = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")

    /**
     * A forgiving, line-based reader for the Markdown models actually write. It is called again and again
     * on a reply that is still arriving, so anything unfinished (an open code fence, half a table) still
     * produces something sensible.
     */
    fun parse(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val lines = text.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
            para.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val lang = trimmed.drop(3).trim().substringBefore(' ').take(24)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                    out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                trimmed.isEmpty() -> flush()
                RULE.matches(line) -> { flush(); out += MdBlock.Rule }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").removePrefix(" ")).append('\n'); i++ }
                    out += MdBlock.Quote(q.toString().trim())
                    continue
                }
                trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    flush()
                    val header = cells(trimmed)
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i].trim()); i++ }
                    out += MdBlock.Table(header, rows)
                    continue
                }
                BULLET.matches(line) || NUMBER.matches(line) -> {
                    flush()
                    val items = ArrayList<Item>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val b = BULLET.find(l)
                        val n = if (b == null) NUMBER.find(l) else null
                        when {
                            b != null -> {
                                val body = b.groupValues[2]
                                val task = TASK.find(body)
                                items += if (task != null) Item(b.groupValues[1].length / 2, "•", task.groupValues[2], task.groupValues[1] != " ")
                                else Item(b.groupValues[1].length / 2, "•", body)
                            }
                            n != null -> items += Item(n.groupValues[1].length / 2, n.groupValues[2] + ".", n.groupValues[3])
                            // An indented line continues the item above it.
                            l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty() && !l.trim().startsWith("```") -> {
                                val last = items.removeAt(items.size - 1)
                                items += last.copy(text = last.text + "\n" + l.trim())
                            }
                            // A single blank line between items of the same kind does not end the list.
                            l.isBlank() && i + 1 < lines.size && items.isNotEmpty() &&
                                (if (items.last().marker == "•") BULLET.matches(lines[i + 1]) else NUMBER.matches(lines[i + 1])) -> {}
                            else -> break
                        }
                        i++
                    }
                    out += MdBlock.Items(items)
                    continue
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line.trimEnd())
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** The reply as plain text, for the chat list preview and notifications. */
    fun plain(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " [code] ")
        .replace(Regex("(?m)^\\s*([-+*]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** One run of inline text with the same styling. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object Inline {
    private val LINK = Regex("^\\[([^\\]\\n]+)]\\((https?://[^)\\s]+|mailto:[^)\\s]+)\\)")
    private val URL = Regex("^https?://[^\\s<>\\])}\"']+")

    fun parse(s: String): List<Span> {
        val out = ArrayList<Span>()
        walk(s, Span(""), out)
        return merge(out)
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (sp in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == sp.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + sp.text)
            else if (sp.text.isNotEmpty()) out += sp
        }
        return out
    }

    private fun walk(s: String, style: Span, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += style.copy(text = plain.toString()); plain.setLength(0) } }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < n && s[i + 1] in "\\`*_{}[]()#+-.!~|>" -> { plain.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val ticks = s.substring(i).takeWhile { it == '`' }.length
                    val end = s.indexOf("`".repeat(ticks), i + ticks)
                    if (end < 0) { plain.append(c); i++ } else {
                        flush()
                        out += style.copy(text = s.substring(i + ticks, end), code = true)
                        i = end + ticks
                    }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = closing(s, mark, i + 2)
                    if (end < 0) { plain.append(mark); i += 2 } else {
                        flush(); walk(s.substring(i + 2, end), style.copy(bold = true), out); i = end + 2
                    }
                }
                s.startsWith("~~", i) -> {
                    val end = closing(s, "~~", i + 2)
                    if (end < 0) { plain.append("~~"); i += 2 } else {
                        flush(); walk(s.substring(i + 2, end), style.copy(strike = true), out); i = end + 2
                    }
                }
                (c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = closingSingle(s, c, i + 1)
                    if (end < 0) { plain.append(c); i++ } else {
                        flush(); walk(s.substring(i + 1, end), style.copy(italic = true), out); i = end + 1
                    }
                }
                c == '[' -> {
                    val m = LINK.find(s.substring(i))
                    if (m == null) { plain.append(c); i++ } else {
                        flush(); walk(m.groupValues[1], style.copy(link = m.groupValues[2]), out); i += m.value.length
                    }
                }
                c == 'h' && style.link == null && (s.startsWith("http://", i) || s.startsWith("https://", i)) -> {
                    val m = URL.find(s.substring(i))
                    if (m == null) { plain.append(c); i++ } else {
                        val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
                        flush(); out += style.copy(text = url, link = url); i += url.length
                    }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
    }

    private fun closing(s: String, mark: String, from: Int): Int {
        var i = s.indexOf(mark, from)
        while (i >= 0) {
            if (i > from && !s[i - 1].isWhitespace()) return i
            i = s.indexOf(mark, i + 1)
        }
        return -1
    }

    private fun closingSingle(s: String, c: Char, from: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\n' && i + 1 < s.length && s[i + 1] == '\n') return -1
            if (s[i] == c && !s[i - 1].isWhitespace() && (i + 1 >= s.length || s[i + 1] != c) && s[i - 1] != c) {
                if (c == '*' || i + 1 >= s.length || !s[i + 1].isLetterOrDigit()) return if (i > from) i else -1
            }
            i++
        }
        return -1
    }
}

/** A deliberately small highlighter: comments, strings, numbers and common keywords, for any language. */
object Highlight {
    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER }
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val KEYWORDS = setOf(
        "if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case", "default", "try", "catch", "finally", "throw", "throws",
        "class", "interface", "enum", "struct", "object", "extends", "implements", "import", "package", "from", "export", "as", "in", "is", "of",
        "fun", "func", "function", "def", "fn", "lambda", "val", "var", "let", "const", "static", "final", "public", "private", "protected", "internal",
        "new", "this", "self", "super", "null", "nil", "None", "true", "false", "True", "False", "void", "int", "long", "float", "double", "string",
        "bool", "boolean", "char", "async", "await", "yield", "with", "when", "where", "and", "or", "not", "elif", "except", "raise", "pass",
        "override", "abstract", "suspend", "data", "sealed", "typeof", "instanceof", "namespace", "using", "use", "mod", "pub", "impl", "trait",
        "match", "type", "select", "insert", "update", "delete", "create", "table", "echo", "then", "fi", "done", "esac", "local",
    )
    private val HASH_LANGS = setOf("py", "python", "sh", "bash", "shell", "zsh", "yaml", "yml", "rb", "ruby", "toml", "r", "perl", "dockerfile", "makefile", "ini", "conf")

    fun tokens(code: String, lang: String): List<Token> {
        if (code.length > 30_000) return emptyList()
        val hash = lang.lowercase() in HASH_LANGS
        val out = ArrayList<Token>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                code.startsWith("//", i) && !hash -> { val e = code.indexOf('\n', i).let { if (it < 0) n else it }; out += Token(i, e, Kind.COMMENT); i = e }
                c == '#' && hash -> { val e = code.indexOf('\n', i).let { if (it < 0) n else it }; out += Token(i, e, Kind.COMMENT); i = e }
                code.startsWith("/*", i) -> { val e = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }; out += Token(i, e, Kind.COMMENT); i = e }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && code[j] != c && (c == '`' || code[j] != '\n')) { if (code[j] == '\\') j++; j++ }
                    val e = minOf(j + 1, n)
                    out += Token(i, e, Kind.STRING); i = e
                }
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    out += Token(i, j, Kind.NUMBER); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    if (code.substring(i, j) in KEYWORDS) out += Token(i, j, Kind.KEYWORD)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }
}
