package app.pocketagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.ai.McpServer
import app.pocketagent.ai.McpStatus
import kotlinx.coroutines.launch

/**
 * The MCP servers part of Connections: tools from other services, added by the person, one server at a time.
 * A server is somebody else's: the screen says so, and says what it is sent.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun McpSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val settingsNow = g.settings.collectAsState().value
    val servers = settingsNow?.mcpServers.orEmpty()
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(mapOf<String, Pair<String, Boolean>>()) }
    val status by g.mcpStatus.collectAsState()
    // Opening this screen is the moment to find out which servers answer, once per start of the app.
    LaunchedEffect(servers.size) { g.checkMcp() }
    fun reconnect(s: McpServer) {
        scope.launch {
            val problem = g.connectMcp(s.id)
            notes = if (problem == null) notes - s.id else notes + (s.id to (problem to true))
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
    Text("MCP servers", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
    // Public servers the app knows of that are not in the list (never added, or removed): one tap adds them back.
    val suggested = remember(servers) { app.pocketagent.ai.McpPresets.ALL.filter { p -> servers.none { it.id == p.id || it.url.trimEnd('/') == p.url.trimEnd('/') } } }
    Text(
        "Tools from other services. Type @mcp in a chat to use one. A server sees what is sent to it, so keep only ones you trust; every call needs your OK.",
        Modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
    )

    servers.forEach { s ->
        val shown = open == s.id
        Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHigh) {
            Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = if (shown) "Hide the tools" else "Show the tools") { open = if (shown) null else s.id }.padding(vertical = 4.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (s.local) "On this computer: ${s.command}" else s.url, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.tools.count { it.on }} of ${s.tools.size} tools on" + (if (s.hasToken) " · token saved" else ""),
                            style = MaterialTheme.typography.labelSmall, color = cs.outline,
                        )
                    }
                    // Connected, or a button that connects: a server that is off, or did not answer, says so here.
                    val state = status[s.id] ?: McpStatus.UNKNOWN
                    when {
                        // One the app came with, not contacted yet: it is ready, and nothing is sent until a call is approved.
                        s.on && s.untouched && state == McpStatus.UNKNOWN -> Text("Ready", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        state == McpStatus.CHECKING -> LoadingIndicator(Modifier.padding(end = 10.dp).size(22.dp))
                        s.on && state == McpStatus.CONNECTED -> Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = cs.primary)
                            Text("Connected", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        }
                        s.on && state == McpStatus.FAILED -> FilledTonalIconButton({ reconnect(s) }, Modifier.padding(end = 6.dp).size(36.dp)) { Icon(Icons.Rounded.Refresh, "Reconnect ${s.name}", Modifier.size(20.dp)) }
                        !s.on -> FilledTonalIconButton({ reconnect(s) }, Modifier.padding(end = 6.dp).size(36.dp)) { Icon(Icons.Rounded.Link, "Connect ${s.name}", Modifier.size(20.dp)) }
                    }
                    // Switching one of the app's own servers back on does not contact it: its tools are already known.
                    Switch(s.on, { v -> if (v && !s.untouched) reconnect(s) else scope.launch { g.changeMcpServer(s.id) { it.copy(on = v) } } })
                }
                if (s.on && status[s.id] == McpStatus.FAILED && notes[s.id] == null) Text("Not connected. The server did not answer.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.error)
                if (s.untouched) Text("Comes with My PAI. Not contacted until you approve a call to it.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (s.wantsToken && !s.hasToken) Text("From a linked device. It needs its token typed in here: choose Edit.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
                notes[s.id]?.let { (text, bad) -> Text(text, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = if (bad) cs.error else cs.primary) }
                AnimatedVisibility(shown) {
                    Column {
                        s.tools.forEach { t ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Switch(t.on, { v -> scope.launch { g.changeMcpServer(s.id) { srv -> srv.copy(tools = srv.tools.map { if (it.name == t.name) it.copy(on = v) else it }) } } }, enabled = s.on)
                            }
                        }
                        // On a computer with the bridge on: whether linked devices may use this server through the pairing.
                        if (g.platform.isDesktop && settingsNow?.bridge == true) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lend to my devices", style = MaterialTheme.typography.bodyMedium)
                                Text("Your linked devices can call its tools.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Switch(s.lend, { v -> scope.launch { g.changeMcpServer(s.id) { it.copy(lend = v) } } }, enabled = s.on)
                        }
                        if (s.tools.isEmpty()) Text(if (s.checkedAt == 0L) "Connect it to see its tools." else "This server offers no tools.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Asks the server again: this both tests the connection and brings its list of tools up to date.
                            TextButton({
                                busy = s.id
                                scope.launch {
                                    val problem = g.connectMcp(s.id)
                                    notes = notes + (s.id to ((problem ?: "Connected. Tools are up to date.") to (problem != null)))
                                    busy = null
                                }
                            }, enabled = busy == null) { if (busy == s.id) LoadingIndicator(Modifier.size(20.dp)) else Text("Test and refresh") }
                            TextButton({ editing = s }, enabled = busy == null) { Text("Edit") }
                            TextButton({ scope.launch { g.removeMcpServer(s.id); notes = notes - s.id } }, enabled = busy == null) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
    FilledTonalButton({ editing = McpServer(id = java.util.UUID.randomUUID().toString().take(12), name = "") }, Modifier.padding(bottom = 6.dp)) { Text("Add an MCP server") }
    if (suggested.isNotEmpty()) {
        Text("Suggested", Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
        Text("Public servers that need no account. They are other people's services, not My PAI's.", Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        suggested.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text(p.about, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ scope.launch { g.addPreset(p.id) } }) { Text("Add") }
            }
        }
    }

    editing?.let { McpDialog(it, isNew = servers.none { s -> s.id == it.id }) { editing = null } }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpDialog(initial: McpServer, isNew: Boolean, onDone: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val desktop = g.platform.isDesktop
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var command by remember { mutableStateOf(initial.command) }
    var local by remember { mutableStateOf(initial.local) }
    var token by remember { mutableStateOf("") }
    var dropToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            val server = initial.copy(name = name, url = if (local) "" else url, command = if (local) command else "")
            // An empty box keeps the token there is; the switch below removes it.
            error = g.saveMcpServer(server, if (local || dropToken) "" else token.takeIf { it.isNotBlank() } ?: if (isNew) "" else null)
            busy = false
            if (error == null) onDone()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(if (isNew) "Add an MCP server" else "Edit ${initial.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(40); error = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("What you will call it") }, singleLine = true, enabled = !busy, shape = MaterialTheme.shapes.large)
                // Only a computer can run a server as a program of its own; a phone only reaches servers by their address.
                if (desktop) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A program on this computer", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(local, { local = it; error = null }, enabled = !busy)
                }
                if (local) OutlinedTextField(
                    command, { command = it; error = null }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Command") }, placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem /home/me/notes") },
                    singleLine = true, enabled = !busy, shape = MaterialTheme.shapes.large, isError = error != null,
                    supportingText = { Text(error ?: "Starts when a tool is needed, stops when the app closes.") },
                ) else {
                    OutlinedTextField(
                        url, { url = it.trim(); error = null }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Address") }, placeholder = { Text("https://example.com/mcp") },
                        singleLine = true, enabled = !busy, shape = MaterialTheme.shapes.large, isError = error != null, supportingText = { Text(error ?: "Must start with https://") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    OutlinedTextField(
                        token, { token = it.trim(); dropToken = false }, Modifier.fillMaxWidth().padding(top = 2.dp), label = { Text(if (initial.hasToken) "New token (empty keeps the saved one)" else "Token (optional)") },
                        singleLine = true, enabled = !busy && !dropToken, shape = MaterialTheme.shapes.large, visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Encrypted, and kept on this device only.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                    if (initial.hasToken) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Remove the saved token", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(dropToken, { dropToken = it; if (it) token = "" }, enabled = !busy)
                    }
                }
            }
        },
        // Saving asks the server for its tools first, so a server that cannot be reached is never saved.
        confirmButton = { TextButton(::save, enabled = !busy && name.isNotBlank() && (if (local) command.isNotBlank() else url.isNotBlank())) { if (busy) LoadingIndicator(Modifier.size(22.dp)) else Text(if (isNew) "Connect" else "Save") } },
        dismissButton = { TextButton(onDone, enabled = !busy) { Text("Cancel") } },
    )
}
