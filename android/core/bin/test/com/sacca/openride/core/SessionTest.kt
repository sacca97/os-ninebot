package com.sacca.openride.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import com.sacca.openride.core.crypto.NinebotCrypto
import com.sacca.openride.core.protocol.Cmd
import com.sacca.openride.core.protocol.Dev
import com.sacca.openride.core.protocol.FrameReassembler
import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.registers.Reg
import com.sacca.openride.core.profile.DeviceProfiles
import com.sacca.openride.core.registers.Registers
import com.sacca.openride.core.safety.FrameGuard
import com.sacca.openride.core.session.CredentialRejected
import com.sacca.openride.core.session.PowerRefused
import com.sacca.openride.core.session.PowerResult
import com.sacca.openride.core.session.ScooterLink
import com.sacca.openride.core.session.ScooterSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs the scooter side of the protocol with the same crypto. */
class FakeScooterLink(
    private val name: String,
    private val password: ByteArray,
    var powered: Boolean = true,
    /** Raw value of the "average speed" register (ctrl 0x65). */
    var speed: Int = 0,
    /** When set the scooter never answers the speed registers. */
    var speedSilent: Boolean = false,
    /** Report power changes with a 0x21 notification, like the real scooter. */
    var notifyOnPowerChange: Boolean = false,
    /** Stop answering power-state reads once a power write was seen (forces the notification path). */
    var muteStateReadsAfterWrite: Boolean = false,
) : ScooterLink {
    private var wrote = false
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = out.receiveAsFlow()
    val sent = mutableListOf<Packet>()
    private val re = FrameReassembler()
    private val bleData = ByteArray(16) { (it * 7 + 1).toByte() }
    private val serial = "NBFAKE0000001A".toByteArray()
    private val nameKey = NinebotCrypto.deriveKey(name.toByteArray(), NinebotCrypto.FW_DATA)
    private val appKey = NinebotCrypto.deriveKey(password, bleData)

    override suspend fun write(chunk: ByteArray) {
        for (f in re.feed(chunk)) handle(f)
    }

    override suspend fun close() { out.close() }

    private fun reply(p: Packet, counter: Int) {
        out.trySend(
            if (counter == 0) NinebotCrypto.sealWeak(p.pack(), nameKey)
            else NinebotCrypto.sealAes(p.pack(), appKey, bleData, counter),
        )
    }

    private fun handle(f: ByteArray) {
        val c = NinebotCrypto.wireCounter(f)
        if (c == 0) {
            val p = Packet.unpack(NinebotCrypto.openWeak(f, nameKey) ?: return) ?: return
            sent += p
            if (p.cmd == Cmd.INIT) reply(Packet(Dev.BLE, Dev.PHONE, Cmd.INIT, 1, bleData + serial), 0)
            return
        }
        val p = Packet.unpack(NinebotCrypto.openAes(f, appKey, bleData) ?: return) ?: return // wrong key: silence
        sent += p
        when {
            p.cmd == Cmd.AUTH -> reply(Packet(Dev.BLE, Dev.PHONE, Cmd.AUTH, 1), c + 1)
            p.cmd == Cmd.READ && p.dst == Dev.BLE && p.idx == Registers.POWER_STATE_IDX ->
                if (!(wrote && muteStateReadsAfterWrite)) reply(Packet(Dev.BLE, Dev.PHONE, Cmd.READ_ACK, p.idx, byteArrayOf(if (powered) 1 else 0, 0)), c + 1)
            p.cmd == Cmd.READ && p.dst == Dev.BATTERY && p.idx == 0x32 ->
                reply(Packet(Dev.BATTERY, Dev.PHONE, Cmd.READ_ACK, p.idx, byteArrayOf(66, 0)), c + 1)
            p.cmd == Cmd.READ && p.dst == Dev.CONTROLLER && (p.idx == 0x65 || p.idx == 0x26) ->
                if (!speedSilent) {
                    val v = if (p.idx == 0x65) speed else 0
                    reply(Packet(Dev.CONTROLLER, Dev.PHONE, Cmd.READ_ACK, p.idx, byteArrayOf(v.toByte(), (v shr 8).toByte())), c + 1)
                }
            p.cmd == Cmd.READ && p.dst == Dev.BATTERY && p.idx == 0x40 && p.data[0].toInt() == 20 ->
                reply(Packet(Dev.BATTERY, Dev.PHONE, Cmd.READ_ACK, p.idx, ByteArray(20) { if (it % 2 == 0) 0x52 else 0x0F }), c + 1)
            p == FrameGuard.POWER_OFF || p == FrameGuard.POWER_ON -> {
                wrote = true
                powered = p == FrameGuard.POWER_ON
                if (notifyOnPowerChange) {
                    reply(Packet(Dev.BLE, Dev.PHONE, 0x21, 0, byteArrayOf(0x02, 0x4D, if (powered) 1 else 0, 0)), c + 1)
                }
            }
        }
    }
}

class SessionTest {
    private val pw = ByteArray(16) { (it + 1).toByte() }

