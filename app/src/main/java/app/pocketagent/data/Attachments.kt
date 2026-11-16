package app.pocketagent.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

/** A file the user picked, turned into the content block that goes in the message. */
class Picked(val block: JsonObject, val name: String, val kind: String, val file: File?)

class AttachmentError(message: String) : Exception(message)

/**
 * Prepares attachments. Images are scaled down and saved as JPEG, PDFs are copied, text files are read.
 * The copies live in the app's private storage, so a chat still works after the original is moved or deleted.
 * Decoding and scaling a picture is the only part that differs per system, so it is passed in.
 */
class Attachments(val dir: File) {

    /**
     * @param isImage whether the system says this is a picture
     * @param read the file's bytes
     * @param toJpeg scales a picture so its long edge is at most [MAX_EDGE] and returns it as JPEG, or null if it cannot be read
     */
    fun import(name: String, isImage: Boolean, size: Long, read: () -> ByteArray?, toJpeg: () -> ByteArray?): Picked {
        dir.mkdirs()
        return when {
            isImage -> {
                val jpeg = toJpeg() ?: throw AttachmentError("Could not open $name.")
                val f = File(dir, UUID.randomUUID().toString() + ".jpg")
                f.writeBytes(jpeg)
                Picked(buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") { put("type", "local"); put("path", "att/${f.name}"); put("media_type", "image/jpeg") }
                }, name, "image", f)
            }
            name.endsWith(".pdf", true) -> {
                if (size > MAX_PDF) throw AttachmentError("$name is too large (the limit is 20 MB).")
                val bytes = read() ?: throw AttachmentError("Could not read $name.")
                if (bytes.size > MAX_PDF) throw AttachmentError("$name is too large (the limit is 20 MB).")
                val f = File(dir, UUID.randomUUID().toString() + ".pdf")
                f.writeBytes(bytes)
                Picked(buildJsonObject {
                    put("type", "document")
                    putJsonObject("source") { put("type", "local"); put("path", "att/${f.name}"); put("media_type", "application/pdf") }
                    put("title", name)
                }, name, "document", f)
            }
            else -> {
                if (size > MAX_TEXT) throw AttachmentError("$name is too large to attach as text (the limit is 400 KB).")
                val bytes = read() ?: throw AttachmentError("Could not read $name.")
                if (bytes.size > MAX_TEXT) throw AttachmentError("$name is too large to attach as text (the limit is 400 KB).")
                if (bytes.take(4096).any { it == 0.toByte() }) throw AttachmentError("$name is not a text file, an image or a PDF.")
                Picked(buildJsonObject {
                    put("type", "document")
                    putJsonObject("source") { put("type", "text"); put("media_type", "text/plain"); put("data", String(bytes, Charsets.UTF_8)) }
                    put("title", name)
                }, name, "document", null)
            }
        }
    }

    fun file(path: String): File = File(dir, File(path).name)

    companion object {
        const val MAX_EDGE = 1568
        const val MAX_PDF = 20 * 1024 * 1024
        const val MAX_TEXT = 400 * 1024
    }
}
