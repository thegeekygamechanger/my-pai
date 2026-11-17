package app.pocketagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.LocalGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** What a file is, as far as showing it goes. */
sealed interface FileContent {
    object Loading : FileContent
    class Markdown(val blocks: List<MdBlock>, val raw: String) : FileContent
    class Plain(val text: String, val note: String = "", /** Prose reads better folded to the width of the screen; code and tables keep their lines. */ val wrap: Boolean = false) : FileContent
    class Picture(val image: ImageBitmap) : FileContent
    class Pdf(val pages: Int) : FileContent
    /** Nothing in the app can show it: [why] says so, and another app is offered. */
    class Other(val why: String) : FileContent
}

/** Reads the words out of office files and lists what is in archives, so they can be looked at without another app. */
object FilePeek {
    private const val MAX_ENTRY = 12L * 1024 * 1024
    const val MAX_TEXT = 400_000

    private fun plain(xml: String): String = xml.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun entry(zip: ZipFile, name: String): String? = zip.getEntry(name)?.takeIf { it.size in 0..MAX_ENTRY }?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

    /** The text of a Word, PowerPoint, Excel or OpenDocument file, or null when it is not one or cannot be read. */
    fun office(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            when (file.extension.lowercase()) {
                "docx" -> entry(zip, "word/document.xml")?.let { plain(it.replace(Regex("</w:p>|<w:br[^>]*/>"), "\n").replace(Regex("<w:tab[^>]*/>"), "\t")) }
                "odt", "ods", "odp" -> entry(zip, "content.xml")?.let { plain(it.replace(Regex("</text:p>|</text:h>|<text:line-break/>"), "\n").replace(Regex("</table:table-cell>"), "\t")) }
                "pptx" -> zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList().mapIndexedNotNull { i, n -> entry(zip, n)?.let { "Slide ${i + 1}\n" + plain(it.replace("</a:p>", "\n")).trim() } }
                    .joinToString("\n\n").ifBlank { null }
                "xlsx" -> {
                    val shared = entry(zip, "xl/sharedStrings.xml")?.let { s -> Regex("(?s)<si>(.*?)</si>").findAll(s).map { plain(it.groupValues[1]) }.toList() }.orEmpty()
                    zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList().mapIndexedNotNull { i, n ->
                        val sheet = entry(zip, n) ?: return@mapIndexedNotNull null
                        val rows = Regex("(?s)<row[^>]*>(.*?)</row>").findAll(sheet).take(2000).map { row ->
                            Regex("(?s)<c([^>]*?)(?:/>|>(.*?)</c>)").findAll(row.groupValues[1]).joinToString("\t") { c ->
                                val value = Regex("(?s)<v>(.*?)</v>").find(c.groupValues[2])?.groupValues?.get(1) ?: Regex("(?s)<t[^>]*>(.*?)</t>").find(c.groupValues[2])?.groupValues?.get(1).orEmpty()
                                if (c.groupValues[1].contains("t=\"s\"")) shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else plain(value)
                            }
                        }.toList()
                        "Sheet ${i + 1}\n" + rows.joinToString("\n")
                    }.joinToString("\n\n").ifBlank { null }
                }
                else -> null
            }
        }
    }.getOrNull()?.lines()?.joinToString("\n") { it.trimEnd() }?.replace(Regex("\n{3,}"), "\n\n")?.trim()?.take(MAX_TEXT)

    /** What an archive holds: names and sizes. */
    fun archive(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val all = zip.entries().asSequence().take(500).toList()
            all.joinToString("\n") { e -> if (e.isDirectory) e.name else "${e.name}  (${"%,d".format(maxOf(0, e.size))} bytes)" } + (if (zip.size() > 500) "\n... and ${zip.size() - 500} more" else "")
        }
    }.getOrNull()

    val PICTURES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    val OFFICE = setOf("docx", "pptx", "xlsx", "odt", "ods", "odp")
    val ARCHIVES = setOf("zip", "jar", "apk", "epub")
}

/** Works out how to show [file]; run off the main thread. */
private fun load(file: File, picture: (File) -> ImageBitmap?, pdfPages: (File) -> Int): FileContent {
    if (!file.isFile) return FileContent.Other("This file is no longer there.")
    val ext = file.extension.lowercase()
    if (ext in FilePeek.PICTURES) return picture(file)?.let { FileContent.Picture(it) } ?: FileContent.Other("This picture could not be shown.")
    if (ext == "pdf") return pdfPages(file).let { if (it > 0) FileContent.Pdf(it) else FileContent.Other("A PDF is opened with your PDF reader on this device.") }
    if (ext in FilePeek.OFFICE) return FilePeek.office(file)?.let { FileContent.Plain(it, "The words of the file, without its layout. Open it in another app to see it as it was made.", wrap = ext == "docx" || ext == "odt" || ext == "pptx" || ext == "odp") } ?: FileContent.Other("This file could not be read here.")
    if (ext in FilePeek.ARCHIVES) return FilePeek.archive(file)?.let { FileContent.Plain(it, "What is inside this archive.") } ?: FileContent.Other("This archive could not be read here.")
    val bytes = runCatching { file.inputStream().use { it.readNBytes(FilePeek.MAX_TEXT) } }.getOrNull() ?: return FileContent.Other("This file could not be read.")
    if (bytes.take(4096).any { it == 0.toByte() }) return FileContent.Other("This kind of file (.${ext.ifEmpty { "?" }}) is not text or a picture, so it is opened with another app.")
    val text = String(bytes, Charsets.UTF_8)
    val cut = if (file.length() > bytes.size) "Only the first part of this long file is shown." else ""
    return if (ext == "md" || ext == "markdown") FileContent.Markdown(Markdown.parse(text), text) else FileContent.Plain(text, cut, wrap = ext == "txt" || ext == "log" || ext.isEmpty())
}

