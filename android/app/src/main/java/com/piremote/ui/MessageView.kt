package com.piremote.ui

import com.piremote.R

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.piremote.data.ChatItem
import com.piremote.data.EditDiff
import com.piremote.data.ToolCall
import com.piremote.data.ToolResult
import com.piremote.data.Truncation
import com.piremote.data.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        is ChatItem.User -> UserBlock(item, expanded, onExpand, modifier)
        is ChatItem.Assistant -> AssistantBlock(item, expanded, onExpand, modifier)
        is ChatItem.OrphanToolResult -> ToolResultBlock(item.result, expanded, onExpand, modifier)
        is ChatItem.Bash -> BashBlock(item, expanded, onExpand, modifier)
        is ChatItem.Notice -> NoticeBlock(item, modifier)
    }
}

@Composable
private fun UserBlock(
    item: ChatItem.User,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier,
) {
    // Fetch the stripped image payload as soon as the message is on screen.
    val image = item.image
    LaunchedEffect(image?.key()) {
        if (image != null && !expanded.containsKey(image.key())) onExpand(image)
    }

    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            image?.let { img ->
                val base64 = expanded[img.key()]
                // Decode off the frame: produceState runs on the main dispatcher.
                val bitmap by produceState<Bitmap?>(initialValue = null, base64) {
                    value = withContext(Dispatchers.Default) {
                        base64?.let { runCatching { decodeBase64Bitmap(it) }.getOrNull() }
                    }
                }
                val bmp = bitmap
                var fullscreen by remember { mutableStateOf(false) }
                when {
                    bmp != null -> {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.image),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (item.text.isNotBlank()) 6.dp else 0.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { fullscreen = true },
                        )
                        if (fullscreen) {
                            ZoomableImageDialog(bitmap = bmp, onDismiss = { fullscreen = false })
                        }
                    }
                    base64 == null -> CircularProgressIndicator(
                        Modifier.padding(top = 6.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
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
            ToolCallsCard(item.toolCalls, item.entryId, expanded, onExpand)
        }

        if (item.text.isNotBlank() || item.error != null) {
            // Text keeps its own softer card (14dp, no border) — it is a body
            // of prose, not a structured block, and pairs with the user bubble.
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
                    Markdown(
                        item.text,
                        typography = LocalMarkdownTypography.current,
                        components = ChatMarkdownComponents,
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

/**
 * Markdown styling for a settled assistant reply.
 *
 * Heading sizes are pinned to GitHub-like proportions — the library's M3
 * defaults map h1 to 57sp displayLarge, which reads as a banner inside a chat
 * card. Built once by [PiRemoteTheme] and handed down as
 * [LocalMarkdownTypography]: it is composable (the styles left unspecified
 * come from MaterialTheme) so it cannot be remembered at the call site, and
 * per-message it allocated a dozen text styles for every reply on screen.
 */
@Composable
internal fun chatMarkdownTypography(colors: ColorScheme = MaterialTheme.colorScheme) = markdownTypography(
    h1 = heading(colors, 28.sp, FontWeight.Bold),
    h2 = heading(colors, 22.sp, FontWeight.Bold),
    h3 = heading(colors, 19.sp, FontWeight.Bold),
    h4 = heading(colors, 16.sp, FontWeight.Bold),
    h5 = heading(colors, 14.sp, FontWeight.Bold),
    h6 = heading(colors, 13.sp, FontWeight.Bold, muted = true),
    paragraph = TextStyle(color = colors.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
)

/** GitHub-like heading text style: body-relative sizes, bold, on-surface. */
private fun heading(
    colors: ColorScheme,
    size: TextUnit,
    weight: FontWeight,
    muted: Boolean = false,
): TextStyle = TextStyle(
    color = if (muted) colors.onSurfaceVariant else colors.onSurface,
    fontSize = size,
    fontWeight = weight,
)

/**
 * Content-adaptive table columns instead of the library's fixed per-column
 * width. Theme-independent, so one instance serves every message.
 */
private val ChatMarkdownComponents =
    markdownComponents(table = { model -> AdaptiveMarkdownTable(model) })

/**
 * Render an edit call's oldText/newText as a unified diff.
 *
 * Line pairing comes from [simpleLineDiff] in DiffView.kt.
 */
@Composable
private fun EditDiffView(diff: EditDiff, modifier: Modifier = Modifier) {
    // No horizontal scroll here: per-line backgrounds need a bounded width to
    // span the card. Long lines wrap instead of scrolling. Vertical is capped
    // the same way as the other expandable bodies: a long diff scrolls within
    // the card instead of growing the row unbounded.
    ExpandableBody(Modifier.padding(bottom = 4.dp)) {
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

// pi-web tool-call palette (dark): green = call ran, red = call errored.
// Ported from pi-web's CSS (--accent green / --error red tints). Shared with
// the streaming tool card in ChatInput.kt so a running call reads exactly
// like the settled card it becomes.
internal val ToolOkGreen = Color(0xFF16A34A)
internal val ToolErrRed = Color(0xFFF87171)
internal val ToolOkBorder = Color(0x4022C55E) // rgba(34,197,94,0.25)
internal val ToolOkBg = Color(0x0A22C55E) // rgba(34,197,94,0.04)
internal val ToolErrBorder = Color(0x73F87171) // rgba(248,113,113,0.45)
internal val ToolErrBg = Color(0x0DF87171) // rgba(248,113,113,0.05)

@Composable
private fun ToolCallsCard(
    calls: List<ToolCall>,
    entryId: String,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
) {
    // One card per call, pi-web style: each tool call is its own tinted card,
    // stacked with a small gap instead of one merged surfaceVariant block.
    // Horizontal margin matches the old card layout (12dp).
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        calls.forEach { call ->
            // key(): each card's saved open/closed state is scoped by position,
            // so without it the cards of one turn would share a slot and swap
            // state whenever the call list shifts.
            key(call.id) {
                ToolCallRow(call, entryId, expanded, onExpand)
            }
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
    // Saveable, not remembered: a LazyColumn item that scrolls out of view is
    // disposed, and a plain remember would re-collapse the card behind the
    // user's back. The list keys items by entryId, which scopes the saved
    // state to this message.
    var open by rememberSaveable { mutableStateOf(false) }
    val full = truncation?.let { expanded[it.key()] }

    // pi-web thinking card: neutral bordered card, plain "Thinking" header
    // (no chevron), body below a hairline divider.
    ThinkingCardShell(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = {
            open = !open
            if (open && truncation != null && full == null) onExpand(truncation)
        },
    ) {
        if (open) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // Height-capped with internal scroll: expanding a card must not
            // grow the row unbounded, or the layout shift pushes whatever the
            // user is reading off-screen. 400dp caps the jump, and the body
            // scrolls within itself when longer.
            ExpandableBody {
                Text(
                    full ?: preview,
                    style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (full == null && truncation != null) {
                LoadingMore(truncation.displaySize)
            }
        }
    }
}

@Composable
private fun ToolCallRow(
    call: ToolCall,
    entryId: String,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
) {
    val result = call.result
    val error = result?.isError == true
    val borderColor = if (error) ToolErrBorder else ToolOkBorder

    ToolCallCard(
        name = call.name,
        nameColor = if (error) ToolErrRed else ToolOkGreen,
        subtitle = call.subtitle,
        borderColor = borderColor,
        bgColor = if (error) ToolErrBg else ToolOkBg,
    ) {
        val diff = call.diff
        if (diff == null && call.arguments.isNotBlank()) {
            // JSON arguments, pi-web style: pre-wrap, dim mono, subtle bg,
            // hairline divider in the card's tint. Height-capped with
            // internal scroll, same as the other expandable bodies.
            ToolDivider(borderColor)
            ExpandableBody(Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))) {
                Text(
                    call.arguments,
                    style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (diff != null) {
            ToolDivider(borderColor)
            EditDiffView(diff, Modifier.padding(vertical = 4.dp))
        }
        // Inside the card's own padding: the body sections below carry theirs
        // via the Box wrapper, this row sits directly in the card column.
        call.truncation?.let {
            ExpandRow(it, expanded, onExpand, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp))
        }
        if (result != null) {
            ToolDivider(borderColor)
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                ToolResultBody(result, expanded, onExpand)
            }
        }
    }
}

/**
 * Hairline separator between a tool call's sections, tinted like the card.
 */
@Composable
internal fun ToolDivider(color: Color) {
    HorizontalDivider(color = color)
}

/**
 * Pulsing 1dp border for running cards. The border alpha breathes up and back
 * (pi-web's pulse rhythm), so a running tool / thinking / compaction reads as
 * alive without a spinner. Settled cards keep a static [Modifier.border].
 *
 * The animated alpha is read inside the draw lambda, never during composition.
 * Reading it as a plain value would restart [CardShell] on every animation
 * frame, and because `Column` is inline that drags the card's whole subtree —
 * text measurement included — through recomposition ~60 times a second while a
 * tool streams output.
 */
@Composable
private fun Modifier.breathingBorder(base: Color, shape: Shape): Modifier {
    val transition = rememberInfiniteTransition(label = "card-breathe")
    val alpha = transition.animateFloat(
        initialValue = base.alpha,
        targetValue = (base.alpha + 0.55f).coerceAtMost(1f),
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "card-breathe-alpha",
    )
    return drawWithCache {
        // Inset by half the stroke, the way Modifier.border does: centred on
        // the bounds, the outer half would fall outside the clip and the line
        // would render at half its width.
        val stroke = 1.dp.toPx()
        val outline = shape.createOutline(
            Size(size.width - stroke, size.height - stroke),
            layoutDirection,
            this,
        )
        val style = Stroke(stroke)
        onDrawWithContent {
            drawContent()
            translate(stroke / 2, stroke / 2) {
                drawOutline(outline, color = base.copy(alpha = alpha.value), style = style)
            }
        }
    }
}

/**
 * One card container for every assistant-side block — thinking, tool calls,
 * bash, results, transient states and text. Neutral by default; tool calls
 * tint it via [background]/[border]; running states breathe via [breathing].
 * Headers and bodies are just content.
 */
@Composable
internal fun CardShell(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    background: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: Color? = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    breathing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val borderModifier = when {
        border == null -> Modifier
        breathing -> Modifier.breathingBorder(border, shape)
        else -> Modifier.border(1.dp, border, shape)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(borderModifier),
    ) { content() }
}

/**
 * pi-web tool-call card: tinted border/background, mono tool name, argument
 * preview and a rotating chevron. Shared by settled tool calls, bash
 * executions and the live streaming card, so a running call looks identical
 * to the card it becomes. [startsOpen] lets the streaming card surface live
 * output by default; settled cards collapse.
 *
 * Open/closed is saveable so it survives the card scrolling out of the list.
 * Siblings are told apart by position, so a caller rendering several of these
 * in a loop must wrap each in `key(id)`.
 */
@Composable
internal fun ToolCallCard(
    name: String,
    nameColor: Color,
    borderColor: Color,
    bgColor: Color,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    startsOpen: Boolean = false,
    breathing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(startsOpen) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "tool-card-chevron")
    // A streaming card's subtitle is stable while its output grows; shortening
    // it once per value keeps the allocation off the recomposition path.
    val compactSubtitle = remember(subtitle) { subtitle?.compactForRow() }

    CardShell(
        modifier = modifier,
        background = bgColor,
        border = borderColor,
        breathing = breathing,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Tool name in green/red (pi-web: mono, semi-bold, tinted by state).
            Text(
                name,
                style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            compactSubtitle?.let {
                Text(
                    it,
                    style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    // weight(fill=true): the text is constrained to the row's
                    // remaining width and ellipsizes at the true end. fill=false
                    // would measure at intrinsic width and overflow the card.
                    modifier = Modifier.weight(1f),
                )
            }
            // Chevron sits at the end and rotates 180° when open (pi-web).
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (open) stringResource(R.string.collapse) else stringResource(R.string.expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation).size(16.dp),
            )
        }
        if (open) content()
    }
}

/**
 * pi-web thinking card: neutral bordered card with a plain "Thinking" header
 * (no chevron). The streaming turn shows just the shell; the settled card
 * adds an expandable body below the hairline divider.
 *
 * [active] means this turn is still reasoning. It drives both the breathing
 * border and the header wording — a settled card reading "Thinking…" claims
 * work that finished long ago, and languages that mark the progressive form
 * (中文) cannot paper over that with one neutral noun the way English does.
 */
@Composable
internal fun ThinkingCardShell(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    active: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    CardShell(modifier = modifier, breathing = active) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (active) R.string.card_thinking_active else R.string.card_thinking),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}


@Composable
private fun ToolResultBlock(
    result: ToolResult,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier,
) {
    CardShell(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                result.toolName.ifBlank { stringResource(R.string.tool_result) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolResultBody(result, expanded, onExpand)
        }
    }
}

@Composable
private fun ToolResultBody(result: ToolResult, expanded: Map<String, String>, onExpand: (Truncation) -> Unit) {
    val truncation = result.truncation
    val full = truncation?.let { expanded[it.key()] }

    if (result.hasImage && full == null) {
        Text(
            stringResource(R.string.tool_image_truncated, truncation?.displaySize ?: stringResource(R.string.not_loaded)),
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
    val error = (item.exitCode ?: 0) != 0
    val borderColor = if (error) ToolErrBorder else ToolOkBorder

    // pi-web renders bash executions as tool-call cards: tinted border, mono
    // green/red "bash" name, command preview, collapsible output.
    ToolCallCard(
        name = "bash",
        nameColor = if (error) ToolErrRed else ToolOkGreen,
        subtitle = item.command,
        borderColor = borderColor,
        bgColor = if (error) ToolErrBg else ToolOkBg,
        modifier = modifier,
    ) {
        ToolDivider(borderColor)
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            ScrollableCode(full ?: item.output, error = error)
        }
        if (full == null) item.truncation?.let { ExpandRow(it, expanded, onExpand) }
    }
}

@Composable
private fun NoticeBlock(item: ChatItem.Notice, modifier: Modifier) {
    val text = when (item.kind) {
        ChatItem.NoticeKind.Generic -> item.text
        ChatItem.NoticeKind.Compaction -> stringResource(R.string.notice_compacted)
        ChatItem.NoticeKind.BranchSummary -> stringResource(R.string.notice_branch_summary)
        ChatItem.NoticeKind.ModelChange -> stringResource(R.string.notice_model_change, item.arg)
        ChatItem.NoticeKind.ThinkingLevel -> stringResource(R.string.notice_thinking_level, item.arg)
        ChatItem.NoticeKind.SessionNamed -> stringResource(R.string.notice_session_named, item.arg)
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

private fun decodeBase64Bitmap(base64: String): Bitmap? = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * Fullscreen image with pinch-zoom, pan, double-tap to toggle zoom, and a
 * tap on the backdrop (or Back) to close.
 */
@Composable
private fun ZoomableImageDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    // BackHandler from the activity-compose dependency already powers system
    // back in this app; the dialog also exposes an explicit close button.
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var panEnabled by remember { mutableStateOf(false) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset += pan
        panEnabled = scale > 1f
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (!panEnabled) onDismiss() },
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                                panEnabled = false
                            } else {
                                scale = 2.5f
                                panEnabled = true
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Long tool output must not force the whole page to scroll sideways, so each
 * block scrolls within itself.
 */
/**
 * Shared cap for every expandable body (tool output, bash output, thinking,
 * arguments, diffs). Keeping them all at one height bounds the layout shift a
 * card expansion causes: at most this tall of new content appears, never a
 * row that grows past the screen. This is what makes expansion painless under
 * either list direction (forward or reverseLayout) — no scroll compensation
 * ever has to chase an unbounded height.
 */
private val MaxExpandableHeight = 400.dp

/**
 * Height-capped, internally scrollable container for every expandable body.
 * One place owns the cap and the scroll behaviour, so thinking, tool
 * arguments, diffs and tool/bash output all expand identically.
 */
@Composable
internal fun ExpandableBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = MaxExpandableHeight)
            .verticalScroll(rememberScrollState())
            .then(modifier),
    ) { content() }
}

@Composable
internal fun ScrollableCode(text: String, error: Boolean = false) {
    // pi-web output block: 12px mono, line-height 1.5, pre-wrap (soft wraps long
    // lines instead of scrolling horizontally), capped at 400dp with internal
    // vertical scroll. Error text uses the card's red for consistency.
    ExpandableBody {
        Text(
            text,
            style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = if (error) ToolErrRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandRow(
    truncation: Truncation,
    expanded: Map<String, String>,
    onExpand: (Truncation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (expanded.containsKey(truncation.key())) return
    Text(
        stringResource(R.string.tool_expand_all, truncation.displaySize),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable { onExpand(truncation) }.padding(top = 4.dp),
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
internal fun String.compactForRow(): String {
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
        stringResource(R.string.tool_loading_more, size),
        style = MaterialTheme.typography.labelSmall,
        color = Color.Gray,
        modifier = Modifier.padding(start = 24.dp),
    )
}
