package app.pocketagent.sync

import app.pocketagent.ai.guarded
import app.pocketagent.ai.parseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * A pairing code is the whole secret. From it each device works out, on its own, which room on the relay
 * to meet in and the key that encrypts everything said there. The relay only ever learns the room name,
 * which reveals nothing about the code, and sees only encrypted bytes.
 */
object SyncCode {
    /** No 0/O, 1/I/L: the code may be read aloud or typed from a screen. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 25
    const val SYNC = "sync"
    const val SHARE = "share"
    private val random = SecureRandom()

    fun new(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** "ABCDE-FGHJK-…" for showing to a person. */
    fun pretty(code: String): String = code.chunked(5).joinToString("-")

    fun qr(code: String, purpose: String): String = "mypai-$purpose:$code"

    /** Accepts what a person types or what a scanned QR code holds; null if it is not a code. */
    fun parse(input: String): Pair<String, String?>? {
        val text = input.trim()
        val purpose = when {
            text.startsWith("mypai-sync:", true) -> SYNC
            text.startsWith("mypai-share:", true) -> SHARE
            else -> null
        }
        val code = text.substringAfter(':', text).uppercase().filter { it in ALPHABET }
        return if (code.length == LENGTH) code to purpose else null
    }

    private fun sha(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    fun room(code: String, purpose: String): String = sha("pocketagent/$purpose/room/$code").joinToString("") { "%02x".format(it) }
    fun key(code: String, purpose: String): ByteArray = sha("pocketagent/$purpose/key/$code")
}

/** Compresses and encrypts messages with AES-256-GCM. The room name is bound in, so a message cannot be replayed into another room. */
class Sealer(key: ByteArray, private val room: String) {
    private val secret = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun seal(plain: ByteArray): ByteArray {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(room.toByteArray())
        return nonce + cipher.doFinal(zipped)
    }

    /** Null when the bytes were not made with this key (or were damaged): such messages are simply ignored. */
    fun open(sealed: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(room.toByteArray())
        val zipped = cipher.doFinal(sealed, 12, sealed.size - 12)
        GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
}

/**
 * One device's connection to a room on the relay. Messages go up as small HTTPS posts and come down on one
 * long-lived stream. The relay passes them to the other devices in the room and keeps nothing.
 */
class RelayLink(private val baseUrl: () -> String, val room: String, val deviceId: String, private val sealer: Sealer) {
    private val outbox = Channel<Pair<ByteArray, Boolean>>(4000)
    private val parts = HashMap<String, Array<ByteArray?>>()
    private val random = SecureRandom()

    /** Queues a message. [droppable] ones (live progress) are skipped when the queue is backed up. */
    fun send(msg: JsonObject, droppable: Boolean = false) {
        val sealed = sealer.seal(msg.toString().toByteArray())
        if (sealed.size <= FRAME) { offer(byteArrayOf(0) + sealed, droppable); return }
        // Too big for one post: cut into numbered pieces the receiver puts back together.
        val id = ByteArray(8).also(random::nextBytes)
        val total = (sealed.size + FRAME - 1) / FRAME
        for (i in 0 until total) {
            val header = ByteBuffer.allocate(1 + 8 + 4 + 4).put(1).put(id).putInt(i).putInt(total).array()
            offer(header + sealed.copyOfRange(i * FRAME, minOf(sealed.size, (i + 1) * FRAME)), false)
        }
    }

    private fun offer(frame: ByteArray, droppable: Boolean) {
        val result = outbox.trySend(frame to droppable)
        if (result.isFailure && !droppable) outbox.trySend(frame to false)
    }

    private fun decode(frame: ByteArray): JsonObject? {
        if (frame.isEmpty()) return null
        val sealed = when (frame[0].toInt()) {
            0 -> frame.copyOfRange(1, frame.size)
            1 -> {
                if (frame.size < 17) return null
                val buf = ByteBuffer.wrap(frame, 1, 16)
                val id = ByteArray(8).also { buf.get(it) }.joinToString("") { "%02x".format(it) }
                val i = buf.int
                val total = buf.int
                if (total !in 1..MAX_PARTS || i !in 0 until total) return null
                if (parts.size > 8 && !parts.containsKey(id)) parts.clear()
                val slots = parts.getOrPut(id) { arrayOfNulls(total) }
                if (slots.size != total) return null
                slots[i] = frame.copyOfRange(17, frame.size)
                if (slots.any { it == null }) return null
                parts.remove(id)
                ByteArrayOutputStream().also { out -> slots.forEach { out.write(it!!) } }.toByteArray()
            }
            else -> return null
        }
        return sealer.open(sealed)?.let { parseObject(String(it)) }
    }

