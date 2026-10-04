package openride.core.crypto

import openride.core.protocol.Cmd
import openride.core.protocol.Dev
import openride.core.protocol.Packet

enum class KeyLabel { NAME, BLE, APP }

class Opened(val plain: ByteArray, val packet: Packet?, val key: KeyLabel, val counter: Int)

/**
 * Per-connection key + counter state (port of f2probe `Session`). One request in flight at a time.
 * [name] must be the advertised local name exactly as scanned; [password] is the 16-byte credential.
 */
class SessionCrypto(private val name: ByteArray, var password: ByteArray? = null) {
    var bleData: ByteArray? = null
        private set
    var serial: String? = null
        private set
    var txKey: KeyLabel = KeyLabel.NAME
    var counter: Int = 0

    private fun key(label: KeyLabel): ByteArray? {
        val bd = bleData
        val pw = password
        return when (label) {
            KeyLabel.NAME -> NinebotCrypto.deriveKey(name, NinebotCrypto.FW_DATA)
            KeyLabel.BLE -> bd?.let { NinebotCrypto.deriveKey(name, it) }
            KeyLabel.APP -> if (bd != null && pw != null) NinebotCrypto.deriveKey(pw, bd) else null
        }
    }

    /** Seal with [txKey]. Counter 0 => weak mode (counter stays 0); otherwise counter+1 in AES mode. */
    @Synchronized
    fun seal(pkt: Packet): ByteArray {
        val k = checkNotNull(key(txKey)) { "key $txKey not available yet" }
        val plain = pkt.pack()
        val out = if (counter == 0) {
            NinebotCrypto.sealWeak(plain, k)
        } else {
            check(counter < 0xFFFF) { "counter exhausted: reconnect" }
            counter += 1
            NinebotCrypto.sealAes(plain, k, checkNotNull(bleData), counter)
        }
        observe(pkt)
        return out
    }

    /** Open one complete frame with any currently possible key; the checksum/MAC decides. Sets [counter]. */
    @Synchronized
    fun open(frame: ByteArray): Opened? {
        if (frame.size < 10) return null
        val c = NinebotCrypto.wireCounter(frame)
        val order = listOf(txKey) + listOf(KeyLabel.APP, KeyLabel.BLE, KeyLabel.NAME).filter { it != txKey }
        for (label in order) {
            val k = key(label) ?: continue
            for (aes in listOf(c != 0, c == 0)) {
                val plain = if (aes) bleData?.let { NinebotCrypto.openAes(frame, k, it) }
                else NinebotCrypto.openWeak(frame, k)
                if (plain != null) {
                    val pkt = Packet.unpack(plain)
                    counter = c
                    if (pkt != null) observe(pkt)
                    return Opened(plain, pkt, label, c)
                }
            }
        }
        return null
    }

    private fun observe(p: Packet) {
        if (p.cmd == Cmd.INIT && p.src == Dev.BLE && p.data.size >= 16) {
            bleData = p.data.copyOfRange(0, 16)
            serial = String(p.data, 16, p.data.size - 16, Charsets.US_ASCII)
            txKey = KeyLabel.BLE
        }
    }
}
