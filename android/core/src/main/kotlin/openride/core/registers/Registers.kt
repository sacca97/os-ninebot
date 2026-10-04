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

    /** One u16 per cell in mV (10S pack), e.g. "4102 4099 ... mV (spread 61 mV)". */
    internal fun cells(b: ByteArray): String {
        val mv = (0 until b.size / 2).map { u(b.copyOfRange(it * 2, it * 2 + 2)).toInt() }
        return "${mv.joinToString(" ")} mV (spread ${mv.max() - mv.min()} mV)"
    }

    internal fun current(raw: Int): String = when {
        raw < 0 -> "charging " + fixed(-raw / 100.0, 2, "A")
        raw > 0 -> "discharging " + fixed(raw / 100.0, 2, "A")
        else -> "idle 0.00 A"
    }

    /** Two sensors, one byte each, degrees C + 20. */
    internal fun cellTemps(b: ByteArray): String =
        b.take(2).joinToString(", ") { "${(it.toInt() and 0xFF) - 20} °C" }

    /** A register consulted by the power-off interlock. */
    class SpeedGuard(val dev: Int, val idx: Int, val label: String, val show: (Long) -> String)

    /**
     * All must read 0 before powering off. 0x65 is the "average speed" the interlock was asked to use; whether it is
     * the live speed or a trip average is not established (it read 0.0 at rest), so 0x26 (polled by the official app,
     * 0 at rest, suspected live speed) is checked too. UNVERIFIED while riding: watch both in the Experimental list.
     */
    val SPEED_GUARD = listOf(
        SpeedGuard(Dev.CONTROLLER, 0x65, "Average speed") { fixed(it / 10.0, 1, "km/h") },
        SpeedGuard(Dev.CONTROLLER, 0x26, "Speed register 0x26") { "raw $it" },
    )

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
        // Read live from the scooter via the Python tool (docs/f2pro-findings.md); not yet confirmed in this app.
        // Negative current = charging (seen in both directions with the charger plugged and unplugged).
        Reg("batt_a", "Battery current", Dev.BATTERY, 0x33, 1) { current(s16(it)) },
        Reg("cell_mv", "Cell voltages", Dev.BATTERY, 0x40, 10, decode = ::cells),
        Reg("cell_temp", "Cell temperatures", Dev.BATTERY, 0x35, 1, decode = ::cellTemps),
        Reg("batt_health", "Battery health", Dev.BATTERY, 0x3B, 1) { "${u(it)} %" },
        Reg("temp", "Body temperature", Dev.CONTROLLER, 0x3E, 1) { fixed(s16(it) / 10.0, 1, "°C") },
        // Still unverified (or meaning unknown): shown only in the Experimental section.
        Reg("speed_26", "Register 0x26 (suspected speed)", Dev.CONTROLLER, 0x26, 1, experimental = true) { "raw ${u(it)}" },
        Reg("ctrl_v", "Controller voltage", Dev.CONTROLLER, 0x47, 1, experimental = true) { fixed(u(it) / 100.0, 2, "V") },
        Reg("range_pred", "Predicted range", Dev.CONTROLLER, 0x25, 1) { fixed(u(it) / 100.0, 2, "km") },
        Reg("walk_mode", "Walk mode (5 km/h)", Dev.CONTROLLER, 0x77, 1) { if (u(it) != 0L) "on" else "off" },
        Reg("charging", "Charging", Dev.CONTROLLER, 0x1D, 1) { if (u(it) and 0x100L != 0L) "yes" else "no" },
        Reg("kers", "KERS", Dev.CONTROLLER, 0x7B, 1) {
            when (u(it).toInt()) { 0 -> "weak"; 1 -> "medium"; 2 -> "strong"; else -> "unknown (${u(it)})" }
        },
        Reg("tcs", "Traction control (TCS)", Dev.CONTROLLER, 0xF3, 1) { if (u(it) != 0L) "on" else "off" },
        Reg("cruise", "Cruise", Dev.CONTROLLER, 0x7C, 1, experimental = true) { "${u(it)}" },
        Reg("tail_light", "Tail light", Dev.CONTROLLER, 0x7D, 1, experimental = true) { "${u(it)}" },
    )
    // Deliberately absent: the "BT pairing code" register (controller 0x17 x3) is never read.
}
