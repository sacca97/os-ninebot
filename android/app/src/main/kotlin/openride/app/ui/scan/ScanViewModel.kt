package openride.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import openride.app.ble.Scanner
import openride.app.ble.ScooterAd
import openride.app.data.CredentialStore
import openride.app.data.ScooterConnector
import openride.app.data.SettingsRepository
import openride.app.ui.nav.Navigator
import openride.app.ui.nav.Screen
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val scans: List<ScooterAd> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connector: ScooterConnector,
    private val store: CredentialStore,
    private val settings: SettingsRepository,
    private val navigator: Navigator,
) : ViewModel() {
    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui
    private var job: Job? = null

    /**
     * First start after the app launches: go straight to the dashboard for the scooter used last (no scan, no probe
     * connection) if we hold its credential. Tried once per process so "back" never loops into it again.
     */
    private suspend fun autoConnect(): Boolean {
        if (autoTried) return false
        autoTried = true
        val (address, name) = settings.lastScooter() ?: return false
        if (store.loginCredential(name) == null) return false
        val ad = Scanner.known(context, address, name) ?: return false
        connector.select(ad)
        navigator.push(Screen.Dashboard)
        return true
    }

    fun start() {
        if (job?.isActive == true) return
        _ui.update { ScanUiState(scanning = true) }
        job = viewModelScope.launch {
            try {
                if (autoConnect()) return@launch
                Scanner.scan(context).collect { ad ->
                    _ui.update { st ->
                        st.copy(scans = (st.scans.filter { it.address != ad.address } + ad).sortedByDescending { it.rssi })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(error = e.message) }
            } finally {
                _ui.update { it.copy(scanning = false) }
            }
        }
    }

    fun rescan() { stop(); start() }
    fun stop() { job?.cancel(); job = null }

    /** Known scooter (credential stored): connect straight from the scan result, skipping the probe connection. */
    fun select(ad: ScooterAd) {
        stop()
        connector.select(ad)
        viewModelScope.launch {
            navigator.push(if (store.loginCredential(ad.name) != null) Screen.Dashboard else Screen.Device)
        }
    }

    /** Credential screen (import / pair / export), always reachable. */
    fun setup(ad: ScooterAd) {
        stop()
        connector.select(ad)
        navigator.push(Screen.Device)
    }

    override fun onCleared() = stop()

    private companion object {
        var autoTried = false
    }
}
