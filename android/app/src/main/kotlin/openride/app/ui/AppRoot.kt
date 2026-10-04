package openride.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openride.app.ui.dashboard.BatteryScreen
import openride.app.ui.dashboard.DashboardScreen
import openride.app.ui.dashboard.RideScreen
import openride.app.ui.dashboard.ScooterInfoScreen
import openride.app.ui.dashboard.DashboardViewModel
import openride.app.ui.debug.DebugScreen
import openride.app.ui.device.DeviceScreen
import openride.app.ui.device.DeviceViewModel
import openride.app.ui.nav.Navigator
import openride.app.ui.nav.Screen
import openride.app.ui.scan.ScanScreen
import openride.app.ui.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(navigator: Navigator) {
    val stack by navigator.stack.collectAsStateWithLifecycle()
    val top = stack.last()
    val device: DeviceViewModel = hiltViewModel()
    val dashboard: DashboardViewModel = hiltViewModel()
    val deviceUi by device.ui.collectAsStateWithLifecycle()
    val dashUi by dashboard.ui.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dashboard.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { dashboard.onForeground() }

    // The home screen is the root: back from it leaves the app (background handling then drops the link).
    BackHandler(enabled = stack.size > 1) {
        if (top == Screen.Device) device.cancel()
        navigator.pop()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    when (top) {
                        Screen.Scan -> "Add scooter"
                        Screen.Device -> deviceUi.name.ifEmpty { "Scooter" }
                        Screen.Dashboard -> dashUi.name.ifEmpty { "OpenRide" }
                        Screen.Battery -> "Battery"
                        Screen.Ride -> "Range and ride"
                        Screen.ScooterInfo -> "Scooter info"
                        Screen.Debug -> "Debug log"
                        Screen.Settings -> "Settings"
                    },
                )
            },
            actions = {
                if (top == Screen.Device) TextButton({ navigator.push(Screen.Debug) }) { Text("Log") }
                if (top == Screen.Dashboard) {
                    TextButton({ menuOpen = true }) { Text("More") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        if (!dashUi.noScooter) {
                            DropdownMenuItem({ Text("Scooter info") }, { menuOpen = false; navigator.push(Screen.ScooterInfo) })
                        }
                        DropdownMenuItem({ Text("Add scooter") }, { menuOpen = false; navigator.push(Screen.Scan) })
                        DropdownMenuItem({ Text("App settings") }, { menuOpen = false; navigator.push(Screen.Settings) })
                        DropdownMenuItem({ Text("Log") }, { menuOpen = false; navigator.push(Screen.Debug) })
                    }
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            when (top) {
                Screen.Scan -> ScanScreen()
                Screen.Device -> DeviceScreen(device)
                Screen.Dashboard -> DashboardScreen(dashboard, { navigator.push(Screen.Battery) }, { navigator.push(Screen.Ride) }, { navigator.push(Screen.Scan) })
                Screen.Battery -> BatteryScreen(dashboard)
                Screen.Ride -> RideScreen(dashboard)
                Screen.ScooterInfo -> ScooterInfoScreen(dashboard)
                Screen.Debug -> DebugScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}
