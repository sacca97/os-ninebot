package openride.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import openride.app.data.CredentialStore
import openride.app.data.ScooterConnector
import openride.app.data.SettingsRepository
import openride.app.ui.describe
import openride.app.ui.nav.Navigator
import openride.app.ui.nav.Screen
import openride.core.registers.Reg
import openride.core.registers.Registers
import openride.core.session.CredentialRejected
import openride.core.session.PowerResult
import openride.core.session.ScooterSession
import openride.core.session.ScooterTimeout
import javax.inject.Inject

data class DashboardUiState(
    val name: String = "",
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
    private val connector: ScooterConnector,
    private val store: CredentialStore,
    private val settings: SettingsRepository,
    private val navigator: Navigator,
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

    /** Idempotent: safe to call again after rotation. */
    fun start() {
        if (connJob?.isActive == true || connector.inCooldown()) return
        _ui.update { DashboardUiState(name = connector.selected.value?.name.orEmpty(), busy = "Connecting…") }
        connJob = viewModelScope.launch {
            try {
                // The advertised name is the serial, so the credential (Keystore decrypt) loads while GATT connects.
                val ad = connector.selected.value
                val early = ad?.let { async { store.loginCredential(it.name) } }
                val conn = connector.connect(this)
                session = conn.session
                val s = conn.session
                val (pw, fromPending) = (if (conn.info.serial == ad?.name) early?.await() else store.loginCredential(conn.info.serial)) ?: run {
                    _ui.update { it.copy(error = "No credential stored for ${conn.info.serial}. Import or pair first.") }
                    return@launch
                }
                _ui.update { it.copy(busy = "Logging in…") }
                s.login(pw) // one attempt, no retry
                if (fromPending) store.promotePending(conn.info.serial)
                _ui.update { it.copy(busy = null, connected = true) }
                ad?.let { settings.setLastScooter(it.address, it.name) } // next launch connects without scanning
                launch {
                    s.notifications.collect { n ->
                        if (n.data.size >= 4 && n.data[0].toInt() == 0x02 && n.data[1].toInt() == Registers.POWER_STATE_IDX) {
                            val on = (n.data[2].toInt() and 0xFF) or ((n.data[3].toInt() and 0xFF) shl 8)
                            _ui.update { it.copy(power = on == 1) }
                        }
                    }
                }
                poll(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialRejected) {
                connector.markRejected()
                _ui.update { it.copy(error = describe(e)) }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = describe(e)) }
            } finally {
                withContext(NonCancellable) { session?.close(); session = null }
                connJob = null
                _ui.update { it.copy(busy = null, connected = false) }
            }
        }
    }

    fun stop() {
        connJob?.cancel(); connJob = null
        powerJob?.cancel(); powerJob = null
        _ui.update { it.copy(connected = false, busy = null, powerBusy = false, power = null) }
    }

    /** Leaving the screen disconnects. */
    fun leave() { stop(); navigator.pop() }

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
            delay((CYCLE_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            cycle++
        }
    }

    private suspend fun readInto(s: ScooterSession, regs: List<Reg>) {
        for (r in regs) {
            val v = try { s.read(r) } catch (_: ScooterTimeout) { "n/a" }
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
                    PowerResult.CHANGED -> _ui.update { it.copy(notice = if (on) "Powered on (ready to ride)." else "Powered off.") }
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
        const val CYCLE_MS = 1_000L // fast values every second
        const val SLOW_EVERY = 5 // the rest every 5 s
        val FAST = setOf("batt_pct", "batt_v", "batt_a", "status", "charging", "avg_speed", "speed_26", "mode", "range")
    }
}
