package app.pocketagent.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.graphics.vector.ImageVector
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

/** A ready-made task the person can add with one tap and then adjust. */
private class Idea(val icon: ImageVector, val needs: String?, val task: Schedule)

private fun idea(icon: ImageVector, title: String, prompt: String, hour: Int, minute: Int = 0, repeat: String = "daily", agent: Boolean = false, needs: String? = null) =
    Idea(icon, needs, Schedule("", title, prompt, agent, hour, minute, repeat))

private val IDEAS = listOf(
    idea(Icons.Rounded.Newspaper, "Morning news brief", "Search the web and give me today's top news from India and the world in seven short points, each with its source.", 8),
    idea(Icons.Rounded.MailOutline, "Unread mail summary", "@Gmail\nSummarise my unread mail from the last day: who wrote, what they want, and which ones need a reply from me.", 9, agent = true, needs = "Gmail"),
    idea(Icons.Rounded.Code, "Weekly GitHub report", "@GitHub\nSummarise the last week across my repositories: new commits, open pull requests, and issues that need my attention.", 10, repeat = "weekly", agent = true, needs = "GitHub"),
    idea(Icons.Rounded.ShowChart, "Markets at close", "Search the web and tell me how the Sensex and Nifty closed today and the main reasons, in five short points.", 16, 30, repeat = "weekdays"),
    idea(Icons.Rounded.WbSunny, "Plan for the day", "Search the web for today's weather where I live, then give me a short plan for the day: three priorities and one thing worth learning.", 7),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(initial: Schedule, fresh: Boolean, onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var agent by remember { mutableStateOf(initial.agent) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var weekday by remember { mutableStateOf(initial.weekday.toString()) }
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fresh) "New scheduled task" else "Edit task") },
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
                onSave(initial.copy(
                    id = if (fresh) UUID.randomUUID().toString() else initial.id,
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
    var adding by remember { mutableStateOf<Schedule?>(null) }
    fun save(change: (List<Schedule>) -> List<Schedule>) { scope.launch { g.settingsStore.update { it.copy(schedules = change(it.schedules)) }; g.scheduler.arm() } }
    // Ideas that fit this person: ones for an account appear only when that account is connected, and an idea goes away once it is added.
    val connected = remember(settings?.sharedRev) { listOfNotNull("GitHub".takeIf { g.integrations.githubOn() }, "Gmail".takeIf { g.integrations.gmailOn() }) }
    val ideas = IDEAS.filter { i -> (i.needs == null || i.needs in connected) && list.none { it.title == i.task.title } }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        AppBackdrop(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(if (wide) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, if (wide) "Close" else "Back") }
                Text("Scheduled tasks", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
                FilledTonalIconButton({ adding = Schedule("", "", "") }, Modifier.padding(end = 6.dp)) { Icon(Icons.Rounded.Add, "New scheduled task") }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp).animateItem(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(80.dp).background(cs.secondaryContainer, MaterialShapes.Clover4Leaf.toShape()), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Schedule, null, Modifier.size(32.dp), tint = cs.onSecondaryContainer)
                            }
                            Text("Nothing scheduled", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (g.platform.isDesktop) "Tasks run by themselves while the app is open." else "Tasks run by themselves and notify you when done.",
                                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                    }
                    items(list, key = { it.id }) { s ->
                        Surface(onClick = { editing = s }, shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow.copy(alpha = 0.86f), modifier = Modifier.fillMaxWidth().animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f))) {
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
                    if (ideas.isNotEmpty()) item(key = "ideas") {
                        Text("Suggestions", Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp).animateItem(), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                    items(ideas, key = { "idea:" + it.task.title }) { i ->
                        Surface(onClick = { adding = i.task }, shape = MaterialTheme.shapes.extraLarge, color = cs.surface.copy(alpha = 0.6f), border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth().animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f))) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(i.icon, null, Modifier.size(22.dp), tint = cs.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(i.task.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(Scheduler.describe(i.task), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.Add, "Add ${i.task.title}", Modifier.padding(end = 8.dp).size(20.dp), tint = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    adding?.let { a -> ScheduleDialog(a, true, { adding = null }) { s -> adding = null; save { it + s } } }
    editing?.let { e -> ScheduleDialog(e, false, { editing = null }) { s -> editing = null; save { l -> l.map { if (it.id == s.id) s else it } } } }
}
