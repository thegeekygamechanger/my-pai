package app.pocketagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.LocalGraph
import app.pocketagent.sync.ShareClient
import androidx.compose.ui.draw.alpha
import app.pocketagent.sync.ShareCode
import app.pocketagent.sync.ShareHost
import app.pocketagent.sync.SyncCode
import app.pocketagent.sync.SyncEngine
import kotlinx.coroutines.launch

/** A code as people read it: grouped, in a typewriter face, selectable. */
@Composable
fun CodeText(code: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(SyncCode.pretty(code), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), textAlign = TextAlign.Center)
    }
}

/** A text field for a code, with a camera button where there is a camera. */
@Composable
private fun CodeEntry(value: String, onValue: (String) -> Unit, error: String?, enabled: Boolean) {
    val g = LocalGraph.current
    val scan = g.platform.rememberQrScanner { text -> if (text != null) onValue(text) }
    OutlinedTextField(
        value, { onValue(it.take(80)) }, Modifier.fillMaxWidth(), label = { Text("Code") }, singleLine = true, enabled = enabled, isError = error != null,
        supportingText = { if (error != null) Text(error) }, shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        trailingIcon = { if (scan != null) IconButton(scan, enabled = enabled) { Icon(Icons.Rounded.QrCodeScanner, "Scan the QR code") } },
    )
}

/** Links this device to ones that already sync, with a typed or scanned code. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JoinSyncDialog(onDismiss: () -> Unit, onJoined: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Link this device") },
        text = {
            Column {
                Text("On the device already set up, open Settings, Sync, and use the code shown.", Modifier.padding(bottom = 14.dp), style = MaterialTheme.typography.bodyMedium)
                CodeEntry(code, { code = it; error = null }, error, !busy)
            }
        },
        confirmButton = {
            TextButton({
                val parsed = SyncCode.parse(code)
                if (parsed == null || parsed.second == SyncCode.SHARE) { error = "That is not a sync code. Check it and try again."; return@TextButton }
                busy = true
                scope.launch {
                    g.sync.join(code)
                    busy = false
                    onJoined()
                }
            }, enabled = code.isNotBlank() && !busy) { if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Link") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** The Sync part of Settings: turn it on, show the code, see and remove linked devices. */
@Composable
fun SyncSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val status by g.sync.status.collectAsState()
    val settings by g.settings.collectAsState()
    var joining by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val code = remember(status.enabled) { g.sync.code() }
    val devices = settings?.devices.orEmpty().filter { !it.removed }

    if (!status.enabled || code == null) {
        Text("Keep your chats, files and keys the same on your phone and computer, live.", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        if (status.error.isNotEmpty()) Text(status.error, Modifier.padding(start = 4.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.error)
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ scope.launch { g.sync.enable(); showCode = true } }) { Text("Turn on") }
            OutlinedButton({ joining = true }) { Text("Link with a code") }
        }
    } else {
        Row(Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val dot by animateColorAsState(if (status.online) cs.primary else cs.outline, label = "dot")
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(
                when {
                    !status.online -> "Connecting…"
                    status.peers.isEmpty() -> "On. No other device is open right now."
                    else -> "Live with ${status.peers.size} other device${if (status.peers.size == 1) "" else "s"}"
                },
                Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge,
            )
        }
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (d.system == "android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, null, Modifier.padding(start = 4.dp).size(20.dp), tint = cs.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(d.name.ifBlank { "Device" } + if (d.id == settings?.deviceId) " (this device)" else "", style = MaterialTheme.typography.bodyMedium)
                    if (d.id != settings?.deviceId) Text(if (d.id in status.peers) "Open now" else "Not open", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (d.id != settings?.deviceId) IconButton({ scope.launch { g.sync.removeDevice(d.id) } }) { Icon(Icons.Rounded.Close, "Remove ${d.name}", tint = cs.onSurfaceVariant) }
            }
        }
        if (devices.size >= SyncEngine.MAX_DEVICES) Text("Five devices are linked, which is the most. Remove one to add another.", Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        AnimatedContent(showCode, label = "code") { open ->
            if (open) Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(SyncCode.qr(code, SyncCode.SYNC), 200.dp)
                CodeText(code, Modifier.padding(top = 12.dp))
                Text("Scan it, or type the code on the other device. Anyone with the code can read your chats: show it only to your own devices.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            } else Box(Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (devices.size < SyncEngine.MAX_DEVICES) FilledTonalButton({ showCode = !showCode }) { Text(if (showCode) "Hide code" else "Add a device") }
            OutlinedButton({ leaving = true }) { Text("Unlink this device") }
        }
    }
    if (joining) JoinSyncDialog({ joining = false }, { joining = false })
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("Unlink this device?") },
        text = { Text("It stops syncing with your other devices. What is already on it stays.") },
        confirmButton = { TextButton({ leaving = false; scope.launch { g.sync.leave() } }) { Text("Unlink") } },
        dismissButton = { TextButton({ leaving = false }) { Text("Cancel") } },
    )
}

/** Shares one chat. The chat is handed over while this stays open, because nothing is kept on a server. */
@Composable
fun ShareChatDialog(chatId: Long, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    // A code works for a few minutes; "New code" starts again with a fresh one.
    var round by remember { mutableStateOf(0) }
    // The share belongs to the app, not to this window: closing the window to send the code does not end it.
    val host = remember(chatId, round) { g.share(chatId, fresh = round > 0) }
    // Copied at once, ready to paste into a message to the other person.
    val shareText = remember(host) { ShareCode.text(host.code) }
    LaunchedEffect(host) { g.platform.copyToClipboard(shareText) }
    val served by host.served.collectAsState()
    val expired by host.expired.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share this chat") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                SelectionContainer {
                    Text(
                        host.code, Modifier.padding(vertical = 10.dp).alpha(if (expired) 0.35f else 1f),
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp), textAlign = TextAlign.Center,
                    )
                }
                Text(
                    when {
                        expired -> "This code has stopped working. Make a new one to share again."
                        served == 0 -> "Copied. Whoever enters it in My PAI's message box gets this chat, and only this chat. It works for ten minutes while My PAI is open here."
                        else -> "Opened $served time${if (served == 1) "" else "s"}."
                    },
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        dismissButton = { if (expired) TextButton({ round++ }) { Text("New code") } else TextButton({ g.platform.copyToClipboard(shareText) }) { Text("Copy again") } },
    )
}

/** Receives a chat someone shared. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenSharedDialog(onDismiss: () -> Unit, onOpened: (Long) -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Open a shared chat") },
        text = {
            Column {
                Text("Enter the four-character code while the sharer still has it on screen.", Modifier.padding(bottom = 14.dp), style = MaterialTheme.typography.bodyMedium)
                CodeEntry(code, { code = it; error = null }, error, !busy)
            }
        },
        confirmButton = {
            TextButton({
                val parsed = ShareCode.parse(code)
                if (parsed == null) { error = "That is not a share code. Check it and try again."; return@TextButton }
                busy = true
                scope.launch {
                    val id = runCatching { ShareClient.open(g, parsed) }.getOrNull()
                    busy = false
                    if (id == null) error = "Could not reach the sharing device. Ask them to keep the share screen open, then try again." else onOpened(id)
                }
            }, enabled = code.isNotBlank() && !busy) { if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Open") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
