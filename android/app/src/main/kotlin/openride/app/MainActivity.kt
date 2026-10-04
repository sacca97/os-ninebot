package openride.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dagger.hilt.android.AndroidEntryPoint
import openride.app.ui.AppRoot
import openride.app.ui.dashboard.DashboardViewModel
import openride.app.ui.nav.Navigator
import openride.app.ui.nav.Screen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var navigator: Navigator
    private val dashboard: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stop polling and disconnect when backgrounded; reconnect when the dashboard is foregrounded again.
        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (navigator.top == Screen.Dashboard) {
                if (e == Lifecycle.Event.ON_STOP) dashboard.stop()
                if (e == Lifecycle.Event.ON_START) dashboard.start()
            }
        })
        setContent { MaterialTheme { Surface { AppRoot(navigator) } } }
    }
}
