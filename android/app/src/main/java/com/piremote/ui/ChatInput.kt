package com.piremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

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

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("发消息给 pi…") },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
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
                            Icon(Icons.Default.Stop, contentDescription = "中止")
                        }
                    } else {
                        val canSend = text.isNotBlank()
                        FilledIconButton(
                            onClick = {
                                val toSend = text.trim()
                                if (toSend.isNotEmpty()) {
                                    onSend(toSend)
                                    onTextChange("")
                                }
                            },
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
