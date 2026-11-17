package app.pocketagent.data

import app.pocketagent.ai.AppJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What a file was before the assistant changed it, kept for a while so the change can be taken back.
 * One entry per tool call, the newest [KEEP] of them, each a file of at most [MAX_BYTES].
 */
class UndoStore(private val dir: File) {
    @Serializable
    private class Entry(val path: String, val existed: Boolean, val what: String, val at: Long, val done: Boolean = false)

    private fun meta(id: String) = File(dir, safe(id) + ".json")
    private fun data(id: String) = File(dir, safe(id) + ".bin")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifEmpty { "x" }

    /** Remembers [file] as it is now, before [what] is done to it. False when it cannot be kept (a folder, or too large). */
    @Synchronized
    fun remember(id: String, file: File, what: String): Boolean = runCatching {
        if (id.isBlank() || file.isDirectory || (file.isFile && file.length() > MAX_BYTES)) return false
        dir.mkdirs()
        if (file.isFile) file.copyTo(data(id), overwrite = true) else data(id).delete()
        meta(id).writeText(AppJson.encodeToString(Entry.serializer(), Entry(file.absolutePath, file.isFile, what, System.currentTimeMillis())))
        dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { old ->
            old.delete(); File(dir, old.name.removeSuffix(".json") + ".bin").delete()
        }
        true
    }.getOrDefault(false)

    private fun entry(id: String): Entry? = meta(id).takeIf { it.isFile }?.let { runCatching { AppJson.decodeFromString(Entry.serializer(), it.readText()) }.getOrNull() }

    /** True while the change made by that call can still be taken back. */
    fun can(id: String): Boolean = id.isNotBlank() && entry(id)?.done == false

    /** Forgets a call whose change did not happen after all. */
    @Synchronized
    fun forget(id: String) { meta(id).delete(); data(id).delete() }

    /** Puts the file back as it was. Returns what was undone, or null when there is nothing to undo. */
    @Synchronized
    fun undo(id: String): String? = runCatching {
        val e = entry(id)?.takeIf { !it.done } ?: return null
        val target = File(e.path)
        if (e.existed) { target.parentFile?.mkdirs(); data(id).copyTo(target, overwrite = true) } else target.delete()
        meta(id).writeText(AppJson.encodeToString(Entry.serializer(), Entry(e.path, e.existed, e.what, e.at, done = true)))
        data(id).delete()
        e.what
    }.getOrNull()

    companion object {
        const val KEEP = 40
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
