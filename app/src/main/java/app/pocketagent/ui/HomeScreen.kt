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
fun HistoryScreen(onOpen: (Long) -> Unit, onMenu: (() -> Unit)?, onNew: () -> Unit, selected: Long?, inSheet: Boolean = false) {
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
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var deleting by remember { mutableStateOf<Chat?>(null) }
    val visible = chats?.filter { it.title.isNotBlank() || runs.containsKey(it.id) }

    // Inside the slide-up sheet it takes the sheet's own ground and needs no room for the status bar.
    Box(Modifier.fillMaxSize().background(if (wide || inSheet) androidx.compose.ui.graphics.Color.Transparent else cs.surface)) {
        Column(if (inSheet) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = if (onMenu != null) 4.dp else 22.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) IconButton(onMenu) { Icon(Icons.Rounded.Menu, "Menu") }
                Text("History", Modifier.weight(1f).padding(start = if (onMenu != null) 6.dp else 0.dp), style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                IconButton({ openShared = true }) { Icon(Icons.Rounded.QrCodeScanner, "Open a shared chat") }
                if (onMenu != null) FilledTonalIconButton(onNew) { Icon(Icons.Rounded.Add, "New chat") }
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
            UpdateBanner()
            when {
                visible == null -> {}
                visible.isEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(88.dp).background(cs.secondaryContainer, MaterialShapes.Sunny.toShape()), contentAlignment = Alignment.Center) {
                        Icon(if (query.isBlank()) Icons.Rounded.ChatBubbleOutline else Icons.Rounded.Search, null, Modifier.size(34.dp), tint = cs.onSecondaryContainer)
                    }
                    Text(if (query.isBlank()) "No chats yet" else "Nothing found", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleLarge)
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
                                        if (chat.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(top = 4.dp).size(14.dp), tint = cs.primary)
                                    }
                                }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(if (chat.pinned) "Unpin" else "Pin to top") }, { menu = false; scope.launch { g.dao.pin(chat.id, !chat.pinned) } }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                                DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = chat }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = chat }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (openShared) OpenSharedDialog({ openShared = false }) { id -> openShared = false; onOpen(id) }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton({ renaming = null; scope.launch { g.dao.rename(c.id, name.trim().ifEmpty { "New chat" }) } }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${c.title.take(40)}\"?") },
            text = { Text("The messages and attachments in it are removed from this phone.") },
            confirmButton = { TextButton({ deleting = null; scope.launch { g.deleteChat(c.id) } }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}
