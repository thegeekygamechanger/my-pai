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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import app.pocketagent.data.incognito
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import app.pocketagent.ui.theme.LocalDark
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chatId: Long, initialText: String, onMenu: (() -> Unit)?, onNew: () -> Unit, onWorkspace: () -> Unit, onDeleted: () -> Unit, onOpenChat: (Long) -> Unit = {}, onIncognito: () -> Unit = {}) {
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

    val builder = remember(chatId) { RowBuilder(g.tools::describe, g.blobs::unpack) }
    val saved = remember(messages, run?.done, run?.pending, run == null, showThinking) { builder.persisted(messages, run, showThinking) }
    val rows = saved + builder.tail(messages, shown, showThinking)

    // A reply is written out at an even pace, however it arrived: in bursts from the network, or all at once after
    // a step of work. Whatever is on screen when a run starts stays as it is; everything that comes after it is
    // written, and the writing carries on to the end even if the run has already finished.
    val turnSize = remember(rows) { turnWeight(rows) }
    val sizeNow by rememberUpdatedState(turnSize)
    val running by rememberUpdatedState(run != null)
    var writing by remember(chatId) { mutableStateOf(false) }
    var written by remember(chatId) { mutableFloatStateOf(0f) }
    LaunchedEffect(chatId, run != null) {
        if (run == null || !motion) return@LaunchedEffect
        if (!writing) { written = sizeNow.toFloat(); writing = true }
    }
    LaunchedEffect(chatId, writing) {
        if (!writing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val behind = sizeNow - written
            // Close behind, it writes steadily; far behind (a long reply that landed whole), it writes faster, and never lags for long.
            if (behind > 0f) written = minOf(sizeNow.toFloat(), written + dt * (behind * 2.4f).coerceIn(170f, 2600f))
            // A new message, or a reply being written again: the turn starts over.
            else if (behind < 0f) written = sizeNow.toFloat()
            else if (!running) { writing = false; break }
        }
    }
    val writtenRows = if (writing && motion) writeOut(rows, written.toInt()) else rows
    // Touching the conversation while a reply is being written means "let me read". The screen then holds
    // exactly what it showed at that moment, while the reply carries on behind it, until the person asks to
    // see the newest again. Nothing is ever scrolled out from under a reader.
    var held0 by remember { mutableStateOf<List<UiRow>?>(null) }
    val shownRows = held0 ?: writtenRows
    val latestRows by rememberUpdatedState(rows)
    val reversed = remember(shownRows) { shownRows.asReversed() }
    val turnFrom = remember(shownRows) { shownRows.indexOfLast { it is UiRow.User } }

    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    // The view follows the newest content (the reply as it arrives, new tool steps) for as long as the
    // person has not scrolled up to read something older. Sending a message always brings it back down.
    var follow by remember { mutableStateOf(true) }
    // Following stops only when the person scrolls away themselves (see the list below), and starts again when they
    // come back to the bottom. The list's own movements while a reply arrives never switch it off.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80) follow = true
        }
    }
    val readingBack = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                // A drag or a turn of the wheel towards older text: the person wants to read, so the view stops following.
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 1f) follow = false
                return Offset.Zero
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
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val incognito = chat?.incognito == true
    // This is the chat being looked at. Opening it, or coming back to the app while it is open, clears its unread dot.
    androidx.compose.runtime.DisposableEffect(chatId) { g.viewing.value = chatId; onDispose { if (g.viewing.value == chatId) g.viewing.value = null } }
    val unreadHere = settings?.unreadChats?.contains(chatId) == true
    LaunchedEffect(chatId, unreadHere) {
        if (!unreadHere) return@LaunchedEffect
        while (!g.platform.inForeground()) delay(1000)
        g.markRead(chatId)
    }
    val unreadElsewhere = settings?.unreadChats?.any { it != chatId } == true
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
    // A code nobody answered for: the code, and the text it came from in case the person meant it as a message.
    var notFound by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Set while a message is sent on purpose although it looks like a share code.
    var plain by remember { mutableStateOf(false) }
    fun openShared(code: String, original: String) {
        if (fetching) return
        fetching = true
        // In the screen's own scope: clearing the message box must not cut the fetch short.
        scope.launch {
            val got = try { ShareClient.fetch(g, code) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } finally { fetching = false }
            if (got == null) notFound = code to original else incoming = got
        }
    }
    LaunchedEffect(input) {
        if (plain) return@LaunchedEffect
        // Either the labelled form anywhere in the text, or the bare four characters pasted into an empty box.
        val hit = ShareCode.find(input)
        val code = hit?.groupValues?.get(1)?.uppercase() ?: ShareCode.parse(input).takeIf { pastedWhole } ?: return@LaunchedEffect
        val original = input
        input = if (hit != null) input.replace(hit.value, "").trim() else ""
        openShared(code, original)
    }
    fun send() {
        val c = chat ?: return
        val text = input.trim()
        if (run != null || (text.isEmpty() && picked.isEmpty() && mentions.isEmpty())) return
        // A message that is nothing but a share code, typed or pasted, opens the shared chat instead of being sent.
        if (!plain && editing == null && picked.isEmpty() && mentions.isEmpty()) ShareCode.parse(text)?.let { code -> input = ""; openShared(code, text); return }
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
        // "@github", "@gmail" or "@mail" typed out by hand counts the same as picking the account from the list.
        var typed = text
        for ((name, words) in listOf("GitHub" to "github|git", "Gmail" to "gmail|mail|email")) {
            val word = Regex("(?<![\\w@])@($words)\\b", RegexOption.IGNORE_CASE)
            if (name in connections && word.containsMatchIn(typed)) {
                typed = typed.replace(word, "").replace(Regex(" {2,}"), " ").trim()
                if (mentions.none { !it.mcp && it.name == name }) mentions += Mention(name)
            }
        }
        // "@mcp" typed out by hand asks for the MCP servers that are switched on; servers picked from the list are named.
        val mcpWord = Regex("(?<![\\w@])@mcp\\b", RegexOption.IGNORE_CASE)
        var anyMcp = false
        if (settings?.mcpServers.orEmpty().any { it.on } && mcpWord.containsMatchIn(typed)) {
            typed = typed.replace(mcpWord, "").replace(Regex(" {2,}"), " ").trim()
            anyMcp = true
        }
        val named = mentions.filter { it.mcp }.joinToString(", ") { it.name }
        val lead = mentions.filter { !it.mcp }.joinToString("") { m -> "@" + m.name + (if (m.repos.isEmpty()) "" else " " + m.repos.joinToString(", ")) + "\n" } +
            (if (named.isNotEmpty()) "@mcp $named\n" else if (anyMcp) "@mcp\n" else "")
        if (typed.isNotEmpty() || lead.isNotEmpty()) blocks += buildJsonObject { put("type", "text"); put("text", if (lead.isEmpty()) typed else if (typed.isEmpty()) lead.trim() else lead + "\n" + typed) }
        if (blocks.none { it.str("type") == "text" }) blocks += buildJsonObject { put("type", "text"); put("text", "Please look at the attached file${if (blocks.size == 1) "" else "s"}.") }
        buzz()
        askNotifications()
        scope.launch {
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
        // Full colour and a slow drift on an empty chat. Once the conversation starts it drifts more slowly still,
        // and flows while a reply is being written.
        val started = rows.isNotEmpty()
        // The colours stay clearly in view once a chat is under way, in dark mode too; they are only a little calmer than on the first screen.
        val glow by animateFloatAsState(if (!started) 1f else if (run != null) 0.9f else 0.78f, tween(900), label = "glow")
        if (chat != null) Backdrop(backdropColors(hourNow, weatherNow), Modifier.fillMaxSize(), speed = if (!started) 2.7f else if (run != null) 9f else 1.4f, strength = glow)
        Column(Modifier.fillMaxSize().imePadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) Box {
                    IconButton(onMenu) { Icon(Icons.Rounded.Menu, if (unreadElsewhere) "Menu, with unread replies" else "Menu") }
                    // A small dot on the menu button says a reply is waiting in another chat.
                    if (unreadElsewhere) Box(Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 9.dp).size(7.dp).background(cs.primary, CircleShape))
                }
                // The name of the chat, and under it the model that answers. A tap or a long press on it opens the model and tools.
                Column(
                    Modifier.weight(1f).padding(start = if (onMenu == null) 14.dp else 0.dp).clip(MaterialTheme.shapes.small)
                        .combinedClickable(onClickLabel = "Model and tools", onLongClick = { buzz(); showControls = true }) { showControls = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(chat?.title?.ifBlank { "New chat" } ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val c = chat
                    if (c != null) {
                        fun named(id: String) = g.models(c.provider).firstOrNull { it.id == id }?.name?.removePrefix("Claude ") ?: shortModel(id)
                        // On Auto, the model that wrote the last reply; before there is one, just "Auto".
                        val used = if (c.model == app.pocketagent.ai.AutoModel.ID) messages.lastOrNull { it.role == "assistant" && it.model.isNotEmpty() }?.model else null
                        Text(
                            (if (incognito) "Incognito · " else "") + when {
                                used != null -> named(used)
                                c.model == app.pocketagent.ai.AutoModel.ID -> app.pocketagent.ai.AutoModel.NAME
                                else -> named(c.model).ifBlank { "Choose a model" }
                            },
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // New chat, then the three dots: bare icons sitting on the chat's own colours, with no ring or ground of their
                // own, drawn in the theme's text colour so they read in light and in dark.
                // New chat wakes up once something has been said here: an empty chat is already a new one.
                val canStartNew = messages.isNotEmpty() || run != null
                IconButton(onNew, enabled = canStartNew) { Icon(Icons.Rounded.Add, "New chat", tint = cs.onSurface.copy(alpha = if (canStartNew) 1f else 0.34f)) }
                // Everything else about this chat: handing it to someone with a code, and going incognito.
                Box(Modifier.padding(end = 6.dp)) {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More for this chat", tint = cs.onSurface) }
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(24.dp), containerColor = cs.surfaceContainerHigh) {
                        @Composable
                        fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                            { Text(label) }, { menu = false; onClick() }, Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)), leadingIcon = { Icon(icon, null) }, enabled = enabled,
                            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = cs.onSurface, leadingIconColor = cs.onSurfaceVariant),
                        )
                        // There is nothing to hand over until something has been said, and an incognito chat is never handed over.
                        item("Share with a code", Icons.Rounded.Share, enabled = messages.isNotEmpty() && !incognito) { sharing = true }
                        if (incognito) item("Leave incognito", Icons.Rounded.Visibility) { buzz(); onNew() }
                        else item("Incognito chat", Icons.Rounded.VisibilityOff) { buzz(); onIncognito() }
                        item("Model and tools", Icons.Rounded.Tune) { showControls = true }
                    }
                }
            }

            // A newer version is announced here, on the screen the app opens to, and one tap installs it.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { UpdateBanner(Modifier.widthIn(max = 560.dp)) }

            // Messages. On a wide window the column is kept to a comfortable reading width.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
                    if (rows.isEmpty() && chat != null) EmptyChat(chatId, chat!!.agent, incognito)
                    else LazyColumn(
                        Modifier.fillMaxSize().nestedScroll(readingBack),
                        state = listState, reverseLayout = true, contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
                    ) {
                        itemsIndexed(reversed, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { i, row ->
                            // Parts of the reply that is arriving open out as they appear. They are not slid into place: while a
                            // reply is growing every row above it moves on each frame, and animating that makes the text lag and overlap.
                            val arriving = (writing || run != null) && reversed.size - 1 - i > turnFrom
                            val spread = Modifier.spreadIn(arriving)
                            val m = if (row is UiRow.Md && row.live) spread else spread.animateItem(fadeInSpec = if (arriving) null else spring(stiffness = 300f), placementSpec = null, fadeOutSpec = null)
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
                        mentions = mentions, connections = connections,
                        onMention = { name -> if (name == "GitHub") pickingRepos = true else if (mentions.none { !it.mcp && it.name == name }) mentions += Mention(name) },
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
    notFound?.let { (code, original) ->
        AlertDialog(
            onDismissRequest = { notFound = null },
            title = { Text("No shared chat found") },
            text = { Text("No chat is shared under $code now. A code lasts ten minutes, with My PAI open on the sharing device.") },
            confirmButton = { TextButton({ notFound = null; openShared(code, original) }) { Text("Try again") } },
            dismissButton = {
                Row {
                    TextButton({ notFound = null; plain = true; input = original; send(); plain = false }) { Text("Send as message") }
                    TextButton({ notFound = null }) { Text("Cancel") }
                }
            },
        )
    }
    incoming?.takeIf { it.setup != null }?.let { shared ->
        SetupOfferDialog(shared) { said -> incoming = null; if (said != null) scope.launch { snack.showSnackbar(said) } }
    }
    incoming?.takeIf { it.setup == null }?.let { shared ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text(if (shared.by.isEmpty()) "Open this shared chat?" else "Open the chat ${shared.by} shared?") },
            text = { Text("\"${shared.title}\", ${shared.messages.size} message${if (shared.messages.size == 1) "" else "s"}. It opens as a new chat of your own; nothing else of theirs comes with it.") },
            confirmButton = { TextButton({ incoming = null; scope.launch { onOpenChat(ShareClient.save(g, shared)) } }) { Text("Open") } },
            dismissButton = { TextButton({ incoming = null }) { Text("Not now") } },
        )
    }
    if (pickingRepos) RepoPicker(mentions.firstOrNull { !it.mcp && it.name == "GitHub" }?.repos.orEmpty(), onDismiss = { pickingRepos = false }) { repos ->
        pickingRepos = false
        mentions.removeAll { !it.mcp && it.name == "GitHub" }
        mentions += Mention("GitHub", repos)
    }

}

