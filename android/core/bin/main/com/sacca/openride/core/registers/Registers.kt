package com.sacca.openride.core.registers

/** One readable register (multi-word registers are read at idx, idx+1, ... and concatenated little-endian). */
class Reg(
    val key: String,
    val label: String,
    val dev: Int,
    val idx: Int,
    val words: Int,
    val experimental: Boolean = false,
    /** Static values (serial, firmware) are read once per connection. */
    val static: Boolean = false,
    val decode: (ByteArray) -> String,
    val byteCount: Int = words * 2,
    val fast: Boolean = false,
    val verification: String = "unverified",
    val evidence: String = "",
)

object Registers {
    internal fun cells(b: ByteArray): String =
        com.sacca.openride.core.profile.ReadingDecoder("cells", unit = "mV").decode(b)
    internal fun cellTemps(b: ByteArray): String =
        com.sacca.openride.core.profile.ReadingDecoder("temperatures", offset = -20.0, unit = "°C").decode(b)
    internal fun current(raw: Int): String =
        com.sacca.openride.core.profile.ReadingDecoder("current", signed = true, scale = 0.01, unit = "A")
            .decode(byteArrayOf(raw.toByte(), (raw shr 8).toByte()))

    // Compatibility accessors for the existing F2 reference tests. App code uses the session profile.
    val all: List<Reg> get() = com.sacca.openride.core.profile.DeviceProfiles.default.readings
    val SERIAL: Reg get() = all.first { it.key == "serial" }
    val POWER_STATE_DEV: Int get() = com.sacca.openride.core.profile.DeviceProfiles.default.power!!.device
    val POWER_STATE_IDX: Int get() = com.sacca.openride.core.profile.DeviceProfiles.default.power!!.register
    fun powerOn(raw: ByteArray): Boolean = com.sacca.openride.core.profile.DeviceProfiles.default.power!!.isOn(raw)
    fun powerFromNotification(p: com.sacca.openride.core.protocol.Packet): Boolean? =
        com.sacca.openride.core.profile.DeviceProfiles.default.power!!.fromNotification(p)
}
