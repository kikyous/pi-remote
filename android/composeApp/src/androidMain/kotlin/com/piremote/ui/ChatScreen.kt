package com.piremote.ui

import com.piremote.R

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.piremote.data.SessionStore
import com.piremote.net.Item
import com.piremote.net.ModelDto
import com.piremote.net.PromptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter


/**
 * One session's conversation.
 *
 * The list is reversed: newest at the bottom, and paging walks backwards from
 * there. That matches how the data is fetched (tail-first) and means opening a
 * long session costs one page, not the whole history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    store: SessionStore,
    models: List<ModelDto>,
    onFollow: (String) -> Unit,
    onUnfollow: (String) -> Unit,
    onBack: () -> Unit,
    onOpenGit: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val attachments by store.attachments.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // reverseLayout pins the newest content at the bottom for free only while
    // the list rests there: a message prepended below the viewport stays
    // below it, because LazyColumn key-anchors on the first visible item
    // instead of re-anchoring to the bottom. One rule covers it — follow
    // while the user has not scrolled away, and re-arm the follow the moment
    // they come back to the bottom (firstVisibleItemIndex == 0). Reading
    // history is never disturbed: any drag clears the follow until the list
    // returns to index 0.
    var follow by remember(store) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { if (listState.firstVisibleItemIndex == 0) follow = true }
    }
    // Newest content growing (a message landing) shifts the count; paging
    // history in shifts it too, but then follow is false. Streaming growth
    // and the keyboard change no count and need nothing here — the bottom
    // anchor already holds them.
    LaunchedEffect(listState, store) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect {
                if (follow && !listState.isScrollInProgress) listState.scrollToItem(0)
            }
    }

    // Sending always returns the view to the newest content: the message
    // lands at index 0 (the bottom anchor) once the server echoes it back,
    // and the follow above then keeps it in view. A reader mid-history would
    // otherwise never see their own message.
    val scrollToLatest: () -> Unit = { scope.launch { listState.animateScrollToItem(0) } }

    // Which picker bottom sheet (model / thinking level) is open; opened from
    // the composer's "More" menu.
    var sheet by remember { mutableStateOf<SessionSheet?>(null) }

    val currentModel = state.detail?.model
    val currentModelDto = models.firstOrNull {
        it.provider == currentModel?.provider && it.id == currentModel.modelId
    }
    // Thinking level is only meaningful when the model reasons at all.
    val canPickThinking = (state.detail?.availableThinkingLevels
        ?: currentModelDto?.thinkingLevels
        ?: emptyList()).size > 1

    // Consecutive tool rows fold into the assistant message above them. Done here
    // rather than on the wire so a patch still addresses exactly one item by id.
    val groups = remember(state.items) { groupRows(state.items) }
    // reverseLayout: index 0 is the bottom, so the newest group goes first.
    val rows = remember(groups) { groups.asReversed() }

    // A snapshot arrives from the socket, so opening a session fetches nothing.
    // `store` identity changes when the session does.
    DisposableEffect(store) {
        onFollow(store.sessionId)
        onDispose { onUnfollow(store.sessionId) }
    }

    // Fetch the previous page as the top of the loaded range comes into view.
    // reverseLayout: older messages live at the END (highest indices, the
    // visually top rows), so the trigger is the LAST visible item.
    LaunchedEffect(store, listState) {
        // One page per gesture, re-armed by the next drag.
        //
        // Neither end of this is free to get wrong. Sampling only "is the top
        // in view" latches: a page of 50 items can collapse into a handful of
        // rows (tool calls fold into the message above them), the top stays in
        // view, the flag never leaves `true` and never re-fires — paging dies
        // with the loader still sitting there. But re-evaluating on row count
        // alone runs away instead: the loader holds the highest index, so a
        // reader parked at the very top keeps reporting the last visible item at
        // the end of the list no matter how much history lands above it, and the
        // list pages itself back to the start of the session in one burst.
        var armed = true
        launch {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) armed = true
            }
        }
        snapshotFlow {
            val info = listState.layoutInfo
            val lastIndex = info.totalItemsCount - 1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (lastIndex - lastVisible) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .filter { (remaining, _) -> remaining <= PREFETCH_DISTANCE }
            .collect {
                // A list too short to scroll cannot re-arm itself — there is no
                // gesture to make — so keep paging until one is possible.
                val scrollable = listState.canScrollForward || listState.canScrollBackward
                if (armed || !scrollable) {
                    armed = false
                    store.loadOlder()
                }
            }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            store.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Text(
                            state.detail?.name?.takeIf { it.isNotBlank() }
                                ?: state.detail?.firstMessage?.lineSequence()?.firstOrNull()?.take(40)
                                ?: stringResource(R.string.chat_session_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.detail?.let { detail ->
                            Text(
                                buildString {
                                    append(detail.cwd.substringAfterLast('/'))
                                    detail.model?.let { append(" · ${it.modelId}") }
                                    if (detail.thinkingLevel.isNotBlank()) append(" · ${detail.thinkingLevel}")
                                    state.status.context?.percent?.let {
                                        append(" · Context ${it.roundToInt()}%")
                                    }
                                    if (state.status.running) append(ctx.getString(R.string.chat_session_running))
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenGit) {
                        Icon(Icons.Outlined.Commit, contentDescription = stringResource(R.string.chat_git_changes))
                    }
                    IconButton(onClick = { store.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        bottomBar = {
            Column {
                SessionPickerSheets(
                    detail = state.detail,
                    models = models,
                    sheet = sheet,
                    onDismiss = { sheet = null },
                    onPickModel = { store.setModel(it.provider, it.id) },
                    onPickThinking = store::setThinkingLevel,
                    loadStats = store::stats,
                )
                ChatInput(
                    draft = store.draft,
                    onTextChange = store::setDraft,
                    running = state.status.running,
                    queued = state.status.queued,
                    onSend = { text ->
                        scrollToLatest()
                        store.send(text)
                    },
                    onAbort = store::abort,
                    onPickModel = { sheet = SessionSheet.Model },
                    onPickThinking = if (canPickThinking) {
                        { sheet = SessionSheet.Thinking }
                    } else null,
                    onNewSession = onNewSession,
                    onGenerateTitle = {
                        store.generateTitle { title, err ->
                            scope.launch {
                                snackbar.showSnackbar(err ?: ctx.getString(R.string.chat_title_generated, title))
                            }
                        }
                    },
                    generatingTitle = state.generatingTitle,
                    onCompact = {
                        store.compact { result, err ->
                            scope.launch {
                                snackbar.showSnackbar(
                                    err ?: ctx.getString(
                                        R.string.chat_compacted,
                                        formatTokens(result?.tokensBefore),
                                        formatTokens(result?.tokensAfter),
                                    ),
                                )
                            }
                        }
                    },
                    // The server's own flag covers a compaction another device
                    // started, or one pi kicked off on its own.
                    compacting = state.compacting || state.status.compacting,
                    onSessionInfo = { sheet = SessionSheet.Info },
                    onSendImage = { uris ->
                        // 方案 A：先挂到附件预览条，配文字后一起发送。
                        scope.launch {
                            val images = uris.mapNotNull { loadPromptImage(ctx, it) }
                            if (images.isNotEmpty()) {
                                images.forEach(store::addAttachment)
                            } else {
                                snackbar.showSnackbar(ctx.getString(R.string.chat_image_read_failed))
                            }
                        }
                    },
                    attachments = attachments,
                    onRemoveAttachment = store::removeAttachment,
                )
            }
        },
    ) { padding ->
        state.busyPrompt?.let { pending ->
            BusyChoiceDialog(
                message = pending.text,
                onSteer = {
                    scrollToLatest()
                    store.send(pending.text, "steer", pending.images)
                },
                onQueue = {
                    scrollToLatest()
                    store.send(pending.text, "followUp", pending.images)
                },
                onDismiss = store::dismissBusyPrompt,
            )
        }

        Box(Modifier.padding(padding).fillMaxSize()) {
            // A refresh keeps the old content visible behind a thin progress bar
            // rather than blanking the screen.
            if (state.loading && state.items.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            when {
                state.loading && state.items.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.items.isEmpty() ->
                    Text(
                        stringResource(R.string.chat_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> SelectionContainer {
                    // One container for the whole list, not one per message:
                    // with per-message containers a selection in one card stays
                    // stuck when tapping another. A single scope clears it.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // reverseLayout: index 0 is the BOTTOM, so the newest
                        // content anchors there. New messages and streaming
                        // stay in view at the bottom with zero scroll
                        // bookkeeping, and the initial scroll position is
                        // already the latest message.
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                    // Index 0 is the bottom. The agent is working but has produced
                    // nothing yet: no item exists to render, so the pulse stands in.
                    if (state.status.running && rows.firstOrNull()?.isEmptyPending() != false) {
                        item(key = "waiting-pulse") { WaitingPulseLine() }
                    }
                    if (state.status.compacting) {
                        item(key = "compacting") { CompactingLine() }
                    }

                    // A tool call is its own item on the wire, so the renderer
                    // decides where it belongs: consecutive tool rows fold into
                    // the assistant message above them. Nothing needs to pair
                    // calls with results — the server did that.
                    items(rows, key = { it.lead.id }) { row ->
                        MessageView(
                            item = row.lead,
                            tools = row.tools,
                            expanded = state.expanded,
                            onExpand = store::expand,
                        )
                    }

                    // Top of the list (highest index): older history.
                    if (state.hasMore) {
                        item(key = "older-loader") {
                            // The spinner tracks loadingOlder, not hasMore: this
                            // row exists for the whole unread history, and a
                            // permanently turning spinner reads as a hang.
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp,
                                    Alignment.CenterHorizontally,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.loadingOlder) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    stringResource(
                                        if (state.loadingOlder) R.string.chat_loading_earlier
                                        else R.string.chat_earlier_messages,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/** How close to the loaded top to get before fetching the next page. */
