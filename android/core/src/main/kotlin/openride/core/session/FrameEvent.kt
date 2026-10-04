package openride.core.session

import openride.core.crypto.KeyLabel
import openride.core.protocol.Cmd
import openride.core.protocol.Dev
import openride.core.protocol.Packet

class FrameEvent(
    val tx: Boolean,
    val packet: Packet,
    val key: KeyLabel,
    val counter: Int,
    val raw: ByteArray,
) {
    /** Redacts the password / bleData by default; ciphertext only on request. */
    fun format(redact: Boolean = true, showRaw: Boolean = false): String {
        val p = packet
        val secret = (p.cmd == Cmd.INIT && p.src == Dev.BLE && p.data.size >= 16) || p.cmd == Cmd.SET_PWD
        val data = when {
            p.data.isEmpty() -> ""
            redact && secret -> " data=<redacted ${p.data.size}B>"
            else -> " data=" + hex(p.data)
        }
        val s = "${if (tx) "TX" else "RX"} #$counter ${key.name.lowercase()} " +
            "%02X->%02X cmd=%02X idx=%02X%s".format(p.src, p.dst, p.cmd, p.idx, data)
        return if (showRaw) s + "\n    raw=" + hex(raw) else s
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}
