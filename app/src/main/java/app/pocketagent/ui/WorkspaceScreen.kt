package app.pocketagent.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import java.io.File

private fun size(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
}

/** The files the agent has written, and its memory notes. Read, share or delete them here. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkspaceScreen(onBack: () -> Unit) {
    val g = LocalGraph.current
    val wide = LocalWide.current
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var tab by rememberSaveable { mutableStateOf("files") }
    val root = if (tab == "files") g.workspace.root else g.memory.root
    var rel by rememberSaveable(tab) { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    // A line that says what just happened to a file ("Saved to Downloads"), gone again after a moment.
    var note by remember { mutableStateOf("") }
    LaunchedEffect(note) { if (note.isNotEmpty()) { kotlinx.coroutines.delay(3500); note = "" } }
    val dir = File(root, rel)
    val entries = remember(tab, rel, version) { dir.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() })) ?: emptyList() }

    OnBack(viewing != null || rel.isNotEmpty()) {
        if (viewing != null) viewing = null else rel = rel.substringBeforeLast('/', "")
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide || viewing != null || rel.isNotEmpty()) IconButton({ if (viewing != null) viewing = null else if (rel.isNotEmpty()) rel = rel.substringBeforeLast('/', "") else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(viewing?.name ?: "Workspace", Modifier.weight(1f).padding(start = if (wide && viewing == null && rel.isEmpty()) 18.dp else 6.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            viewing?.let { f ->
                IconButton({ runCatching { clipboard.setText(AnnotatedString(f.readText().take(500_000))) } }) { Icon(Icons.Rounded.ContentCopy, "Copy contents") }
                if (tab == "files") IconButton({ note = g.platform.saveCopy(f) ?: "Could not save a copy here. Use Share instead." }) { Icon(Icons.Rounded.Download, "Download a copy") }
                if (tab == "files") IconButton({ g.platform.shareFile(f) }) { Icon(Icons.Rounded.Share, if (g.platform.isDesktop) "Show in folder" else "Share file") }
                IconButton({ deleting = f }) { Icon(Icons.Rounded.DeleteOutline, "Delete file") }
            }
        }
        val file = viewing
        if (file != null) {
            val text = remember(file, version) {
                runCatching {
                    val bytes = file.readBytes()
                    if (bytes.take(4096).any { it == 0.toByte() }) "(Not a text file. Use Share to open it in another app.)" else String(bytes, 0, minOf(bytes.size, 400_000), Charsets.UTF_8)
                }.getOrDefault("(Could not read this file.)")
            }
            SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    text, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp), softWrap = false,
                )
            }
        } else {
            Segmented(listOf("files" to "Files", "memory" to "Memory"), tab, { tab = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (rel.isNotEmpty()) Text("/$rel", Modifier.padding(horizontal = 22.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            if (entries.isEmpty()) Column(Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(88.dp).background(cs.secondaryContainer, MaterialShapes.Clover4Leaf.toShape()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(34.dp), tint = cs.onSecondaryContainer)
                }
                Text(if (tab == "files") "No files yet" else "No memory notes yet", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (tab == "files") "Files written in Work mode appear here. They stay on this device." else "In Work mode, the model can keep notes here that carry over between chats.",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(entries, key = { it.path }) { f ->
                    Row(
                        Modifier.animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f)).fillMaxWidth()
                            .clickable { if (f.isDirectory) rel = if (rel.isEmpty()) f.name else "$rel/${f.name}" else viewing = f }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = CircleShape, color = if (f.isDirectory) cs.tertiaryContainer else cs.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(if (f.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(20.dp)) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (f.isDirectory) "${f.list()?.size ?: 0} items" else size(f.length()) + " · " + friendlyTime(f.lastModified()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        // A file can leave the workspace two ways: a copy into Downloads, or handed to another app (on a computer, shown in its folder).
                        if (f.isFile && tab == "files") {
                            IconButton({ note = g.platform.saveCopy(f) ?: "Could not save a copy here. Use Share instead." }) { Icon(Icons.Rounded.Download, "Download ${f.name}", tint = cs.onSurfaceVariant) }
                            IconButton({ g.platform.shareFile(f) }) { Icon(Icons.Rounded.Share, if (g.platform.isDesktop) "Show ${f.name} in its folder" else "Share ${f.name}", tint = cs.onSurfaceVariant) }
                        }
                        IconButton({ deleting = f }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${f.name}", tint = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    // Said over the list, not in it, so the rows do not jump under the finger.
    androidx.compose.animation.AnimatedVisibility(note.isNotEmpty(), Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Surface(shape = RoundedCornerShape(50), color = cs.inverseSurface, shadowElevation = 3.dp) {
            Text(note, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = cs.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text(if (f.isDirectory) "The folder and everything in it are erased from this phone." else "The file is erased from this phone.") },
            confirmButton = { TextButton({ f.deleteRecursively(); if (viewing == f) viewing = null; deleting = null; version++; g.sync.filesMayHaveChanged() }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}
