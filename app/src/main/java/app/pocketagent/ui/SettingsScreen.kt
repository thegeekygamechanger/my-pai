package app.pocketagent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Code
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import app.pocketagent.ai.ModelCaps
import app.pocketagent.ai.Provider
import app.pocketagent.ai.Providers
import app.pocketagent.ai.SPEECH_LANGUAGES
import app.pocketagent.ai.formatCost
import app.pocketagent.ai.formatTokens
import app.pocketagent.data.Settings
import kotlinx.coroutines.launch

private val LocalOpen = staticCompositionLocalOf<MutableState<String>> { error("no section state") }

/**
 * A labelled group of settings rows: a small heading, then the rows as joined tiles inside one rounded shape.
 */
@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(label.uppercase(), Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = cs.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** A row of a group that leads somewhere else in the app, such as a page of terms, instead of to a section of Settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkRow(title: String, icon: ImageVector, summary: String = "", onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(6.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(cs.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open $title", tint = cs.onSurfaceVariant)
        }
    }
}

/** A tile of a group that holds a control directly, such as the theme switch, instead of leading to a page. */
@Composable
private fun Tile(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(6.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = cs.onSurface)
                Text(title, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/**
 * One section of Settings. In the list it is a row: icon, name, a line saying how it is set, and an arrow.
 * Tapping it slides the section's controls up in a sheet over the list; the list stays where it was underneath.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Card(title: String, icon: ImageVector, summary: String = "", content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val open = LocalOpen.current
    Surface(onClick = { open.value = title }, shape = RoundedCornerShape(6.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(cs.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open $title", tint = cs.onSurfaceVariant)
        }
    }
    if (open.value == title) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open.value = "" },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(cs.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
                    }
                    Text(title, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.headlineSmall)
                }
                content()
            }
        }
    }
}

@Composable
private fun Label(title: String, subtitle: String = "") {
    Column(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Adds another service, or replaces the key of one that is already set up. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddServiceDialog(initial: Provider?, exclude: Set<String>, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(initial ?: Providers.PRESETS.firstOrNull { it.id !in exclude } ?: Providers.PRESETS.first()) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (busy || key.isBlank()) return
        if (provider.custom && !provider.baseUrl.startsWith("http")) { error = "Enter the service's address first."; return }
        busy = true
        scope.launch {
            error = g.connect(provider, key)
            busy = false
            if (error == null) onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial != null) "Replace ${initial.name} key" else "Add a service") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initial != null) {
                    OutlinedTextField(
                        key, { key = it.trim(); error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("New key") }, isError = error != null, enabled = !busy,
                        supportingText = { Text(error ?: "It is checked before it replaces the old one.") }, shape = MaterialTheme.shapes.large,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                } else ProviderKeyFields(provider, { provider = it; error = null }, key, { key = it; error = null }, error, !busy, ::save, exclude)
            }
        },
        confirmButton = { TextButton(::save, enabled = key.isNotBlank() && !busy) { if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Save") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onDoc: (String) -> Unit = {}) {
    val g = LocalGraph.current
    val platform = g.platform
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val wide = LocalWide.current
    val s = g.settings.collectAsState().value ?: return
    // Until the person picks a model themselves, new chats use Auto.
    val auto = !s.modelChosen || s.model == app.pocketagent.ai.AutoModel.ID
    val defaultModel = if (auto) app.pocketagent.ai.AutoModel.NAME else g.models(s.provider).firstOrNull { it.id == s.model }?.name ?: s.model
    val totals by remember { g.dao.totals() }.collectAsState(null)
    fun set(change: (Settings) -> Settings) { scope.launch { g.settingsStore.update(change) } }
    fun shared(change: (Settings) -> Settings) { scope.launch { g.updateShared(change) } }

    var adding by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Provider?>(null) }
    var removing by remember { mutableStateOf<Provider?>(null) }
    var wiping by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val connected = remember(s) { g.connected() }
    val syncStatus by g.sync.status.collectAsState()
    val updateState by g.updates.state.collectAsState()
    val askNotifications = g.platform.rememberNotificationAsk()

    val open = rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onBack) { Icon(if (wide) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, if (wide) "Close" else "Back") }
            Text("Settings", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.headlineMedium)
        }
        CompositionLocalProvider(LocalOpen provides open) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Group("AI") {
                    Card("AI services", Icons.Rounded.VpnKey, connected.joinToString { it.name }) {
                        connected.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.VpnKey, null, Modifier.size(20.dp), tint = cs.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    val k = g.keyFor(p.id).orEmpty()
                                    Text((if (k.length > 12) k.take(6) + "…" + k.takeLast(4) else "…") + " · ${g.models(p.id).size} models", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                TextButton({ replacing = p }) { Text("Replace") }
                                IconButton({ removing = p }) { Icon(Icons.Rounded.Close, "Remove ${p.name}", tint = cs.onSurfaceVariant) }
                            }
                        }
                        FilledTonalButton({ adding = true }, Modifier.padding(vertical = 6.dp)) { Text("Add a service") }
                    }
                    Card("New chats", Icons.Rounded.AutoAwesome, defaultModel) {
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { pickModel = true }.padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Model", style = MaterialTheme.typography.bodyLarge)
                                Text(defaultModel + (g.provider(s.provider)?.let { " · ${it.name}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Text("Change", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                        }
                        Label("Effort", EFFORT_HELP[s.effort].orEmpty())
                        Segmented(ModelCaps.EFFORTS.map { it to (if (it == "medium") "Med" else EFFORT_LABELS[it] ?: it) }, s.effort, { v -> set { it.copy(effort = v) } }, Modifier.fillMaxWidth())
                        SwitchRow("Web search", "Let new chats search the web (Claude models).", s.web) { v -> set { it.copy(web = v) } }
                        Label("Longest reply", "The most a single reply can contain.")
                        Segmented(listOf(8_000 to "Short", 32_000 to "Normal", 64_000 to "Long", 128_000 to "Max"), s.maxOutput, { v -> set { it.copy(maxOutput = v) } }, Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    }
                    // Making pictures and videos is offered only when one of the person's own keys can do it; otherwise it is not mentioned at all.
                    if (g.keyFor("gemini") != null || g.keyFor("openai") != null || g.keyFor("pollinations") != null) Card("Pictures and videos", Icons.Rounded.Image, listOfNotNull("Gemini".takeIf { g.keyFor("gemini") != null }, "OpenAI".takeIf { g.keyFor("openai") != null }, "Pollinations".takeIf { g.keyFor("pollinations") != null }).joinToString(" · ")) {
                        Text(
                            "Ask for a picture in any chat and it is made with your own key and shown in the conversation. Videos work with a Gemini or Pollinations key.",
                            Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                    }
                }
                Group("You") {
                    Card("About you", Icons.Rounded.Person, listOf(s.name, s.role).filter { it.isNotBlank() }.joinToString(" · ")) {
                        var name by remember { mutableStateOf(s.name) }
                        OutlinedTextField(
                            name, { name = it.take(40); shared { st -> st.copy(name = name.trim()) } }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Your name") }, singleLine = true,
                            shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        )
                        var city by remember { mutableStateOf(s.city) }
                        OutlinedTextField(
                            city, { city = it.take(60); shared { st -> st.copy(city = city.trim()) } }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("City (optional)") }, singleLine = true,
                            supportingText = { Text("For the weather in your greeting.") }, shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        )
                        Label("I am a")
                        Choices(ROLES.map { it to it }, s.role, { v -> shared { it.copy(role = if (it.role == v) "" else v) } })
                        Label("I like answers")
                        Segmented(STYLES, s.style, { v -> shared { it.copy(style = v) } }, Modifier.fillMaxWidth())
                        Label("Voice typing language", if (platform.isDesktop) "Automatic lets the speech model recognise the language itself." else "Automatic follows the phone's language. Pick one to speak in another.")
                        Choices(SPEECH_LANGUAGES, s.speechLang, { v -> set { it.copy(speechLang = v) } }, Modifier.padding(bottom = 6.dp))
                    }
                    Card("Your instructions", Icons.Rounded.EditNote, s.customInstructions.lineSequence().firstOrNull().orEmpty()) {
                        var text by remember { mutableStateOf(s.customInstructions) }
                        OutlinedTextField(
                            text, { text = it.take(4000); shared { st -> st.copy(customInstructions = text) } },
                            Modifier.fillMaxWidth().padding(vertical = 6.dp), minLines = 3, maxLines = 8, shape = MaterialTheme.shapes.large,
                            placeholder = { Text("For example: answer in simple English, keep replies short.") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                }
                Group("Work") {
                    Card("Work", Icons.Rounded.Checklist, "${s.maxSteps} steps") {
                        Label("Permissions for new chats", PERMISSION_HELP[s.permission].orEmpty())
                        Segmented(listOf("ask" to "Ask all", "auto_read" to "Ask to change", "full_auto" to "Full auto"), s.permission, { v -> set { it.copy(permission = v) } }, Modifier.fillMaxWidth())
                        var steps by remember { mutableFloatStateOf(s.maxSteps.toFloat()) }
                        Label("Step limit: ${steps.toInt()}", "Work pauses after this many steps in one run.")
                        Slider(steps, { steps = it }, valueRange = 5f..100f, steps = 18, onValueChangeFinished = { set { it.copy(maxSteps = steps.toInt()) } })
                        var budget by remember { mutableFloatStateOf(s.budgetK.toFloat()) }
                        Label(if (budget < 50f) "Token limit: none" else "Token limit: ${formatTokens(budget.toLong() * 1000)}", "Work pauses when one run has used this many tokens.")
                        Slider(budget, { budget = it }, valueRange = 0f..2000f, steps = 39, onValueChangeFinished = { set { it.copy(budgetK = if (budget < 50f) 0 else budget.toInt()) } })
                    }
                    Card("Reliability", Icons.Rounded.Shield) {
                        SwitchRow("Summarise long chats", "When a chat gets very long, older messages are condensed so it can keep going (Claude models).", s.compaction) { v -> set { it.copy(compaction = v) } }
                        SwitchRow("Fall back when declined", "If the chosen Claude model declines a request, let another Claude model answer it.", s.fallbacks) { v -> set { it.copy(fallbacks = v) } }
                    }
                }
                Group("Connected") {
                    Card("Connections", Icons.Rounded.Link, listOfNotNull("GitHub".takeIf { g.integrations.githubOn() }, "Gmail".takeIf { g.integrations.gmailOn() }).joinToString(" · ")) { ConnectionsSection() }
                    Card("Sync", Icons.Rounded.Sync, syncStatus.let { st -> if (!st.enabled) "Off" else if (st.peers.isEmpty()) "On" else "Live with ${st.peers.size} other" }) { SyncSection() }
                }
                Group("Appearance") {
                    Tile("Theme", Icons.Rounded.Palette) {
                        Segmented(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), s.theme, { v -> set { it.copy(theme = v) } }, Modifier.fillMaxWidth(), icons = mapOf(s.theme to Icons.Rounded.Check))
                    }
                    Card("Display", Icons.Rounded.Contrast, listOfNotNull("Pure black".takeIf { s.pureBlack }, "Wallpaper colours".takeIf { s.dynamicColor && platform.hasDynamicColors }).joinToString(" · ").ifEmpty { "Colours and feedback" }) {
                        SwitchRow("Pure black", "Use true black backgrounds in the dark theme.", s.pureBlack) { v -> set { it.copy(pureBlack = v) } }
                        if (platform.hasDynamicColors) SwitchRow("Wallpaper colours", "Take the app's colours from your wallpaper.", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                        if (!platform.isDesktop) SwitchRow("Vibration feedback", "A light tap when you send, approve or stop.", s.haptics) { v -> set { it.copy(haptics = v) } }
                    }
                }
                Group("Alerts and safety") {
                    Card("Notifications", Icons.Rounded.Notifications, when { s.notifyReplies && s.notifyTasks -> "On"; s.notifyReplies || s.notifyTasks -> "Partly on"; else -> "Off" }) {
                        SwitchRow("Replies", "Tell me when a reply is ready while I am in another app.", s.notifyReplies) { on -> if (on) askNotifications(); scope.launch { g.settingsStore.update { it.copy(notifyReplies = on) } } }
                        SwitchRow("Scheduled tasks", "Announce the result of a scheduled task at its time, with a sound.", s.notifyTasks) { on -> if (on) askNotifications(); scope.launch { g.settingsStore.update { it.copy(notifyTasks = on) } } }
                    }
                    if (!platform.isDesktop) Card("Security", Icons.Rounded.Lock, if (s.lock) "App lock on" else "") {
                        val can = remember { platform.canLock }
                        SwitchRow("App lock", if (can) "Ask for fingerprint, face or screen lock to open the app." else "Set a screen lock on this phone first.", s.lock, enabled = can) { v ->
                            platform.authenticate(if (v) "Turn on app lock" else "Turn off app lock") { set { it.copy(lock = v) } }
                        }
                        if (s.lock) {
                            Label("Lock after leaving the app")
                            Segmented(listOf(0 to "At once", 60 to "1 minute", 300 to "5 minutes"), s.lockDelaySec, { v -> set { it.copy(lockDelaySec = v) } }, Modifier.fillMaxWidth())
                        }
                        SwitchRow("Hide screen content", "Blocks screenshots and blanks the app in the recent-apps view.", s.secureScreen) { v -> set { it.copy(secureScreen = v) } }
                    }
                }
                Group("About") {
                    Card("App update", Icons.Rounded.SystemUpdateAlt, updateSummary(updateState)) { UpdateSection() }
                    Card("Usage", Icons.Rounded.DataUsage) {
                        val t = totals
                        Label(
                            if (t == null || (t.inTok ?: 0) + (t.outTok ?: 0) == 0L) "Nothing used yet"
                            else "${formatTokens((t.inTok ?: 0) + (t.cacheReadTok ?: 0))} in · ${formatTokens(t.outTok ?: 0)} out" + formatCost(t.costMicros ?: 0).let { if (it.isEmpty()) "" else " · about $it" },
                            "Across the chats on this device. Cost is an estimate for Claude models from list prices; your account with each service shows the real figure.",
                        )
                    }
                    Card("Data", Icons.Rounded.Storage) {
                        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({
                                note = "Checking…"
                                scope.launch {
                                    val counts = connected.map { p -> runCatching { g.refreshModels(p).size }.getOrDefault(-1) }
                                    note = if (counts.any { it < 0 }) "Some lists could not be refreshed." else "Found ${counts.sum()} models."
                                }
                            }) { Text("Refresh model lists") }
                            OutlinedButton({ wiping = true }) { Text("Delete all data") }
                        }
                        if (note.isNotEmpty()) Text(note, Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    // Offered only while the relay can pass a note on, so the button never leads nowhere.
                    val feedbackOn by androidx.compose.runtime.produceState(false) { value = app.pocketagent.sync.Feedback.available() }
                    if (feedbackOn) Card("Send feedback", Icons.Rounded.RateReview, "Tell the developer what works and what does not") { FeedbackForm() }
                    LinkRow(Docs.title(Docs.TERMS), Icons.Rounded.Gavel) { onDoc(Docs.TERMS) }
                    LinkRow(Docs.title(Docs.PRIVACY), Icons.Rounded.PrivacyTip) { onDoc(Docs.PRIVACY) }
                    LinkRow(Docs.title(Docs.LICENSES), Icons.Rounded.Code) { onDoc(Docs.LICENSES) }
                }
                Text("Pocket Agent ${platform.versionName}", Modifier.fillMaxWidth().padding(top = 22.dp, start = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.outline)
            }
        } }
    }

    if (pickModel) ModelPicker(s.provider, if (auto) app.pocketagent.ai.AutoModel.ID else s.model, onPick = { p, m -> pickModel = false; shared { it.copy(provider = p, model = m, modelChosen = true) } }, onDismiss = { pickModel = false })
    if (adding) AddServiceDialog(null, connected.map { it.id }.toSet()) { adding = false }
    replacing?.let { p -> AddServiceDialog(p, emptySet()) { replacing = null } }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text(if (connected.size <= 1) "This is the only service set up. Removing its key takes you back to the first screen. Your chats stay." else "Its key is erased from this device and from your linked devices. Your chats stay.") },
            confirmButton = {
                TextButton({
                    removing = null
                    scope.launch {
                        g.removeKey(p.id)
                        if (g.connected().isEmpty()) { g.runs.stopAll(); g.settingsStore.update { it.copy(onboarded = false) } }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
    if (wiping) AlertDialog(
        onDismissRequest = { wiping = false },
        title = { Text("Delete all data?") },
        text = { Text("Every chat, attachment, workspace file and memory note is erased from this device, and from linked devices when they are next open. This cannot be undone. Your keys and settings stay.") },
        confirmButton = { TextButton({ wiping = false; scope.launch { g.deleteEverything(); note = "All chats and files deleted." } }) { Text("Delete everything", color = cs.error) } },
        dismissButton = { TextButton({ wiping = false }) { Text("Cancel") } },
    )
}

/**
 * A note to the developer, written and sent without leaving the app. Where it goes is not shown, and the app does
 * not know it: the note is handed to the relay, which passes it on by email.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedbackForm() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }

    if (sent) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(cs.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(30.dp), tint = cs.onPrimaryContainer)
            }
            Text("Thank you, it is sent", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                if (email.isBlank()) "Your note is on its way to the developer. You left no address, so there will be no reply; send another with your email if you would like one."
                else "Your note is on its way to the developer. Please allow a few hours for a reply at ${email.trim()}.",
                Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton({ sent = false; message = ""; problem = "" }, Modifier.padding(top = 8.dp)) { Text("Write another") }
        }
        return
    }

    Text("What works, what does not, what you wish it did. It goes straight to the developer.", Modifier.padding(start = 4.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    OutlinedTextField(
        message, { message = it.take(app.pocketagent.sync.Feedback.MAX); problem = "" }, Modifier.fillMaxWidth().heightIn(min = 132.dp), enabled = !sending,
        placeholder = { Text("Your feedback") }, shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
    )
    val emailOk = email.isBlank() || app.pocketagent.sync.Feedback.validEmail(email)
    OutlinedTextField(
        email, { email = it.take(120).trim(); problem = "" }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !sending, singleLine = true, isError = !emailOk,
        placeholder = { Text("Your email, for a reply (optional)") }, shape = androidx.compose.foundation.shape.CircleShape,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
    )
    Text(
        problem.ifEmpty { "Sent with it: this text, the email if you give one, and the app version (${g.platform.versionName}). Nothing from your chats or files." },
        Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (problem.isEmpty()) cs.onSurfaceVariant else cs.error,
    )
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sending) androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp))
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Button(
            {
                sending = true; problem = ""
                scope.launch {
                    val where = (if (g.platform.isDesktop) "desktop " else "android ") + g.platform.versionName
                    when (app.pocketagent.sync.Feedback.send(message, email, where)) {
                        app.pocketagent.sync.Feedback.Result.SENT -> sent = true
                        app.pocketagent.sync.Feedback.Result.TOO_MANY -> problem = "That is several notes in a short while. Please try again in an hour."
                        app.pocketagent.sync.Feedback.Result.FAILED -> problem = "It could not be sent. Check the connection and try again."
                    }
                    sending = false
                }
            },
            enabled = !sending && message.trim().length >= 3 && emailOk,
        ) { Text(if (sending) "Sending" else "Send") }
    }
}
