package openride.app.ui.debug

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DebugScreen(vm: DebugViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val log by vm.events.collectAsStateWithLifecycle()
    var redact by remember { mutableStateOf(true) }
    var raw by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Redact"); Switch(redact, { redact = it })
        Text("Raw"); Switch(raw, { raw = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.clear() }) { Text("Clear") }
        OutlinedButton({
            val text = log.joinToString("\n") { it.format(redact, raw) }
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            ctx.startActivity(Intent.createChooser(send, "Export log"))
        }) { Text("Export") }
    }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        itemsIndexed(log) { _, e ->
            Text(e.format(redact, raw), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
