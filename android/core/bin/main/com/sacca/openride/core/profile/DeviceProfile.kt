package com.sacca.openride.core.profile

import com.sacca.openride.core.crypto.SessionCrypto
import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.registers.Reg
import com.sacca.openride.core.session.WeakModePairing

/** Protocol identifiers resolve only to implementations compiled into the app. */
enum class Protocol { NINEBOT }
enum class Encryption {
    NINEBOT_CURRENT;
    fun create(name: String): SessionCrypto = when (this) {
        NINEBOT_CURRENT -> SessionCrypto(name.toByteArray(Charsets.UTF_8))
    }
}
enum class Authentication { NINEBOT_INIT_AUTH }
enum class Pairing {
    WEAK_MODE;
    val strategy get() = when (this) { WEAK_MODE -> WeakModePairing }
}
enum class WriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
enum class ReceiveMode { NOTIFICATION, INDICATION }

data class GattProfile(
    val service: String, val write: String, val notify: String,
    val writeMode: WriteMode, val receiveMode: ReceiveMode, val mtu: Int,
)

data class PowerProfile(
    val device: Int, val register: Int, val bytes: Int, val onValue: Long,
    val notificationCommand: Int, val notificationMarker: Int,
    val on: Packet, val off: Packet, val zeroChecks: List<String>, val verification: String,
) {
    fun isOn(raw: ByteArray): Boolean {
        require(raw.size == bytes) { "invalid power-state length" }
        return unsignedLittleEndian(raw) == onValue
    }

    fun fromNotification(p: Packet): Boolean? =
        if (p.src == device && p.cmd == notificationCommand && p.data.size == bytes + 2 &&
            (p.data[0].toInt() and 0xFF) == notificationMarker && (p.data[1].toInt() and 0xFF) == register
        ) isOn(p.data.copyOfRange(2, p.data.size)) else null
}

data class DeviceProfile(
    val id: String, val name: String, val manufacturerId: Int,
    val protocol: Protocol, val encryption: Encryption, val authentication: Authentication,
    val pairing: Pairing?, val gatt: GattProfile, val readings: List<Reg>, val power: PowerProfile?,
) {
    fun reading(key: String): Reg = readings.first { it.key == key }
}

internal fun unsignedLittleEndian(raw: ByteArray): Long =
    raw.indices.fold(0L) { value, i -> value or ((raw[i].toLong() and 0xFF) shl (8 * i)) }
