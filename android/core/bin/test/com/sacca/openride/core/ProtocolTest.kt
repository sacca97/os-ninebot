package com.sacca.openride.core

import com.sacca.openride.core.protocol.Cmd
import com.sacca.openride.core.protocol.Dev
import com.sacca.openride.core.protocol.FrameReassembler
import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.safety.FrameGuard
import com.sacca.openride.core.safety.UnsafeCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    private fun frame(len: Int, fill: Int) =
        byteArrayOf(0x5A, 0xA5.toByte(), len.toByte()) + ByteArray(len + 10) { fill.toByte() }

    @Test fun reassemblerHandlesChunksMergesAndGarbage() {
        val a = frame(1, 1)
        val b = frame(4, 2)
        val r = FrameReassembler()
        assertEquals(0, r.feed(a.copyOfRange(0, 1)).size) // lone 5A
        val out = r.feed(a.copyOfRange(1, 5))
        assertEquals(0, out.size)
        val done = r.feed(a.copyOfRange(5, a.size) + b)
        assertEquals(2, done.size)
        assertArrayEquals(a, done[0])
        assertArrayEquals(b, done[1])
        val g = r.feed(byteArrayOf(1, 2, 3, 0x5A) + a)
        assertEquals(1, g.size)
        assertArrayEquals(a, g[0])
    }

    @Test fun guardAllowsOnlyReadsAndLogin() {
        FrameGuard.assertSafe(Packet(Dev.PHONE, Dev.BLE, Cmd.INIT, 0))
        FrameGuard.assertSafe(Packet(Dev.PHONE, Dev.BLE, Cmd.AUTH, 0, ByteArray(14)))
        FrameGuard.assertSafe(Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.READ, 0x1D, byteArrayOf(2)))
        FrameGuard.assertSafe(Packet(Dev.PHONE, Dev.BATTERY, Cmd.READ, 0x32, byteArrayOf(2)))
    }

    private fun refused(p: Packet, pairing: Boolean = false, power: Boolean = false) {
        try {
            FrameGuard.assertSafe(p, pairing, power)
            fail("should refuse $p")
        } catch (_: UnsafeCommand) {
        }
    }

    @Test fun guardRefusesEverythingElse() {
        // power and SET_PWD are gated, and only the exact power frames pass even when enabled
        refused(FrameGuard.POWER_ON)
        refused(FrameGuard.POWER_OFF)
        refused(Packet(Dev.PHONE, Dev.BLE, Cmd.SET_PWD, 0, ByteArray(16)))
        refused(Packet(Dev.PC, Dev.CONTROLLER, Cmd.READ, 0x1D, byteArrayOf(2)))
        refused(Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.READ, 0x1D, byteArrayOf(2, 0)))
        FrameGuard.assertSafe(FrameGuard.POWER_ON, allowPower = true)
        FrameGuard.assertSafe(FrameGuard.POWER_OFF, allowPower = true)
        FrameGuard.assertSafe(Packet(Dev.PHONE, Dev.BLE, Cmd.SET_PWD, 0, ByteArray(16)), allowPairing = true)
        refused(FrameGuard.POWER_OFF, pairing = true)
        for (bad in listOf(
            Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.WRITE_NO_REPLY, 0x79, byteArrayOf(0, 0)),
            Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.WRITE_NO_REPLY, 0x7A, byteArrayOf(1, 0)),
            Packet(Dev.PHONE, Dev.BLE, Cmd.WRITE_NO_REPLY, 0x79, byteArrayOf(1, 0)),
            Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.WRITE, 0x79, byteArrayOf(1, 0)),
        )) refused(bad, power = true)
    }
}
