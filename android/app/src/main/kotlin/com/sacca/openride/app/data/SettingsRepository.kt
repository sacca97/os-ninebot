package com.sacca.openride.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    /** The scooter used last, so the next launch can connect without scanning: "address|name". */
    private val lastKey = stringPreferencesKey("last_scooter")
    suspend fun lastScooter(): Pair<String, String>? =
        context.dataStore.data.first()[lastKey]?.split("|", limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    suspend fun setLastScooter(address: String, name: String) { context.dataStore.edit { it[lastKey] = "$address|$name" } }
    suspend fun clearLastScooter() { context.dataStore.edit { it.remove(lastKey) } }
    suspend fun setPowerControl(v: Boolean) { context.dataStore.edit { it[powerKey] = v } }
    suspend fun setShowExperimental(v: Boolean) { context.dataStore.edit { it[experimentalKey] = v } }
}
