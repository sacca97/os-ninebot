package com.sacca.openride.core.safety

import com.sacca.openride.core.protocol.Cmd
import com.sacca.openride.core.protocol.Dev
import com.sacca.openride.core.protocol.Packet

class UnsafeCommand(message: String) : RuntimeException(message)

/** Allowlist of frames the app may ever send (port of `transport.assert_safe`). */
object FrameGuard {
    val POWER_ON = Packet(Dev.PHONE, Dev.BLE, Cmd.WRITE_NO_REPLY, 0x1E, byteArrayOf(1, 0))
    val POWER_OFF = Packet(Dev.PHONE, Dev.CONTROLLER, Cmd.WRITE_NO_REPLY, 0x79, byteArrayOf(1, 0))
    const val POWER_STATE_REG = 0x4D

    fun assertSafe(p: Packet, allowPairing: Boolean = false, allowPower: Boolean = false) {
        if (p.src != Dev.PHONE) throw UnsafeCommand("refusing non-PHONE source: $p")
        if (p == POWER_ON || p == POWER_OFF) {
            if (allowPower) return
            throw UnsafeCommand("power control not enabled: $p")
        }
        if (p.dst == Dev.BLE && (p.cmd == Cmd.INIT || p.cmd == Cmd.AUTH)) return
        if (p.dst == Dev.BLE && p.cmd == Cmd.SET_PWD) {
            if (allowPairing) return
            throw UnsafeCommand("SET_PWD replaces the scooter's password; pairing not enabled: $p")
        }
        if (p.cmd == Cmd.READ && p.dst in setOf(Dev.CONTROLLER, Dev.BATTERY, Dev.BLE) && p.data.size == 1) return
        throw UnsafeCommand("refusing to send non-read frame: $p")
    }
}