private const val PREFETCH_DISTANCE = 8

/**
 * An item and the tool rows that belong under it.
 *
 * [lead] carries the row's identity, so a tool arriving under an assistant message
 * changes that group and nothing else.
 */
private data class ChatRow(val lead: Item, val tools: List<Item.Tool>)

/**
 * Fold consecutive tool items into the assistant message that called them.
 *
 * The server emits a tool call as an item of its own, right after the message that
 * made it, which keeps every patch addressed to a single id. Where those rows are
 * *drawn* is a rendering decision, and adjacency is all it takes. A tool item with
 * no assistant above it — its call is on an older page — stands on its own.
 */
private fun groupRows(items: List<Item>): List<ChatRow> {
    val out = ArrayList<ChatRow>(items.size)
    var i = 0
    while (i < items.size) {
        val lead = items[i]
        if (lead is Item.Assistant) {
            var end = i + 1
            while (end < items.size && items[end] is Item.Tool) end++
            @Suppress("UNCHECKED_CAST")
            out += ChatRow(lead, items.subList(i + 1, end) as List<Item.Tool>)
            i = end
        } else {
            out += ChatRow(lead, emptyList())
            i++
        }
    }
    return out
}

/** A turn that has started but produced nothing yet: the pulse stands in for it. */
private fun ChatRow.isEmptyPending(): Boolean {
    val assistant = lead as? Item.Assistant ?: return false
    return assistant.pending && assistant.text.s.isBlank() && assistant.thinking == null && tools.isEmpty()
}

