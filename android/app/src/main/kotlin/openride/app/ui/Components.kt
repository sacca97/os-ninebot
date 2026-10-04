package openride.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun Messages(error: String?, notice: String?, onDismiss: () -> Unit) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).clickable(onClick = onDismiss)) }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp).clickable(onClick = onDismiss)) }
}

/** Small inline spinner: the default is 40 dp, and setting only one dimension made it oval. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp)
}

@Composable
fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Spinner()
        Spacer(Modifier.padding(6.dp))
        Text(text)
    }
}

@Composable
fun LabelValue(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value ?: "…", fontFamily = FontFamily.Monospace)
    }
}
