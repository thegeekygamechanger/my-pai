package app.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.ui.theme.LocalCodeColors
import kotlinx.coroutines.delay

/**
 * Inline Markdown as styled text. With [limit] (0 or more), the text is being written out: only that many of its
 * characters are there, and the last few fade in, so a reply seems to be written rather than to appear at once.
 * A limit past the end of the text lets that fading edge run off the end.
 */
@Composable
fun inlineText(text: String, limit: Int = -1): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val base = MaterialTheme.colorScheme.onSurface
    val built = remember(text, linkColor, codeBg) {
        buildAnnotatedString {
            for (sp in Inline.parse(text)) {
                val style = SpanStyle(
                    fontWeight = if (sp.bold) FontWeight.SemiBold else null,
                    fontStyle = if (sp.italic) FontStyle.Italic else null,
                    textDecoration = if (sp.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (sp.code) FontFamily.Monospace else null,
                    background = if (sp.code) codeBg else Color.Unspecified,
                    fontSize = if (sp.code) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
                if (sp.link != null) {
                    withLink(LinkAnnotation.Url(sp.link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        withStyle(style) { append(sp.text) }
                    }
                } else withStyle(style) { append(sp.text) }
            }
        }
    }
    if (limit < 0) return built
    // The limit counts characters as they were written, with their Markdown marks; the edge is placed in the styled text.
    val edge = if (limit >= text.length || text.isEmpty()) built.length + (limit - text.length) else (limit.toLong() * built.length / text.length).toInt()
    if (edge >= built.length + WRITE_EDGE) return built
    return remember(built, edge, base) {
        var cut = edge.coerceIn(0, built.length)
        if (cut in 1 until built.length && Character.isHighSurrogate(built[cut - 1])) cut--
        buildAnnotatedString {
            append(built.subSequence(0, cut))
            // Behind the edge the words are at full strength; across it they come in gradually, two characters at a
            // time, on a curve that is gentle at both ends, so the front of the text looks soft rather than cut.
            var end = cut
            while (end > 0 && edge - end < WRITE_EDGE) {
                val start = maxOf(0, end - 2)
                val t = ((edge - end + 1f) / WRITE_EDGE).coerceIn(0f, 1f)
                addStyle(SpanStyle(color = base.copy(alpha = base.alpha * t * t * (3f - 2f * t))), start, end)
                end = start
            }
        }
    }
}

@Composable
fun MdBlockView(block: MdBlock, modifier: Modifier = Modifier, limit: Int = -1) {
    val cs = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Para -> Text(inlineText(block.text, limit), modifier, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
        is MdBlock.Heading -> Text(
            inlineText(block.text, limit), modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            color = cs.onSurface,
        )
        is MdBlock.Code -> CodeBlock(block, modifier, limit)
        is MdBlock.Items -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // While the list is being written out its items appear one after another, each from its first word.
            var before = 0
            for (item in block.items) {
                val mine = if (limit < 0) -1 else limit - before
                before += item.text.length
                if (limit >= 0 && mine <= 0) break
                Row(Modifier.padding(start = (item.indent.coerceAtMost(4) * 18).dp)) {
                    when (item.checked) {
                        null -> Text(
                            item.marker, Modifier.width(if (item.marker == "•") 18.dp else 28.dp),
                            style = MaterialTheme.typography.bodyLarge, color = cs.primary, fontWeight = FontWeight.SemiBold,
                        )
                        else -> Icon(
                            if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                            Modifier.padding(top = 3.dp, end = 6.dp).size(18.dp), tint = if (item.checked) cs.primary else cs.outline,
                        )
                    }
                    Text(inlineText(item.text, mine), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                }
            }
        }
        is MdBlock.Quote -> Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            Text(inlineText(block.text, limit), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant)
        }
        is MdBlock.Table -> TableView(block, modifier, limit)
        MdBlock.Rule -> HorizontalDivider(modifier.padding(vertical = 4.dp), color = cs.outlineVariant)
    }
}

@Composable
private fun CodeBlock(whole: MdBlock.Code, modifier: Modifier, limit: Int = -1) {
    // Being written out, the code grows a little at a time like the text around it.
    val block = if (limit < 0 || limit >= whole.code.length) whole else whole.copy(code = whole.code.take(limit))
    val cs = MaterialTheme.colorScheme
    val colors = LocalCodeColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    val highlighted = remember(block.code, block.lang, colors) {
        buildAnnotatedString {
            append(block.code)
            for (t in Highlight.tokens(block.code, block.lang)) {
                val color = when (t.kind) {
                    Highlight.Kind.KEYWORD -> colors.keyword
                    Highlight.Kind.STRING -> colors.string
                    Highlight.Kind.COMMENT -> colors.comment
                    Highlight.Kind.NUMBER -> colors.number
                    Highlight.Kind.PLAIN -> continue
                }
                addStyle(SpanStyle(color = color, fontStyle = if (t.kind == Highlight.Kind.COMMENT) FontStyle.Italic else null), t.start, t.end)
            }
        }
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)); copied = true }, modifier = Modifier.size(36.dp)) {
                    Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, "Copy code", Modifier.size(16.dp), tint = if (copied) cs.primary else cs.onSurfaceVariant)
                }
            }
            SelectionContainer {
                Text(
                    highlighted,
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TableView(whole: MdBlock.Table, modifier: Modifier, limit: Int = -1) {
    // Being written out, the table gains one row at a time. Its columns are sized for the whole table from the start.
    val t = if (limit < 0) whole else {
        var left = limit - whole.header.sumOf { it.length }
        whole.copy(rows = whole.rows.takeWhile { r -> left -= r.sumOf { it.length }; left >= 0 })
    }
    val cs = MaterialTheme.colorScheme
    val cols = maxOf(whole.header.size, whole.rows.maxOfOrNull { it.size } ?: 0)
    val widths = remember(whole) {
        (0 until cols).map { c ->
            val longest = maxOf(whole.header.getOrNull(c)?.length ?: 0, whole.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0)
            (longest * 8 + 28).coerceIn(72, 240)
        }
    }
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLow) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(cs.surfaceContainerHigh)) {
                for (c in 0 until cols) Text(
                    inlineText(t.header.getOrNull(c).orEmpty()), Modifier.width(widths[c].dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                )
            }
            t.rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                Row {
                    for (c in 0 until cols) Text(
                        inlineText(row.getOrNull(c).orEmpty()), Modifier.width(widths[c].dp).padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                    )
                }
            }
        }
    }
}
