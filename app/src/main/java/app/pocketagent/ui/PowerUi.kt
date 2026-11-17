package app.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.LocalGraph
import app.pocketagent.ai.arr
import app.pocketagent.ai.formatTokens
import app.pocketagent.ai.str
import app.pocketagent.sync.ShareCode
import app.pocketagent.sync.SharedChat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The activity log: every tool call made on this device, newest first, and what each tool has cost. */
@Composable
fun ActivitySection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    var round by remember { mutableIntStateOf(0) }
    val calls = remember(round) { g.activity.recent(200) }
    val totals = remember(round) { g.activity.byTool().take(8) }
    val time = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Text("Every tool call on this device. Kept here only; never synced.", Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    if (calls.isEmpty()) { Text("No tool has been used yet.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodyLarge); return }
    Text("By tool", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
    totals.forEach { t ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.calls} · about ${formatTokens(t.tokens)}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
    Text("Counts are calls; tokens are an estimate of what each added to its chat.", Modifier.padding(start = 4.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
    calls.forEach { c ->
        var open by remember(c.at, c.tool) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (c.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, if (c.ok) "Done" else "Failed", Modifier.size(16.dp), tint = if (c.ok) cs.primary else cs.error)
                Text((if (c.server.isEmpty()) "" else c.server + ": ") + c.tool, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time.format(Date(c.at)), style = MaterialTheme.typography.labelSmall, color = cs.outline)
            }
            if (c.from.isNotEmpty()) Text("Asked by ${c.from}", Modifier.padding(start = 24.dp), style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
            Text(c.summary, Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = if (open) 8 else 1, overflow = TextOverflow.Ellipsis)
            if (open) Text("Sent: ${c.args}", Modifier.padding(start = 24.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
    }
    OutlinedButton({ g.activity.clear(); round++ }, Modifier.padding(top = 10.dp)) { Text("Clear the log") }
}

/** Choosing the model to carry on with when a chat's own service fails or has had enough. */
@Composable
fun BackupModelRow() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s = g.settings.collectAsState().value ?: return
    var picking by remember { mutableStateOf(false) }
    val current = g.provider(s.backupProvider)?.takeIf { s.backupModel.isNotBlank() }?.let { "${it.name}: ${s.backupModel}" }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { picking = true }.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Backup model", style = MaterialTheme.typography.bodyLarge)
            Text(current ?: "None. If a service fails or hits its limit, the reply stops.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (current == null) "Choose" else "Change", style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
    if (picking) AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text("Backup model") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Used when a chat's own service fails, is busy or has run out. You are told when it happens.", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                g.connected().forEach { p ->
                    Text(p.name, Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    g.models(p.id).take(30).forEach { m ->
                        val chosen = s.backupProvider == p.id && s.backupModel == m.id
                        Text(
                            m.name.ifBlank { m.id }, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picking = false; scope.launch { g.settingsStore.update { it.copy(backupProvider = p.id, backupModel = m.id) } } }.padding(horizontal = 8.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium, color = if (chosen) cs.primary else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton({ picking = false }) { Text("Close") } },
        dismissButton = { if (current != null) TextButton({ picking = false; scope.launch { g.settingsStore.update { it.copy(backupProvider = "", backupModel = "") } } }) { Text("No backup") } },
    )
}

/**
 * The bridge between the person's own devices. On a computer: the switch that lends marked servers to linked devices.
 * On any device: what the linked computers are lending right now.
 */
@Composable
fun BridgeSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s = g.settings.collectAsState().value ?: return
    val lent by g.bridge.lent.collectAsState()
    val sync by g.sync.status.collectAsState()
    val here = lent.filterKeys { it in sync.peers }
    if (!g.platform.isDesktop && here.isEmpty()) return
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
    Text("Your own devices", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
    if (g.platform.isDesktop) {
        SwitchRow(
            "Lend tools to my linked devices",
            if (!sync.enabled) "Needs Sync to be on. Your phone could then use the servers you mark on this computer."
            else "Your linked devices can call the servers you mark below as lent. Each call is asked about on the device that makes it.",
            s.bridge, enabled = sync.enabled || s.bridge,
        ) { v -> scope.launch { g.settingsStore.update { it.copy(bridge = v) } } }
        if (s.bridge && s.mcpServers.none { it.lend && it.on }) Text("No server is lent yet. Open a server above and switch on \"Lend to my devices\".", Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    here.values.forEach { tools ->
        val first = tools.firstOrNull() ?: return@forEach
        Surface(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(first.deviceName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = cs.primary)
                    Text("Connected", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
                Text("Lends ${tools.size} tool${if (tools.size == 1) "" else "s"}: " + tools.joinToString(", ") { it.tool }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** My PAI as an MCP server for other programs on this computer: off by default, read-only, every call asked about. */
@Composable
fun ServeSection() {
    val g = LocalGraph.current
    if (!g.platform.isDesktop) return
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s = g.settings.collectAsState().value ?: return
    val problem by g.serveProblem.collectAsState()
    var round by remember { mutableIntStateOf(0) }
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
    Text("Other programs on this computer", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
    SwitchRow("Let them read My PAI", "Other agents can read your files, memory notes and tasks. Read-only, this computer only, and each request asks you.", s.serve) { v -> scope.launch { g.settingsStore.update { it.copy(serve = v) } } }
    if (!s.serve) return
    if (problem.isNotEmpty()) { Text(problem, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.error); return }
    val token = remember(round, s.serve) { g.serveToken.orEmpty() }
    val address = "http://127.0.0.1:${s.servePort}/mcp"
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Address", style = MaterialTheme.typography.labelMedium, color = cs.outline)
            SelectionContainer { Text(address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
            Text("Token (sent as \"Authorization: Bearer …\")", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = cs.outline)
            Text(if (token.length > 12) token.take(6) + "…" + token.takeLast(4) else "", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ g.platform.copyToClipboard(address) }) { Text("Copy address") }
                TextButton({ g.platform.copyToClipboard(token) }) { Text("Copy token") }
                TextButton({ g.newServeToken(); round++ }) { Text("New token") }
            }
        }
    }
}

/** Asks the person whether another program may read something, when My PAI is serving. Shown over whatever screen is open. */
@Composable
fun ServeAskDialog() {
    val g = LocalGraph.current
    val ask = g.serveAsk.collectAsState().value ?: return
    AlertDialog(
        onDismissRequest = { ask.answer.complete(0) },
        title = { Text("Another program is asking") },
        text = { Text("A program on this computer wants to ${ask.what}. It can only read, and only what you allow.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ ask.answer.complete(2) }) { Text("For 10 minutes") }
                FilledTonalButton({ ask.answer.complete(1) }) { Text("Allow once") }
            }
        },
        dismissButton = { TextButton({ ask.answer.complete(0) }) { Text("Deny") } },
    )
}

/** Handing a setup to someone: servers, routines and instructions under a four-character code. Never a token or a key. */
@Composable
fun ShareSetupSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val s = g.settings.collectAsState().value ?: return
    val remote = s.mcpServers.count { !it.local }
    if (remote == 0 && s.schedules.isEmpty() && s.customInstructions.isBlank()) return
    var asking by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf<app.pocketagent.sync.ShareHost?>(null) }
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
    Text("Share your setup", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
    Text("Hand your servers, routines and instructions to someone with a code. Tokens and keys are never included.", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    FilledTonalButton({ asking = true }) { Text("Share with a code") }
    if (asking) {
        var servers by remember { mutableStateOf(remote > 0) }
        var routines by remember { mutableStateOf(s.schedules.isNotEmpty()) }
        var instructions by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("What to share") },
            text = {
                Column {
                    if (remote > 0) SwitchRow("MCP servers ($remote)", "Names and addresses. No tokens.", servers) { servers = it }
                    if (s.schedules.isNotEmpty()) SwitchRow("Tasks and routines (${s.schedules.size})", "What each one does, not when it runs.", routines) { routines = it }
                    if (s.customInstructions.isNotBlank()) SwitchRow("Your instructions", "The standing instructions from Settings.", instructions) { instructions = it }
                }
            },
            confirmButton = { TextButton({ asking = false; host = g.shareSetup(g.setupBundle(servers, routines, instructions)) }, enabled = servers || routines || instructions) { Text("Make a code") } },
            dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
        )
    }
    host?.let { h ->
        val expired by h.expired.collectAsState()
        val served by h.served.collectAsState()
        AlertDialog(
            onDismissRequest = { host = null },
            title = { Text("Share your setup") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SelectionContainer {
                        Text(h.code, Modifier.padding(vertical = 10.dp).alpha(if (expired) 0.35f else 1f), style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 8.sp), textAlign = TextAlign.Center)
                    }
                    Text(
                        if (expired) "This code has stopped working." else if (served == 0) "Whoever enters it in My PAI's message box is offered your setup. It works for ten minutes while My PAI is open here." else "Opened $served time${if (served == 1) "" else "s"}.",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            },
            confirmButton = { TextButton({ host = null }) { Text("Done") } },
            dismissButton = { TextButton({ g.platform.copyToClipboard(ShareCode.text(h.code)) }) { Text("Copy") } },
        )
    }
}

/** A setup someone shared: says what is in it, and adds it only if the person agrees. */
@Composable
fun SetupOfferDialog(shared: SharedChat, onDone: (String?) -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val setup = shared.setup ?: return
    val servers = setup.arr("servers").orEmpty().size
    val routines = setup.arr("routines").orEmpty().size
    val theirs = setup.str("instructions").orEmpty()
    var instructions by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (shared.by.isEmpty()) "Add this shared setup?" else "Add the setup ${shared.by} shared?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    listOfNotNull("$servers MCP server${if (servers == 1) "" else "s"}".takeIf { servers > 0 }, "$routines task${if (routines == 1) "" else "s"} or routine${if (routines == 1) "" else "s"}".takeIf { routines > 0 }).joinToString(" and ").ifEmpty { "Instructions only" } +
                        ". Servers arrive switched off and without tokens: nothing is contacted until you connect one. Routines run only when you start them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (theirs.isNotBlank()) {
                    SwitchRow("Add their instructions under mine", theirs.take(160), instructions) { instructions = it }
                }
            }
        },
        confirmButton = { TextButton({ scope.launch { onDone(g.addSetup(setup, instructions)) } }) { Text("Add") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Not now") } },
    )
}

/** The app's own tools, in a few groups, each with a switch: what the assistant may use on this device. */
@Composable
fun ToolsSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s = g.settings.collectAsState().value ?: return
    val media = remember(s.sharedRev) { g.media.specs().isNotEmpty() }
    Text("Built-in tools", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
    Text("What the assistant can use on this device. Switch off what you do not want; switch it back on any time.", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    app.pocketagent.ai.Tools.GROUPS.filter { it.id != "media" || media }.forEach { group ->
        SwitchRow(group.name, group.about, group.id !in s.toolsOff) { on ->
            scope.launch { g.settingsStore.update { st -> st.copy(toolsOff = if (on) st.toolsOff - group.id else (st.toolsOff + group.id).distinct()) } }
        }
    }
}
