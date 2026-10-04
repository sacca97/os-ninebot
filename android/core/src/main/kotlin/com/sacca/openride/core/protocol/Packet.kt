package com.sacca.openride.core.protocol

object Cmd {
    const val READ = 0x01
    const val WRITE = 0x02
    const val WRITE_NO_REPLY = 0x03
    const val READ_ACK = 0x04
    const val WRITE_ACK = 0x05
    const val INIT = 0x5B
    const val SET_PWD = 0x5C // "PING" in the Python tool
    const val AUTH = 0x5D // "PAIR" in the Python tool
}

object Dev {
    const val CONTROLLER = 0x20
    const val BLE = 0x21
    const val BATTERY = 0x22
    const val PC = 0x3D
    const val PHONE = 0x3E
}

object Ble {
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val MANUFACTURER_ID = 0x424E
}

/** Plaintext frame: `5A A5 | LEN | SRC | DST | CMD | IDX | DATA[LEN]`. */
class Packet(val src: Int, val dst: Int, val cmd: Int, val idx: Int, data: ByteArray = ByteArray(0)) {
    val data: ByteArray = data.copyOf()

    fun pack(): ByteArray {
        val out = ByteArray(7 + data.size)
        out[0] = 0x5A
        out[1] = 0xA5.toByte()
        out[2] = data.size.toByte()
        out[3] = src.toByte()
        out[4] = dst.toByte()
        out[5] = cmd.toByte()
        out[6] = idx.toByte()
        data.copyInto(out, 7)
        return out
    }

    override fun equals(other: Any?) = other is Packet && src == other.src && dst == other.dst &&
        cmd == other.cmd && idx == other.idx && data.contentEquals(other.data)

    override fun hashCode() = ((src * 31 + dst) * 31 + cmd) * 31 + idx + 31 * data.contentHashCode()

    override fun toString() = "Packet(src=%02X dst=%02X cmd=%02X idx=%02X data=%s)"
        .format(src, dst, cmd, idx, data.joinToString("") { "%02X".format(it) })

    companion object {
        fun unpack(frame: ByteArray): Packet? {
            if (frame.size < 7 || frame[0] != 0x5A.toByte() || frame[1] != 0xA5.toByte()) return null
            if (frame.size != 7 + (frame[2].toInt() and 0xFF)) return null
            return Packet(
                frame[3].toInt() and 0xFF, frame[4].toInt() and 0xFF,
                frame[5].toInt() and 0xFF, frame[6].toInt() and 0xFF,
                frame.copyOfRange(7, frame.size),
            )
        }
    }
}
