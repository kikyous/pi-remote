package com.piremote.ui

import com.piremote.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.piremote.net.PromptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The composer.
 *
 * While a turn is running the send button becomes stop, which is the action
 * that actually matters at that moment — sending anyway needs an explicit
 * steer/queue decision, handled by the dialog in ChatScreen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatInput(
    draft: StateFlow<String>,
    onTextChange: (String) -> Unit,
    running: Boolean,
    queued: List<String>,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
    onPickModel: () -> Unit,
    onPickThinking: (() -> Unit)?,
    onNewSession: () -> Unit,
    onGenerateTitle: () -> Unit,
    generatingTitle: Boolean = false,
    onSendImage: (List<android.net.Uri>) -> Unit,
    attachments: List<PromptImage>,
    onRemoveAttachment: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collected here, not in ChatScreen: typing recomposes only the composer,
    // and the value itself lives in the session store so it survives leaving
    // and re-entering the chat screen.
    val text by draft.collectAsStateWithLifecycle()

    // The "More" panel (WeChat-style): tapping + swaps the keyboard for a
    // grid of actions at exactly the IME height, so nothing jumps.
    var panelOpen by remember { mutableStateOf(false) }
    // While the keyboard slides up over the panel, keep the panel mounted so
    // the space below the composer is never empty (no drop-then-push jitter).
    var panelClosing by remember { mutableStateOf(false) }
    val panelVisible = panelOpen || panelClosing
    val scope = rememberCoroutineScope()

    // Freeze the last keyboard height: while the panel is open the keyboard is
    // hidden, but the panel takes its place 1:1 (no jitter on toggle). Only
    // grow the capture — the hide animation would otherwise shrink it to 0.
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    var panelHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(imePx) {
        if (imePx > panelHeightPx) panelHeightPx = imePx
    }

    // The animation TARGET, not the live inset: hiding the keyboard sets the
    // target to 0 immediately, so opening the panel never trips this; showing
    // it (tapping the field) flips the target to >0 and closes the panel.
    val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    LaunchedEffect(imeTarget) {
        if (imeTarget > 0) {
            panelOpen = false
            panelClosing = false
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    fun setIme(show: Boolean) {
        if (show) {
            // Hiding the keyboard earlier cleared the field's focus; show()
            // needs it back to actually raise the IME.
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    val togglePanel: () -> Unit = {
        if (panelOpen) {
            panelOpen = false
            panelClosing = true
            setIme(true)
            scope.launch {
                delay(400) // long enough for the IME slide-up to cover the panel
                panelClosing = false
            }
        } else {
            // Capture the keyboard's CURRENT height before hiding it, so the
            // panel matches whatever IME height the user configured — not a
            // stale max from an earlier, taller keyboard.
            panelHeightPx = imePx
            panelOpen = true
            panelClosing = false
            setIme(false)
        }
    }
    val closePanel = { panelOpen = false }

    // Back closes the panel first, then the screen.
    BackHandler(enabled = panelOpen) {
        panelOpen = false
        panelClosing = false
        setIme(true)
    }

    // System photo picker, multi-select: no storage permission needed, returns
    // readable Uris. Cap keeps the combined base64 within the server body limit.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        panelOpen = false
        if (uris.isNotEmpty()) onSendImage(uris)
    }

    // Shared by the send button and the Enter key: trim, send, clear.
    val submit = {
        val toSend = text.trim()
        if (toSend.isNotEmpty() || attachments.isNotEmpty()) {
            panelOpen = false
            onSend(toSend)
            onTextChange("")
        }
    }

    // enableEdgeToEdge draws behind the system bars, so the composer has to
    // step around the navigation bar. Instead of `imePadding()` (which animates
    // with the keyboard and would let the composer drop during a panel<->ime
    // switch), pad by the IME *target*: it jumps to the full keyboard height
    // the instant show() is called, so the composer never moves either way.
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(
                bottom = with(density) {
                    // Panel mode: the panel itself provides the height, so no
                    // extra padding (avoids double-counting). Keyboard mode:
                    // pad by the IME *target* so the composer never drops while
                    // the keyboard animates in.
                    val padPx = if (panelVisible) 0 else WindowInsets.imeAnimationTarget.getBottom(density)
                    padPx.toDp()
                },
            ),
    ) {
        if (queued.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                for (message in queued) {
                    Text(
                        stringResource(R.string.chat_queued, message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // 附件预览条：选中的图片先挂在这里，配上文字一起发送。
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEachIndexed { index, image ->
                    AttachmentThumb(image = image, onRemove = { onRemoveAttachment(index) })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 160.dp)
                    .focusRequester(focusRequester)
                    // Enter sends; Shift+Enter inserts a newline. KeyDown is
                    // swallowed so the newline never lands in the draft.
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Enter || event.nativeKeyEvent.isShiftPressed) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.type) {
                            KeyEventType.KeyDown -> true
                            KeyEventType.KeyUp -> {
                                submit()
                                true
                            }
                            else -> false
                        }
                    },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                leadingIcon = {
                    // Tapping + swaps the keyboard for the action panel;
                    // tapping again brings the keyboard back.
                    IconButton(onClick = togglePanel) {
                        Icon(
                            if (panelOpen) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = stringResource(R.string.more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = {
                    if (running) {
                        IconButton(
                            onClick = onAbort,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(R.string.stop),
                                // A bit bigger than the default so the stop
                                // square reads clearly at a glance.
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    } else {
                        val canSend = text.isNotBlank() || attachments.isNotEmpty()
                        FilledIconButton(
                            onClick = submit,
                            enabled = canSend,
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }

        // WeChat-style action panel: occupies exactly the keyboard height, so
        // swapping keyboard <-> panel never moves the composer.
        if (panelVisible) {
            val panelHeight =
                if (panelHeightPx > 100) panelHeightPx else with(density) { 300.dp.toPx() }.toInt()
            MorePanel(
                generatingTitle = generatingTitle,
                onPickModel = onPickModel,
                onPickThinking = onPickThinking,
                onNewSession = {
                    panelOpen = false
                    setIme(true)
                    onNewSession()
                },
                onGenerateTitle = onGenerateTitle,
                onPickImages = {
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(with(density) { panelHeight.toDp() }),
            )
        }
    }
}

/** WeChat-style grid of actions: icon cells with labels, 4 per row. */
@Composable
private fun MorePanel(
    generatingTitle: Boolean,
    onPickModel: () -> Unit,
    onPickThinking: (() -> Unit)?,
    onNewSession: () -> Unit,
    onGenerateTitle: () -> Unit,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    data class Cell(
        val label: String,
        val icon: ImageVector,
        val action: () -> Unit,
        val generating: Boolean = false,
    )

    val cells = buildList {
        add(Cell(stringResource(R.string.chat_switch_model), Icons.Outlined.SmartToy, onPickModel))
        onPickThinking?.let { add(Cell(stringResource(R.string.thinking_level), Icons.Outlined.Psychology, it)) }
        add(Cell(stringResource(R.string.new_session), Icons.Outlined.NoteAdd, onNewSession))
        add(
            Cell(
                if (generatingTitle) stringResource(R.string.generating)
                else stringResource(R.string.generate_title),
                Icons.Outlined.Title,
                { if (!generatingTitle) onGenerateTitle() },
                generating = generatingTitle,
            ),
        )
        add(Cell(stringResource(R.string.chat_send_image), Icons.Outlined.Image, onPickImages))
    }

    // The grid is centered as a whole; items flow left-to-right inside fixed
    // columns, so a partial last row left-aligns under the first column.
    // Column count adapts to the available width (more columns in landscape).
    BoxWithConstraints(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 16.dp),
    ) {
        val cellWidth = 68.dp
        val gap = 10.dp
        // Columns adapt to the available width (landscape fits more), but when
        // every cell fits in a single row use exactly that many so the row
        // centers as a whole instead of leaving a sparse tail.
        val rawColumns = ((maxWidth - 48.dp) / (cellWidth + gap)).toInt().coerceIn(3, 8)
        val columns = if (cells.size < rawColumns) cells.size else rawColumns
        val gridWidth = (cellWidth + gap) * columns.toFloat() - gap

        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cells.chunked(columns).forEach { rowCells ->
                Row(
                    Modifier.width(gridWidth),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    rowCells.forEach { cell ->
                        Column(
                            Modifier
                                .width(cellWidth)
                                .clickable(enabled = !cell.generating) { cell.action() },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cell.generating) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    cell.icon,
                                    contentDescription = cell.label,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Text(
                            cell.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/** The assistant turn as it arrives, before it settles into a real entry. */
@Composable
fun StreamingBubble(
    text: String,
    thinking: Boolean,
    toolName: String?,
    toolSubtitle: String?,
    toolOutput: String,
    compacting: Boolean,
    modifier: Modifier = Modifier,
) {
    // Same card as a settled assistant message, so the live turn and the
    // finished one read alike.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (compacting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(12.dp), strokeWidth = 2.dp)
                Text(
                    "Compacting…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (thinking && text.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(12.dp), strokeWidth = 2.dp)
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (text.isNotBlank()) {
            // Plain text while streaming — markdown is applied once the message
            // settles, so the layout is not re-parsed on every frame.
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }

        if (toolName != null) {
            // One card only: the tool indicator is a plain row inside the
            // bubble, not a nested box. Same 4dp top gap as the output below,
            // so the running tool and its output share one rhythm.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(12.dp), strokeWidth = 2.dp)
                Text(
                    toolName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
                toolSubtitle?.let {
                    Text(
                        it,
                        style = MonoStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (toolOutput.isNotBlank()) {
                Text(
                    toolOutput.takeLast(LIVE_OUTPUT_TAIL),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Live tool output can run to megabytes; only the tail is worth showing, and
 * rendering all of it would stall the frame it arrives on.
 */
private const val LIVE_OUTPUT_TAIL = 2000

/** Max images per send: picker cap keeps base64 under the server body limit. */
private const val MAX_ATTACHMENTS = 5

/** One picked image in the composer's attachment bar, with a remove button. */
@Composable
private fun AttachmentThumb(image: PromptImage, onRemove: () -> Unit) {
    Box {
        val bitmap by produceState<Bitmap?>(initialValue = null, image.data) {
            // produceState runs on the composition's (main) dispatcher; decode
            // a large base64 payload off the frame.
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = android.util.Base64.decode(image.data, android.util.Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_attached_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        // Small circular ✕ half-hanging off the thumbnail's top-right corner.
        // Plain Box+clickable: IconButton's 48dp minimum touch target would
        // overlap the image and look like a dark blob on it.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-8).dp)
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_remove_attachment),
                tint = Color.White,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
