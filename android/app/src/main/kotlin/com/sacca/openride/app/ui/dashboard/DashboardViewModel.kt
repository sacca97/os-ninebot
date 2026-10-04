package com.sacca.openride.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sacca.openride.app.ble.BluetoothAccess
import com.sacca.openride.app.ble.Scanner
import com.sacca.openride.app.data.CredentialStore
import com.sacca.openride.app.data.ScooterConnector
import com.sacca.openride.app.data.SettingsRepository
import com.sacca.openride.app.ui.describe
import com.sacca.openride.core.registers.Reg
import com.sacca.openride.core.registers.Registers
import com.sacca.openride.core.session.CredentialRejected
import com.sacca.openride.core.session.PowerResult
import com.sacca.openride.core.session.ScooterSession
import com.sacca.openride.core.session.ScooterTimeout
import javax.inject.Inject

data class DashboardUiState(
    val name: String = "",
    /** Nothing saved (or its credential is gone): the home screen offers to add one instead of connecting. */
    val noScooter: Boolean = false,
    /** Not connecting: the radio is off / the permission is missing. The screen offers the fix instead of an error. */
    val bluetoothOff: Boolean = false,
    val permissionNeeded: Boolean = false,
    val busy: String? = null,
    val connected: Boolean = false,
    val power: Boolean? = null,
    val values: Map<String, String> = emptyMap(),
    val powerBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connector: ScooterConnector,
    private val store: CredentialStore,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui
    private var connJob: Job? = null
    private var powerJob: Job? = null
    private var session: ScooterSession? = null

    /** Polling only runs while the app is on screen; in the background it is suspended (nothing changes unattended). */
    private val foreground = MutableStateFlow(true)
    private var idleJob: Job? = null
    private var droppedWhileIdle = false

    /** App left the screen: stop polling now, and drop the connection (and the single-app lock) after a grace period. */
    fun onBackground() {
        foreground.value = false
        if (connJob?.isActive != true) return
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(IDLE_DISCONNECT_MS)
            droppedWhileIdle = true
            stop()
        }
    }

    /** Back on screen: resume polling at once, or reconnect if the grace period ran out. */
    fun onForeground() {
        foreground.value = true
        idleJob?.cancel(); idleJob = null
        if (droppedWhileIdle) { droppedWhileIdle = false; start() }
    }

    fun clearMessages() = _ui.update { it.copy(error = null, notice = null) }

    private var connectedTo: String? = null

    init {
        // Connect as soon as the radio comes on; when it goes off, drop quietly (no "link closed" error).
        viewModelScope.launch {
            BluetoothAccess.enabledFlow(context).collect { on ->
                if (on && _ui.value.bluetoothOff) start()
                if (!on && wantsConnection) { stop(); _ui.update { DashboardUiState(bluetoothOff = true) } }
            }
        }
    }

    /** Set once the home screen has asked to connect; the radio flow ignores changes before that. */
    private var wantsConnection = false

    /** Wakes the poll loop early (the scooter just reported it is on). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Connects to the selected scooter, else to the one saved last (no scan). Idempotent: calling it again for the same
     * scooter does nothing; for a different one it switches. With nothing saved it just reports [DashboardUiState.noScooter].
     */
    fun start() {
        wantsConnection = true
        if (!BluetoothAccess.hasPermissions(context)) { _ui.update { DashboardUiState(permissionNeeded = true) }; return }
        if (!BluetoothAccess.isEnabled(context)) { _ui.update { DashboardUiState(bluetoothOff = true) }; return }
        if (connector.inCooldown()) return
        val wanted = connector.selected.value?.address
        if (connJob?.isActive == true) {
            if (wanted == null || wanted == connectedTo) return
            stop() // another scooter was picked
        }
        _ui.update { DashboardUiState() }
        connJob = viewModelScope.launch {
            val me = currentCoroutineContext()[Job]
            try {
                var ad = connector.selected.value
                val savedName = if (ad == null) settings.lastScooter()?.second else null
                val credName = ad?.name ?: savedName
                // The advertised name is the serial, so the credential (Keystore decrypt) loads while we look for / connect to it.
                val early = credName?.let { async { store.loginCredential(it) } }
                if (early?.await() == null) {
                    _ui.update { it.copy(noScooter = true) }
                    return@launch
                }
                if (ad == null) {
                    // After a restart: find the saved scooter with a short scan (see Scanner.find for why not by address).
                    _ui.update { it.copy(name = savedName.orEmpty(), busy = "Looking for scooter…") }
                    ad = Scanner.find(context, savedName!!)
                    if (ad == null) {
                        _ui.update { it.copy(error = "Scooter not found. Is it on and in range, and is the other app closed (it allows one connection)?") }
                        return@launch
                    }
                    connector.select(ad)
                }
                connectedTo = ad.address
                _ui.update { it.copy(name = ad.name, busy = "Connecting…") }
                val conn = connector.connect(this)
                session = conn.session
                val s = conn.session
                val (pw, fromPending) = (if (conn.info.serial == ad.name) early!!.await() else store.loginCredential(conn.info.serial)) ?: run {
                    _ui.update { it.copy(noScooter = true) }
                    return@launch
                }
                _ui.update { it.copy(busy = "Logging in…") }
                s.login(pw) // one attempt, no retry
                if (fromPending) store.promotePending(conn.info.serial)
                _ui.update { it.copy(busy = null, connected = true) }
                settings.setLastScooter(ad.address, ad.name) // next launch connects without scanning
                launch {
                    s.notifications.collect { n ->
                        val on = Registers.powerFromNotification(n) ?: return@collect
                        _ui.update { it.copy(power = on) }
                        if (on) wake.trySend(Unit) // start reading at once instead of at the next tick
                    }
                }
                poll(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialRejected) {
                connector.markRejected()
                _ui.update { it.copy(error = describe(e)) }
            } catch (e: Throwable) {
                if (BluetoothAccess.isEnabled(context)) _ui.update { it.copy(error = describe(e)) }
            } finally {
                withContext(NonCancellable) { session?.close(); session = null }
                if (connJob === me) connJob = null
                _ui.update { it.copy(busy = null, connected = false) }
            }
        }
    }

    fun stop() {
        connJob?.cancel(); connJob = null
        powerJob?.cancel(); powerJob = null
        _ui.update { it.copy(connected = false, busy = null, powerBusy = false, power = null) }
    }

    /**
     * One request in flight at a time (the scooter is not known to cope with more), so the cycle time is the number
     * of reads x ~60 ms. Order: power first, then what the screen shows, then the slow-changing values, and the
     * static ones (serial, firmware) last. One cycle every [CYCLE_MS]; the FAST set each cycle, the rest every [SLOW_EVERY] cycles.
     */
    private suspend fun poll(s: ScooterSession) {
        var staticsDone = false
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            foreground.first { it } // suspended here while the app is in the background
            if (_ui.value.powerBusy) { delay(100); continue } // a power command owns the link: no competing reads
            val started = System.currentTimeMillis()
            val on = s.powerState()
            _ui.update { it.copy(power = on) }
            if (on) {
                val exp = settings.current().showExperimental
                val live = Registers.all.filter { !it.static && (exp || !it.experimental) }
                val (fast, slow) = live.partition { it.key in FAST }
                readInto(s, if (cycle % SLOW_EVERY == 0) fast + slow else fast)
                if (!staticsDone) {
                    readInto(s, Registers.all.filter { it.static })
                    staticsDone = true
                }
            }
            // Fixed period, whatever the reads took: nothing on the scooter changes faster than this.
            withTimeoutOrNull((CYCLE_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0)) { wake.receive() }
            cycle++
        }
    }

    private suspend fun readInto(s: ScooterSession, regs: List<Reg>) {
        for (r in regs) {
            // The scooter just powered off (reported by notification): its boards will not answer, stop reading.
            if (_ui.value.power == false || _ui.value.powerBusy) return
            // Fail fast: a polled value is refreshed a second later anyway, so one retry at 1 s, not 3 tries at 3 s each.
            val v = try { s.read(r, retries = 1, timeoutMs = POLL_TIMEOUT_MS) } catch (_: ScooterTimeout) { "n/a" }
            _ui.update { it.copy(values = it.values + (r.key to v)) }
        }
    }

    fun setPower(on: Boolean) {
        val s = session ?: return
        if (powerJob?.isActive == true) return
        powerJob = viewModelScope.launch {
            _ui.update { it.copy(powerBusy = true, error = null, notice = null) }
            try {
                when (s.setPower(on)) {
                    PowerResult.ALREADY_IN_STATE -> _ui.update { it.copy(notice = "Already ${if (on) "on" else "off"}; nothing sent.") }
                    PowerResult.CHANGED -> Unit // the state line on the home screen already shows it
                    PowerResult.NO_CHANGE -> _ui.update { it.copy(error = "Power state did not change. The command was NOT resent.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(error = describe(e)) }
            } finally {
                _ui.update { it.copy(powerBusy = false) }
            }
        }
    }

    override fun onCleared() { connJob?.cancel(); powerJob?.cancel() }

    private companion object {
        const val IDLE_DISCONNECT_MS = 60_000L
        const val POLL_TIMEOUT_MS = 1_000L
        const val CYCLE_MS = 1_000L // fast values every second
        const val SLOW_EVERY = 5 // the rest every 5 s
        val FAST = setOf("batt_pct", "batt_v", "batt_a", "status", "charging", "avg_speed", "speed_26", "mode", "range")
    }
}
