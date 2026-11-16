package app.pocketagent.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The four-character code for sharing one chat. Four characters are easy to pass on but far too few to be
 * an encryption key, so the code only does two things: it leads both devices to the same room, and it
 * wraps their first exchange. The chat itself travels under a key the two devices agree on afresh for
 * each hand-over ([ShareSession]), which cannot be worked out from the code afterwards.
 */
object ShareCode {
    const val LENGTH = 4
    /** No 0/O, 1/I/L: the code may be read aloud or typed from a screen. */
    private const val LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val ALPHABET = LETTERS + DIGITS
    private const val PREFIX = "mypai-share:"
    private val random = SecureRandom()
    private val TAGGED = Regex("mypai-share:\\s*([A-Za-z0-9]{4})(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    /** A new code. It always has a letter and a digit, so an ordinary four-letter word is never taken for one. */
    fun new(): String {
        while (true) {
            val code = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            if (valid(code)) return code
        }
    }

    private fun valid(code: String) = code.length == LENGTH && code.all { it in ALPHABET } && code.any { it in LETTERS } && code.any { it in DIGITS }

    /** What is copied for the other person: the code with a label the app recognises anywhere in a message. */
    fun text(code: String): String = PREFIX + code

    /** The labelled code inside [text], wherever it is; null if there is none. */
    fun find(text: String): MatchResult? = TAGGED.find(text)?.takeIf { valid(it.groupValues[1].uppercase()) }

    /** Accepts the labelled form or just the four characters; null if it is not a share code. */
    fun parse(input: String): String? {
        find(input)?.let { return it.groupValues[1].uppercase() }
        return input.trim().uppercase().takeIf(::valid)
    }

    class Keys(val room: String, val key: ByteArray)

    /**
     * The room and the wrapping key for a code. Deliberately slow, so that trying every possible code
     * takes real effort; run it off the main thread.
     */
    fun derive(code: String): Keys {
        val spec = PBEKeySpec(code.toCharArray(), "pocketagent/share/v2".toByteArray(), 200_000, 512)
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Keys(out.copyOfRange(0, 32).joinToString("") { "%02x".format(it) }, out.copyOfRange(32, 64))
    }
}

/** The fresh key for one hand-over of a shared chat: an elliptic-curve exchange, tied to the code and to this request. */
object ShareSession {
    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /**
     * @param theirs the other device's public key as it arrived
     * @return the key for this hand-over, or null if what arrived is not a usable public key
     */
    fun key(mine: KeyPair, theirs: ByteArray, wrap: ByteArray, guestPublic: ByteArray, hostPublic: ByteArray, rid: String): ByteArray? = runCatching {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(mine.private as PrivateKey); doPhase(other, true) }.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf("pocketagent/share/session".toByteArray(), wrap, shared, guestPublic, hostPublic, rid.toByteArray())) {
            digest.update(byteArrayOf((part.size ushr 8).toByte(), part.size.toByte()))
            digest.update(part)
        }
        digest.digest()
    }.getOrNull()
}
