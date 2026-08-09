package com.piremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.piremote.data.ChatItem
import com.piremote.data.EditDiff
import com.piremote.data.ToolCall
import com.piremote.data.ToolResult
import com.piremote.data.Truncation
import com.piremote.data.key

/**
 * Renders one chat item.
 *
 * User turns get a tinted block; assistant turns are laid out bare, closer to
 * how a terminal reads. Tool calls collapse to a single line and open on tap.
 */
@Composable
fun MessageView(
    item: ChatItem,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ChatItem.User -> UserBlock(item, modifier)
        is ChatItem.Assistant -> AssistantBlock(item, expanded, onExpand, modifier)
        is ChatItem.OrphanToolResult -> ToolResultBlock(item.result, expanded, onExpand, modifier)
        is ChatItem.Bash -> BashBlock(item, expanded, onExpand, modifier)
        is ChatItem.Notice -> NoticeBlock(item, modifier)
    }
}

@Composable
private fun UserBlock(item: ChatItem.User, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(item.text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBlock(
    item: ChatItem.Assistant,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier,
) {
    // Three stacked cards, in the order the turn actually happened: thinking,
    // tool calls, then the reply text. Each can fold to its header rows.
    Column(modifier.fillMaxWidth()) {
        item.thinking?.let { ThinkingCard(it.preview, it.truncation, expanded, onExpand) }

        if (item.toolCalls.isNotEmpty()) {
            ToolCallsCard(item.toolCalls, expanded, onExpand)
        }

        if (item.text.isNotBlank() || item.error != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (item.text.isNotBlank()) {
                    // Settled messages are fully parsed: code blocks, tables and
                    // links read properly. The streaming bubble stays plain.
                    // Heading sizes are pinned to GitHub-like proportions — the
                    // library's M3 defaults map h1 to 57sp displayLarge.
                    val mdTypography = markdownTypography(
                        h1 = heading(28.sp, FontWeight.Bold),
                        h2 = heading(22.sp, FontWeight.Bold),
                        h3 = heading(19.sp, FontWeight.Bold),
                        h4 = heading(16.sp, FontWeight.Bold),
                        h5 = heading(14.sp, FontWeight.Bold),
                        h6 = heading(13.sp, FontWeight.Bold, muted = true),
                        paragraph = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        ),
                    )
                    Markdown(
                        item.text,
                        typography = mdTypography,
                        // Content-adaptive table columns instead of the library's
                        // fixed per-column width.
                        components = markdownComponents(table = { model -> AdaptiveMarkdownTable(model) }),
                    )
                }

                item.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // Per-turn token usage, pi-TUI style. Rendered outside the card,
        // snug against it, left-aligned with the card's text. Skipped when the
        // turn used nothing (failed/aborted runs report all-zero usage).
        item.usage?.let { usage ->
            if (!usage.isEmpty) {
                Text(
                    usage.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** One card for the whole tool segment of a turn; each call folds to a header row. */
/** GitHub-like heading text style: body-relative sizes, bold, on-surface. */
@Composable
private fun heading(size: TextUnit, weight: FontWeight, muted: Boolean = false): TextStyle =
    TextStyle(
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
        fontSize = size,
        fontWeight = weight,
    )

/**
 * Render an edit call's oldText/newText as a unified diff.
 *
 * Line pairing comes from [simpleLineDiff] in DiffView.kt.
 */
@Composable
private fun EditDiffView(diff: EditDiff, modifier: Modifier = Modifier) {
    // No horizontal scroll here: per-line backgrounds need a bounded width to
    // span the card. Long lines wrap instead of scrolling.
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        diff.filePath?.let {
            Text(
                "--- $it",
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        diff.hunks.forEachIndexed { index, hunk ->
            if (index > 0) {
                Text(
                    "···",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            val rows = simpleLineDiff(hunk.oldText.lines(), hunk.newText.lines())
            for (row in rows) {
                DiffLine(row)
            }
        }
    }
}

@Composable
private fun ToolCallsCard(
    calls: List<ToolCall>,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        calls.forEachIndexed { index, call ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ToolCallRow(call, expanded, onExpand)
        }
    }
}

@Composable
private fun ThinkingCard(
    preview: String,
    truncation: Truncation?,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val full = truncation?.let { expanded[it.key()] }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    open = !open
                    if (open && truncation != null && full == null) onExpand(truncation)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp),
            )
            Text(
                "Thinking",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Text(
                full ?: preview,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 2.dp),
            )
            if (full == null && truncation != null) {
                LoadingMore(truncation.displaySize)
            }
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall, expanded: Map<String, String>, onExpand: (Truncation) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val result = call.result

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                call.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            call.subtitle?.let {
                Text(
                    it.compactForRow(),
                    style = MonoStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    // weight(fill=true): the text is constrained to the row's
                    // remaining width and ellipsizes at the true end. fill=false
                    // would measure at intrinsic width and overflow the card.
                    modifier = Modifier.weight(1f),
                )
            }
            if (result?.isError == true) {
                Text(" 失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (open) {
            // Tight bottom: the card's own 4dp padding already clears the
            // next item — 8dp here made the gap under the last output line
            // look much larger than the header's top spacing.
            Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 2.dp)) {
                val diff = call.diff
                if (diff != null) {
                    EditDiffView(diff)
                } else if (call.arguments.isNotBlank()) {
                    ScrollableCode(call.arguments)
                }
                call.truncation?.let { ExpandRow(it, expanded, onExpand) }
                if (result != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    ToolResultBody(result, expanded, onExpand)
                }
            }
        }
    }
}

@Composable
private fun ToolResultBlock(
    result: ToolResult,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text(
            result.toolName.ifBlank { "工具结果" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolResultBody(result, expanded, onExpand)
    }
}

@Composable
private fun ToolResultBody(result: ToolResult, expanded: Map<String, String>, onExpand: (Truncation) -> Unit) {
    val truncation = result.truncation
    val full = truncation?.let { expanded[it.key()] }

    if (result.hasImage && full == null) {
        Text(
            "［图片，${truncation?.displaySize ?: "未加载"}］",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val body = full ?: result.text
    if (body.isNotBlank()) {
        ScrollableCode(body, error = result.isError)
    }
    if (full == null && truncation != null && !result.hasImage) {
        ExpandRow(truncation, expanded, onExpand)
    }
}

@Composable
private fun BashBlock(
    item: ChatItem.Bash,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier,
) {
    val full = item.truncation?.let { expanded[it.key()] }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Text("$ ${item.command}", style = MonoStyle, color = MaterialTheme.colorScheme.primary)
        ScrollableCode(full ?: item.output, error = (item.exitCode ?: 0) != 0)
        if (full == null) item.truncation?.let { ExpandRow(it, expanded, onExpand) }
    }
}

@Composable
private fun NoticeBlock(item: ChatItem.Notice, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            item.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * Long tool output must not force the whole page to scroll sideways, so each
 * block scrolls within itself.
 */
@Composable
private fun ScrollableCode(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(
            text,
            style = MonoStyle,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandRow(truncation: Truncation, expanded: Map<String, String>, onExpand: (Truncation) -> Unit) {
    if (expanded.containsKey(truncation.key())) return
    Text(
        "展开全部（${truncation.displaySize}）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { onExpand(truncation) }.padding(top = 4.dp),
    )
}

/**
 * Shorten a tool argument for the collapsed row.
 *
 * Paths are cut from the front: the tail identifies the file, while the
 * leading `/Users/chen/…` is the same on every row and says nothing. The limit
 * is generous because the row's own ellipsis handles narrow screens; this only
 * avoids shipping megabytes of args over a header.
 */
private fun String.compactForRow(): String {
    val flat = replace('\n', ' ').trim()
    if (flat.length <= ROW_ARG_LIMIT) return flat
    if (flat.contains('/')) {
        val tail = flat.takeLast(ROW_ARG_LIMIT)
        val fromSegment = tail.substringAfter('/', tail)
        return "…/$fromSegment"
    }
    return flat.take(ROW_ARG_LIMIT) + "…"
}

private const val ROW_ARG_LIMIT = 160

@Composable
private fun LoadingMore(size: String) {
    Text(
        "加载中…（$size）",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Gray,
        modifier = Modifier.padding(start = 24.dp),
    )
}
