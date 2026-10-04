package openride.core.protocol

/**
 * Rebuilds on-air (encrypted) frames from arbitrary notification chunks. A frame starts with `5A A5` and is
 * complete when it is `LEN + 13` bytes long. Bytes before a frame start are discarded.
 */
class FrameReassembler {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<ByteArray> {
        buf += chunk
        val out = ArrayList<ByteArray>()
        while (true) {
            val start = findStart()
            if (start < 0) {
                // keep a trailing 0x5A: it may be the first half of the magic
                buf = if (buf.isNotEmpty() && buf.last() == 0x5A.toByte()) byteArrayOf(0x5A) else ByteArray(0)
                return out
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 3) return out
            val total = (buf[2].toInt() and 0xFF) + 13
            if (buf.size < total) return out
            out += buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    fun reset() {
        buf = ByteArray(0)
    }

    private fun findStart(): Int {
        for (i in 0 until buf.size - 1) {
            if (buf[i] == 0x5A.toByte() && buf[i + 1] == 0xA5.toByte()) return i
        }
        return -1
    }
}