    /**
     * Stays connected until cancelled, reconnecting by itself.
     * @param onOpen each time the stream (re)opens
     * @param onPeers the ids of the other devices in the room, whenever that changes
     */
    suspend fun run(onOnline: (Boolean) -> Unit, onOpen: suspend () -> Unit, onPeers: suspend (List<String>) -> Unit, onMessage: suspend (JsonObject) -> Unit) = coroutineScope {
        launch(Dispatchers.IO) { pump() }
        var wait = 1000L
        while (true) {
            try {
                listen(onOnline, onOpen, onPeers, onMessage)
                wait = 1000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoomFull) {
                onOnline(false)
                throw e
            } catch (e: Exception) {
                // A dropped or refused connection: wait a little and try again.
            }
            onOnline(false)
            delay(wait)
            wait = minOf(wait * 2, 20_000L)
        }
    }

    private suspend fun listen(onOnline: (Boolean) -> Unit, onOpen: suspend () -> Unit, onPeers: suspend (List<String>) -> Unit, onMessage: suspend (JsonObject) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl().trimEnd('/')}/v1/r/$room/s?d=$deviceId").header("accept", "text/event-stream").get().build()
        val call = http.newCall(req)
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use { resp ->
                if (resp.code == 409) throw RoomFull()
                if (!resp.isSuccessful) throw IOException("relay ${resp.code}")
                onOnline(true)
                onOpen()
                val source = resp.body.source()
                var event = ""
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            val data = line.substring(5).trim()
                            when (event) {
                                "p" -> parseObject(data)?.let { o ->
                                    val ids = (o["ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                                    onPeers(ids.filter { it != deviceId })
                                }
                                "m" -> runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { batch ->
                                    // One post can carry several messages, each with its length in front.
                                    val buf = ByteBuffer.wrap(batch)
                                    while (buf.remaining() >= 4) {
                                        val len = buf.int
                                        if (len <= 0 || len > buf.remaining()) break
                                        val frame = ByteArray(len).also { buf.get(it) }
                                        decode(frame)?.let { onMessage(it) }
                                    }
                                }
                            }
                        }
                        line.isEmpty() -> event = ""
                    }
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            closer.cancel()
        }
    }

    /**
     * Sends what is queued. Every post costs a round trip to the relay, so whatever is waiting is packed
     * into one post (up to a size limit) instead of going one message at a time.
     */
    private suspend fun pump() {
        var carry: Pair<ByteArray, Boolean>? = null
        while (true) {
            val first = carry ?: outbox.receive()
            carry = null
            if (first.second && !outbox.isEmpty) continue
            val batch = ByteArrayOutputStream()
            var droppable = first.second
            fun add(frame: ByteArray) { batch.write(ByteBuffer.allocate(4).putInt(frame.size).array()); batch.write(frame) }
            add(first.first)
            while (true) {
                val next = outbox.tryReceive().getOrNull() ?: break
                if (next.second && !outbox.isEmpty) continue
                if (batch.size() + next.first.size + 4 > MAX_POST) { carry = next; break }
                add(next.first)
                droppable = droppable && next.second
            }
            val body = batch.toByteArray()
            var tries = 0
            while (true) {
                val ok = try {
                    val req = Request.Builder().url("${baseUrl().trimEnd('/')}/v1/r/$room/p?d=$deviceId").post(body.toRequestBody(BYTES)).build()
                    http.newCall(req).execute().use { it.isSuccessful || it.code == 413 }
                } catch (e: IOException) {
                    false
                }
                coroutineContext.ensureActive()
                if (ok || droppable || ++tries >= 4) break
                delay(700L * tries)
            }
        }
    }

    class RoomFull : Exception("room full")

    companion object {
        /** Bytes per post; the relay refuses anything much larger. */
        const val FRAME = 180_000
        /** The most one post carries; the relay refuses bodies over 256 KB. */
        const val MAX_POST = 250_000
        const val MAX_PARTS = 2000
        private val BYTES = "application/octet-stream".toMediaType()
        private val http = OkHttpClient.Builder().guarded()
        // Never carried from an https address to a plain http one, key and all.
        .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            // The relay sends a heartbeat every 20 seconds; silence for longer than this means the line is dead.
            .readTimeout(55, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
