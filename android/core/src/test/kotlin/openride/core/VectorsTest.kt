package openride.core

import openride.core.crypto.KeyLabel
import openride.core.crypto.NinebotCrypto
import openride.core.crypto.SessionCrypto
import openride.core.protocol.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class VectorsTest {
    private val json = javaClass.getResource("/crypto_vectors.json")!!.readText()
    private fun field(src: String, k: String) = Regex("\"$k\"\\s*:\\s*\"?([^\",}\\s]+)").find(src)!!.groupValues[1]

    private val name = field(json, "name").toByteArray()
    private val bleData = hex(field(json, "ble_data"))
    private val appKey = hex(field(json, "app_key"))
    private val keys = mapOf(
        "name" to NinebotCrypto.deriveKey(name, NinebotCrypto.FW_DATA),
        "ble" to NinebotCrypto.deriveKey(name, bleData),
        "app" to NinebotCrypto.deriveKey(appKey, bleData),
    )

    private class V(val id: String, val key: String, val counter: Int, val plain: ByteArray, val frame: ByteArray)

    private val vectors = Regex("\\{[^{}]*\"id\"[^{}]*\\}").findAll(json.substringAfter("\"vectors\"")).map {
        val s = it.value
        V(field(s, "id"), field(s, "key"), field(s, "counter").toInt(), hex(field(s, "plaintext")), hex(field(s, "frame")))
    }.toList()

    @Test fun sealMatchesByteForByte() {
        assertEquals("vector parser dropped cases", 21, vectors.size)
        for (v in vectors) {
            val k = keys.getValue(v.key)
            val sealed = if (v.counter == 0) NinebotCrypto.sealWeak(v.plain, k)
            else NinebotCrypto.sealAes(v.plain, k, bleData, v.counter)
            assertArrayEquals(v.id, v.frame, sealed)
        }
    }

    @Test fun openMatchesByteForByte() {
        for (v in vectors) {
            val k = keys.getValue(v.key)
            val plain = if (v.counter == 0) NinebotCrypto.openWeak(v.frame, k)
            else NinebotCrypto.openAes(v.frame, k, bleData)
            assertNotNull(v.id, plain)
            assertArrayEquals(v.id, v.plain, plain)
        }
    }

    @Test fun tamperedAesFrameIsRejected() {
        val v = vectors.first { it.id == "auth_request" }
        for (i in v.frame.indices) {
            val bad = v.frame.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            if (i >= v.frame.size - 2) continue // counter bytes change the nonce, also rejected below
            assertNull("byte $i", NinebotCrypto.openAes(bad, keys.getValue("app"), bleData))
        }
        val wrongKey = keys.getValue("ble")
        assertNull(NinebotCrypto.openAes(v.frame, wrongKey, bleData))
    }

    @Test fun sessionOpensEveryVectorAndLearnsHandshake() {
        val s = SessionCrypto(name, appKey)
        val init = vectors.first { it.id == "init_reply_password_stored" }
        val o = s.open(init.frame)!!
        assertEquals(KeyLabel.NAME, o.key)
        assertArrayEquals(bleData, s.bleData)
        assertEquals("NBTEST0000001A", s.serial)
        for (v in vectors) {
            val r = s.open(v.frame)
            assertNotNull(v.id, r)
            assertArrayEquals(v.id, v.plain, r!!.plain)
            assertEquals(v.id, v.counter, r.counter)
        }
    }

    @Test fun loginIsSentAsCounter2() {
        val s = SessionCrypto(name, appKey)
        val init = Packet.unpack(vectors.first { it.id == "init_request" }.plain)!!
        assertArrayEquals(vectors.first { it.id == "init_request" }.frame, s.seal(init))
        s.open(vectors.first { it.id == "init_reply_password_stored" }.frame)
        s.txKey = KeyLabel.APP
        s.counter = 1
        val login = vectors.first { it.id == "auth_request" }
        assertArrayEquals(login.frame, s.seal(Packet.unpack(login.plain)!!))
        assertEquals(2, s.counter)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
