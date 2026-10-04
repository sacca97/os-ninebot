package openride.core.registers

import openride.core.protocol.Dev

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
)

object Registers {
    const val POWER_STATE_DEV = Dev.BLE
    const val POWER_STATE_IDX = 0x4D

    private fun u(b: ByteArray): Long {
        var v = 0L
        for (i in b.indices.reversed()) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun s16(b: ByteArray): Int = u(b).toInt().toShort().toInt()
    private fun fixed(v: Double, d: Int, unit: String) = "%.${d}f %s".format(java.util.Locale.ROOT, v, unit)
    private fun fw(b: ByteArray): String {
        val v = u(b).toInt()
        return "${v shr 8}.${(v shr 4) and 0xF}.${v and 0xF}"
    }

    fun powerOn(raw: ByteArray): Boolean = u(raw) == 1L

    val SERIAL = Reg("serial", "Serial", Dev.CONTROLLER, 0x10, 7, static = true) {
        String(it.takeWhile { b -> b != 0.toByte() }.toByteArray(), Charsets.US_ASCII)
    }

    val all: List<Reg> = listOf(
        SERIAL,
        Reg("ctrl_fw", "Controller firmware", Dev.CONTROLLER, 0x1A, 1, static = true, decode = ::fw),
        Reg("ble_fw", "Bluetooth firmware", Dev.CONTROLLER, 0x68, 1, static = true, decode = ::fw),
        Reg("bms_fw", "Battery (BMS) firmware", Dev.BATTERY, 0x17, 1, static = true) { "%X".format(u(it)) },
        Reg("batt_pct", "Battery", Dev.BATTERY, 0x32, 1) { "${u(it)} %" },
        Reg("batt_v", "Battery voltage", Dev.BATTERY, 0x34, 1) { fixed(u(it) / 100.0, 2, "V") },
        Reg("range", "Range", Dev.CONTROLLER, 0x24, 1) { fixed(u(it) / 100.0, 2, "km") },
        Reg("mileage", "Mileage", Dev.CONTROLLER, 0x29, 2) { fixed(u(it) / 1000.0, 1, "km") },
        Reg("avg_speed", "Average speed", Dev.CONTROLLER, 0x65, 1) { fixed(u(it) / 10.0, 1, "km/h") },
        Reg("mode", "Mode", Dev.CONTROLLER, 0x75, 1) {
            when (u(it).toInt()) { 0 -> "NORMAL"; 1 -> "ECO"; 2 -> "SPORT"; else -> "unknown (${u(it)})" }
        },
        Reg("error", "Error code", Dev.CONTROLLER, 0x1B, 1) { "0x%04X".format(u(it)) },
        Reg("alarm", "Alarm code", Dev.CONTROLLER, 0x1C, 1) { "0x%04X".format(u(it)) },
        Reg("status", "Status word", Dev.CONTROLLER, 0x1D, 1) { "0x%04X".format(u(it)) },
        // Unverified on a real scooter: shown only in the Experimental section.
        Reg("batt_a", "Battery current", Dev.BATTERY, 0x33, 1, experimental = true) { fixed(s16(it) / 100.0, 2, "A") },
        Reg("batt_health", "Battery health", Dev.BATTERY, 0x3B, 1, experimental = true) { "${u(it)} %" },
        Reg("temp", "Body temperature", Dev.CONTROLLER, 0x3E, 1, experimental = true) { fixed(s16(it) / 10.0, 1, "°C") },
        Reg("ctrl_v", "Controller voltage", Dev.CONTROLLER, 0x47, 1, experimental = true) { fixed(u(it) / 100.0, 2, "V") },
        Reg("range_pred", "Predicted range", Dev.CONTROLLER, 0x25, 1, experimental = true) { fixed(u(it) / 100.0, 2, "km") },
        Reg("kers", "KERS", Dev.CONTROLLER, 0x7B, 1, experimental = true) { "${u(it)}" },
        Reg("cruise", "Cruise", Dev.CONTROLLER, 0x7C, 1, experimental = true) { "${u(it)}" },
        Reg("tail_light", "Tail light", Dev.CONTROLLER, 0x7D, 1, experimental = true) { "${u(it)}" },
    )
    // Deliberately absent: the "BT pairing code" register (controller 0x17 x3) is never read.
}
