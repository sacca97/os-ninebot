package openride.app.ui.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openride.app.ui.Messages

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(c: Context) =
    requiredPermissions().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

private fun bluetoothOn(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

@Composable
fun ScanScreen(vm: ScanViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val st by vm.ui.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    var btOn by remember { mutableStateOf(bluetoothOn(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(ctx)
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = bluetoothOn(ctx)
    }

    Text(
        "Unofficial open-source client for the Segway F2 Pro. Not affiliated with the manufacturer; use at your own risk. " +
            "The scooter accepts one app at a time, so close the official app first.",
        modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
    )
    if (!granted) {
        Text(
            if (Build.VERSION.SDK_INT >= 31) "Bluetooth permission is needed to find and connect to your scooter. It is not used for location."
            else "Android 11 and older require the location permission (and location services switched on) to scan for Bluetooth devices. Your location is not used or stored.",
        )
        Button({ permLauncher.launch(requiredPermissions()) }, Modifier.padding(top = 8.dp)) { Text("Grant permission") }
        return
    }
    if (!btOn) {
        Text("Bluetooth is off.")
        Button({ enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, Modifier.padding(top = 8.dp)) { Text("Turn on Bluetooth") }
        return
    }
    LaunchedEffect(Unit) { vm.start() }
    Messages(st.error, null) {}
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (st.scanning) CircularProgressIndicator(Modifier.padding(end = 12.dp).height(24.dp))
        OutlinedButton({ vm.rescan() }) { Text(if (st.scanning) "Rescan" else "Scan") }
    }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        items(st.scans, key = { it.address }) { ad ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.select(ad) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(ad.name, style = MaterialTheme.typography.titleMedium)
                    Text("${ad.address}  ·  ${ad.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (st.scans.isEmpty()) item { Text(if (st.scanning) "Looking for scooters…" else "No scooter found.") }
    }
}
