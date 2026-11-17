package app.pocketagent.data

import java.io.File

class ToolError(message: String) : Exception(message)

/**
 * The only folder the agent's file tools can touch. Every path is resolved to its real location
 * first and refused unless it is inside [root], so "../" tricks and absolute paths cannot escape.
 */
class Workspace(val root: File) {
    private val rootCanon: File by lazy { root.mkdirs(); root.canonicalFile }

    fun resolve(path: String): File {
        val p = path.trim().replace('\\', '/').trimStart('/').removePrefix("./")
        if (p.contains('\u0000')) throw ToolError("Invalid path.")
        val f = File(rootCanon, p).canonicalFile
        if (f != rootCanon && !f.path.startsWith(rootCanon.path + File.separator)) throw ToolError("That path is outside the workspace. Use a path relative to the workspace folder.")
        return f
    }

    fun relative(f: File): String = f.canonicalFile.path.removePrefix(rootCanon.path).trimStart(File.separatorChar)

    fun list(path: String = ""): String {
        val dir = resolve(path)
        if (!dir.exists()) throw ToolError("No such folder: ${path.ifBlank { "." }}")
        if (dir.isFile) return "${relative(dir)} (${dir.length()} bytes)"
        val out = StringBuilder()
        var count = 0
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() })) ?: return
            for (k in kids) {
                if (count >= MAX_LIST) return
                count++
                out.append("  ".repeat(depth)).append(k.name)
                if (k.isDirectory) { out.append("/\n"); if (depth < 4) walk(k, depth + 1) } else out.append(" (").append(k.length()).append(" bytes)\n")
            }
        }
        walk(dir, 0)
        if (count == 0) return "(empty)"
        if (count >= MAX_LIST) out.append("... more entries not shown\n")
        return out.toString().trimEnd()
    }

    /** Every line, in every text file under [path], that contains [query] (letter case ignored): "file:line: text", at most [MAX_HITS]. */
    fun search(query: String, path: String = ""): String {
        val q = query.trim()
        if (q.isEmpty()) throw ToolError("Give some text to look for.")
        val dir = resolve(path)
        if (!dir.exists()) throw ToolError("No such folder: ${path.ifBlank { "." }}")
        val hits = ArrayList<String>()
        var files = 0
        for (f in dir.walkTopDown().filter { it.isFile && it.length() <= MAX_SEARCHED }.take(MAX_LIST * 4)) {
            if (hits.size >= MAX_HITS) break
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (bytes.take(2048).any { it == 0.toByte() }) continue
            files++
            String(bytes, Charsets.UTF_8).lineSequence().forEachIndexed { i, line ->
                if (hits.size < MAX_HITS && line.contains(q, ignoreCase = true)) hits += "${relative(f)}:${i + 1}: ${line.trim().take(200)}"
            }
        }
        return if (hits.isEmpty()) "Nothing in $files files contains \"$q\"." else hits.joinToString("\n") + (if (hits.size >= MAX_HITS) "\n... more matches not shown" else "")
    }

    /** Text of a file with line numbers. [offset] is the first line (1-based), [limit] the number of lines. */
    fun read(path: String, offset: Int? = null, limit: Int? = null): String {
        val f = resolve(path)
        if (!f.isFile) throw ToolError("No such file: $path")
        if (f.length() > MAX_READ_BYTES) throw ToolError("File is too large to read here (${f.length()} bytes).")
        val bytes = f.readBytes()
        if (bytes.take(4096).any { it == 0.toByte() }) throw ToolError("This looks like a binary file, not text.")
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        val from = ((offset ?: 1) - 1).coerceIn(0, lines.size)
        val to = (from + (limit ?: MAX_LINES)).coerceAtMost(lines.size)
        val out = StringBuilder()
        var chars = 0
        var i = from
        while (i < to) {
            val l = lines[i]
            chars += l.length + 8
            if (chars > MAX_READ_CHARS) break
            out.append(i + 1).append('\t').append(l).append('\n')
            i++
        }
        if (i < lines.size) out.append("[lines ${i + 1}-${lines.size} not shown; read again with offset=${i + 1}]")
        return out.toString().ifEmpty { "(empty file)" }
    }

    fun write(path: String, content: String): String {
        val f = resolve(path)
        if (f == rootCanon || f.isDirectory) throw ToolError("That path is a folder.")
        if (content.length > MAX_WRITE_CHARS) throw ToolError("Content is too large for one file (${content.length} characters).")
        val existed = f.exists()
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp~")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(content, Charsets.UTF_8); tmp.delete() }
        return (if (existed) "Updated " else "Created ") + relative(f) + " (${content.length} characters)"
    }

    fun edit(path: String, old: String, new: String, all: Boolean): String {
        val f = resolve(path)
        if (!f.isFile) throw ToolError("No such file: $path")
        if (old.isEmpty()) throw ToolError("old_text must not be empty.")
        val text = f.readText(Charsets.UTF_8)
        var hits = 0
        var at = text.indexOf(old)
        while (at >= 0) { hits++; at = text.indexOf(old, at + old.length) }
        if (hits == 0) throw ToolError("old_text was not found in ${relative(f)}. Read the file and copy the text exactly.")
        if (hits > 1 && !all) throw ToolError("old_text appears $hits times. Add more surrounding text to make it unique, or set replace_all.")
        write(path, if (all) text.replace(old, new) else text.replaceFirst(old, new))
        return "Edited ${relative(f)} ($hits replacement${if (hits == 1) "" else "s"})"
    }

    fun delete(path: String): String {
        val f = resolve(path)
        if (f == rootCanon) throw ToolError("The workspace folder itself cannot be deleted.")
        if (!f.exists()) throw ToolError("No such file: $path")
        val rel = relative(f)
        if (!f.deleteRecursively()) throw ToolError("Could not delete $rel")
        return "Deleted $rel"
    }

    fun rename(from: String, to: String): String {
        val a = resolve(from)
        val b = resolve(to)
        if (!a.exists()) throw ToolError("No such file: $from")
        if (b.exists()) throw ToolError("Something already exists at $to")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) throw ToolError("Could not rename $from")
        return "Renamed ${relative(a)} to ${relative(b)}"
    }

    companion object {
        const val MAX_HITS = 60
        const val MAX_SEARCHED = 1024L * 1024
        const val MAX_LIST = 400
        const val MAX_LINES = 2000
        const val MAX_READ_BYTES = 4_000_000L
        const val MAX_READ_CHARS = 120_000
        const val MAX_WRITE_CHARS = 2_000_000
    }
}

/**
 * Message content normally lives in the database row. Very large content (long web results, big tool
 * output) goes to a file instead, because Android cannot read a database row larger than about 2 MB.
 */
class Blobs(private val dir: File) {
    fun pack(chatId: Long, json: String): String {
        if (json.length < LIMIT) return json
        dir.mkdirs()
        val name = "${chatId}_${java.util.UUID.randomUUID()}.json"
        File(dir, name).writeText(json)
        return PREFIX + name
    }

    fun unpack(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val f = File(dir, File(stored.removePrefix(PREFIX)).name)
        return if (f.isFile) f.readText() else "[]"
    }

    fun deleteFor(chatId: Long) {
        dir.listFiles { f -> f.name.startsWith("${chatId}_") }?.forEach { it.delete() }
    }

    fun deleteAll() { dir.deleteRecursively() }

    companion object {
        const val PREFIX = "@file:"
        const val LIMIT = 300_000
    }
}
