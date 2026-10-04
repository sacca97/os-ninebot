package openride.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openride.app.ui.BusyRow
import openride.app.ui.Messages
import openride.app.ui.settings.SettingsViewModel

/** Home: name, power, battery and range. Everything else is one tap away (battery and range open their own pages). */
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onBattery: () -> Unit,
    onRange: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.start() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Messages(st.error, st.notice, vm::clearMessages)
        st.busy?.let { BusyRow(it) }
        if (!st.connected && st.busy == null) {
            Text("Disconnected.", modifier = Modifier.padding(vertical = 8.dp))
            Button({ vm.start() }) { Text("Reconnect") }
        }
        if (st.connected) {
            val on = st.power == true
            val charging = st.values["charging"] == "yes"
            Text(st.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                when (st.power) { true -> if (charging) "On · charging" else "On"; false -> "Off"; null -> "Reading…" },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Tile("Battery", if (on) st.values["batt_pct"] else null, if (charging) "charging" else null, Modifier.weight(1f), onBattery)
                Tile("Range", if (on) st.values["range"] else null, null, Modifier.weight(1f), onRange)
            }
            if (settings.powerControl && st.power != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Button({ vm.setPower(true) }, enabled = !st.powerBusy && st.power == false, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Power on")
                    }
                    OutlinedButton({ vm.setPower(false) }, enabled = !st.powerBusy && st.power == true, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Power off")
                    }
                }
                if (st.powerBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Text("  Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(label: String, value: String?, extra: String?, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value ?: "—", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp))
            Text(extra ?: "Details ›", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
