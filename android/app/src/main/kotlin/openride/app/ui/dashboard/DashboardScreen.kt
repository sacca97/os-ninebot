package openride.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openride.app.ui.BusyRow
import openride.app.ui.LabelValue
import openride.app.ui.Messages
import openride.app.ui.settings.SettingsViewModel
import openride.core.registers.Registers

@Composable
fun DashboardScreen(vm: DashboardViewModel, settingsVm: SettingsViewModel = hiltViewModel()) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var confirmOff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.start() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Messages(st.error, st.notice, vm::clearMessages)
        st.busy?.let { BusyRow(it) }
        if (!st.connected && st.busy == null) {
            Text("Disconnected.", modifier = Modifier.padding(vertical = 8.dp))
            Button({ vm.start() }) { Text("Reconnect") }
        }
        if (st.connected) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        when (st.power) { true -> "Scooter is on"; false -> "Scooter is off"; null -> "Reading…" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (st.power == false) {
                        Text("The controller and battery boards don't answer while the scooter is off.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (st.power == true) {
                Section("Battery", listOf("batt_pct", "batt_v", "batt_a", "charging", "batt_health", "cell_mv", "cell_temp"), st.values)
                Section("Status", listOf("range", "range_pred", "mileage", "avg_speed", "mode", "temp"), st.values)
                Section("Settings (read-only)", listOf("kers", "tcs", "walk_mode"), st.values)
                Section("Diagnostics", listOf("error", "alarm", "status"), st.values)
                Section("Device", listOf("serial", "ctrl_fw", "ble_fw", "bms_fw"), st.values)
                if (settings.showExperimental) {
                    Text("Experimental (unverified)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Registers.all.filter { it.experimental }.forEach { LabelValue(it.label, st.values[it.key]) }
                }
            }
            if (settings.powerControl && st.power != null) {
                Text("Power", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("Powering on over Bluetooth leaves the scooter ready to ride.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button({ vm.setPower(true) }, enabled = !st.powerBusy && st.power == false) { Text("Power on") }
                    OutlinedButton({ confirmOff = true }, enabled = !st.powerBusy && st.power == true) { Text("Power off") }
                    if (st.powerBusy) CircularProgressIndicator(Modifier.height(24.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Power off?") },
        text = { Text("Only power off while the scooter is stopped. The speed is not checked by this app.") },
        confirmButton = { TextButton({ confirmOff = false; vm.setPower(false) }) { Text("Power off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, keys: List<String>, values: Map<String, String>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    keys.forEach { k -> LabelValue(Registers.all.first { it.key == k }.label, values[k]) }
}
