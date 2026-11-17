package app.pocketagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.data.Chat
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

fun friendlyTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)).substringBeforeLast(",")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onMenu: (() -> Unit)?, onNew: () -> Unit, selected: Long?, inSheet: Boolean = false, onDeleted: (Long) -> Unit = {}) {
    val g = LocalGraph.current
    val wide = LocalWide.current
    var openShared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val chats by remember(query) {
        if (query.isBlank()) g.dao.chats() else g.dao.search("%" + query.trim().replace("%", "") + "%")
    }.collectAsState(null)
    val runs by g.allRuns.collectAsState()
    val listState = rememberLazyListState()
    // What to show: newest first as usual, oldest first, or only one kind of chat.
    var filter by rememberSaveable { mutableStateOf("new") }
    val unread = g.settings.collectAsState().value?.unreadChats.orEmpty()
    val listed = chats?.filter { it.title.isNotBlank() || runs.containsKey(it.id) }
    val visible = listed?.let { all ->
        when (filter) {
            "old" -> all.sortedBy { it.updatedAt }
            "running" -> all.filter { runs.containsKey(it.id) }
            "unread" -> all.filter { it.id in unread }
            "pinned" -> all.filter { it.pinned }
            "chat" -> all.filter { !it.agent }
            "work" -> all.filter { it.agent }
            "today" -> { val since = System.currentTimeMillis() - 24 * 3600_000L; all.filter { it.updatedAt >= since } }
            else -> all
        }
    }

    var clearing by remember { mutableStateOf(false) }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Clear all chats?") },
        text = { Text("${(listed?.size ?: 0).let { n -> if (n == 1) "The one chat here and its attachments are" else "All $n chats and their attachments are" }} removed from this device, and from your linked devices when they next open. Files, memory notes and tasks stay. This cannot be undone.") },
        confirmButton = { TextButton({ clearing = false; g.scope.launch { g.clearHistory(); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { selected?.let(onDeleted) } } }) { Text("Clear all", color = cs.error) } },
        dismissButton = { TextButton({ clearing = false }) { Text("Cancel") } },
    )
    // Inside the slide-up sheet it takes the sheet's own ground and needs no room for the status bar.
    Box(Modifier.fillMaxSize().background(if (wide || inSheet) androidx.compose.ui.graphics.Color.Transparent else cs.surface)) {
        Column(if (inSheet) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = if (onMenu != null) 4.dp else 22.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) IconButton(onMenu) { Icon(Icons.Rounded.Menu, "Menu") }
                Text("History", Modifier.weight(1f).padding(start = if (onMenu != null) 6.dp else 0.dp), style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                if (!listed.isNullOrEmpty() && query.isBlank()) TextButton({ clearing = true }) { Text("Clear all") }
                IconButton({ openShared = true }) { Icon(Icons.Rounded.QrCodeScanner, "Open a shared chat") }
                if (onMenu != null) IconButton(onNew) { Icon(Icons.Rounded.Add, "New chat") }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), shape = CircleShape, color = cs.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                    BasicTextField(
                        query, { query = it }, Modifier.weight(1f).padding(horizontal = 12.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search chats", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant.copy(alpha = 0.7f)); inner() } },
                    )
                    if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear search", Modifier.size(20.dp).clip(CircleShape).combinedClickable { query = "" }, tint = cs.onSurfaceVariant)
                }
            }
            if (!listed.isNullOrEmpty()) Choices(
                listOf("new" to "New", "old" to "Old", "running" to "In progress", "unread" to "Unread", "today" to "Last 24 hours", "pinned" to "Pinned", "chat" to "Chat", "work" to "Work"),
                filter, { filter = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
            UpdateBanner()
            when {
                visible == null -> {}
                visible.isEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(88.dp).background(cs.secondaryContainer, MaterialShapes.Sunny.toShape()), contentAlignment = Alignment.Center) {
                        Icon(if (query.isBlank()) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Search, null, Modifier.size(34.dp), tint = cs.onSecondaryContainer)
                    }
                    Text(
                        when {
                            query.isNotBlank() -> "Nothing found"
                            listed.isNullOrEmpty() -> "No chats yet"
                            filter == "running" -> "Nothing in progress"
                            filter == "unread" -> "Nothing unread"
                            filter == "pinned" -> "Nothing pinned"
                            filter == "today" -> "Nothing in the last 24 hours"
                            filter == "work" -> "No work yet"
                            else -> "No chats here"
                        },
                        Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleLarge,
                    )
                    if (query.isNotBlank()) Text("No chat matches \"$query\".", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visible, key = { it.id }) { chat ->
                        var menu by remember { mutableStateOf(false) }
                        val running = runs.containsKey(chat.id)
                        Box(Modifier.animateItem(fadeInSpec = spring(stiffness = 300f), placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f))) {
                            Surface(
                                shape = MaterialTheme.shapes.large, color = if (chat.id == selected) cs.secondaryContainer else if (chat.pinned) cs.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).onSecondaryClick { menu = true }.combinedClickable(onClick = { onOpen(chat.id) }, onLongClick = { menu = true }),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = if (chat.agent) cs.tertiaryContainer else cs.primaryContainer, modifier = Modifier.size(44.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (running) LoadingIndicator(Modifier.size(30.dp), color = if (chat.agent) cs.onTertiaryContainer else cs.onPrimaryContainer)
                                            else Icon(if (chat.agent) Icons.Rounded.AutoAwesome else Icons.Rounded.ChatBubbleOutline, null, Modifier.size(20.dp), tint = if (chat.agent) cs.onTertiaryContainer else cs.onPrimaryContainer)
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                        Text(chat.title.ifBlank { "New chat" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (running) (runs[chat.id]?.remote?.takeIf { it.isNotEmpty() }?.let { "Working on $it…" } ?: "Working…") else Markdown.plain(chat.preview).ifBlank { shortModel(chat.model) },
                                            style = MaterialTheme.typography.bodyMedium, color = if (running) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(friendlyTime(chat.updatedAt), style = MaterialTheme.typography.labelSmall, color = cs.outline)
                                        // A small dot: a reply here has not been looked at yet. It goes when the chat is opened.
                                        if (chat.id in unread) Box(Modifier.padding(top = 5.dp).size(7.dp).background(cs.primary, CircleShape))
                                        if (chat.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(top = 4.dp).size(14.dp), tint = cs.primary)
                                    }
                                }
                            }
                            ChatMenu(chat, menu, { menu = false }, onDeleted)
                        }
                    }
                }
            }
        }
    }

    if (openShared) OpenSharedDialog({ openShared = false }) { id -> openShared = false; onOpen(id) }
}
