package eu.kanade.tachiyomi.extension.pt.lycantoons

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Port of the reader's page decryption: /api/reader-key hands out masked AES-GCM keys and
// /api/chapters/{id}/view-pages may answer with { enc, kid } instead of { pages }.
object ReaderCipher {

    private const val MASK_SEED = "k2wo8JGra4M8b-BV"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun unmaskKey(masked: String): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(masked.trimEnd('='))
        val stream = keystream(seed(MASK_SEED), bytes.size)

        return ByteArray(bytes.size) { (bytes[bytes.size - 1 - it].toInt() xor stream[it].toInt()).toByte() }
    }

    fun decrypt(enc: String, key: ByteArray): String {
        val data = Base64.getDecoder().decode(enc)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH))
        }

        return cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    private fun seed(text: String): Int {
        var h = 0x6a09e667 xor text.length
        for (char in text) {
            h = ((h xor char.code) * 0xcc9e2d51.toInt()).rotateLeft(13)
        }
        h = (h xor (h ushr 16)) * 0x85ebca6b.toInt()
        h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()

        return h xor (h ushr 16)
    }

    private fun keystream(seed: Int, length: Int): ByteArray {
        var state = seed

        return ByteArray(length) {
            state += 0x6d2b79f5
            var t = (state xor (state ushr 15)) * (state or 1)
            t = (t + (t xor (t ushr 7)) * (t or 61)) xor t
            (t xor (t ushr 14)).toByte()
        }
    }
}
