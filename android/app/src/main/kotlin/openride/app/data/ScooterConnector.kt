package openride.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import openride.app.ble.AndroidGattLink
import openride.app.ble.ScooterAd
import openride.core.session.InitInfo
import openride.core.session.ScooterSession
import javax.inject.Inject
import javax.inject.Singleton

class Connection(val session: ScooterSession, val info: InitInfo)

/** Opens a GATT link + session and runs INIT. Also holds the app-wide scooter selection and login cool-down. */
@Singleton
class ScooterConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val frameLog: FrameLog,
) {
    private val _selected = MutableStateFlow<ScooterAd?>(null)
    val selected: StateFlow<ScooterAd?> = _selected
    fun select(ad: ScooterAd?) { _selected.value = ad }

    private val _cooldownUntil = MutableStateFlow(0L)
    val cooldownUntil: StateFlow<Long> = _cooldownUntil
    fun markRejected() { _cooldownUntil.value = System.currentTimeMillis() + COOLDOWN_MS }
    fun inCooldown() = System.currentTimeMillis() < _cooldownUntil.value

    /** [scope] owns the session's reader coroutine; closing the session cancels it. */
    suspend fun connect(scope: CoroutineScope, pairing: Boolean = false): Connection {
        val ad = checkNotNull(_selected.value) { "no scooter selected" }
        val link = AndroidGattLink.open(context, ad.device)
        val session = ScooterSession(
            link, ad.name,
            allowPower = settings.current().powerControl,
            allowPairing = pairing,
            onFrame = frameLog::add,
        )
        session.start(scope)
        try {
            return Connection(session, session.init())
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    private companion object {
        const val COOLDOWN_MS = 30_000L
    }
}
