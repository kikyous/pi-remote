package com.piremote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.piremote.net.PromptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The composer.
 *
 * While a turn is running the send button becomes stop, which is the action
 * that actually matters at that moment — sending anyway needs an explicit
 * steer/queue decision, handled by the dialog in ChatScreen.
 */
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
    onSendImage: (List<android.net.Uri>) -> Unit,
    attachments: List<PromptImage>,
    onRemoveAttachment: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collected here, not in ChatScreen: typing recomposes only the composer,
    // and the value itself lives in the session store so it survives leaving
    // and re-entering the chat screen.
    val text by draft.collectAsStateWithLifecycle()

    // The "更多" menu: model and thinking level live here now.
    var moreOpen by remember { mutableStateOf(false) }

    // Back closes the menu first, not the whole screen. The menu popup itself
    // is non-focusable, so opening it never steals focus from the text field.
    BackHandler(enabled = moreOpen) { moreOpen = false }

    // System photo picker, multi-select: no storage permission needed, returns
    // readable Uris. Cap keeps the combined base64 within the server body limit.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        moreOpen = false
        if (uris.isNotEmpty()) onSendImage(uris)
    }

    // Shared by the send button and the Enter key: trim, send, clear.
    val submit = {
        val toSend = text.trim()
        if (toSend.isNotEmpty() || attachments.isNotEmpty()) {
            onSend(toSend)
            onTextChange("")
        }
    }

    // enableEdgeToEdge draws behind the system bars, so the composer has to
    // step around the navigation bar and lift for the keyboard itself.
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (queued.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                for (message in queued) {
                    Text(
                        "排队中：$message",
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
                placeholder = { Text("发消息给 pi…") },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 160.dp)
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
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false },
                            // focusable = false: opening the menu must not take
                            // focus away from the input — the keyboard stays up
                            // and the field keeps its current activation state.
                            properties = PopupProperties(focusable = false),
                        ) {
                            DropdownMenuItem(
                                text = { Text("切换模型") },
                                leadingIcon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                                onClick = {
                                    moreOpen = false
                                    onPickModel()
                                },
                            )
                            onPickThinking?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text("思考等级") },
                                    leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        pick()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("新建会话") },
                                leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                                onClick = {
                                    moreOpen = false
                                    onNewSession()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("生成标题") },
                                leadingIcon = { Icon(Icons.Outlined.Title, contentDescription = null) },
                                onClick = {
                                    moreOpen = false
                                    onGenerateTitle()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("发送图片") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    moreOpen = false
                                    pickImages.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                        }
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
                                contentDescription = "中止",
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
                                contentDescription = "发送",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
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
                contentDescription = "附件图片",
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
                contentDescription = "移除附件",
                tint = Color.White,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
