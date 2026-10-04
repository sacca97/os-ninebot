package com.sacca.openride.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.sacca.openride.core.crypto.KeyLabel
import com.sacca.openride.core.crypto.SessionCrypto
import com.sacca.openride.core.protocol.Cmd
import com.sacca.openride.core.protocol.Dev
import com.sacca.openride.core.protocol.FrameReassembler
import com.sacca.openride.core.protocol.Packet
import com.sacca.openride.core.registers.Reg
import com.sacca.openride.core.registers.Registers
import com.sacca.openride.core.safety.FrameGuard

class LinkClosedException(cause: Throwable? = null) : RuntimeException("Bluetooth link closed", cause)
class ScooterTimeout(what: String) : RuntimeException("No reply: $what")
class CredentialRejected : RuntimeException("Scooter did not accept the credential")

/** The power-off interlock said no (scooter not known to be standing still). Nothing was sent. */
class PowerRefused(message: String) : RuntimeException(message)

class InitInfo(val serial: String, val passwordStored: Boolean)

enum class PowerResult { ALREADY_IN_STATE, CHANGED, NO_CHANGE }

/**
 * Handshake + request/response over a [ScooterLink]. One request in flight at a time.
 * Every outgoing frame goes through [FrameGuard].
 */
class ScooterSession(
    private val link: ScooterLink,
    deviceName: String,
    private val allowPower: Boolean = false,
    private val allowPairing: Boolean = false,
    private val onFrame: ((FrameEvent) -> Unit)? = null,
) {
    private val crypto = SessionCrypto(deviceName.toByteArray(Charsets.UTF_8))
    private val reassembler = FrameReassembler()
    private val replies = Channel<Packet>(Channel.UNLIMITED)
    private val gate = Mutex()
    private var reader: Job? = null
    private val _notifications = MutableSharedFlow<Packet>(extraBufferCapacity = 16)

    /** Unsolicited BLE-board register-change notifications (cmd 0x21). */
    val notifications: SharedFlow<Packet> = _notifications

    fun start(scope: CoroutineScope) {
        reader = scope.launch {
            try {
                link.incoming.collect { chunk ->
                    for (frame in reassembler.feed(chunk)) {
                        val o = crypto.open(frame) ?: continue
                        val p = o.packet ?: continue
                        onFrame?.invoke(FrameEvent(false, p, o.key, o.counter, frame))
                        if (p.cmd == NOTIFY_CMD) _notifications.tryEmit(p) else replies.trySend(p)
                    }
                }
                replies.close(LinkClosedException())
            } catch (e: CancellationException) {
                replies.close(LinkClosedException())
                throw e
            } catch (e: Throwable) {
                replies.close(LinkClosedException(e))
            }
        }
    }

    suspend fun close() {
        reader?.cancel()
        link.close()
    }

    // -- low level ------------------------------------------------------

    private suspend fun send(p: Packet) {
        FrameGuard.assertSafe(p, allowPairing, allowPower)
        val frame = crypto.seal(p)
        onFrame?.invoke(FrameEvent(true, p, crypto.txKey, crypto.counter, frame))
        var off = 0
        while (off < frame.size) {
            val n = minOf(link.maxChunk, frame.size - off)
            link.write(frame.copyOfRange(off, off + n))
            off += n
        }
    }

    private suspend fun await(timeoutMs: Long, what: String, match: (Packet) -> Boolean): Packet {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val r = replies.receive()
                    if (match(r)) return@withTimeout r
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            throw ScooterTimeout(what)
        }
    }

    private suspend fun request(p: Packet, what: String, timeoutMs: Long = 3000, match: (Packet) -> Boolean): Packet =
        gate.withLock {
            while (replies.tryReceive().isSuccess) { /* drop stale frames */ }
            send(p)
            await(timeoutMs, what, match)
        }

    // -- handshake ------------------------------------------------------

    suspend fun init(): InitInfo {
        val r = request(Packet(Dev.PHONE, Dev.BLE, Cmd.INIT, 0), "INIT") { it.src == Dev.BLE && it.cmd == Cmd.INIT }
        val serial = checkNotNull(crypto.serial) { "INIT reply without serial" }
        return InitInfo(serial, r.idx == 1)
    }

    /** One attempt, no retry. A wrong password gets no reply: reported as [CredentialRejected]. */
    suspend fun login(password: ByteArray, resetCounter: Boolean = true) {
        require(password.size == 16) { "credential must be 16 bytes" }
        val serial = checkNotNull(crypto.serial) { "call init() first" }
        crypto.password = password
        crypto.txKey = KeyLabel.APP
        // After a fresh INIT the login must go out as counter 2. Right after pairing on the same connection the
        // counter is left as the scooter last reported it (as the Python tool does).
        if (resetCounter) crypto.counter = 1
        try {
            request(Packet(Dev.PHONE, Dev.BLE, Cmd.AUTH, 0, serial.toByteArray(Charsets.US_ASCII)), "AUTH") {
                it.src == Dev.BLE && it.cmd == Cmd.AUTH && it.idx == 1
            }
        } catch (_: ScooterTimeout) {
            throw CredentialRejected()
        }
    }

    // -- reads ----------------------------------------------------------

    /**
     * Reads [length] bytes starting at register [idx] in ONE request (the official app does the same, e.g. 20 bytes
     * for the ten cell voltages: 73 ms against 958 ms for ten 2-byte reads, measured live). Reads are idempotent:
     * at most [retries] retries on timeout. Strictly one request in flight (see [request]).
     */
    suspend fun readRaw(dev: Int, idx: Int, retries: Int = 2, timeoutMs: Long = 3000, length: Int = 2): ByteArray {
        require(length in 1..MAX_READ_BYTES) { "read length must be 1..$MAX_READ_BYTES" }
        var attempt = 0
        while (true) {
            try {
                val r = request(Packet(Dev.PHONE, dev, Cmd.READ, idx, byteArrayOf(length.toByte())), "READ %02X:%02X".format(dev, idx), timeoutMs) {
                    it.src == dev && it.cmd == Cmd.READ_ACK && it.idx == idx
                }
                return r.data
            } catch (e: ScooterTimeout) {
                if (attempt++ >= retries) throw e
            }
        }
    }

    suspend fun read(reg: Reg, retries: Int = 2, timeoutMs: Long = 3000): String =
        reg.decode(readRaw(reg.dev, reg.idx, retries, timeoutMs, length = reg.words * 2))

    suspend fun powerState(retries: Int = 2, timeoutMs: Long = 3000): Boolean =
        Registers.powerOn(readRaw(Registers.POWER_STATE_DEV, Registers.POWER_STATE_IDX, retries, timeoutMs))

    // -- power ----------------------------------------------------------

    /**
     * Powering OFF is interlocked: the speed registers ([Registers.SPEED_GUARD]) are read first and the write only
     * goes out if every one reads zero. A non-zero value, or a register that cannot be read, throws [PowerRefused]
     * and sends nothing (fail closed).
     *
     * Never retries the write. If already in the requested state, sends nothing. Polls the power register for up
     * to [waitMs] and reports [PowerResult.NO_CHANGE] if it did not flip (does not resend).
     */
    suspend fun setPower(on: Boolean, waitMs: Long = 15_000): PowerResult {
        if (powerState() == on) return PowerResult.ALREADY_IN_STATE
        if (!on) requireStandingStill()
        // The scooter announces the change itself (notification 0x21, `02 4D <state>`), usually 3-4 s after the write, so
        // that is what we wait on. We subscribe BEFORE the write goes out so it cannot be missed; a quick poll of the
        // state register is only the fallback.
        val changed = coroutineScope {
            val notified = CompletableDeferred<Unit>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                notifications.mapNotNull { Registers.powerFromNotification(it) }.first { it == on }
                notified.complete(Unit)
            }
            gate.withLock { send(if (on) FrameGuard.POWER_ON else FrameGuard.POWER_OFF) }
            val ok = withTimeoutOrNull(waitMs) {
                while (withTimeoutOrNull(CONFIRM_POLL_MS) { notified.await() } == null) {
                    try { if (powerState(retries = 0, timeoutMs = 800) == on) break } catch (_: ScooterTimeout) { /* boards busy or rebooting */ }
                }
                true
            } ?: false
            listener.cancel()
            ok
        }
        return if (changed) PowerResult.CHANGED else PowerResult.NO_CHANGE
    }

    private suspend fun requireStandingStill() {
        for (g in Registers.SPEED_GUARD) {
            val raw = try {
                readRaw(g.dev, g.idx, retries = 1, timeoutMs = 1500)
            } catch (_: ScooterTimeout) {
                throw PowerRefused("Could not read ${g.label}, so it is not known whether the scooter is standing still. Not powering off.")
            }
            val v = raw.indices.fold(0L) { acc, i -> acc or ((raw[i].toLong() and 0xFF) shl (8 * i)) }
            if (v != 0L) throw PowerRefused("${g.label} reads ${g.show(v)}, not zero. Not powering off.")
        }
    }

    // -- pairing (experimental) ------------------------------------------

    /**
     * Sends SET_PWD once. [weak] = counter 0 with the bleKey (the verified flow); otherwise an AES frame at counter 2.
     * Returns the reply idx (0 = pending, 1 = accepted) or null if there was no reply.
     */
    suspend fun sendSetPassword(password: ByteArray, weak: Boolean, timeoutMs: Long): Int? {
        require(password.size == 16)
        crypto.txKey = KeyLabel.BLE
        crypto.counter = if (weak) 0 else 1
        return try {
            request(Packet(Dev.PHONE, Dev.BLE, Cmd.SET_PWD, 0, password), "SET_PWD", timeoutMs) {
                it.src == Dev.BLE && it.cmd == Cmd.SET_PWD
            }.idx
        } catch (_: ScooterTimeout) {
            null
        }
    }

    /** Waits for the next SET_PWD reply (e.g. the "accepted" one after the button press). */
    suspend fun awaitSetPasswordReply(timeoutMs: Long): Int? = try {
        gate.withLock { await(timeoutMs, "SET_PWD reply") { it.src == Dev.BLE && it.cmd == Cmd.SET_PWD }.idx }
    } catch (_: ScooterTimeout) {
        null
    }

    companion object {
        const val NOTIFY_CMD = 0x21

        /** The longest single read the official app is seen to use (ten cell voltages). */
        const val MAX_READ_BYTES = 20

        /** Fallback poll while waiting for a power change to be reported. */
        const val CONFIRM_POLL_MS = 300L
    }
}
