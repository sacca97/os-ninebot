package openride.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import openride.app.ui.AppRoot
import openride.app.ui.OpenRideTheme
import openride.app.ui.nav.Navigator
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var navigator: Navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Background / foreground handling lives in AppRoot -> DashboardViewModel.onBackground/onForeground
        // (polling pauses at once, the link is dropped after a grace period). There is deliberately no immediate disconnect here.
        setContent { OpenRideTheme { Surface(Modifier.fillMaxSize()) { AppRoot(navigator) } } }
    }
}
