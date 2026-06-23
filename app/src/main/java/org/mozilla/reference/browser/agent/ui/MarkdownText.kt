/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.mozilla.reference.browser.agent.ui.theme.AgentColors
import org.mozilla.reference.browser.agent.ui.theme.AgentText

/**
 * Full Markdown rendering for the overlay Agent's chat messages (and ONLY chat messages — tool /
 * plan / task cards keep their own flat styling). Parsing is done by commonmark-java (CommonMark +
 * GFM tables / strikethrough / task-lists / autolink); this file only walks the resulting AST and
 * emits bare-Compose primitives styled with the AgentColors / AgentText design tokens, since the
 * overlay has no MaterialTheme. Supported: headings, bold / italic / strikethrough / inline code,
 * fenced code blocks (language label + copy + horizontal scroll), bullet / ordered / task lists,
 * block quotes, thematic breaks, links and tables.
 */
@Composable
fun MarkdownContent(text: String) {
    val doc = remember(text) { markdownParser.parse(unescapeMarkdown(text)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarkdownBlocks(doc)
    }
}

private val markdownParser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()
}

/**
 * Renders [text] as a single inline-markdown [AnnotatedString] (bold / italic / strikethrough /
 * inline code / links), flattening block structure onto one line. For short one-line labels such
 * as task-tracker titles that may carry light markdown but must keep their own base text style.
 */
fun inlineMarkdown(text: String): AnnotatedString {
    val doc = markdownParser.parse(unescapeMarkdown(text))
    return buildAnnotatedString {
        var block = doc.firstChild
        var first = true
        while (block != null) {
            if (!first) append(' ')
            appendInlineChildren(block)
            first = false
            block = block.next
        }
    }
}

// ── block level ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownBlocks(parent: Node) {
    parent.children().forEach { MarkdownBlock(it) }
}

@Composable
private fun MarkdownBlock(node: Node) {
    when (node) {
        is Heading -> BasicText(inlineString(node), style = headingStyle(node.level))
        is Paragraph -> BasicText(inlineString(node), style = MdBody)
        is FencedCodeBlock -> {
            val src = node.literal.trimEnd('\n')
            val info = node.info ?: ""
            // The model opts into an interactive window by tagging the fence html/js/markdown;
            // every other language stays a flat code box. No content sniffing — tag only.
            val kind = interactiveLang(info)
            if (kind != null) InteractiveCodeWindow(src, kind) else MdCodeBlock(src, info)
        }
        is IndentedCodeBlock -> MdCodeBlock(node.literal.trimEnd('\n'), "")
        is BulletList -> ListBlock(node, ordered = false)
        is OrderedList -> ListBlock(node, ordered = true)
        is BlockQuote -> QuoteBlock(node)
        is ThematicBreak ->
            Box(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                    .background(AgentColors.Hairline),
            )
        is TableBlock -> TableBlockView(node)
        is HtmlBlock -> BasicText(node.literal.trim(), style = MonoBody)
        else -> {
            val s = inlineString(node)
            if (s.isNotBlank()) BasicText(s, style = MdBody) else MarkdownBlocks(node)
        }
    }
}

@Composable
private fun ListBlock(list: Node, ordered: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var index = 1
        list.children().forEach { item ->
            if (item is ListItem) {
                ListItemRow(item, ordered, index)
                index++
            }
        }
    }
}

@Composable
private fun ListItemRow(item: ListItem, ordered: Boolean, index: Int) {
    val firstPara = item.firstChild as? Paragraph
    val taskMarker = firstPara?.firstChild as? TaskListItemMarker
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.widthIn(min = 22.dp).padding(end = 6.dp)) {
            when {
                taskMarker != null ->
                    BasicText(
                        if (taskMarker.isChecked) "\u2611" else "\u2610",
                        style = MdBody.copy(color = AgentColors.TextSecondary),
                    )
                ordered -> BasicText("$index.", style = MdBody.copy(color = AgentColors.TextSecondary))
                else -> BasicText("\u2022", style = MdBody.copy(color = AgentColors.TextSecondary))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item.children().forEach { child ->
                if (child === firstPara && taskMarker != null) {
                    BasicText(inlineString(child, skip = taskMarker), style = MdBody)
                } else {
                    MarkdownBlock(child)
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(node: BlockQuote) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(AgentColors.Hairline))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkdownBlocks(node)
        }
    }
}

