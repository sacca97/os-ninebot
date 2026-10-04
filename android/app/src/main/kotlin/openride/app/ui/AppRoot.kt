package openride.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openride.app.ui.dashboard.DashboardScreen
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

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { dashboard.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { dashboard.onForeground() }

    BackHandler(enabled = stack.size > 1) {
        when (top) {
            Screen.Dashboard -> dashboard.leave() // disconnects
            Screen.Device -> { device.cancel(); navigator.pop() }
            else -> navigator.pop()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    when (top) {
                        Screen.Scan -> "OpenRide (unofficial)"
                        Screen.Device -> deviceUi.name.ifEmpty { "Scooter" }
                        Screen.Dashboard -> "Dashboard"
                        Screen.Debug -> "Debug log"
                        Screen.Settings -> "Settings"
                    },
                )
            },
            actions = {
                if (top == Screen.Scan) TextButton({ navigator.push(Screen.Settings) }) { Text("Settings") }
                if (top == Screen.Device || top == Screen.Dashboard) TextButton({ navigator.push(Screen.Debug) }) { Text("Log") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            when (top) {
                Screen.Scan -> ScanScreen()
                Screen.Device -> DeviceScreen(device)
                Screen.Dashboard -> DashboardScreen(dashboard)
                Screen.Debug -> DebugScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}
