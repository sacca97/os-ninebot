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
    private val navigator: Navigator,
) : ViewModel() {
    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _ui.update { ScanUiState(scanning = true) }
        job = viewModelScope.launch {
            try {
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

    /**
     * Picked a scooter. With its credential stored it becomes THE scooter and the stack collapses to the home screen
     * (so back never returns to scanning); otherwise the credential screen opens first.
     */
    fun select(ad: ScooterAd) {
        stop()
        connector.select(ad)
        viewModelScope.launch {
            if (store.loginCredential(ad.name) != null) navigator.resetTo(Screen.Dashboard) else navigator.push(Screen.Device)
        }
    }

    /** Credential screen (import / pair / export), always reachable. */
    fun setup(ad: ScooterAd) {
        stop()
        connector.select(ad)
        navigator.push(Screen.Device)
    }

    override fun onCleared() = stop()

}
