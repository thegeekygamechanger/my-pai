package app.pocketagent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import app.pocketagent.ai.AutoModel
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.Providers
import app.pocketagent.data.Chat
import kotlinx.coroutines.launch

val EFFORT_LABELS = mapOf("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "X-High", "max" to "Max")
val EFFORT_HELP = mapOf(
    "low" to "Quickest and cheapest. Good for simple questions.",
    "medium" to "A balanced default for everyday use.",
    "high" to "Thinks harder. Good for tricky problems and agent work.",
    "xhigh" to "Extra thorough. Slower and uses more tokens.",
    "max" to "The most it can do. Slowest and most expensive.",
)
val PERMISSION_HELP = mapOf(
    "ask" to "Asks before every action, including reading files.",
    "auto_read" to "Reads and searches freely. Asks before writing, editing or deleting.",
    "full_auto" to "Does everything without asking, including deleting files in the workspace.",
)

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f))
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** Picks a service and one of its models. With hundreds of models on some services, it has a search box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(provider: String, model: String, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val settings by g.settings.collectAsState()
    val connected = remember(settings) { g.connected() }
    var current by remember { mutableStateOf(connected.firstOrNull { it.id == provider }?.id ?: connected.firstOrNull()?.id.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val all = remember(current, settings) { g.models(current) }
    val shown = remember(all, query) { (if (query.isBlank()) all else all.filter { it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true) }).take(200) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            SectionLabel("Model")
            if (connected.size > 1) Choices(connected.map { it.id to it.name }, current, { current = it; query = "" }, Modifier.padding(bottom = 10.dp))
            if (all.size > 8 || query.isNotEmpty()) OutlinedTextField(
                query, { query = it.take(80) }, Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true, shape = CircleShape,
                placeholder = { Text("Search ${all.size} models") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Auto comes first: the app picks the service's quick, everyday or strongest model for each message.
                if (query.isBlank() && all.isNotEmpty()) item(key = "auto") {
                    val sel = model == AutoModel.ID && current == provider
                    val bg by animateColorAsState(if (sel) cs.primaryContainer else cs.surfaceContainerHigh, label = "auto")
                    Surface(onClick = { onPick(current, AutoModel.ID) }, shape = MaterialTheme.shapes.large, color = bg, modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(end = 12.dp).size(22.dp), tint = if (sel) cs.onPrimaryContainer else cs.primary)
                            Column(Modifier.weight(1f)) {
                                Text(AutoModel.NAME, style = MaterialTheme.typography.titleMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurface)
                                Text("Picks the right model for each message", style = MaterialTheme.typography.bodySmall, color = (if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant).copy(alpha = 0.85f))
                            }
                            if (sel) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(22.dp), tint = cs.primary)
                        }
                    }
                }
                items(shown, key = { it.id }) { m ->
                    val sel = m.id == model && current == provider
                    val bg by animateColorAsState(if (sel) cs.primaryContainer else cs.surfaceContainerHigh, label = "model")
                    Surface(onClick = { onPick(current, m.id) }, shape = MaterialTheme.shapes.large, color = bg, modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name.ifBlank { m.id }, style = MaterialTheme.typography.titleMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val price = if (current == Providers.ANTHROPIC) ModelCaps.price(m.id)?.let { (i, o) -> "$${trim(i)} in / $${trim(o)} out per million tokens" } else null
                                val sub = listOfNotNull(ModelCaps.tagline(m.id).takeIf { current == Providers.ANTHROPIC && it.isNotEmpty() }, price, m.id.takeIf { it != m.name && current != Providers.ANTHROPIC }).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = (if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant).copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (sel) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(22.dp), tint = cs.primary)
                        }
                    }
                }
                // A model the list does not have (or a service with no list) can be named by hand.
                if (query.isNotBlank() && shown.none { it.id == query.trim() }) item(key = "manual") {
                    Surface(onClick = { onPick(current, query.trim()) }, shape = MaterialTheme.shapes.large, color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text("Use \"${query.trim()}\" as the model name", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer)
                    }
                }
                if (all.isEmpty() && query.isBlank()) item(key = "empty") {
                    Text("This service did not give a list. Type the model's name above.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            if (all.isEmpty() && query.isEmpty()) OutlinedTextField(
                query, { query = it.take(80) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, shape = CircleShape, placeholder = { Text("Model name") },
            )
        }
    }
}

/** Model, effort and tools for one chat. Changes apply from the next message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsSheet(chat: Chat, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val settings by g.settings.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val provider = g.provider(chat.provider)
    val server = provider != null && Providers.serverTools(provider)
    fun save(c: Chat) {
        if (settings?.haptics != false) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { g.dao.update(c) }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            SectionLabel("Model")
            Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.large, color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (chat.model == AutoModel.ID) AutoModel.NAME else g.models(chat.provider).firstOrNull { it.id == chat.model }?.name?.ifBlank { null } ?: chat.model.ifBlank { "Choose a model" }, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(provider?.name ?: "Service not set up", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            }

            val efforts = provider?.let { Providers.efforts(it, chat.model) }.orEmpty()
            if (efforts.isNotEmpty()) {
                SectionLabel("Effort", Modifier.padding(top = 18.dp))
                val current = chat.effort.takeIf { it in efforts } ?: if (chat.effort in listOf("xhigh", "max")) efforts.last() else "medium"
                Segmented(efforts.map { it to (if (efforts.size > 4 && it == "medium") "Med" else EFFORT_LABELS[it] ?: it) }, current, { save(chat.copy(effort = it)) }, Modifier.fillMaxWidth())
                Text(EFFORT_HELP[current].orEmpty(), Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            SectionLabel("Tools", Modifier.padding(top = 18.dp))
            SwitchRow("Web search", if (server) "Search the web and open pages." else "Available with Claude models.", chat.web && server, enabled = server) { save(chat.copy(web = it)) }
            SwitchRow("Memory", "Work mode: keep notes that carry over between chats.", chat.memory, enabled = chat.agent) { save(chat.copy(memory = it)) }
            if (server && ModelCaps.fast(chat.model)) SwitchRow("Fast mode", "Much faster output at about twice the price.", chat.fast) { save(chat.copy(fast = it)) }
            SwitchRow("Show the working", "Show the reasoning, searches and other steps behind each reply.", settings?.showWork == true) { v -> scope.launch { g.settingsStore.update { it.copy(showWork = v) } } }

            if (chat.agent) {
                SectionLabel("Work permissions", Modifier.padding(top = 18.dp))
                Segmented(listOf("ask" to "Ask all", "auto_read" to "Ask to change", "full_auto" to "Full auto"), chat.permission, { save(chat.copy(permission = it)) }, Modifier.fillMaxWidth())
                Text(PERMISSION_HELP[chat.permission].orEmpty(), Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (chat.permission == "full_auto") cs.error else cs.onSurfaceVariant)
            }
        }
    }
    if (picking) ModelPicker(chat.provider, chat.model, onPick = { p, m -> picking = false; save(chat.copy(provider = p, model = m)) }, onDismiss = { picking = false })
}

fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.US, "%.2f", d)