/** An empty chat: a greeting that changes with the hour, the weather and the person's name, over slowly moving colour. */
@Composable
private fun EmptyChat(chatId: Long, work: Boolean, incognito: Boolean = false) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val settings by g.settings.collectAsState()
    val weather by g.weather.collectAsState()
    val hour = remember(chatId) { java.time.LocalTime.now().hour }
    // A new pick every time a fresh chat is opened.
    val seed = remember(chatId) { kotlin.random.Random.nextInt() }
    // An incognito chat says what it is instead of greeting: nothing here is kept.
    val (title, line) = remember(seed, settings?.name, weather, work, incognito) {
        if (incognito) "Incognito chat" to "Not saved, not synced, no memory notes. Erased when you leave."
        else greeting(settings?.name.orEmpty(), hour, weather, work, seed)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AnimatedContent(title, transitionSpec = { (fadeIn(tween(450)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(200)) }, label = "greeting") { t ->
                Text(t, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            }
            AnimatedContent(line, transitionSpec = { fadeIn(tween(600, delayMillis = 120)) togetherWith fadeOut(tween(200)) }, label = "greetingLine") { l ->
                Text(l, Modifier.padding(top = 12.dp).widthIn(max = 380.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The icons of the message bar (attach, speak, send) share one size and one touch area. */
private val BAR_BUTTON = 44.dp
private val BAR_ICON = 24.dp

/** Short invitations for the empty message box: one to four words each, so they never wrap. */
private val CHAT_HINTS = listOf(
    "Ask me anything", "What's on your mind?", "Curious about something?", "Got a question?", "Think out loud", "Start with a thought",
    "What shall we explore?", "Say anything", "Wonder away", "Ask away", "Spark an idea", "Your move", "What's the puzzle?",
    "Dream something up", "Need a second opinion?", "Let's untangle it", "Name the topic", "Pick my brain", "Where to today?",
    "Tell me a problem", "Something to figure out?", "Go on", "I'm listening", "What's new?",
)
private val WORK_HINTS = listOf(
    "Hand me a task", "What needs doing?", "Give me a job", "Name the mission", "Drop a to-do", "What shall I build?",
    "Put me to work", "Assign me something", "Next task, please", "What should I make?", "Convert, draft or fix?",
    "Point me somewhere", "Let's get it done", "Queue something up", "Describe the result", "What's the brief?",
)

/** The words shown in the empty message box: a new line for every chat, changing every few seconds, never the same twice running. */
@Composable
private fun hint(work: Boolean, chatId: Long?): String {
    val pool = if (work) WORK_HINTS else CHAT_HINTS
    var words by remember(work, chatId) { mutableStateOf(pool.random()) }
    val motion = LocalMotion.current
    LaunchedEffect(work, chatId, motion) {
        while (motion) {
            delay(5200)
            words = (pool - words).random()
        }
    }
    return words
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
    val offers = if (at == null) emptyList() else connections.filter { it.startsWith(at.groupValues[2], ignoreCase = true) && (it == "GitHub" || mentions.none { m -> !m.mcp && m.name == it }) }
    // "@mcp" (or the start of a server's name) lists the MCP servers that were added, each marked connected or not.
    val g = LocalGraph.current
    val mcpStatus by g.mcpStatus.collectAsState()
    val allServers = g.settings.collectAsState().value?.mcpServers.orEmpty()
    val servers = if (at == null) emptyList() else allServers.filter { s ->
        ("mcp".startsWith(at.groupValues[2], ignoreCase = true) || s.name.startsWith(at.groupValues[2], ignoreCase = true)) && mentions.none { m -> m.mcp && m.name == s.name }
    }
    // Usable now: it answered when last asked, or it is one the app came with, whose tools are known without asking.
    fun connected(s: app.pocketagent.ai.McpServer) = s.on && (mcpStatus[s.id] == app.pocketagent.ai.McpStatus.CONNECTED || (s.untouched && s.tools.isNotEmpty() && mcpStatus[s.id] != app.pocketagent.ai.McpStatus.FAILED))
    // A connected server is pulled into the message; one that is not is connected first, and stays in the list.
    fun pick(s: app.pocketagent.ai.McpServer) {
        if (connected(s)) { onInput(input.substring(0, at!!.range.first + at.groupValues[1].length)); mentions += Mention(s.name, mcp = true) }
        else g.scope.launch { g.connectMcp(s.id) }
    }
    // The list opening is the moment to find out which servers answer, once per start of the app.
    LaunchedEffect(servers.isNotEmpty()) { if (servers.isNotEmpty()) g.checkMcp() }
    // While a reply is being generated a soft band of colour travels round the composer's edge.
    val glow = if (running && LocalMotion.current) {
        val t = rememberInfiniteTransition(label = "glow")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "glow")
        v
    } else -1f
    val edge by animateFloatAsState(if (running) 1f else 0f, tween(450), label = "edge")
    val c1 = cs.primary; val c2 = cs.tertiary; val c3 = cs.secondary
    // A rounded bar of frosted colour, so the chat's colours show through instead of a dark slab across the screen.
    Surface(
        shape = RoundedCornerShape(34.dp), color = cs.surfaceContainerHigh.copy(alpha = if (LocalDark.current) 0.42f else 0.62f),
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.onSurface.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            if (edge > 0f) {
                val w = size.width
                val shift = if (glow < 0f) 0f else glow * w * 2f
                val brush = Brush.linearGradient(listOf(c1, c2, c3, c1), start = Offset(shift - w, 0f), end = Offset(shift, size.height), tileMode = TileMode.Mirror)
                drawRoundRect(brush, cornerRadius = CornerRadius(34.dp.toPx()), style = Stroke(2.dp.toPx()), alpha = edge * 0.9f)
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
            AnimatedVisibility((offers.isNotEmpty() || servers.isNotEmpty()) && !running, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offers.forEach { name ->
                        AssistChip(
                            onClick = { onInput(input.substring(0, at!!.range.first + at.groupValues[1].length)); onMention(name) },
                            label = { Text(name) }, leadingIcon = { Icon(if (name == "GitHub") Icons.Rounded.Code else Icons.Rounded.Email, null, Modifier.size(18.dp)) },
                        )
                    }
                    servers.forEach { s ->
                        val on = connected(s)
                        val checking = mcpStatus[s.id] == app.pocketagent.ai.McpStatus.CHECKING
                        val failed = s.on && mcpStatus[s.id] == app.pocketagent.ai.McpStatus.FAILED
                        AssistChip(
                            onClick = { pick(s) }, enabled = !checking,
                            label = { Text(s.name + if (on && s.untouched) " · Ready" else if (on) " · Connected" else if (checking) " · Connecting" else if (failed) " · Not connected" else "", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Rounded.Extension, null, Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (on) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = cs.primary)
                                else if (checking) androidx.compose.material3.LoadingIndicator(Modifier.size(18.dp))
                                else Icon(if (failed) Icons.Rounded.Refresh else Icons.Rounded.Link, if (failed) "Reconnect ${s.name}" else "Connect ${s.name}", Modifier.size(18.dp), tint = cs.primary)
                            },
                        )
                    }
                }
            }
            AnimatedVisibility(mentions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mentions.toList().forEach { m ->
                        InputChip(
                            selected = true, onClick = { if (m.name == "GitHub" && !m.mcp) onMention("GitHub") },
                            label = { Text(m.name + when (m.repos.size) { 0 -> ""; 1 -> " · " + m.repos[0].substringAfter('/'); else -> " · ${m.repos.size} repositories" }, maxLines = 1) },
                            leadingIcon = { Icon(if (m.mcp) Icons.Rounded.Extension else if (m.name == "GitHub") Icons.Rounded.Code else Icons.Rounded.Email, null, Modifier.size(18.dp)) },
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
                        else {
                            if (e.type == KeyEventType.KeyDown && !running) {
                                // With the list of accounts showing, Enter takes the first one, as it would in any pick list.
                                if (offers.isNotEmpty()) { onInput(input.substring(0, at!!.range.first + at.groupValues[1].length)); onMention(offers.first()) }
                                else if (servers.isNotEmpty()) pick(servers.first())
                                else onSend()
                            }
                            true
                        }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        // An empty box invites with a few words that keep changing, always on one line.
                        if (input.isEmpty()) AnimatedContent(hint(chat?.agent == true, chat?.id), transitionSpec = { fadeIn(tween(520)) togetherWith fadeOut(tween(260)) }, label = "hint") { words ->
                            Text(words, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 372.dp
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The three icons of the bar are one size and sit the same distance from its edges, so the row reads as even.
                IconButton(onAttach, Modifier.size(BAR_BUTTON), enabled = !running && picked.size < 6) { Icon(Icons.Rounded.Add, "Attach a file", Modifier.size(BAR_ICON)) }
                Segmented(
                    listOf(false to "Chat", true to "Work"), chat?.agent == true, { if (!running) onMode(it) }, Modifier.alpha(if (locked) 0.6f else 1f),
                    // On a narrow phone the words alone keep the row to one line.
                    icons = if (roomy) mapOf(false to Icons.Rounded.ChatBubbleOutline, true to Icons.Rounded.AutoAwesome) else emptyMap(), fill = false, compact = true,
                )
                Spacer(Modifier.weight(1f))
                if (speech != null && !running) {
                    // Voice typing: what is said is added to the message. A bare icon like the others on the bar, drawn as an
                    // outline so it weighs the same as the arrow beside it; while it listens it turns into moving bars in the
                    // theme's colour and gently pulses. Nothing fills in behind it.
                    val on = speech.listening.value
                    val micTint by animateColorAsState(if (on) cs.primary else cs.onSurfaceVariant, label = "mic")
                    val beat = if (on) pulse() else 1f
                    IconButton(speech.toggle, Modifier.size(BAR_BUTTON), enabled = !speech.busy.value) {
                        if (speech.busy.value) androidx.compose.material3.LoadingIndicator(Modifier.size(BAR_ICON))
                        else Icon(if (on) Icons.Rounded.GraphicEq else Icons.Rounded.MicNone, if (on) "Stop listening" else "Speak your message", Modifier.size(BAR_ICON).scale(if (on) 0.9f + 0.2f * beat else 1f), tint = micTint)
                    }
                }
                // Send and Stop are bare icons on the bar, with no circle behind them. The arrow takes the theme's colour when
                // there is something to send and is faint when there is not. While a reply is being written it becomes a grey
                // square that still stops it: nothing turns red.
                val tint by animateColorAsState(if (running) cs.onSurfaceVariant else if (canSend) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f), label = "sendTint")
                IconButton({ if (running) onStop() else onSend() }, Modifier.size(BAR_BUTTON), enabled = running || canSend) {
                    AnimatedContent(running, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "sendIcon") { r ->
                        Icon(if (r) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (r) "Stop" else "Send", Modifier.size(BAR_ICON), tint = tint)
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


/** A connected account or an MCP server named in a message with "@", and for GitHub the repositories it is about. */
data class Mention(val name: String, val repos: List<String> = emptyList(), val mcp: Boolean = false)

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
