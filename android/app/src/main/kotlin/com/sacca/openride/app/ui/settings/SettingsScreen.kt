package com.sacca.openride.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val st by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingRow("Enable power on/off", "Sends the same two frames as the official app. Whether \"off\" is a real lock is unverified.", st.powerControl, vm::setPowerControl)
        SettingRow("Show experimental readings", "Registers whose decoding is unverified.", st.showExperimental, vm::setShowExperimental)
        Text(
            "Privacy: no account, no network access, no analytics. The scooter password is stored encrypted with an Android Keystore key and excluded from backups.",
            modifier = Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "OpenRide is unofficial, not affiliated with Segway-Ninebot. Use at your own risk.",
            modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}
