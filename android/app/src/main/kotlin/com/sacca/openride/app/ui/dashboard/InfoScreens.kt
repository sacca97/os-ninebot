package com.sacca.openride.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sacca.openride.app.ui.LabelValue
import com.sacca.openride.app.ui.settings.SettingsViewModel
import com.sacca.openride.core.registers.Reg

/** Reads the same live values as the home screen (one shared connection and poll loop). */
@Composable
private fun InfoPage(vm: DashboardViewModel, content: @Composable (Map<String, String>, List<Reg>) -> Unit) {
    val st by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!st.connected) Text("Not connected.", modifier = Modifier.padding(vertical = 8.dp))
        else if (st.power == false) Text("The scooter is off: most values are unavailable.", modifier = Modifier.padding(vertical = 8.dp))
        content(st.values, st.profile.readings)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun BatteryScreen(vm: DashboardViewModel) = InfoPage(vm) { v, readings ->
    Section("Charge", listOf("batt_pct", "charging", "batt_a", "batt_v", "batt_health"), v, readings)
    if (readings.none { it.key == "cell_mv" || it.key == "cell_temp" }) return@InfoPage
    Text("Cells", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    val cells = parseCells(v["cell_mv"])
    if (cells == null && readings.any { it.key == "cell_mv" }) LabelValue("Cell voltages", v["cell_mv"])
    else if (cells != null) {
        cells.forEachIndexed { i, mv -> LabelValue("Cell ${i + 1}", "%.3f V".format(java.util.Locale.ROOT, mv / 1000.0)) }
        LabelValue("Spread", "${cells.max() - cells.min()} mV")
    }
    if (readings.any { it.key == "cell_temp" }) LabelValue("Cell temperatures", v["cell_temp"])
}

@Composable
fun RideScreen(vm: DashboardViewModel) = InfoPage(vm) { v, readings ->
    Section("Range", listOf("range", "range_pred"), v, readings)
    Section("Ride", listOf("mode", "speed", "avg_speed", "mileage", "temp"), v, readings)
}

@Composable
fun ScooterInfoScreen(vm: DashboardViewModel, settingsVm: SettingsViewModel = hiltViewModel()) {
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    InfoPage(vm) { v, readings ->
        Section("Settings (read-only)", listOf("kers", "tcs", "walk_mode"), v, readings)
        Section("Diagnostics", listOf("error", "alarm", "status"), v, readings)
        Section("Device", listOf("serial", "ctrl_fw", "ble_fw", "bms_fw"), v, readings)
        if (settings.showExperimental) {
            Text("Experimental (unverified)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            readings.filter { it.experimental }.forEach { LabelValue(it.label, v[it.key]) }
        }
    }
}

@Composable
private fun Section(title: String, keys: List<String>, values: Map<String, String>, readings: List<Reg>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    readings.filter { it.key in keys }.forEach { r -> LabelValue(r.label, values[r.key]) }
}

/** "3922 3922 … mV (spread 156 mV)" -> the ten voltages, or null if it is not that format (e.g. "n/a"). */
internal fun parseCells(s: String?): List<Int>? =
    s?.substringBefore(" mV", "")?.trim()?.split(" ")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size >= 2 }