/**
 * A token count for the compaction snackbar: `18.4k`, `740`, or `?` when the
 * agent reported no estimate.
 */
private fun formatTokens(tokens: Int?): String = when {
    tokens == null -> "?"
    tokens >= 1000 -> "%.1fk".format(tokens / 1000.0)
    else -> tokens.toString()
}

/**
 * Shown while the server compacts context. Not an item: compaction is something
 * happening to the session, and it lands as a notice once it is done.
 */
@Composable
private fun CompactingLine() {
    Text(
        stringResource(R.string.card_compacting),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp),
    )
}

/**
 * pi-web's "Waiting for model..." — a pulsing text line at the bottom of the
 * list while the agent runs but has not produced any content yet. Pulse is
 * opacity 1 → 0.5 over 1.5s (Tailwind animate-pulse), text 13px muted.
 */
@Composable
private fun WaitingPulseLine() {
    val transition = rememberInfiniteTransition(label = "waiting-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waiting-pulse-alpha",
    )
    Text(
        stringResource(R.string.chat_waiting_model),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
    )
}

/**
 * Read a picked image and turn it into a [PromptImage].
 *
 * Runs off the main thread; large photos are scaled down to [MAX_IMAGE_EDGE]
 * and re-encoded so the base64 payload stays within reason for the model API.
 */
private suspend fun loadPromptImage(context: Context, uri: Uri): PromptImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val bitmap = decodeScaled(bytes, MAX_IMAGE_EDGE)
                ?: return@withContext null
            val out = ByteArrayOutputStream()
            val png = mime.contains("png")
            bitmap.compress(
                if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                IMAGE_QUALITY,
                out,
            )
            PromptImage(
                data = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
                mimeType = if (png) "image/png" else "image/jpeg",
            )
        }.getOrNull()
    }

/**
 * Decode with `inSampleSize` so a huge photo never materialises at full
 * resolution (a 48MP shot is ~192MB of pixels) before being scaled down.
 */
private fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    return scaleDown(bitmap, maxEdge)
}

private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt(),
        (bitmap.height * scale).toInt(),
        true,
    )
}

private const val MAX_IMAGE_EDGE = 2048
private const val IMAGE_QUALITY = 85