/** The inside of a file, shown by the app itself: Markdown as a page, text as text, a picture as a picture, a PDF page by page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileBody(file: File, modifier: Modifier = Modifier, version: Int = 0) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val content by produceState<FileContent>(FileContent.Loading, file, version) {
        value = withContext(Dispatchers.IO) { runCatching { load(file, { g.platform.picture(it, 2400) }, g.platform::pdfPages) }.getOrElse { FileContent.Other("This file could not be read.") } }
    }
    var said by remember(file) { mutableStateOf("") }
    Box(modifier.fillMaxSize()) {
        when (val c = content) {
            FileContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            is FileContent.Markdown -> SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    items(c.blocks) { b -> MdBlockView(b, Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) }
                }
            }
            is FileContent.Plain -> Column(Modifier.fillMaxSize()) {
                if (c.note.isNotEmpty()) Text(c.note, Modifier.padding(horizontal = 18.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                SelectionContainer(Modifier.fillMaxSize()) {
                    if (c.wrap) Text(c.text, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(18.dp), style = MaterialTheme.typography.bodyLarge)
                    else Text(
                        c.text, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp), softWrap = false,
                    )
                }
            }
            is FileContent.Picture -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.TopCenter) {
                Image(c.image, file.name, Modifier.fillMaxWidth().widthIn(max = 1100.dp), contentScale = ContentScale.FillWidth)
            }
            is FileContent.Pdf -> LazyColumn(Modifier.fillMaxSize().background(cs.surfaceContainerLow).navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                items((0 until c.pages).toList()) { i ->
                    val page by produceState<ImageBitmap?>(null, file, i) { value = withContext(Dispatchers.IO) { g.platform.pdfPage(file, i, 1400) } }
                    page?.let { Image(it, "Page ${i + 1}", Modifier.widthIn(max = 900.dp).fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                        ?: Box(Modifier.fillMaxWidth().padding(vertical = 120.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
            is FileContent.Other -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(44.dp), tint = cs.onSurfaceVariant)
                Text(file.name, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(c.why, Modifier.padding(top = 6.dp, bottom = 18.dp).widthIn(max = 420.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ said = if (g.platform.openOutside(file)) "" else "No app on this device opens this kind of file. Share it, or download a copy." }) { Text("Open with another app") }
                    OutlinedButton({ g.platform.shareFile(file) }) { Text(if (g.platform.isDesktop) "Show in folder" else "Share") }
                }
                if (said.isNotEmpty()) Text(said, Modifier.padding(top = 12.dp).widthIn(max = 420.dp), style = MaterialTheme.typography.bodySmall, color = cs.error, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A file on a screen of its own, over whatever was open: what "Open" on a file in a chat leads to. */
@Composable
fun FileViewer(file: File, onClose: () -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var note by remember(file) { mutableStateOf("") }
    LaunchedEffect(note) { if (note.isNotEmpty()) { kotlinx.coroutines.delay(3500); note = "" } }
    val isText = remember(file) { file.extension.lowercase().let { it !in FilePeek.PICTURES && it != "pdf" && it !in FilePeek.ARCHIVES } }
    OnBack(true, onClose)
    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(file.name, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isText) IconButton({ runCatching { clipboard.setText(AnnotatedString(FilePeek.office(file) ?: file.readText().take(500_000))); note = "Copied" } }) { Icon(Icons.Rounded.ContentCopy, "Copy the text") }
            IconButton({ note = g.platform.saveCopy(file) ?: "Could not save a copy here. Use Share instead." }) { Icon(Icons.Rounded.Download, "Download a copy") }
            IconButton({ g.platform.shareFile(file) }) { Icon(Icons.Rounded.Share, if (g.platform.isDesktop) "Show in folder" else "Share") }
            IconButton({ if (!g.platform.openOutside(file)) note = "No app on this device opens this kind of file." }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open with another app") }
        }
        if (note.isNotEmpty()) Text(note, Modifier.padding(horizontal = 22.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
        FileBody(file)
    }
}