@Composable
private fun TableBlockView(table: Node) {
    // Flatten head + body into (isHeader, cells) rows so the renderer is structure-driven and
    // never needs the (version-sensitive) TableCell.isHeader() accessor.
    val rows = ArrayList<Pair<Boolean, List<Node>>>()
    table.children().forEach { section ->
        val header = section is TableHead
        section.children().forEach { row -> rows.add(header to row.children()) }
    }
    if (rows.isEmpty()) return
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, AgentColors.HairlineFaint, RoundedCornerShape(8.dp))) {
            rows.forEachIndexed { ri, (isHeader, cells) ->
                if (ri > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AgentColors.HairlineFaint))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    cells.forEachIndexed { ci, cell ->
                        if (ci > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(AgentColors.HairlineFaint))
                        }
                        Box(Modifier.width(132.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                            BasicText(
                                inlineString(cell),
                                style = if (isHeader) {
                                    MdBody.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MdBody
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dark rounded code box (ChatGPT style): header row with the language name + a copy button, then
 *  the code in a horizontally-scrollable monospace block so long lines never wrap or clip. */
@Composable
private fun MdCodeBlock(code: String, info: String) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val lang = info.trim().substringBefore(' ').ifBlank { "code" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MdCodeBg)) {
        Row(
            Modifier.fillMaxWidth().background(MdCodeHeaderBg).padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(lang, style = AgentText.Label.copy(color = MdCodeLangFg, fontFamily = FontFamily.Monospace))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(24.dp).clip(CircleShape).noRippleClickable {
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                },
                contentAlignment = Alignment.Center,
            ) { CopyIcon(size = 14.dp, color = MdCodeLangFg, bg = MdCodeHeaderBg) }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
            BasicText(
                code,
                style = MdCodeStyle.copy(color = MdCodeFg),
            )
        }
    }
}

// ── inline level ─────────────────────────────────────────────────────────────────────────────

private fun inlineString(node: Node, skip: Node? = null): AnnotatedString = buildAnnotatedString {
    appendInlineChildren(node, skip)
}

private fun AnnotatedString.Builder.appendInlineChildren(parent: Node, skip: Node? = null) {
    var c = parent.firstChild
    while (c != null) {
        if (c !== skip) appendInline(c)
        c = c.next
    }
}

private fun AnnotatedString.Builder.appendInline(node: Node) {
    when (node) {
        is Text -> append(node.literal)
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlineChildren(node) }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlineChildren(node) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlineChildren(node) }
        is Code ->
            withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = MdInlineCodeBg, color = MdInlineCodeFg),
            ) { append(node.literal) }
        is Link ->
            withStyle(SpanStyle(color = AgentColors.Accent, textDecoration = TextDecoration.Underline)) {
                appendInlineChildren(node)
            }
        is Image -> appendInlineChildren(node)
        is HardLineBreak -> append('\n')
        is SoftLineBreak -> append('\n')
        is HtmlInline -> append(node.literal)
        else -> appendInlineChildren(node)
    }
}

// ── helpers / tokens ─────────────────────────────────────────────────────────────────────────

private fun Node.children(): List<Node> {
    val out = ArrayList<Node>()
    var c = firstChild
    while (c != null) {
        out.add(c)
        c = c.next
    }
    return out
}

private fun headingStyle(level: Int) = when (level) {
    1 -> H1Style
    2 -> H2Style
    3 -> H3Style
    else -> H4Style
}

// Chat prose runs ~10% smaller than the global Body token (the overlay is narrow and the
// transcript reads as dense text); headings scale with it. The code block keeps its own size.
private val MdBody = AgentText.Body.copy(fontSize = 10.5.sp)
private val H1Style = AgentText.Body.copy(fontSize = 16.2.sp, fontWeight = FontWeight.Bold)
private val H2Style = AgentText.Body.copy(fontSize = 13.8.sp, fontWeight = FontWeight.Bold)
private val H3Style = AgentText.Body.copy(fontSize = 12.2.sp, fontWeight = FontWeight.Bold)
private val H4Style = AgentText.Body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
private val MonoBody = MdBody.copy(fontFamily = FontFamily.Monospace)
// Code block body: a touch smaller so ~35–40 mono chars fit per line in the overlay width.
private val MdCodeStyle = AgentText.Body.copy(fontSize = 10.4.sp, fontFamily = FontFamily.Monospace)

// Light-gray fenced code block (gray, not black) with dark text, matching the tool-output box.
private val MdCodeBg = Color(0xFFEDEEF0)
private val MdCodeHeaderBg = Color(0xFFE2E4E8)
private val MdCodeFg = Color(0xFF24292F)
private val MdCodeLangFg = Color(0xFF666666)
// Inline `code`: subtle gray pill background with the normal dark text.
private val MdInlineCodeBg = Color(0x14000000)
private val MdInlineCodeFg = Color(0xFF24292F)

/**
 * Unescapes the backslash escapes (`\n` `\t` `\r` `\"` `\\`) that some providers leak as literal
 * two-character sequences, so they render as real newlines / tabs / quotes before Markdown parsing.
 */
private fun unescapeMarkdown(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2; continue }
                't' -> { sb.append('\t'); i += 2; continue }
                'r' -> { i += 2; continue }
                '"' -> { sb.append('"'); i += 2; continue }
                '\\' -> { sb.append('\\'); i += 2; continue }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
