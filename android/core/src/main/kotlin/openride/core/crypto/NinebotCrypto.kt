package openride.core.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless Ninebot "encryption 1" primitives, implemented from the spec in docs/android-plan.md §4.
 * On-air frame = header(3) | encrypted body (LEN+4) | tail(6).
 */
object NinebotCrypto {
    val FW_DATA: ByteArray = hex("97CFB802844143DE56002B3B34780A5D")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun pad16(x: ByteArray): ByteArray = x.copyOf(16) // right-pads with 0x00 (and truncates beyond 16)

    /** `SHA1(pad16(k1) || pad16(k2))[0..16]` */
    fun deriveKey(k1: ByteArray, k2: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(pad16(k1) + pad16(k2)).copyOf(16)

    private fun aes(key: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(block)
    }

    private fun nonce(prefix: Int, counter: Int, bleData: ByteArray, last: Int): ByteArray {
        val a = ByteArray(16)
        a[0] = prefix.toByte()
        a[1] = (counter ushr 24).toByte()
        a[2] = (counter ushr 16).toByte()
        a[3] = (counter ushr 8).toByte()
        a[4] = counter.toByte()
        bleData.copyInto(a, 5, 0, 8)
        a[15] = last.toByte()
        return a
    }

    private fun checksum(body: ByteArray): Int = body.sumOf { it.toInt() and 0xFF }.inv() and 0xFFFF

    private fun xorKeystream(body: ByteArray, keystream: (Int) -> ByteArray): ByteArray {
        val out = ByteArray(body.size)
        var i = 0
        var block = 0
        while (i < body.size) {
            val ks = keystream(block)
            for (j in 0 until minOf(16, body.size - i)) out[i + j] = (body[i + j].toInt() xor ks[j].toInt()).toByte()
            i += 16
            block++
        }
        return out
    }

    private fun mac(key: ByteArray, header: ByteArray, plainBody: ByteArray, counter: Int, bleData: ByteArray): ByteArray {
        var x = aes(key, nonce(0x59, counter, bleData, plainBody.size))
        x = aes(key, xor16(x, header.copyOf(16)))
        var i = 0
        while (i < plainBody.size) {
            x = aes(key, xor16(x, plainBody.copyOfRange(i, minOf(i + 16, plainBody.size)).copyOf(16)))
            i += 16
        }
        val s = aes(key, nonce(0x01, counter, bleData, 0))
        return ByteArray(4) { (x[it].toInt() xor s[it].toInt()).toByte() }
    }

    private fun xor16(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    /** Seal a plaintext frame (`5A A5 LEN ...`) in weak mode (counter 0). */
    fun sealWeak(plain: ByteArray, key: ByteArray): ByteArray {
        val body = plain.copyOfRange(3, plain.size)
        val ks = aes(key, FW_DATA)
        val enc = xorKeystream(body) { ks }
        val crc = checksum(body)
        return plain.copyOfRange(0, 3) + enc +
            byteArrayOf(0, 0, crc.toByte(), (crc ushr 8).toByte(), 0, 0)
    }

    /** Seal a plaintext frame in AES-CTR + CBC-MAC mode. [counter] is the 16-bit wire value (>= 2). */
    fun sealAes(plain: ByteArray, key: ByteArray, bleData: ByteArray, counter: Int): ByteArray {
        require(counter in 2..0xFFFF) { "AES counter out of range: $counter" }
        val header = plain.copyOfRange(0, 3)
        val body = plain.copyOfRange(3, plain.size)
        val enc = xorKeystream(body) { aes(key, nonce(0x01, counter, bleData, it + 1)) }
        val tag = mac(key, header, body, counter, bleData)
        return header + enc + tag + byteArrayOf((counter ushr 8).toByte(), counter.toByte())
    }

    fun wireCounter(frame: ByteArray): Int =
        ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or (frame[frame.size - 1].toInt() and 0xFF)

    /** Open a weak-mode frame; null if the checksum does not match. */
    fun openWeak(frame: ByteArray, key: ByteArray): ByteArray? {
        if (frame.size < 10) return null
        val n = frame.size - 9
        val ks = aes(key, FW_DATA)
        val body = xorKeystream(frame.copyOfRange(3, 3 + n)) { ks }
        val crc = checksum(body)
        if (frame[3 + n + 2] != crc.toByte() || frame[3 + n + 3] != (crc ushr 8).toByte()) return null
        return frame.copyOfRange(0, 3) + body
    }

    /** Open an AES frame; null unless the MAC verifies (never accept an unauthenticated frame). */
    fun openAes(frame: ByteArray, key: ByteArray, bleData: ByteArray): ByteArray? {
        if (frame.size < 10) return null
        val n = frame.size - 9
        val counter = wireCounter(frame)
        val body = xorKeystream(frame.copyOfRange(3, 3 + n)) { aes(key, nonce(0x01, counter, bleData, it + 1)) }
        val tag = mac(key, frame.copyOfRange(0, 3), body, counter, bleData)
        var diff = 0
        for (i in 0 until 4) diff = diff or (tag[i].toInt() xor frame[3 + n + i].toInt())
        if (diff != 0) return null
        return frame.copyOfRange(0, 3) + body
    }
}