    private fun <T> run(link: FakeScooterLink, power: Boolean = false, block: suspend (ScooterSession) -> T): T =
        runBlocking {
            val s = ScooterSession(link, "NBFAKE0000001A", allowPower = power)
            s.start(CoroutineScope(Dispatchers.Default))
            try { block(s) } finally { s.close() }
        }

    @Test fun handshakeAndRead() {
        val link = FakeScooterLink("NBFAKE0000001A", pw)
        run(link) { s ->
            val info = s.init()
            assertEquals("NBFAKE0000001A", info.serial)
            assertTrue(info.passwordStored)
            s.login(pw)
            assertTrue(s.powerState())
            assertEquals("66 %", s.read(Registers.all.first { it.key == "batt_pct" }))
        }
    }

    @Test fun sessionReadsTheSelectedModelsRegisterMapping() = runBlocking {
        val original = DeviceProfiles.default.reading("batt_pct")
        val mapped = Reg(original.key, original.label, Dev.CONTROLLER, 0x65, 1, decode = original.decode)
        val profile = DeviceProfiles.default.copy(id = "synthetic-model", readings = listOf(mapped), power = null)
        val link = FakeScooterLink("NBFAKE0000001A", pw, speed = 55)
        val session = ScooterSession(link, "NBFAKE0000001A", profile = profile)
        session.start(CoroutineScope(Dispatchers.Default))
        try {
            session.init(); session.login(pw)
            assertEquals("55 %", session.read(session.profile.reading("batt_pct")))
            assertTrue(link.sent.any { it.cmd == Cmd.READ && it.dst == Dev.CONTROLLER && it.idx == 0x65 })
        } finally { session.close() }
    }

    @Test fun wrongCredentialIsRejected() {
        val link = FakeScooterLink("NBFAKE0000001A", pw)
        run(link) { s ->
            s.init()
            try { s.login(ByteArray(16)); fail() } catch (_: CredentialRejected) {}
        }
    }

    @Test fun powerAlreadyInStateSendsNothing() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.ALREADY_IN_STATE, s.setPower(true))
        }
        assertTrue(link.sent.none { it.cmd == Cmd.WRITE_NO_REPLY })
    }

    @Test fun powerOffSendsExactlyOneWrite() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.CHANGED, s.setPower(false))
        }
        assertEquals(1, link.sent.count { it.cmd == Cmd.WRITE_NO_REPLY })
        assertFalse(link.powered)
    }

    @Test fun powerOffRefusedWhileSpeedNonZero() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true, speed = 55) // 5.5 km/h
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            try { s.setPower(false); fail("should refuse") } catch (e: PowerRefused) {
                assertTrue(e.message!!.contains("5.5 km/h"))
            }
        }
        assertTrue(link.sent.none { it.cmd == Cmd.WRITE_NO_REPLY })
        assertTrue(link.powered)
    }

    @Test fun powerOffRefusedWhenSpeedCannotBeRead() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true, speedSilent = true)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            try { s.setPower(false); fail("should refuse") } catch (_: PowerRefused) {}
        }
        assertTrue(link.sent.none { it.cmd == Cmd.WRITE_NO_REPLY })
    }

    @Test fun powerOffProceedsWhenSpeedIsZeroAndChecksSpeedFirst() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true, speed = 0)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.CHANGED, s.setPower(false))
        }
        val order = link.sent.filter { it.cmd == Cmd.READ && it.dst == Dev.CONTROLLER || it.cmd == Cmd.WRITE_NO_REPLY }
        assertEquals(listOf(0x65, 0x26, 0x79), order.map { it.idx })
        assertFalse(link.powered)
    }

    @Test fun powerOnIsNotInterlocked() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = false, speed = 99)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.CHANGED, s.setPower(true))
        }
        assertTrue(link.sent.none { it.cmd == Cmd.READ && it.dst == Dev.CONTROLLER })
    }

    @Test fun cellVoltagesAreOneRequest() {
        val link = FakeScooterLink("NBFAKE0000001A", pw)
        run(link) { s ->
            s.init(); s.login(pw)
            val out = s.read(Registers.all.first { it.key == "cell_mv" })
            assertTrue(out.startsWith("3922 3922"))
        }
        assertEquals(1, link.sent.count { it.cmd == Cmd.READ && it.dst == Dev.BATTERY && it.idx == 0x40 })
    }

    @Test fun powerChangeIsConfirmedByNotificationNotPolling() {
        // State reads are silent after the write, so only the scooter's own notification can confirm.
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true, notifyOnPowerChange = true, muteStateReadsAfterWrite = true)
        val t0 = System.currentTimeMillis()
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.CHANGED, s.setPower(false, waitMs = 10_000))
        }
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
        assertEquals(1, link.sent.count { it.cmd == Cmd.WRITE_NO_REPLY })
    }

    @Test fun noChangeReportedMeansNoChangeAndNoResend() {
        val link = FakeScooterLink("NBFAKE0000001A", pw, powered = true, muteStateReadsAfterWrite = true)
        run(link, power = true) { s ->
            s.init(); s.login(pw)
            assertEquals(PowerResult.NO_CHANGE, s.setPower(false, waitMs = 1_500))
        }
        assertEquals(1, link.sent.count { it.cmd == Cmd.WRITE_NO_REPLY })
    }
}
