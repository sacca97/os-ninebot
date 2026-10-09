package com.sacca.openride.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.sacca.openride.app.data.ScooterConnector
import com.sacca.openride.app.data.CredentialStore
import com.sacca.openride.app.ui.describe
import com.sacca.openride.core.crypto.CredentialHex
import com.sacca.openride.app.ui.nav.Navigator
import com.sacca.openride.app.ui.nav.Screen
import com.sacca.openride.core.session.CredentialRejected
import com.sacca.openride.core.session.InitInfo
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
    val pairingSupported: Boolean = false,
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
        _ui.update { DeviceUiState(name = connector.selected.value?.name.orEmpty(), busy = "Connecting…",
            pairingSupported = connector.selected.value?.profile?.pairing != null) }
        job = viewModelScope.launch {
            var conn: com.sacca.openride.app.data.Connection? = null
            try {
                conn = connector.connect(this)
                val info = conn.info
                _ui.update { it.copy(info = info, hasCredential = store.loginCredential(info.serial) != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(error = describe(e)) }
            } finally {
                withContext(NonCancellable) { conn?.session?.close() }
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    fun cancel() { job?.cancel() } // Keep ownership until the cancelled operation's finally block has closed its link.

    fun openDashboard() {
        if (connector.inCooldown() || job?.isActive == true) return
        navigator.resetTo(Screen.Dashboard)
    }

    fun clearMessages() = _ui.update { it.copy(error = null, notice = null) }

    /**
     * From the text field or a file: same format (32 hex characters), see [CredentialHex]. The password is NOT trusted until the
     * scooter accepts it: one real login is attempted, and only then is it stored. A wrong one
     * never replaces a working credential, and a rejection starts the usual login cool-down.
     */
    fun importCredential(text: String): Boolean {
        if (job?.isActive == true) return false
        val info = _ui.value.info ?: return false
        val pw = CredentialHex.parse(text)
        if (pw == null) {
            _ui.update { it.copy(error = "Credential must be exactly 32 hex characters (16 bytes).") }
            return false
        }
        if (connector.inCooldown()) {
            _ui.update { it.copy(error = "Wait for the login cool-down to end, then import again.") }
            return false
        }
        _ui.update { it.copy(busy = "Connecting to verify credential…", error = null, notice = null) }
        job = viewModelScope.launch {
            var conn: com.sacca.openride.app.data.Connection? = null
            var verified = false
            try {
                conn = connector.connect(this)
                check(conn.info.serial == info.serial) { "The connected scooter differs from the one selected for import. Select it again." }
                _ui.update { it.copy(busy = "Verifying credential…") }
                conn.session.login(pw) // one attempt, no retry
                // Import does not change the scooter password, so no recovery/pending slot is needed.
                store.save(info.serial, pw)
                store.discardPending(info.serial)
                verified = true
                _ui.update { it.copy(hasCredential = true, notice = "Credential verified.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialRejected) {
                connector.markRejected()
                _ui.update { it.copy(error = "The scooter rejected this credential, so it was not saved. Check that it is the one the official app or `f2` currently uses.") }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = "Could not verify the credential, so it was not saved: ${describe(e)}") }
            } finally {
                withContext(NonCancellable) { conn?.session?.close() }
                _ui.update { it.copy(busy = null) }
            }
            // Dashboard immediately reconnects. Release the verification connection before it starts.
            if (verified) navigator.resetTo(Screen.Dashboard)
        }
        return true
    }

    fun report(error: String? = null, notice: String? = null) = _ui.update { it.copy(error = error, notice = notice) }

    fun forgetCredential() {
        if (job?.isActive == true) return
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
        if (job?.isActive == true) return
        if (connector.selected.value?.profile?.pairing == null) return
        val serial = _ui.value.info?.serial ?: return
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = "Pairing…", error = null, notice = null) }
            var conn: com.sacca.openride.app.data.Connection? = null
            try {
                val pw = ByteArray(16).also { SecureRandom().nextBytes(it) }
                store.savePending(serial, pw) // persisted BEFORE SET_PWD goes out
                conn = connector.connect(this, pairing = true)
                val ok = requireNotNull(conn.session.profile.pairing) { "Pairing unsupported for this model" }.strategy.pair(conn.session, pw) {
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
                withContext(NonCancellable) { conn?.session?.close() }
                _ui.update { it.copy(busy = null, pairingPending = false) }
            }
        }
    }

    override fun onCleared() { job?.cancel() }
}
