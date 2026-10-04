package com.sacca.openride.core

import com.sacca.openride.core.profile.DeviceProfiles
import com.sacca.openride.core.profile.ReadingDecoder
import com.sacca.openride.core.protocol.Dev
import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.safety.FrameGuard
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    @Test fun signedEndianScaleAndOffset() {
        val decoder = ReadingDecoder("number", signed = true, scale = 0.1, offset = 2.0,
            precision = 1, unit = "km/h", byteOrder = "big_endian")
        assertEquals("-3.5 km/h", decoder.decode(byteArrayOf(0xFF.toByte(), 0xC9.toByte())))
    }

    @Test fun notificationMustComeFromTheConfiguredBoardAndHaveTheExactLength() {
        val power = DeviceProfiles.default.power!!
        assertEquals(true, power.fromNotification(Packet(Dev.BLE, Dev.PHONE, 0x21, 0, byteArrayOf(2, 0x4D, 1, 0))))
        assertNull(power.fromNotification(Packet(Dev.CONTROLLER, Dev.PHONE, 0x21, 0, byteArrayOf(2, 0x4D, 1, 0))))
        assertNull(power.fromNotification(Packet(Dev.BLE, Dev.PHONE, 0x21, 0, byteArrayOf(2, 0x4D, 1))))
    }

    @Test fun generatedPowerPacketsStillMatchTheIndependentSafetyAllowlist() {
        val power = DeviceProfiles.default.power!!
        assertEquals(FrameGuard.POWER_ON, power.on)
        assertEquals(FrameGuard.POWER_OFF, power.off)
        FrameGuard.assertSafe(power.on, allowPower = true)
        FrameGuard.assertSafe(power.off, allowPower = true)
    }

    @Test fun incompleteReadingIsRejectedAndSpeedCandidateRemainsRaw() {
        val candidate = DeviceProfiles.default.reading("speed_26")
        assertTrue(candidate.experimental)
        assertTrue(candidate.evidence.contains("unknown"))
        assertEquals("raw 55", candidate.decode(byteArrayOf(55, 0)))
        assertThrows(IllegalArgumentException::class.java) { candidate.decode(byteArrayOf(55)) }
    }
}
