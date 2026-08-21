@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.piremote.ui


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.stringResource
import piremote.composeapp.generated.resources.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.piremote.platform.decodeImageScaled
import com.piremote.net.BlobDto
import com.piremote.net.Item
import com.piremote.net.MoreDto
import com.piremote.net.TextDto
import com.piremote.net.ToolDiffDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders one conversation row: an item, plus the tool rows it produced.
 *
 * User turns get a tinted block; assistant turns are laid out bare, closer to how a
 * terminal reads. Tool calls collapse to a single line and open on tap.
 *
 * A streaming message is not a separate case. It is an [Item.Assistant] with
 * `pending`, and the only differences are that its cards breathe and its text stays
 * plain — there used to be a second renderer for exactly this, kept in step by hand.
 */
@Composable
fun MessageView(
    item: Item,
    tools: List<Item.Tool>,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is Item.User -> UserBlock(item, expanded, onExpand, modifier)
        is Item.Assistant -> AssistantBlock(item, tools, expanded, onExpand, modifier)
        is Item.Tool -> ToolRowCard(item, expanded, onExpand, modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        is Item.Notice -> NoticeBlock(item, modifier)
    }
}

@Composable
private fun UserBlock(
    item: Item.User,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
    modifier: Modifier,
) {
    // Fetch the placeholder's bytes as soon as the message is on screen.
    val image: BlobDto? = item.images.firstOrNull()
    LaunchedEffect(image?.ref) {
        if (image != null && !expanded.containsKey(image.ref)) onExpand(image.ref)
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
            if (item.text.s.isNotBlank()) {
                Text(
                    item.text.s,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            image?.let { img ->
                val base64 = expanded[img.ref]
                // Decode off the frame: produceState runs on the main dispatcher.
                val bitmap by produceState<ImageBitmap?>(initialValue = null, base64) {
                    value = withContext(Dispatchers.Default) {
                        base64?.let { runCatching { decodeBase64Bitmap(it) }.getOrNull() }
                    }
                }
                val bmp = bitmap
                var fullscreen by remember { mutableStateOf(false) }
                when {
                    bmp != null -> {
                        Image(
                            bitmap = bmp,
                            contentDescription = stringResource(Res.string.image),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (item.text.s.isNotBlank()) 6.dp else 0.dp)
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
    item: Item.Assistant,
    tools: List<Item.Tool>,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
    modifier: Modifier,
) {
    // Stacked cards, reply-first: thinking, the reply text, then the tool
    // calls that produced it. Text above tools so a streamed reply is never
    // visually jumped over by a tool card appearing above it.
    Column(modifier.fillMaxWidth()) {
        item.thinking?.let { ThinkingCard(it, active = item.pending, expanded = expanded, onExpand = onExpand) }

        if (item.text.s.isNotBlank() || item.error != null) {
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
                if (item.text.s.isNotBlank()) {
                    // Markdown is parsed once, when the message settles. While it
                    // streams the text stays plain: re-parsing a growing string a
                    // dozen times a second is what actually drops frames on a long
                    // answer, and half-written markdown renders wrong anyway.
                    if (item.pending) {
                        Text(
                            item.text.s,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Markdown(
                            item.text.s,
                            typography = LocalMarkdownTypography.current,
                            components = ChatMarkdownComponents,
                        )
                    }
                }
                item.text.more?.let { ExpandRow(it, expanded, onExpand) }

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

        if (tools.isNotEmpty()) {
            ToolCallsCard(tools, expanded, onExpand)
        }

        // Per-turn token usage, pi-TUI style. Rendered outside the card,
        // snug against it, left-aligned with the card's text. Skipped when the
        // turn used nothing (failed/aborted runs report all-zero usage).
        // The server omits an all-zero usage (what a failed or aborted turn
        // reports), so its presence is the whole condition.
        item.usage?.let { usage ->
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
private fun EditDiffView(diff: ToolDiffDto, modifier: Modifier = Modifier) {
    // No horizontal scroll here: per-line backgrounds need a bounded width to
    // span the card. Long lines wrap instead of scrolling. Vertical is capped
    // the same way as the other expandable bodies: a long diff scrolls within
    // the card instead of growing the row unbounded.
    ExpandableBody(Modifier.padding(bottom = 4.dp)) {
        diff.path?.let {
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
            val rows = simpleLineDiff(hunk.old.lines(), hunk.new.lines())
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
    calls: List<Item.Tool>,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
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
                ToolRowCard(call, expanded, onExpand)
            }
        }
    }
}

@Composable
private fun ThinkingCard(
    thinking: TextDto,
    active: Boolean,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
) {
    // Saveable, not remembered: a LazyColumn item that scrolls out of view is
    // disposed, and a plain remember would re-collapse the card behind the
    // user's back. The list keys items by entryId, which scopes the saved
    // state to this message.
    var open by rememberSaveable { mutableStateOf(false) }
    val more = thinking.more
    val full = more?.let { expanded[it.ref] }

    // pi-web thinking card: neutral bordered card, plain "Thinking" header
    // (no chevron), body below a hairline divider.
    ThinkingCardShell(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        active = active,
        // Thinking streams as `append` patches, so the text is the pulse key —
        // the same rhythm as a running tool card.
        pulseOn = thinking.takeIf { active },
        onClick = {
            open = !open
            if (open && more != null && full == null) onExpand(more.ref)
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
                    full ?: thinking.s,
                    style = MonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (full == null && more != null) {
                LoadingMore(more.displaySize)
            }
        }
    }
}

/**
 * One tool row: the call, its arguments or diff, and its output.
 *
 * Serves every shape a tool takes — a call with a pending result, a finished call, a
 * `/bash` execution, and a result whose call sits on an older page — because the
 * server hands all four over as the same item. There used to be three composables
 * here, plus client-side logic to pair calls with results across page boundaries.
 */
@Composable
private fun ToolRowCard(
    tool: Item.Tool,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = tool.isError || (tool.exit ?: 0) != 0
    val borderColor = if (error) ToolErrBorder else ToolOkBorder

    ToolCallCard(
        name = tool.name.ifBlank { stringResource(Res.string.tool_result) },
        nameColor = if (error) ToolErrRed else ToolOkGreen,
        subtitle = tool.title,
        borderColor = borderColor,
        bgColor = if (error) ToolErrBg else ToolOkBg,
        modifier = modifier,
        // Breathes once per patch: every patch for this call — output appended,
        // args or title filled in — arrives as a new [Item.Tool], so the item is
        // both the "something landed" signal and the pulse key. Dropped once the
        // call settles, or a finished card would breathe again on every scroll in.
        //
        // The card still does not open itself: every one starts collapsed, so the
        // conversation stays scannable and nothing shifts under the reader when a
        // tool starts.
        pulseOn = tool.takeIf { it.running },
    ) {
        val diff = tool.diff
        val args = tool.args
        if (diff == null && args != null && args.s.isNotBlank()) {
            // Arguments, pi-web style: pre-wrap, dim mono, subtle bg, hairline
            // divider in the card's tint, height-capped with internal scroll.
            ToolDivider(borderColor)
            ExpandableBody(Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))) {
                Text(
                    expanded[args.more?.ref] ?: args.s,
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
        args?.more?.let {
            ExpandRow(it, expanded, onExpand, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp))
        }

        val full = expanded[tool.output.more?.ref]
        val body = full ?: tool.output.s
        if (body.isNotBlank() || tool.hasImage) {
            ToolDivider(borderColor)
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (tool.hasImage) {
                    Text(
                        stringResource(
                            Res.string.tool_image_truncated,
                            tool.output.more?.displaySize ?: stringResource(Res.string.not_loaded),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (body.isNotBlank()) ScrollableCode(body, error = error)
                if (full == null && !tool.hasImage) {
                    tool.output.more?.let { ExpandRow(it, expanded, onExpand) }
                }
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
 * 1dp border that breathes once each time [key] changes.
 *
 * Every live card here has a per-update signal — a patch — and every patch
 * arrives as a fresh item, so the item itself is the key. The border snaps
 * bright when one lands and decays back to rest, then stays there: a tool
 * waiting on a slow command, or a thinking block that has stopped growing, sits
 * still instead of implying progress it is not making.
 *
 * A patch landing mid-breath restarts it — [LaunchedEffect] cancels the running
 * animation — so a fast stream reads as a border held bright rather than a queue
 * of breaths played back late.
 *
 * The animated alpha is read inside the draw lambda, never during composition.
 * Reading it as a plain value would restart [CardShell] on every animation
 * frame, and because `Column` is inline that drags the card's whole subtree —
 * text measurement included — through recomposition ~60 times a second while a
 * tool streams output.
 */
@Composable
private fun Modifier.pulsingBorder(base: Color, shape: Shape, key: Any?): Modifier {
    val alpha = remember(base) { Animatable(base.alpha) }
    LaunchedEffect(alpha, key) {
        alpha.animateTo((base.alpha + 0.55f).coerceAtMost(1f), tween(durationMillis = 140))
        alpha.animateTo(base.alpha, tween(durationMillis = 560))
    }
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
 * tint it via [background]/[border]. Headers and bodies are just content.
 *
 * [pulseOn] is the live-card signal: pass the value that changes with every
 * patch — the item itself — and the border breathes once per patch. Null means a
 * settled card, drawn with a static border. Gating it on "still running" is the
 * caller's job: a [pulseOn] that outlives the work would breathe again every
 * time the card scrolls back into view.
 */
@Composable
internal fun CardShell(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    background: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: Color? = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    pulseOn: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val borderModifier = when {
        border == null -> Modifier
        pulseOn != null -> Modifier.pulsingBorder(border, shape, pulseOn)
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
 * preview and a rotating chevron. Serves every shape a tool takes — a call, a
 * bash execution, a running call — so one looks exactly like the card it becomes.
 *
 * **Always starts collapsed**, running or not. The header alone says what happened
 * (tool, path or command, green or red), and a card that opened itself would push
 * whatever the reader was looking at down the screen mid-turn.
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
    pulseOn: Any? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "tool-card-chevron")
    // A streaming card's subtitle is stable while its output grows; shortening
    // it once per value keeps the allocation off the recomposition path.
    val compactSubtitle = remember(subtitle) { subtitle?.compactForRow() }

    CardShell(
        modifier = modifier,
        background = bgColor,
        border = borderColor,
        pulseOn = pulseOn,
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
                contentDescription = if (open) stringResource(Res.string.collapse) else stringResource(Res.string.expand),
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
 * [active] means this turn is still reasoning, and drives the header wording —
 * a settled card reading "Thinking…" claims work that finished long ago, and
 * languages that mark the progressive form (中文) cannot paper over that with
 * one neutral noun the way English does. [pulseOn] breathes the border per
 * patch; see [CardShell].
 */
@Composable
internal fun ThinkingCardShell(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    active: Boolean = false,
    pulseOn: Any? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    CardShell(modifier = modifier, pulseOn = pulseOn) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (active) Res.string.card_thinking_active else Res.string.card_thinking),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}


@Composable
private fun NoticeBlock(item: Item.Notice, modifier: Modifier) {
    val arg = item.arg.orEmpty()
    val text = when (item.note) {
        "compaction" -> stringResource(Res.string.notice_compacted)
        "branch" -> stringResource(Res.string.notice_branch_summary)
        "model" -> stringResource(Res.string.notice_model_change, arg)
        "thinking" -> stringResource(Res.string.notice_thinking_level, arg)
        "named" -> stringResource(Res.string.notice_session_named, arg)
        // "text", and anything a newer bridge invents: show what it sent.
        else -> arg
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

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun decodeBase64Bitmap(base64: String): ImageBitmap? = runCatching {
    val bytes = kotlin.io.encoding.Base64.decode(base64)
    decodeImageScaled(bytes, MAX_FULL_IMAGE_EDGE)
}.getOrNull()

/** Longest edge for a full-size message image; thumbs use a smaller cap. */
private const val MAX_FULL_IMAGE_EDGE = 1536

/**
 * Fullscreen image with pinch-zoom, pan, double-tap to toggle zoom, and a
 * tap on the backdrop (or Back) to close.
 */
@Composable
private fun ZoomableImageDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    // BackHandler powers system back in this app; the dialog also exposes an
    // explicit close button.
    androidx.compose.ui.backhandler.BackHandler(onBack = onDismiss)

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
                bitmap = bitmap,
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
                    contentDescription = stringResource(Res.string.close),
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
    more: MoreDto,
    expanded: Map<String, String>,
    onExpand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (expanded.containsKey(more.ref)) return
    Text(
        stringResource(Res.string.tool_expand_all, more.displaySize),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable { onExpand(more.ref) }.padding(top = 4.dp),
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
        stringResource(Res.string.tool_loading_more, size),
        style = MaterialTheme.typography.labelSmall,
        color = Color.Gray,
        modifier = Modifier.padding(start = 24.dp),
    )
}
