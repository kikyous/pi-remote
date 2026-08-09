package com.piremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piremote.data.AppRepository
import com.piremote.net.ProjectDto
import com.piremote.net.SessionSummaryDto
import kotlinx.coroutines.launch

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

    LaunchedEffect(Unit) { if (state.projects.isEmpty()) repo.refreshProjects() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Pi Remote") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewWorkspace,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建工作区") },
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
                EmptyHint(state.error ?: "还没有任何会话。在 PC 上用 pi 开一个试试。")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.projects, key = { it.cwd }) { project ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    pendingDelete = project
                                    false // keep the row; the dialog decides
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = { DeleteBackground() },
                        ) {
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
                                    "${project.sessionCount} 个会话 · ${project.lastModified.toFriendlyTime()}",
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
            title = { Text("删除工作区？") },
            text = { Text("将删除 ${project.name} 下的 ${project.sessionCount} 个会话。目录本身保留，不会删除磁盘上的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    repo.deleteWorkspace(project.cwd) { error ->
                        scope.launch {
                            snackbar.showSnackbar(error ?: "已删除工作区 ${project.name}")
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                text = { Text("新建会话") },
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
                EmptyHint(state.error ?: "这个目录下还没有会话")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { session ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    pendingDelete = session
                                    false // keep the row; the dialog decides
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = { DeleteBackground() },
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSession(session) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    session.displayTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${session.messageCount} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        session.modified.toFriendlyTime(),
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
            title = { Text("删除会话？") },
            text = { Text("将永久删除这个会话及其全部历史。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    repo.deleteSession(session.id, project.cwd) { error ->
                        scope.launch {
                            snackbar.showSnackbar(error ?: "已删除会话")
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** Red swipe background with a delete icon on the trailing edge. */
@Composable
private fun RowScope.DeleteBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "删除",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
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
fun String.toFriendlyTime(): String {
    val instant = runCatching { java.time.Instant.parse(this) }.getOrNull() ?: return this
    val now = java.time.Instant.now()
    val minutes = java.time.Duration.between(instant, now).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }
}

private typealias BoxScope = androidx.compose.foundation.layout.BoxScope
