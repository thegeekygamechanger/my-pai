package app.pocketagent.desktop

import app.pocketagent.ai.parseObject
import app.pocketagent.ai.str
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Voice typing on a computer with no key and no service: the Vosk recogniser runs on the machine itself.
 * Each language needs a pack of about 40 MB, fetched once from the Vosk project's site and kept in the app's
 * data folder. What is said never leaves the computer.
 *
 * A Groq or OpenAI key, when there is one, is still preferred: it is more accurate and knows every language.
 */
object OfflineSpeech {
    class Pack(val model: String, val megabytes: Int, val name: String)

    private val INDIAN_ENGLISH = Pack("vosk-model-small-en-in-0.4", 36, "English (India)")
    private val PACKS = mapOf(
        "en" to Pack("vosk-model-small-en-us-0.15", 40, "English"),
        "hi" to Pack("vosk-model-small-hi-0.22", 42, "Hindi"),
        "te" to Pack("vosk-model-small-te-0.42", 58, "Telugu"),
        "gu" to Pack("vosk-model-small-gu-0.42", 100, "Gujarati"),
        "es" to Pack("vosk-model-small-es-0.42", 39, "Spanish"),
        "fr" to Pack("vosk-model-small-fr-0.22", 41, "French"),
        "de" to Pack("vosk-model-small-de-0.15", 45, "German"),
        "pt" to Pack("vosk-model-small-pt-0.3", 31, "Portuguese"),
        "ru" to Pack("vosk-model-small-ru-0.22", 45, "Russian"),
        "ja" to Pack("vosk-model-small-ja-0.22", 48, "Japanese"),
        "ko" to Pack("vosk-model-small-ko-0.22", 82, "Korean"),
        "zh" to Pack("vosk-model-small-cn-0.22", 42, "Chinese"),
    )

    /** The pack for a language tag such as "hi-IN"; "Automatic" means Indian English. Null when there is none for that language. */
    fun packFor(lang: String): Pack? = if (lang.isBlank() || lang.equals("en-IN", true)) INDIAN_ENGLISH else PACKS[lang.substringBefore('-').lowercase()]

    private fun folder(root: File, pack: Pack) = File(root, "speech/" + pack.model)

    fun ready(root: File, pack: Pack): Boolean = File(folder(root, pack), ".ready").isFile

    /** Fetches and unpacks a language pack. Throws IOException when it cannot be fetched. */
    fun download(root: File, pack: Pack, url: String = "https://alphacephei.com/vosk/models/${pack.model}.zip") {
        val target = folder(root, pack)
        val work = File(target.parentFile.apply { mkdirs() }, pack.model + ".part")
        work.deleteRecursively()
        work.mkdirs()
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("The pack could not be fetched (error ${resp.code}).")
                ZipInputStream(resp.body.byteStream()).use { zip ->
                    val base = work.canonicalFile
                    var total = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // The archive holds one folder named after the pack; its contents go straight into ours.
                        val inner = entry.name.replace('\\', '/').substringAfter('/', "")
                        if (inner.isEmpty()) continue
                        val out = File(base, inner).canonicalFile
                        // Nothing in the archive may land outside the pack's own folder.
                        if (!out.path.startsWith(base.path + File.separator)) throw IOException("The pack is not safe to unpack.")
                        if (entry.isDirectory) { out.mkdirs(); continue }
                        out.parentFile.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_UNPACKED) throw IOException("The pack is larger than expected.")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            if (!File(work, "am").isDirectory && !File(work, "conf").isDirectory) throw IOException("The pack is incomplete.")
            target.deleteRecursively()
            if (!work.renameTo(target)) throw IOException("The pack could not be saved.")
            File(target, ".ready").writeText("ok")
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * @param pcm 16-bit mono samples at 16 kHz
     * @return what was said, in the pack's language; empty if nothing was recognised
     */
    fun transcribe(root: File, pack: Pack, pcm: ByteArray): String {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val parts = ArrayList<String>()
        // The pack is loaded for this one recording and released again, so it does not hold memory while idle.
        Model(folder(root, pack).path).use { model ->
            Recognizer(model, 16000f).use { rec ->
                var at = 0
                while (at < pcm.size) {
                    val n = minOf(8000, pcm.size - at)
                    // True means a pause ended a sentence; its words must be collected now or they are lost.
                    if (rec.acceptWaveForm(pcm.copyOfRange(at, at + n), n)) parseObject(rec.result)?.str("text")?.let { parts += it }
                    at += n
                }
                parseObject(rec.finalResult)?.str("text")?.let { parts += it }
            }
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    private const val MAX_UNPACKED = 600L * 1024 * 1024
}

/**
 * Checks offline voice typing from a terminal: SpeechCheck <data dir> <language tag> <16 kHz mono 16-bit WAV>.
 * It fetches the pack if needed and prints what it heard.
 */
object SpeechCheck {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]).apply { mkdirs() }
        val pack = OfflineSpeech.packFor(args.getOrElse(1) { "" }) ?: run { println("NO PACK"); return }
        if (!OfflineSpeech.ready(root, pack)) { println("FETCHING ${pack.model}"); OfflineSpeech.download(root, pack) }
        val wav = File(args[2]).readBytes()
        println("HEARD: " + OfflineSpeech.transcribe(root, pack, wav.copyOfRange(44.coerceAtMost(wav.size), wav.size)))
    }
}
