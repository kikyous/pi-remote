package com.piremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piremote.data.AppRepository
import com.piremote.net.GitChangeDto
import com.piremote.net.GitDiffDto
import com.piremote.net.GitDiffLineDto
import com.piremote.net.GitStatusDto
import kotlinx.coroutines.launch

/**
 * Read-only git view for the repo a session lives in: the changed-files list
 * and per-file diffs. Entry is from the chat top bar.
 */
@Composable
fun GitScreen(
    repo: AppRepository,
    cwd: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedPath by remember { mutableStateOf<String?>(null) }
    val path = selectedPath
    if (path != null) {
        // Inner handler wins while a diff is open: the system back key pops the
        // detail back to the changes list, not straight to the chat screen.
        BackHandler { selectedPath = null }
        GitDiffScreen(repo, cwd, path, onBack = { selectedPath = null }, modifier = modifier)
    } else {
        GitChangesScreen(repo, cwd, onBack = onBack, onOpenFile = { selectedPath = it }, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitChangesScreen(
    repo: AppRepository,
    cwd: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatusDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        refreshing = true
        try {
            status = repo.client.gitStatus(cwd)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "获取 git 状态失败"
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(cwd) { load() }

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
                        Text(
                            if (status?.branch.isNullOrBlank()) "Git 改动" else status!!.branch,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            cwd,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                error != null -> Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                status == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                status!!.changes.isEmpty() -> Text(
                    "没有改动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(status!!.changes, key = { it.path }) { change ->
                        GitChangeRow(change, onClick = { onOpenFile(change.path) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GitChangeRow(change: GitChangeDto, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(change.status)
        Text(
            change.path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (change.added > 0 || change.deleted > 0) {
            Text(
                "+${change.added}  -${change.deleted}",
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/** Small coloured status letter, GitHub-style. */
@Composable
private fun StatusBadge(status: String) {
    val (color, label) = when (status) {
        "A" -> diffPalette().first to "A"
        "D" -> diffPalette().second to "D"
        "M" -> MaterialTheme.colorScheme.primary to "M"
        "R" -> Color(0xFFE3B341) to "R"
        "C" -> Color(0xFFE3B341) to "C"
        "T" -> MaterialTheme.colorScheme.onSurfaceVariant to "T"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "?"
    }
    Box(
        Modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitDiffScreen(
    repo: AppRepository,
    cwd: String,
    path: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var diff by remember { mutableStateOf<GitDiffDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd, path) {
        runCatching { repo.client.gitDiff(cwd, path) }
            .onSuccess { diff = it }
            .onFailure { error = it.message ?: "获取 diff 失败" }
    }

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
                    Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        },
    ) { padding ->
        when {
            error != null -> Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
            )

            diff == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                for (hunk in diff!!.hunks) {
                    Text(
                        "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                    for (line in hunk.lines) {
                        DiffLine(DiffRow(line.text, line.kindOf()))
                    }
                }
                if (diff!!.hunks.isEmpty()) {
                    Text(
                        "无文本差异",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun GitDiffLineDto.kindOf(): DiffKind = when (type) {
    "add" -> DiffKind.Added
    "remove" -> DiffKind.Removed
    else -> DiffKind.Context
}
