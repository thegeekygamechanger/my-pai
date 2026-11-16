package app.pocketagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.ai.Decision
import app.pocketagent.ai.Phase
import app.pocketagent.ai.RunState
import app.pocketagent.ai.formatCost
import app.pocketagent.ai.formatTokens
import app.pocketagent.ai.parseArray
import app.pocketagent.ai.str
import app.pocketagent.data.AttachmentError
import app.pocketagent.data.Chat
import app.pocketagent.data.Picked
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import app.pocketagent.Speech
import app.pocketagent.ai.Providers
import app.pocketagent.sync.ShareClient
import app.pocketagent.sync.ShareCode
import app.pocketagent.sync.SharedChat
import app.pocketagent.sync.SyncCode
import app.pocketagent.ui.theme.LocalMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(chatId: Long, initialText: String, onMenu: (() -> Unit)?, onNew: () -> Unit, onWorkspace: () -> Unit, onDeleted: () -> Unit, onOpenChat: (Long) -> Unit = {}) {
    val g = LocalGraph.current
    val platform = g.platform
    val wide = LocalWide.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val motion = LocalMotion.current
    val settings by g.settings.collectAsState()
    val chat by remember(chatId) { g.dao.chatFlow(chatId) }.collectAsState(null)
    val messages by remember(chatId) { g.dao.messagesFlow(chatId) }.collectAsState(emptyList())
    val runs by g.allRuns.collectAsState()
    val run = runs[chatId]
    val snack = remember { SnackbarHostState() }
    val showThinking = settings?.showWork == true
    fun buzz(type: HapticFeedbackType = HapticFeedbackType.Confirm) { if (settings?.haptics != false) haptic.performHapticFeedback(type) }

    // A finished reply is saved, then the run ends; the saved copy reaches the screen a moment later.
    // Holding the last live state for that moment keeps the reply from blinking.
    var held by remember { mutableStateOf<RunState?>(null) }
    LaunchedEffect(run) {
        if (run != null) held = run else { delay(350); held = null }
    }
    val shown = run ?: held?.takeIf { h -> h.apiId.isNotEmpty() && messages.none { it.apiId == h.apiId } }

    // Text arrives from the network in uneven bursts. Reveal it at a steady pace instead.
    val liveLast = shown?.live?.lastOrNull()?.takeIf { it.type == "text" && !it.done }
    val liveKey = if (liveLast == null) "" else shown.apiId + ":" + shown.live.lastIndex
    var revealed by remember { mutableIntStateOf(0) }
    val target by rememberUpdatedState(liveLast?.text?.length ?: 0)
    LaunchedEffect(liveKey) {
        revealed = 0
        if (liveKey.isEmpty() || !motion) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val gap = target - revealed
            if (gap > 0) revealed += maxOf(1, (gap * 0.22f).roundToInt())
        }
    }
    val paced = if (liveLast != null && motion && shown != null) {
        var cut = revealed.coerceIn(0, liveLast.text.length)
        if (cut in 1 until liveLast.text.length && Character.isHighSurrogate(liveLast.text[cut - 1])) cut--
        shown.copy(live = shown.live.dropLast(1) + liveLast.copy(text = liveLast.text.substring(0, cut)))
    } else shown

    val builder = remember(chatId) { RowBuilder(g.tools::describe, g.blobs::unpack) }
    val saved = remember(messages, run?.done, run?.pending, run == null, showThinking) { builder.persisted(messages, run, showThinking) }
    val rows = saved + builder.tail(messages, paced, showThinking)
    // Touching the conversation while a reply is being written means "let me read". The screen then holds
    // exactly what it showed at that moment, while the reply carries on behind it, until the person asks to
    // see the newest again. Nothing is ever scrolled out from under a reader.
    var held0 by remember { mutableStateOf<List<UiRow>?>(null) }
    val shownRows = held0 ?: rows
    val latestRows by rememberUpdatedState(rows)
    val reversed = remember(shownRows) { shownRows.asReversed() }

    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    // The view follows the newest content (the reply as it arrives, new tool steps) for as long as the
    // person has not scrolled up to read something older. Sending a message always brings it back down.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val bottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80
                // While the screen is being held for reading, only the chip (or sending a message) lets it move again.
                follow = bottom && held0 == null
            }
        }
    }
    val userCount = messages.count { it.role == "user" }
    LaunchedEffect(userCount) { held0 = null; follow = true }
    val newestKey = shownRows.lastOrNull()?.key
    LaunchedEffect(newestKey, shownRows.size, follow) { if (follow && shownRows.isNotEmpty()) listState.scrollToItem(0) }
    fun goToLatest() { held0 = null; follow = true; scope.launch { listState.animateScrollToItem(0) } }

    var input by rememberSaveable(chatId) { mutableStateOf(initialText) }
    val picked = remember(chatId) { mutableStateListOf<Picked>() }
    var editing by remember { mutableStateOf<UiRow.User?>(null) }
    var showControls by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // Connected accounts the person has pulled into this message with "@".
    val mentions = remember(chatId) { mutableStateListOf<Mention>() }
    var pickingRepos by remember { mutableStateOf(false) }
    val connections = remember(settings?.sharedRev) { listOfNotNull("GitHub".takeIf { g.integrations.githubOn() }, "Gmail".takeIf { g.integrations.gmailOn() }) }

    val pickFiles = platform.rememberFilePicker { sources ->
        importing = true
        scope.launch {
            for (src in sources.take(6 - picked.size)) {
                try {
                    picked += withContext(Dispatchers.IO) { platform.importAttachment(src) }
                } catch (e: AttachmentError) {
                    snack.showSnackbar(e.message ?: "Could not attach that file.")
                } catch (e: Exception) {
                    snack.showSnackbar("Could not attach that file.")
                }
            }
            importing = false
        }
    }
    val askNotifications = platform.rememberNotificationAsk()

    // A share code pasted into the message box is recognised at once: the chat behind it is fetched, and the
    // person is asked before it is opened as a new chat of their own.
    var incoming by remember { mutableStateOf<SharedChat?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var pastedWhole by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        // Either the labelled form anywhere in the text, or the bare four characters pasted into an empty box.
        val hit = ShareCode.find(input)
        val code = hit?.groupValues?.get(1)?.uppercase() ?: ShareCode.parse(input).takeIf { pastedWhole } ?: return@LaunchedEffect
        input = if (hit != null) input.replace(hit.value, "").trim() else ""
        if (fetching) return@LaunchedEffect
        fetching = true
        // Clearing the box restarts this effect, so the fetch runs in the screen's own scope and is not cut short.
        scope.launch {
            val got = try { ShareClient.fetch(g, code) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { fetching = false }
            if (got == null) snack.showSnackbar("Could not reach the device sharing that chat. Ask them to keep the share window open, then paste the code again.") else incoming = got
        }
    }
    fun send() {
        val c = chat ?: return
        val text = input.trim()
        if (run != null || (text.isEmpty() && picked.isEmpty() && mentions.isEmpty())) return
        if (g.keyFor(c.provider) == null) { scope.launch { snack.showSnackbar("Add a key for ${g.provider(c.provider)?.name ?: "this service"} in Settings, or pick another model.") }; return }
        if (c.model.isBlank()) { showControls = true; return }
        val blocks = ArrayList<JsonObject>()
        val edit = editing
        if (edit != null) {
            // Keep the attachments of the message being edited; only its words change.
            messages.firstOrNull { it.id == edit.msgId }?.let { m ->
                parseArray(g.blobs.unpack(m.content)).forEach { b -> if (b is JsonObject && b.str("type") != "text") blocks += b }
            }
        }
        picked.forEach { blocks += it.block }
        // "@GitHub owner/repo" and "@Gmail" go at the top of the message, as lines the model is told how to read.
        val lead = mentions.joinToString("") { m -> "@" + m.name + (if (m.repos.isEmpty()) "" else " " + m.repos.joinToString(", ")) + "\n" }
        if (text.isNotEmpty() || lead.isNotEmpty()) blocks += buildJsonObject { put("type", "text"); put("text", (if (lead.isEmpty()) "" else lead + "\n") + text.ifEmpty { "Use this for what comes next." }) }
        if (blocks.none { it.str("type") == "text" }) blocks += buildJsonObject { put("type", "text"); put("text", "Please look at the attached file${if (blocks.size == 1) "" else "s"}.") }
        buzz()
        askNotifications()
        val usesConnection = mentions.isNotEmpty()
        scope.launch {
            // The connected accounts are tools, and tools belong to Work mode: a message that names one switches the chat over.
            if (usesConnection && !c.agent) g.dao.update(c.copy(agent = true))
            if (edit != null) g.runs.editAndResend(c.id, edit.msgId, JsonArray(blocks)) else g.runs.send(c.id, JsonArray(blocks))
        }
        input = ""
        picked.clear()
        mentions.clear()
        editing = null
    }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        // An empty chat sits on slowly moving colour that follows the hour and the weather; it fades away once the conversation starts.
        val weatherNow by g.weather.collectAsState()
        val hourNow = remember(chatId) { java.time.LocalTime.now().hour }
        // Full colour and a slow drift on an empty chat. Once the conversation starts the colour drops back so text stays
        // easy to read; it stands still while nothing is happening and flows while a reply is being written.
        val started = rows.isNotEmpty()
        val glow by animateFloatAsState(if (!started) 1f else if (run != null) 0.62f else 0.4f, tween(900), label = "glow")
        if (chat != null) Backdrop(backdropColors(hourNow, weatherNow), Modifier.fillMaxSize(), speed = if (!started) 2.7f else if (run != null) 9f else 0f, strength = glow)
        Column(Modifier.fillMaxSize().imePadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) IconButton(onMenu) { Icon(Icons.Rounded.Menu, "Menu") }
                Column(Modifier.weight(1f).padding(start = if (onMenu == null) 14.dp else 0.dp).clip(MaterialTheme.shapes.small).clickable { showControls = true }.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(chat?.title?.ifBlank { "New chat" } ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val c = chat
                    if (c != null) Text(
                        listOfNotNull(
                            g.models(c.provider).firstOrNull { it.id == c.model }?.name?.removePrefix("Claude ") ?: shortModel(c.model).ifBlank { "Choose a model" },
                            c.effort.takeIf { g.provider(c.provider)?.let { p -> Providers.efforts(p, c.model).isNotEmpty() } == true },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // The three dots hold everything about this chat, starting with its model.
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Chat options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Model and tools") }, { menu = false; showControls = true }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                        DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text("Share with a code") }, { menu = false; sharing = true }, leadingIcon = { Icon(Icons.Rounded.QrCode2, null) })
                        DropdownMenuItem({ Text("Delete chat") }, { menu = false; deleting = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                    }
                }
                // New chat sits alone in the top corner.
                Surface(onClick = onNew, shape = CircleShape, color = cs.surface.copy(alpha = 0.35f), border = androidx.compose.foundation.BorderStroke(1.dp, cs.onSurface.copy(alpha = 0.12f)), modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "New chat") }
                }
            }

            // Messages. On a wide window the column is kept to a comfortable reading width.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
                    if (rows.isEmpty() && chat != null) EmptyChat(chatId, chat!!.agent) { input = it; send() }
                    else LazyColumn(
                        Modifier.fillMaxSize().pointerInput(run != null) {
                            // Watches for a finger going down without taking the touch away from the list or its buttons.
                            if (run != null) awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (held0 == null) held0 = latestRows
                                follow = false
                            }
                        },
                        state = listState, reverseLayout = true, contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
                    ) {
                        items(reversed, key = { it.key }, contentType = { it::class }) { row ->
                            // New rows fade in. They are not slid into place: while a reply is growing every row above it
                            // moves on each frame, and animating that makes the text lag and overlap.
                            val m = if (row is UiRow.Md && row.live) Modifier else Modifier.animateItem(fadeInSpec = spring(stiffness = 300f), placementSpec = null, fadeOutSpec = null)
                            when (row) {
                                is UiRow.User -> UserRow(row, m, onCopy = { clipboard.setText(AnnotatedString(it)); buzz() }, onEdit = { editing = it; input = it.text }, canEdit = run == null)
                                is UiRow.Md -> MdRow(row, m)
                                is UiRow.Thinking -> ThinkingRow(row, m)
                                is UiRow.Tool -> ToolRow(row, m)
                                is UiRow.Steps -> StepsRow(row, m)
                                is UiRow.Note -> NoteRow(row, m)
                                is UiRow.Sources -> SourcesRow(row, m)
                                is UiRow.Footer -> FooterRow(row, m, onCopy = { clipboard.setText(AnnotatedString(it)); buzz() }, onRetry = { buzz(); g.runs.regenerate(chatId) }, busy = run != null)
                                is UiRow.Status -> StatusRow(row, m)
                            }
                        }
                    }
                    // A small chip that takes the reader back to the newest text.
                    val waiting = held0 != null
                    androidx.compose.animation.AnimatedVisibility(
                        waiting || !atBottom, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 450f)) + fadeIn() + slideInVertically(spring(dampingRatio = 0.6f, stiffness = 400f)) { it },
                        exit = scaleOut() + fadeOut(),
                    ) {
                        val bob = if (waiting && run != null && motion) {
                            val t = rememberInfiniteTransition(label = "bob")
                            val v by t.animateFloat(-2f, 3f, infiniteRepeatable(tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "bob")
                            v
                        } else 0f
                        Surface(
                            onClick = { buzz(HapticFeedbackType.ContextClick); goToLatest() }, shape = CircleShape, shadowElevation = 3.dp,
                            color = if (waiting) cs.primary else cs.surfaceContainerHighest, contentColor = if (waiting) cs.onPrimary else cs.onSurface,
                        ) {
                            Row(Modifier.padding(horizontal = if (waiting) 14.dp else 10.dp, vertical = 8.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.ArrowDownward, "Go to the newest text", Modifier.size(18.dp).offset(y = bob.dp))
                                if (waiting) Text(if (run != null) "Still writing" else "New reply", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
                }
            }

            // Everything anchored above the keyboard
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The cards above the composer scroll if they do not fit (for example with the keyboard open),
                    // so the composer and its Stop button are never pushed off the screen.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tasks = remember(chat?.tasksJson) { parseTasks(chat?.tasksJson.orEmpty()) }
                        AnimatedVisibility(tasks.isNotEmpty() && chat?.agent == true, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 350f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            TaskCard(tasks, run != null)
                        }
                        AnimatedVisibility(
                            run?.pending != null,
                            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 350f)) { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut(),
                        ) {
                            val p = run?.pending ?: held?.pending
                            if (p != null) ApprovalCard(
                                p,
                                onAllow = { buzz(); g.decide(chatId, Decision.ALLOW) },
                                onAlways = { buzz(); g.decide(chatId, Decision.ALLOW_ALWAYS) },
                                onDeny = { buzz(HapticFeedbackType.Reject); g.decide(chatId, Decision.DENY) },
                            )
                        }
                    }
                    Composer(
                        chat = chat, run = run, input = input, onInput = { pastedWhole = input.isBlank() && it.trim().length == ShareCode.LENGTH; input = it }, picked = picked, importing = importing, editing = editing != null,
                        // Connected accounts belong to Work, so a chat that has already started as a chat does not offer them.
                        mentions = mentions, connections = if (messages.isNotEmpty() && chat?.agent != true) emptyList() else connections,
                        onMention = { name -> if (name == "GitHub") pickingRepos = true else if (mentions.none { it.name == name }) mentions += Mention(name) },
                        onCancelEdit = { editing = null; input = "" },
                        onAttach = pickFiles,
                        // Chat or Work is chosen before the first message. After that the switch stays put, and a tap on the other side offers a fresh start instead.
                        locked = messages.isNotEmpty(),
                        onMode = { agent ->
                            chat?.let { c ->
                                if (messages.isEmpty()) { buzz(HapticFeedbackType.ToggleOn); scope.launch { g.dao.update(c.copy(agent = agent)) } }
                                else if (agent != c.agent) scope.launch {
                                    snack.currentSnackbarData?.dismiss()
                                    val picked = snack.showSnackbar(
                                        if (agent) "This is a chat. Start a new one to use Work." else "This is a piece of work. Start a new one to chat.",
                                        actionLabel = "Start new", duration = androidx.compose.material3.SnackbarDuration.Short,
                                    )
                                    if (picked == androidx.compose.material3.SnackbarResult.ActionPerformed) onNew()
                                }
                            }
                        },
                        speech = platform.rememberSpeech(g, onText = { said -> input = if (input.isBlank()) said else input.trimEnd() + " " + said }, onError = { msg -> scope.launch { snack.showSnackbar(msg) } }),
                        onSend = ::send,
                        onStop = { buzz(HapticFeedbackType.Reject); g.stopRun(chatId) },
                    )
                }
            }
        }
    }

    if (showControls) chat?.let { c -> ControlsSheet(c, onDismiss = { showControls = false }) }
    if (sharing) ShareChatDialog(chatId) { sharing = false }
    if (fetching) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Opening the shared chat") }, text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) })
    incoming?.let { shared ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text(if (shared.by.isEmpty()) "Open this shared chat?" else "Open the chat ${shared.by} shared?") },
            text = { Text("\"${shared.title}\", ${shared.messages.size} message${if (shared.messages.size == 1) "" else "s"}. It opens as a new chat of your own; nothing else of theirs comes with it.") },
            confirmButton = { TextButton({ incoming = null; scope.launch { onOpenChat(ShareClient.save(g, shared)) } }) { Text("Open") } },
            dismissButton = { TextButton({ incoming = null }) { Text("Not now") } },
        )
    }
    if (pickingRepos) RepoPicker(mentions.firstOrNull { it.name == "GitHub" }?.repos.orEmpty(), onDismiss = { pickingRepos = false }) { repos ->
        pickingRepos = false
        mentions.removeAll { it.name == "GitHub" }
        mentions += Mention("GitHub", repos)
    }

    if (renaming) {
        var name by remember { mutableStateOf(chat?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)) },
            confirmButton = { TextButton({ renaming = false; scope.launch { g.dao.rename(chatId, name.trim().ifEmpty { "New chat" }) } }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this chat?") },
        text = { Text("The messages and attachments in it are removed from this device and from linked devices. Files in the workspace stay.") },
        confirmButton = { TextButton({ deleting = false; scope.launch { g.deleteChat(chatId); onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
    )
}

/** An empty chat: a greeting that changes with the hour, the weather and the person's name, over slowly moving colour. */
@Composable
private fun EmptyChat(chatId: Long, work: Boolean, onPick: (String) -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val settings by g.settings.collectAsState()
    val weather by g.weather.collectAsState()
    val hour = remember(chatId) { java.time.LocalTime.now().hour }
    // A new pick every time a fresh chat is opened.
    val seed = remember(chatId) { kotlin.random.Random.nextInt() }
    val (title, line) = remember(seed, settings?.name, weather, work) { greeting(settings?.name.orEmpty(), hour, weather, work, seed) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AnimatedContent(title, transitionSpec = { (fadeIn(tween(450)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(200)) }, label = "greeting") { t ->
                Text(t, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            }
            AnimatedContent(line, transitionSpec = { fadeIn(tween(600, delayMillis = 120)) togetherWith fadeOut(tween(200)) }, label = "greetingLine") { l ->
                Text(l, Modifier.padding(top = 12.dp).widthIn(max = 380.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            val ideas = if (work) listOf("Research today's top technology news and save a summary as notes.md", "Plan a 3-day trip to Jaipur and write the itinerary to a file", "Draft a README for a to-do list app in the workspace")
            else listOf("Explain how a rainbow forms, simply", "Help me write a polite message to reschedule a meeting", "Give me a 7-day plan to start learning Python")
            Column(Modifier.padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ideas.forEach { idea ->
                    SuggestionChip({ onPick(idea) }, { Text(idea, Modifier.padding(vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis) }, Modifier.widthIn(max = 420.dp), colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(containerColor = cs.surface.copy(alpha = 0.6f)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    chat: Chat?, run: RunState?, input: String, onInput: (String) -> Unit, picked: MutableList<Picked>, importing: Boolean, editing: Boolean,
    mentions: MutableList<Mention>, connections: List<String>, onMention: (String) -> Unit,
    onCancelEdit: () -> Unit, onAttach: () -> Unit, locked: Boolean, onMode: (Boolean) -> Unit, speech: Speech?, onSend: () -> Unit, onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val running = run != null
    val desktop = LocalGraph.current.platform.isDesktop
    // Choosing to edit a sent message puts the cursor in the box, ready to change it and send again.
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }
    val canSend = !running && (input.isNotBlank() || picked.isNotEmpty() || mentions.isNotEmpty())
    // Typing "@" (or "@gi…") at the end of the message offers the connected accounts that match.
    val at = remember(input) { AT.find(input) }
    val offers = if (at == null) emptyList() else connections.filter { it.startsWith(at.groupValues[2], ignoreCase = true) && (it == "GitHub" || mentions.none { m -> m.name == it }) }
    // While a reply is being generated a soft band of colour travels round the composer's edge.
    val glow = if (running && LocalMotion.current) {
        val t = rememberInfiniteTransition(label = "glow")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "glow")
        v
    } else -1f
    val edge by animateFloatAsState(if (running) 1f else 0f, tween(450), label = "edge")
    val c1 = cs.primary; val c2 = cs.tertiary; val c3 = cs.secondary
    Surface(
        shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            if (edge > 0f) {
                val w = size.width
                val shift = if (glow < 0f) 0f else glow * w * 2f
                val brush = Brush.linearGradient(listOf(c1, c2, c3, c1), start = Offset(shift - w, 0f), end = Offset(shift, size.height), tileMode = TileMode.Mirror)
                drawRoundRect(brush, cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(2.dp.toPx()), alpha = edge * 0.9f)
            }
        },
    ) {
        Column {
            // What goes on behind a reply stays out of sight; only what the person can act on, or where it is running, is named.
            val note = listOfNotNull(
                when (run?.phase) { Phase.APPROVAL -> "Waiting for your OK"; Phase.RETRYING -> "Reconnecting"; else -> null },
                "Running on ${run?.remote}".takeIf { run?.remote?.isNotEmpty() == true },
            ).joinToString(" · ")
            AnimatedVisibility(running && note.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(note, Modifier.padding(start = 22.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium.copy(brush = Brush.linearGradient(listOf(c1, c2))))
            }
            AnimatedVisibility(editing) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing your message. Replies after it will be replaced.", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
                    IconButton(onCancelEdit, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Cancel edit", Modifier.size(16.dp)) }
                }
            }
            AnimatedVisibility(offers.isNotEmpty() && !running, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offers.forEach { name ->
                        AssistChip(
                            onClick = { onInput(input.substring(0, at!!.range.first + at.groupValues[1].length)); onMention(name) },
                            label = { Text(name) }, leadingIcon = { Icon(if (name == "GitHub") Icons.Rounded.Code else Icons.Rounded.Email, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            AnimatedVisibility(mentions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mentions.toList().forEach { m ->
                        InputChip(
                            selected = true, onClick = { if (m.name == "GitHub") onMention("GitHub") },
                            label = { Text(m.name + when (m.repos.size) { 0 -> ""; 1 -> " · " + m.repos[0].substringAfter('/'); else -> " · ${m.repos.size} repositories" }, maxLines = 1) },
                            leadingIcon = { Icon(if (m.name == "GitHub") Icons.Rounded.Code else Icons.Rounded.Email, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove ${m.name}", Modifier.size(16.dp).clip(CircleShape).clickable { mentions.remove(m) }) },
                        )
                    }
                }
            }
            AnimatedVisibility(picked.isNotEmpty() || importing) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picked.forEach { p ->
                        Box {
                            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest) {
                                if (p.kind == "image" && p.file != null) {
                                    val bmp = rememberThumb("att/" + p.file.name)
                                    Box(Modifier.size(64.dp)) { if (bmp != null) Image(bmp, p.name, contentScale = ContentScale.Crop) }
                                } else Row(Modifier.heightIn(min = 64.dp).widthIn(max = 170.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(20.dp), tint = cs.primary)
                                    Text(p.name, Modifier.padding(start = 8.dp, end = 14.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Surface(shape = CircleShape, color = cs.inverseSurface, contentColor = cs.inverseOnSurface, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).clickable { p.file?.delete(); picked.remove(p) }) {
                                Icon(Icons.Rounded.Close, "Remove ${p.name}", Modifier.padding(3.dp))
                            }
                        }
                    }
                    if (importing) Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp)) }
                }
            }
            BasicTextField(
                value = input, onValueChange = onInput,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 168.dp).padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 4.dp).focusRequester(focus)
                    // On a computer Enter sends and Shift+Enter starts a new line, as people expect from a keyboard.
                    .onPreviewKeyEvent { e ->
                        if (!desktop || (e.key != Key.Enter && e.key != Key.NumPadEnter) || e.isShiftPressed || e.isCtrlPressed || e.isAltPressed) false
                        else { if (e.type == KeyEventType.KeyDown && !running) onSend(); true }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) Text(if (chat?.agent == true) "What should I work on?" else "Message", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
                        inner()
                    }
                },
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 372.dp
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onAttach, enabled = !running && picked.size < 6) { Icon(Icons.Rounded.Add, "Attach a file") }
                Segmented(
                    listOf(false to "Chat", true to "Work"), chat?.agent == true, { if (!running) onMode(it) }, Modifier.alpha(if (locked) 0.6f else 1f),
                    // On a narrow phone the words alone keep the row to one line.
                    icons = if (roomy) mapOf(false to Icons.Rounded.ChatBubbleOutline, true to Icons.Rounded.AutoAwesome) else emptyMap(), fill = false,
                )
                Spacer(Modifier.weight(1f))
                if (speech != null && !running) {
                    // Voice typing: what is said is added to the message. The button fills while it listens.
                    val on = speech.listening.value
                    val micBg by animateColorAsState(if (on) cs.tertiary else Color.Transparent, label = "mic")
                    val beat = if (on) pulse() else 1f
                    Surface(onClick = speech.toggle, enabled = !speech.busy.value, shape = CircleShape, color = micBg, contentColor = if (on) cs.onTertiary else cs.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp).size(44.dp).scale(if (on) 0.92f + 0.12f * beat else 1f)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (speech.busy.value) androidx.compose.material3.LoadingIndicator(Modifier.size(26.dp))
                            else Icon(if (on) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, if (on) "Stop listening" else "Speak your message")
                        }
                    }
                }
                // Send becomes Stop while a reply is in progress: the circle squares off and the colour changes.
                val corner by animateDpAsState(if (running) 14.dp else 24.dp, spring(dampingRatio = 0.5f, stiffness = 400f), label = "corner")
                val bg by animateColorAsState(if (running) cs.errorContainer else if (canSend) cs.primary else cs.surfaceContainerHighest, label = "sendBg")
                val fg by animateColorAsState(if (running) cs.onErrorContainer else if (canSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.5f), label = "sendFg")
                val press by animateFloatAsState(if (canSend || running) 1f else 0.9f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "sendScale")
                Surface(
                    onClick = { if (running) onStop() else onSend() }, enabled = running || canSend,
                    shape = RoundedCornerShape(corner), color = bg, contentColor = fg, modifier = Modifier.size(48.dp).scale(press),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedContent(running, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "sendIcon") { r ->
                            Icon(if (r) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (r) "Stop" else "Send")
                        }
                    }
                }
            }
            }
        }
    }
}

/** The chat as Markdown, for sharing. Tool steps and reasoning are left out. */
fun exportMarkdown(chat: Chat?, messages: List<app.pocketagent.data.Msg>, unpack: (String) -> String): String {
    val sb = StringBuilder("# ").append(chat?.title?.ifBlank { "Chat" } ?: "Chat").append("\n\n")
    for (m in messages) {
        if (m.role == "note") continue
        val text = parseArray(unpack(m.content)).mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n\n").trim()
        if (text.isEmpty()) continue
        sb.append(if (m.role == "user") "**You**\n\n" else "**Assistant**\n\n").append(text).append("\n\n")
    }
    return sb.toString().trim()
}


/** A connected account named in a message with "@", and for GitHub the repositories it is about. */
data class Mention(val name: String, val repos: List<String> = emptyList())

private val AT = Regex("(^|\\s)@(\\w*)$")

/** Chooses which GitHub repositories a message is about: one, several, or none for the whole account. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepoPicker(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    var repos by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    LaunchedEffect(Unit) {
        try {
            val text = withContext(Dispatchers.IO) { g.integrations.run("github", buildJsonObject { put("method", "GET"); put("path", "/user/repos?per_page=100&sort=pushed") }) }
            repos = parseArray(text).mapNotNull { (it as? JsonObject)?.str("full_name") }
        } catch (e: Exception) {
            error = e.message ?: "Could not load your repositories."
            repos = emptyList()
        }
    }
    val shown = repos.orEmpty().filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            SectionLabel("GitHub repositories")
            OutlinedTextField(query, { query = it.take(60) }, Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true, shape = CircleShape, placeholder = { Text("Search your repositories") })
            when {
                repos == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.LoadingIndicator(Modifier.size(40.dp)) }
                error.isNotEmpty() -> Text(error, Modifier.padding(8.dp), color = cs.error, style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it }) { name ->
                        val on = name in chosen
                        val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainerHigh, label = "repo")
                        Surface(onClick = { if (on) chosen.remove(name) else chosen.add(name) }, shape = MaterialTheme.shapes.large, color = bg, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(20.dp), tint = cs.primary)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (chosen.isEmpty()) "None picked: the whole account" else "${chosen.size} picked", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                TextButton(onDismiss) { Text("Cancel") }
                androidx.compose.material3.FilledTonalButton({ onDone(chosen.toList()) }) { Text("Done") }
            }
        }
    }
}
