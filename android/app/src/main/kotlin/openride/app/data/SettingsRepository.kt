package openride.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** Verified on a real F2 Pro, so on by default. Can still be turned off. */
    val powerControl: Boolean = true,
    val showExperimental: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val powerKey = booleanPreferencesKey("power_control")
    private val experimentalKey = booleanPreferencesKey("show_experimental")

    val settings: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(it[powerKey] ?: true, it[experimentalKey] ?: false)
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun setPowerControl(v: Boolean) { context.dataStore.edit { it[powerKey] = v } }
    suspend fun setShowExperimental(v: Boolean) { context.dataStore.edit { it[experimentalKey] = v } }
}
