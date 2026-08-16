package com.piremote.ui


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import piremote.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.Instant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piremote.data.AppRepository
import com.piremote.net.ProjectDto
import com.piremote.net.SessionSummaryDto
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Projects — one row per working directory that has sessions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    repo: AppRepository,
    onOpenProject: (ProjectDto) -> Unit,
    onOpenSettings: () -> Unit,
    onNewWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repo.browse.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ProjectDto?>(null) }
    val toFriendly = rememberFriendlyTime()
    val wsDeletedTemplate = stringResource(Res.string.ws_deleted)

    LaunchedEffect(Unit) { if (state.projects.isEmpty()) repo.refreshProjects() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Pi Remote CMP") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.ws_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewWorkspace,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.ws_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loadingProjects,
            onRefresh = { repo.refreshProjects() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.projects.isEmpty() && state.loadingProjects) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.projects.isEmpty()) {
                EmptyHint(state.error ?: stringResource(Res.string.ws_no_sessions))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.projects, key = { it.cwd }) { project ->
                        SwipeRevealAction(onAction = { pendingDelete = project }) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenProject(project) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    project.cwd,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(Res.string.ws_meta, project.sessionCount, toFriendly(project.lastModified)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.ws_delete_title)) },
            text = { Text(stringResource(Res.string.ws_delete_msg, project.name, project.sessionCount)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    repo.deleteWorkspace(project.cwd) { error ->
                        scope.launch {
                            snackbar.showSnackbar(error ?: formatTemplate(wsDeletedTemplate, project.name))
                        }
                    }
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/** Sessions inside one project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    repo: AppRepository,
    project: ProjectDto,
    onOpenSession: (SessionSummaryDto) -> Unit,
    onOpenNewSession: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repo.browse.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<SessionSummaryDto?>(null) }
    val toFriendly = rememberFriendlyTime()
    val wsSessionDeleted = stringResource(Res.string.ws_session_deleted)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                title = {
                    Column {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            project.cwd,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { repo.createSession(project.cwd, onOpenNewSession) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.new_session)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loadingSessions,
            onRefresh = { repo.refreshSessions(project.cwd) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.sessions.isEmpty() && state.loadingSessions) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.sessions.isEmpty()) {
                EmptyHint(state.error ?: stringResource(Res.string.ws_no_sessions_dir))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { session ->
                        SwipeRevealAction(onAction = { pendingDelete = session }) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSession(session) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    session.displayTitle.ifBlank { stringResource(Res.string.empty_session_title) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(Res.string.ws_message_count, session.messageCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        toFriendly(session.modified),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.ws_delete_session_title)) },
            text = { Text(stringResource(Res.string.ws_delete_session_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    repo.deleteSession(session.id, project.cwd) { error ->
                        scope.launch {
                            snackbar.showSnackbar(error ?: wsSessionDeleted)
                        }
                    }
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/**
 * iOS-style swipe-to-reveal: dragging a row left slides it aside, exposing a
 * delete action pinned to the trailing edge. No full-row tint — the action is
 * the only colored element, and it exists only while revealed.
 */
@Composable
private fun SwipeRevealAction(
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = 88.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        // Full-size layer behind the row; holds the action pinned to the end.
        // matchParentSize gives it the row's exact height, so the action
        // fills the row from top to bottom.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .width(actionWidth)
                    .fillMaxHeight()
                    // Fixed red, not colorScheme.error: dynamic color schemes
                    // derive "error" from the wallpaper (monochrome palettes
                    // make it a near-invisible light gray).
                    .background(Color(0xFFE53935))
                    .clickable {
                        scope.launch { offsetX.animateTo(0f, tween(150)) }
                        onAction()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.delete),
                    tint = Color.White,
                )
            }
        }

        // The row itself; only this slides, its background never changes.
        // It must be opaque so the action underneath stays hidden until the
        // row is dragged aside.
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque so the action underneath stays hidden until the row is
                // dragged aside. Must be INSIDE the offset, or the background
                // stays put and covers the revealed action.
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { scope.launch { offsetX.stop() } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                // A quarter swipe is enough to keep it revealed.
                                val target = if (offsetX.value < -actionWidthPx * 0.25f) -actionWidthPx else 0f
                                offsetX.animateTo(target, tween(150))
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(150)) } },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun BoxScope.EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
    )
}

/** ISO timestamp → something readable at a glance. */
@Composable
fun rememberFriendlyTime(): (String) -> String {
    val justNow = stringResource(Res.string.time_just_now)
    val minutesAgo = stringResource(Res.string.time_minutes_ago)
    val hoursAgo = stringResource(Res.string.time_hours_ago)
    val daysAgo = stringResource(Res.string.time_days_ago)
    return { iso ->
        val instant = runCatching { Instant.parse(iso) }.getOrNull()
        if (instant == null) {
            iso
        } else {
            val minutes = (Clock.System.now() - instant).inWholeMinutes
            when {
                minutes < 1 -> justNow
                minutes < 60 -> formatTemplate(minutesAgo, minutes)
                minutes < 60 * 24 -> formatTemplate(hoursAgo, minutes / 60)
                minutes < 60 * 24 * 30 -> formatTemplate(daysAgo, minutes / (60 * 24))
                // ISO date prefix (yyyy-MM-dd) — close enough without a timezone DB.
                else -> iso.take(10)
            }
        }
    }
}

private typealias BoxScope = androidx.compose.foundation.layout.BoxScope
