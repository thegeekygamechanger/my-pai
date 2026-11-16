package app.pocketagent.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import app.pocketagent.ai.Scheduler
import app.pocketagent.data.Schedule
import kotlinx.coroutines.launch
import java.util.UUID

private val DAYS = listOf("1" to "Mon", "2" to "Tue", "3" to "Wed", "4" to "Thu", "5" to "Fri", "6" to "Sat", "7" to "Sun")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(initial: Schedule?, onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var agent by remember { mutableStateOf(initial?.agent ?: false) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: "daily") }
    var weekday by remember { mutableStateOf((initial?.weekday ?: 1).toString()) }
    val time = rememberTimePickerState(initial?.hour ?: 9, initial?.minute ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New scheduled task" else "Edit task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                OutlinedTextField(
                    prompt, { prompt = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("What should it do?") }, minLines = 3, maxLines = 6, shape = MaterialTheme.shapes.large,
                    placeholder = { Text("For example: summarise today's top technology news in five points.") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Segmented(listOf(false to "Chat", true to "Work"), agent, { agent = it }, Modifier.fillMaxWidth(), icons = mapOf(false to Icons.Rounded.ChatBubbleOutline, true to Icons.Rounded.AutoAwesome))
                TimeInput(time)
                Choices(listOf("once" to "Once", "daily" to "Every day", "weekdays" to "Weekdays", "weekly" to "Weekly"), repeat, { repeat = it })
                if (repeat == "weekly") Choices(DAYS, weekday, { weekday = it })
            }
        },
        confirmButton = {
            TextButton({
                onSave((initial ?: Schedule(UUID.randomUUID().toString(), "", "")).copy(
                    title = title.trim().ifEmpty { prompt.trim().take(40) }, prompt = prompt.trim(), agent = agent, hour = time.hour, minute = time.minute,
                    repeat = repeat, weekday = weekday.toInt(), enabled = true, lastRun = 0, createdAt = System.currentTimeMillis(),
                ))
            }, enabled = prompt.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Tasks that run by themselves at a set time and leave their result as a chat. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SchedulesScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val wide = LocalWide.current
    val scope = rememberCoroutineScope()
    val settings by g.settings.collectAsState()
    val list = settings?.schedules.orEmpty()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun save(change: (List<Schedule>) -> List<Schedule>) { scope.launch { g.settingsStore.update { it.copy(schedules = change(it.schedules)) }; g.scheduler.arm() } }

    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(if (wide) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, if (wide) "Close" else "Back") }
            Text("Scheduled tasks", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
            FilledTonalIconButton({ adding = true }, Modifier.padding(end = 6.dp)) { Icon(Icons.Rounded.Add, "New scheduled task") }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (list.isEmpty()) Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(88.dp).background(cs.secondaryContainer, MaterialShapes.Clover4Leaf.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(34.dp), tint = cs.onSecondaryContainer)
                }
                Text("Nothing scheduled", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (g.platform.isDesktop) "Have a chat or a piece of work run by itself at a set time. On a computer, tasks run while the app is open."
                    else "Have a chat or a piece of work run by itself at a set time. The result arrives as a chat, with a notification.",
                    Modifier.padding(top = 6.dp).widthIn(max = 360.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            } else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { s ->
                    Surface(onClick = { editing = s }, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth().animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f))) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (s.agent) Icons.Rounded.AutoAwesome else Icons.Rounded.ChatBubbleOutline, null, Modifier.size(22.dp), tint = cs.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Scheduler.describe(s) + if (s.repeat == "once" && s.lastRun > 0) " · done" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            IconButton({ scope.launch { g.scheduler.runNow(s.id)?.let(onOpenChat) } }) { Icon(Icons.Rounded.PlayArrow, "Run ${s.title} now") }
                            IconButton({ save { l -> l.filter { it.id != s.id } } }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${s.title}", tint = cs.onSurfaceVariant) }
                            Switch(s.enabled, { on -> save { l -> l.map { if (it.id == s.id) it.copy(enabled = on, createdAt = if (on) System.currentTimeMillis() else it.createdAt, lastRun = if (on && it.repeat == "once") 0 else it.lastRun) else it } } })
                        }
                    }
                }
            }
        }
    }
    if (adding) ScheduleDialog(null, { adding = false }) { s -> adding = false; save { it + s } }
    editing?.let { e -> ScheduleDialog(e, { editing = null }) { s -> editing = null; save { l -> l.map { if (it.id == s.id) s else it } } } }
}
