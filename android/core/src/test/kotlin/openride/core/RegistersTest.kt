package openride.core

import openride.core.registers.Registers
import org.junit.Test
import org.junit.Assert.assertEquals

class RegistersTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun cellVoltagesFromOfficialAppRead() {
        val out = Registers.cells(hex("520F520F580F520F540F5A0F540F540F540FBE0E"))
        assertEquals("3922 3922 3928 3922 3924 3930 3924 3924 3924 3774 mV (spread 156 mV)", out)
    }

    @Test
    fun cellTemperaturesAreOffsetByTwenty() {
        assertEquals("28 °C, 28 °C", Registers.cellTemps(hex("3030")))
    }

    @Test
    fun kersAndTcsLabels() {
        val kers = Registers.all.first { it.key == "kers" }
        assertEquals(listOf("weak", "medium", "strong"), (0..2).map { kers.decode(byteArrayOf(it.toByte(), 0)) })
        val tcs = Registers.all.first { it.key == "tcs" }
        assertEquals("on", tcs.decode(byteArrayOf(1, 0)))
        assertEquals("off", tcs.decode(byteArrayOf(0, 0)))
    }

    @Test
    fun walkModeOnOff() {
        val walk = Registers.all.first { it.key == "walk_mode" }
        assertEquals("on", walk.decode(byteArrayOf(1, 0)))
        assertEquals("off", walk.decode(byteArrayOf(0, 0)))
    }

    @Test
    fun chargingIsStatusWordBit8() {
        val charging = Registers.all.first { it.key == "charging" }
        assertEquals("yes", charging.decode(hex("0009")))
        assertEquals("no", charging.decode(hex("0008")))
    }

    @Test
    fun batteryCurrentDirection() {
        assertEquals("charging 1.10 A", Registers.current(-110))
        assertEquals("discharging 0.08 A", Registers.current(8))
        assertEquals("idle 0.00 A", Registers.current(0))
    }
}
