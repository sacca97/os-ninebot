package com.sacca.openride.app.ui.device

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sacca.openride.app.ui.BusyRow
import com.sacca.openride.app.ui.Messages

@Composable
fun DeviceScreen(vm: DeviceViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by vm.ui.collectAsStateWithLifecycle()
    val cooldownUntil by vm.cooldownUntil.collectAsStateWithLifecycle()
    var hex by rememberSaveable { mutableStateOf("") }
    var confirmPair by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (st.info == null && st.busy == null) vm.probe() }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1024) // a credential file is ~33 bytes; never read more than this
                String(buf, 0, input.read(buf).coerceAtLeast(0), Charsets.US_ASCII)
            }
        } catch (e: Exception) { null }
        if (text == null) vm.report(error = "Could not read that file.") else vm.importCredential(text)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val pw = exported
        if (uri == null || pw == null) return@rememberLauncherForActivityResult
        val ok = try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write((pw + "\n").toByteArray()) } != null
        } catch (e: Exception) { false }
        exported = null
        if (ok) vm.report(notice = "Saved. The file holds the scooter password in clear text: keep it private.")
        else vm.report(error = "Could not write the file.")
    }

    val gate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) scope.launch { exported = vm.exportCredentialHex() }
    }
    val now by produceState(System.currentTimeMillis(), cooldownUntil) {
        while (true) { value = System.currentTimeMillis(); delay(500) }
    }
    val cooldown = ((cooldownUntil - now) / 1000).coerceAtLeast(0)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Messages(st.error, st.notice, vm::clearMessages)
        st.busy?.let { BusyRow(it) }
        st.info?.let {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    com.sacca.openride.core.profile.modelNameFromSerial(it.serial)?.let { model -> Text(model) }
                    Text("Serial: ${it.serial}")
                    Text("Password stored on scooter: ${if (it.passwordStored) "yes" else "no"}")
                    Text("Credential in this app: ${if (st.hasCredential) "yes" else "no"}")
                }
            }
        }
        if (st.info != null && st.busy == null) {
            if (st.hasCredential) {
                Button({ vm.openDashboard() }, enabled = cooldown == 0L, modifier = Modifier.fillMaxWidth()) {
                    Text(if (cooldown > 0) "Cool-down: ${cooldown}s" else "Log in and open dashboard")
                }
            }
            Text("Credential", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Import a 32-hex-character password: typed or pasted, or from a text file (the file `f2 keys --export` writes). " +
                    "It is checked with a real login first and only saved if the scooter accepts it. " +
                    "Nothing on the scooter is changed.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                hex, { hex = it }, label = { Text("Password (32 hex chars)") }, singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button({ if (vm.importCredential(hex)) hex = "" }) { Text("Import") }
                OutlinedButton({ openFile.launch(arrayOf("text/plain", "*/*")) }) { Text("From file") }
                if (st.hasCredential) {
                    OutlinedButton({ vm.forgetCredential() }) { Text("Forget") }
                    OutlinedButton({
                        val km = ctx.getSystemService(KeyguardManager::class.java)
                        @Suppress("DEPRECATION")
                        val i = km.createConfirmDeviceCredentialIntent("Export credential", "Confirm it's you to reveal the scooter password")
                        if (i == null) exportError = "Set a screen lock to export the credential." else { exportError = null; gate.launch(i) }
                    }) { Text("Export") }
                }
            }
            exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            OutlinedButton({ confirmPair = true }, enabled = st.pairingSupported) { Text("Pair new password") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmPair) AlertDialog(
        onDismissRequest = { confirmPair = false },
        title = { Text("Replace the scooter's password?") },
        text = {
            Text(
                "This sets a NEW password on the scooter: the official app (and any other tool) will stop being able to log in " +
                    "until you re-pair it. You will be asked to press the scooter's power button. Prefer importing the existing credential.",
            )
        },
        confirmButton = { TextButton({ confirmPair = false; vm.pair() }) { Text("Pair") } },
        dismissButton = { TextButton({ confirmPair = false }) { Text("Cancel") } },
    )
    exported?.let { pw ->
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text("Scooter password") },
            text = { Text(pw, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton({ saveFile.launch("scooter-key.txt") }) { Text("Save file") }
                TextButton({
                    val clip = ClipData.newPlainText("password", pw)
                    if (Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    exported = null
                }) { Text("Copy & close") }
            },
            dismissButton = { TextButton({ exported = null }) { Text("Close") } },
        )
    }
}
