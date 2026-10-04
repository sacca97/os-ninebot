package openride.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import openride.app.data.ScooterConnector
import openride.app.data.CredentialStore
import openride.app.ui.describe
import openride.core.crypto.CredentialHex
import openride.app.ui.nav.Navigator
import openride.app.ui.nav.Screen
import openride.core.session.CredentialRejected
import openride.core.session.InitInfo
import openride.core.session.WeakModePairing
import java.security.SecureRandom
import javax.inject.Inject

data class DeviceUiState(
    val name: String = "",
    val info: InitInfo? = null,
    val hasCredential: Boolean = false,
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val pairingPending: Boolean = false,
)

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val connector: ScooterConnector,
    private val store: CredentialStore,
    private val navigator: Navigator,
) : ViewModel() {
    private val _ui = MutableStateFlow(DeviceUiState())
    val ui: StateFlow<DeviceUiState> = _ui
    val cooldownUntil: StateFlow<Long> = connector.cooldownUntil
    private var job: Job? = null

    /** INIT only: serial + "password stored", nothing else is sent. */
    fun probe() {
        if (job?.isActive == true) return
        _ui.update { DeviceUiState(name = connector.selected.value?.name.orEmpty(), busy = "Connecting…") }
        job = viewModelScope.launch {
            var conn: openride.app.data.Connection? = null
            try {
                conn = connector.connect(this)
                val info = conn.info
                _ui.update { it.copy(info = info, hasCredential = store.loginCredential(info.serial) != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(error = describe(e)) }
            } finally {
                conn?.session?.close()
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _ui.update { it.copy(busy = null, pairingPending = false) } }

    fun openDashboard() {
        if (connector.inCooldown()) return
        navigator.push(Screen.Dashboard)
    }

    fun clearMessages() = _ui.update { it.copy(error = null, notice = null) }

    /** From the text field or a file: same format (32 hex characters), see [CredentialHex]. */
    fun importCredential(text: String): Boolean {
        val info = _ui.value.info ?: return false
        val pw = CredentialHex.parse(text)
        if (pw == null) {
            _ui.update { it.copy(error = "Credential must be exactly 32 hex characters (16 bytes).") }
            return false
        }
        viewModelScope.launch {
            store.save(info.serial, pw)
            _ui.update { it.copy(hasCredential = true, error = null, notice = "Credential imported.") }
        }
        return true
    }

    fun report(error: String? = null, notice: String? = null) = _ui.update { it.copy(error = error, notice = notice) }

    fun forgetCredential() {
        val info = _ui.value.info ?: return
        viewModelScope.launch {
            store.forget(info.serial)
            _ui.update { it.copy(hasCredential = false, notice = "Credential forgotten.") }
        }
    }

    /** Call only after the device-credential gate has passed. */
    suspend fun exportCredentialHex(): String? {
        val info = _ui.value.info ?: return null
        return store.load(info.serial)?.let { CredentialHex.format(it) }
    }

    /** Replaces the scooter's password (the flow verified live with `f2 pair`). */
    fun pair() {
        val serial = _ui.value.info?.serial ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = "Pairing…", error = null, notice = null) }
            var conn: openride.app.data.Connection? = null
            try {
                val pw = ByteArray(16).also { SecureRandom().nextBytes(it) }
                store.savePending(serial, pw) // persisted BEFORE SET_PWD goes out
                conn = connector.connect(this, pairing = true)
                val ok = WeakModePairing.pair(conn.session, pw) {
                    _ui.update { it.copy(pairingPending = true, busy = "Press the scooter's power button…") }
                }
                _ui.update { it.copy(pairingPending = false) }
                if (!ok) {
                    _ui.update { it.copy(error = "Pairing was not confirmed in time. The new password is kept as pending and is tried at the next login.", hasCredential = true) }
                    return@launch
                }
                _ui.update { it.copy(busy = "Verifying new password…") }
                try {
                    conn.session.login(pw, resetCounter = false) // same connection, like `f2 pair`
                } catch (_: CredentialRejected) {
                    // Fall back to a fresh connection with the normal login (one attempt on each connection).
                    conn.session.close()
                    delay(2000)
                    conn = connector.connect(this)
                    conn.session.login(pw)
                }
                store.promotePending(serial)
                _ui.update { it.copy(hasCredential = true, notice = "Paired. The official app's credential is now invalid: export the new password and keep it safe.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialRejected) {
                connector.markRejected()
                _ui.update { it.copy(error = describe(e)) }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = describe(e)) }
            } finally {
                conn?.session?.close()
                _ui.update { it.copy(busy = null, pairingPending = false) }
            }
        }
    }

    override fun onCleared() { job?.cancel() }
}
