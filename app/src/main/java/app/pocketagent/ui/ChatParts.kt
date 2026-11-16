package app.pocketagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.ai.PendingApproval
import app.pocketagent.ai.Risk
import app.pocketagent.ai.formatTokens
import app.pocketagent.ai.str
import app.pocketagent.LocalGraph
import app.pocketagent.ui.theme.LocalMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/**
 * A pill-shaped switch between a few choices. One highlight sits behind the selected choice and slides
 * to the next one with a spring when the selection changes.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, icons: Map<T, ImageVector> = emptyMap(), fill: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val lefts = remember(options.size) { mutableStateListOf<Int>().apply { repeat(options.size) { add(0) } } }
    val widths = remember(options.size) { mutableStateListOf<Int>().apply { repeat(options.size) { add(0) } } }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val motion = LocalMotion.current
    val targetX = lefts.getOrElse(index) { 0 }
    val targetW = widths.getOrElse(index) { 0 }
    LaunchedEffect(targetX, targetW) {
        if (targetW == 0) return@LaunchedEffect
        if (!placed || !motion) {
            // First layout: appear in place rather than sliding in from the edge.
            x.snapTo(targetX.toFloat()); w.snapTo(targetW.toFloat()); placed = true
        } else {
            launch { x.animateTo(targetX.toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f)) }
            launch { w.animateTo(targetW.toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f)) }
        }
    }
    Box(modifier.clip(CircleShape).background(cs.surfaceContainerHighest).padding(3.dp).height(IntrinsicSize.Min)) {
        if (placed) Box(
            Modifier.offset { IntOffset(x.value.roundToInt(), 0) }.width(with(LocalDensity.current) { w.value.coerceAtLeast(0f).toDp() }).fillMaxHeight()
                .background(cs.primary, CircleShape)
        )
        Row(if (fill) Modifier.fillMaxWidth() else Modifier) {
            options.forEachIndexed { i, (value, label) ->
                val on = i == index
                val color by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, label = "segment")
                Row(
                    (if (fill) Modifier.weight(1f) else Modifier)
                        .onGloballyPositioned { c -> lefts[i] = c.positionInParent().x.roundToInt(); widths[i] = c.size.width }
                        .clip(CircleShape)
                        .selectable(selected = on, role = Role.Tab, onClick = { if (!on) onSelect(value) })
                        .padding(horizontal = if (fill) 6.dp else 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons[value]?.let { Icon(it, null, Modifier.padding(end = 6.dp).size(18.dp), tint = color) }
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun rememberThumb(path: String): ImageBitmap? {
    val platform = LocalGraph.current.platform
    val bmp by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { platform.thumbnail(path) } }
    return bmp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserRow(row: UiRow.User, modifier: Modifier, onCopy: (String) -> Unit, onEdit: (UiRow.User) -> Unit, canEdit: Boolean) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), horizontalAlignment = Alignment.End) {
        if (row.attachments.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.attachments.take(4).forEach { a ->
                if (a.kind == "image") {
                    val bmp = rememberThumb(a.path)
                    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.size(84.dp)) {
                        if (bmp != null) Image(bmp, "Attached image", contentScale = ContentScale.Crop)
                    }
                } else Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 180.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(18.dp), tint = cs.primary)
                        Text(a.title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (row.text.isNotBlank()) Box {
            Surface(
                color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
                shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                modifier = Modifier.clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(row.text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Copy") }, { menu = false; onCopy(row.text) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                if (canEdit) DropdownMenuItem({ Text("Edit and resend") }, { menu = false; onEdit(row) }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) })
            }
        }
    }
}

@Composable
fun MdRow(row: UiRow.Md, modifier: Modifier) {
    val pad = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
    if (row.live || row.block is MdBlock.Code || row.block is MdBlock.Table || row.block == MdBlock.Rule) MdBlockView(row.block, modifier.then(pad), fadeTail = row.live && LocalMotion.current)
    else SelectionContainer(modifier) { MdBlockView(row.block, pad) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThinkingRow(row: UiRow.Thinking, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "chevron")
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))) {
        Row(
            Modifier.clip(CircleShape).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.active) LoadingIndicator(Modifier.size(22.dp), color = cs.primary)
            else Icon(Icons.Rounded.Psychology, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            Text(
                if (row.active) "Thinking" else "Thought process", Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            )
            Icon(Icons.Rounded.ExpandMore, if (open) "Hide" else "Show", Modifier.padding(start = 2.dp).size(18.dp).rotate(turn), tint = cs.onSurfaceVariant)
        }
        if (open) {
            Text(
                row.text.trim(), Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant,
            )
        } else if (row.active && row.text.isNotBlank()) {
            // While it is thinking, show the line it is on right now.
            Text(
                row.text.trim().takeLast(220).substringAfterLast('\n'), Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp).alpha(0.8f),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun toolIcon(name: String): ImageVector = when (name) {
    "web_search" -> Icons.Rounded.Search
    "web_fetch" -> Icons.Rounded.Language
    "code_execution", "bash_code_execution", "text_editor_code_execution" -> Icons.Rounded.Terminal
    "read_file" -> Icons.Rounded.Description
    "write_file", "edit_file" -> Icons.Rounded.EditNote
    "delete_file" -> Icons.Rounded.DeleteOutline
    "list_files" -> Icons.Rounded.FolderOpen
    "update_tasks" -> Icons.Rounded.Checklist
    "get_time" -> Icons.Rounded.Schedule
    "copy_to_clipboard" -> Icons.Rounded.ContentPaste
    "memory" -> Icons.Rounded.Bookmark
    "github" -> Icons.Rounded.Code
    "gmail_search", "gmail_read", "gmail_send" -> Icons.Rounded.Email
    else -> Icons.Rounded.Terminal
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolRow(row: UiRow.Tool, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val hasDetails = row.input.isNotBlank() || row.result.isNotBlank() || row.sources.isNotEmpty()
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (row.state == ToolState.ERROR) cs.errorContainer.copy(alpha = 0.45f) else cs.surfaceContainerLow,
    ) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = hasDetails) { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = cs.secondaryContainer, modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(toolIcon(row.name), null, Modifier.size(18.dp), tint = cs.onSecondaryContainer) }
                }
                Text(
                    row.title, Modifier.weight(1f).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(row.state, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "state") { st ->
                    when (st) {
                        ToolState.RUNNING -> LoadingIndicator(Modifier.size(24.dp), color = cs.primary)
                        ToolState.WAITING -> Icon(Icons.Rounded.HourglassTop, "Waiting for approval", Modifier.size(20.dp), tint = cs.tertiary)
                        ToolState.DONE -> Icon(Icons.Rounded.CheckCircle, "Done", Modifier.size(20.dp), tint = cs.primary)
                        ToolState.ERROR -> Icon(Icons.Rounded.ErrorOutline, "Failed", Modifier.size(20.dp), tint = cs.error)
                    }
                }
            }
            if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.input.isNotBlank()) Mono(row.input.take(1500))
                if (row.result.isNotBlank()) Mono(row.result)
                if (row.sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.sources.forEach { s ->
                        Text(
                            s.title, Modifier.clip(MaterialTheme.shapes.small).clickable { runCatching { uri.openUri(s.url) } }.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NoteRow(row: UiRow.Note, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    if (row.error) Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium, color = cs.errorContainer, contentColor = cs.onErrorContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp))
            Text(row.text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    } else Row(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, Modifier.size(15.dp), tint = cs.outline)
        Text(row.text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
fun SourcesRow(row: UiRow.Sources, modifier: Modifier) {
    val uri = LocalUriHandler.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.sources.take(10).forEach { s ->
            AssistChip(
                onClick = { runCatching { uri.openUri(s.url) } },
                label = { Text(s.title.take(34), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Language, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
fun FooterRow(row: UiRow.Footer, modifier: Modifier, onCopy: (String) -> Unit, onShare: (String) -> Unit, onRetry: () -> Unit, busy: Boolean) {
    val cs = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onCopy(row.text); copied = true }, Modifier.size(38.dp)) {
            AnimatedContent(copied, transitionSpec = { scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) togetherWith fadeOut() }, label = "copied") { done ->
                Icon(if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy, "Copy reply", Modifier.size(17.dp), tint = if (done) cs.primary else cs.onSurfaceVariant)
            }
        }
        IconButton({ onShare(row.text) }, Modifier.size(38.dp)) { Icon(Icons.Rounded.Share, "Share reply", Modifier.size(17.dp), tint = cs.onSurfaceVariant) }
        if (row.last && !busy) IconButton(onRetry, Modifier.size(38.dp)) { Icon(Icons.Rounded.Refresh, "Try again", Modifier.size(18.dp), tint = cs.onSurfaceVariant) }
        Spacer(Modifier.weight(1f))
        Text(
            shortModel(row.model),
            style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1,
        )
    }
}

/** "claude-opus-5-5" -> "Opus 5.5" */
fun shortModel(id: String): String {
    val parts = id.removePrefix("claude-").split('-').filter { it.length < 8 }
    if (parts.isEmpty()) return id
    val name = parts.first().replaceFirstChar { it.uppercase() }
    val version = parts.drop(1).filter { it.all(Char::isDigit) }.joinToString(".")
    return if (version.isEmpty()) name else "$name $version"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusRow(row: UiRow.Status, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(30.dp), color = if (row.retrying) cs.tertiary else cs.primary)
        if (row.text.isNotBlank()) Text(row.text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** The agent's checklist, pinned above the composer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TaskCard(tasks: List<Task>, running: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val done = tasks.count { it.status == "done" }
    val progress by animateFloatAsState(if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size, spring(dampingRatio = 0.8f, stiffness = 200f), label = "tasks")
    val current = tasks.firstOrNull { it.status == "in_progress" } ?: tasks.firstOrNull { it.status != "done" }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "chevron")
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
        Column(Modifier.clickable { open = !open }.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Checklist, null, Modifier.size(20.dp))
                Text(
                    if (done == tasks.size) "All ${tasks.size} tasks done" else current?.title ?: "Tasks",
                    Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("$done/${tasks.size}", style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Rounded.ExpandMore, if (open) "Hide tasks" else "Show tasks", Modifier.padding(start = 4.dp).size(20.dp).rotate(turn))
            }
            LinearWavyProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = cs.primary, trackColor = cs.onSecondaryContainer.copy(alpha = 0.15f),
                amplitude = { if (running && it < 0.99f) 1f else 0f },
            )
            if (open) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(t.status, transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "task") { st ->
                            when (st) {
                                "done" -> Icon(Icons.Rounded.CheckCircle, "Done", Modifier.size(20.dp), tint = cs.primary)
                                "in_progress" -> if (running) LoadingIndicator(Modifier.size(20.dp), color = cs.primary) else Icon(Icons.Rounded.HourglassTop, "In progress", Modifier.size(20.dp))
                                else -> Icon(Icons.Rounded.RadioButtonUnchecked, "To do", Modifier.size(20.dp), tint = cs.onSecondaryContainer.copy(alpha = 0.6f))
                            }
                        }
                        Text(
                            t.title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium,
                            color = if (t.status == "done") cs.onSecondaryContainer.copy(alpha = 0.6f) else cs.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** Asks before the agent changes something. Sits above the composer so it cannot be dismissed by accident. */
@Composable
fun ApprovalCard(p: PendingApproval, modifier: Modifier = Modifier, onAllow: () -> Unit, onAlways: () -> Unit, onDeny: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val destructive = p.risk == Risk.DESTRUCTIVE
    val outward = p.risk == Risk.OUTWARD
    val integrations = LocalGraph.current.integrations
    val preview = remember(p) {
        if (integrations.handles(p.name)) integrations.preview(p.name, p.input).take(3000) else when (p.name) {
            "write_file" -> p.input.str("content").orEmpty()
            "edit_file" -> "- " + p.input.str("old_text").orEmpty().take(400) + "\n+ " + p.input.str("new_text").orEmpty().take(400)
            "copy_to_clipboard" -> p.input.str("text").orEmpty()
            "memory" -> p.input.str("file_text") ?: p.input.str("new_str") ?: p.input.str("insert_text") ?: ""
            else -> ""
        }.take(1200)
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = if (destructive) cs.errorContainer else cs.tertiaryContainer, contentColor = if (destructive) cs.onErrorContainer else cs.onTertiaryContainer, shadowElevation = 3.dp) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(p.name), null, Modifier.size(22.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(if (destructive) "Allow this? It cannot be undone" else if (outward) "Allow this? It goes out from your account" else "Allow this?", style = MaterialTheme.typography.labelLarge)
                    Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (preview.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = MaterialTheme.shapes.small, color = cs.surface.copy(alpha = 0.55f), contentColor = cs.onSurface) {
                Text(
                    preview, Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDeny) { Text("Deny") }
                Spacer(Modifier.weight(1f))
                if (!destructive && !outward) OutlinedButton(onAlways) { Text("Always", maxLines = 1) }
                FilledTonalButton(onAllow) { Text("Allow", maxLines = 1) }
            }
        }
    }
}

/** A slow breathing pulse for things that are alive but waiting. */
@Composable
fun pulse(): Float {
    if (!LocalMotion.current) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    return v
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
