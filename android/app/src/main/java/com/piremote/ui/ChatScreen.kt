package com.piremote.ui

import com.piremote.R

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.derivedStateOf
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
    val streaming by store.streaming.collectAsStateWithLifecycle()
    val attachments by store.attachments.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // Load once per session. `store` identity changes when the session does.
    LaunchedEffect(store) {
        if (state.items.isEmpty()) store.refresh()
    }

    // Follow live events only while this session is on screen; other sessions
    // keep their own stores and resubscribe when reopened.
    DisposableEffect(store) {
        onFollow(store.sessionId)
        onDispose { onUnfollow(store.sessionId) }
    }

    // Keep the newest content in view as it streams in, but only when the user
    // is already at the bottom — never yank them away from what they scrolled to.
    // Forward layout: the newest message is the LAST item.
    val atBottom by remember { derivedStateOf {
        val total = listState.layoutInfo.totalItemsCount
        total > 0 && listState.firstVisibleItemIndex >= total - 2
    } }
    // Forward layout starts at the top, so jump to the newest end when a session
    // first loads (before any user interaction).
    var initialScrollDone by remember(store) { mutableStateOf(false) }
    LaunchedEffect(state.items.size, store) {
        if (!initialScrollDone && state.items.isNotEmpty()) {
            initialScrollDone = true
            val loaderOffset = if (state.hasMore) 1 else 0
            listState.scrollToItem(loaderOffset + state.items.size - 1)
        }
    }
    // Key on the whole streaming state, not just the text: during a tool run
    // (bash etc.) the bubble grows via activeTool.partialOutput while text stays
    // unchanged — with narrower keys the effect would never re-run and the new
    // output would pile up below the fold until the user pulls it into view.
    LaunchedEffect(streaming, state.items.size) {
        if (atBottom) {
            val loaderOffset = if (state.hasMore) 1 else 0
            val streamOffset = if (streaming.hasContent || streaming.compacting) 1 else 0
            listState.animateScrollToItem(loaderOffset + state.items.size + streamOffset - 1)
        }
    }

    // Fetch the previous page as the top of the loaded range comes into view.
    // Forward layout: older messages live at the START (low indices).
    val shouldLoadOlder by remember(listState) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            first <= PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(store, listState) {
        snapshotFlow { shouldLoadOlder }
            .distinctUntilChanged()
            .filter { it }
            .collect { store.loadOlder() }
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
                                    detail.context?.percent?.let {
                                        append(" · Context ${it.roundToInt()}%")
                                    }
                                    if (detail.running) append(context.getString(R.string.chat_session_running))
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
                )
                ChatInput(
                    draft = store.draft,
                    onTextChange = store::setDraft,
                    running = streaming.running,
                    queued = streaming.queued,
                    onSend = { store.send(it) },
                    onAbort = store::abort,
                    onPickModel = { sheet = SessionSheet.Model },
                    onPickThinking = if (canPickThinking) {
                        { sheet = SessionSheet.Thinking }
                    } else null,
                    onNewSession = onNewSession,
                    onGenerateTitle = {
                        store.generateTitle { title, err ->
                            scope.launch {
                                snackbar.showSnackbar(err ?: context.getString(R.string.chat_title_generated, title))
                            }
                        }
                    },
                    generatingTitle = state.generatingTitle,
                    onSendImage = { uris ->
                        // 方案 A：先挂到附件预览条，配文字后一起发送。
                        scope.launch {
                            val images = uris.mapNotNull { loadPromptImage(context, it) }
                            if (images.isNotEmpty()) {
                                images.forEach(store::addAttachment)
                            } else {
                                snackbar.showSnackbar(context.getString(R.string.chat_image_read_failed))
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
                onSteer = { store.send(pending.text, "steer", pending.images) },
                onQueue = { store.send(pending.text, "followUp", pending.images) },
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

                state.items.isEmpty() && !streaming.hasContent ->
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
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                    if (state.hasMore) {
                        item(key = "older-loader") {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.chat_loading_earlier),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Forward layout: oldest first, newest at the end. Expanding
                    // a mid-chat card then naturally pushes newer messages DOWN
                    // (top-anchored) — no scroll compensation needed at all.
                    // animateItem placement-only: fades would play when the older
                    // page is prepended at the top (items fading in mid-scroll).
                    items(state.items, key = { it.entryId }) { item ->
                        MessageView(
                            item = item,
                            expanded = state.expanded,
                            onExpand = store::expand,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 0),
                                fadeOutSpec = tween(durationMillis = 0),
                            ),
                        )
                    }

                    if (streaming.hasContent || streaming.compacting) {
                        item(key = "streaming") {
                            StreamingBubble(
                                text = streaming.text,
                                thinking = streaming.thinking,
                                toolName = streaming.activeTool?.name,
                                toolSubtitle = streaming.activeTool?.subtitle,
                                toolOutput = streaming.activeTool?.partialOutput.orEmpty(),
                                compacting = streaming.compacting,
                            )
                        }
                    }

                    // pi-web's waiting hint: while the agent runs but has not
                    // produced any content yet (before the first token / thinking
                    // / tool card), show a pulsing line instead of empty space.
                    if (streaming.running && !streaming.hasContent && !streaming.compacting) {
                        item(key = "waiting-pulse") { WaitingPulseLine() }
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
