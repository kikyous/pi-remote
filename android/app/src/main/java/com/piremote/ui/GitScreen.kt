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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piremote.data.AppRepository
import com.piremote.net.GitChangeDto
import com.piremote.net.GitCommitDiffDto
import com.piremote.net.GitCommitDto
import com.piremote.net.GitDiffDto
import com.piremote.net.GitDiffLineDto
import com.piremote.net.GitStatusDto
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private enum class GitTab { Changes, Commits }

/**
 * Read-only git view for the repo a session lives in: changed files, commit
 * history, and per-file/commit diffs. Entry is from the chat top bar.
 */
@Composable
fun GitScreen(
    repo: AppRepository,
    cwd: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(GitTab.Changes) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var selectedCommit by remember { mutableStateOf<GitCommitDto?>(null) }

    when {
        selectedPath != null || selectedCommit != null -> {
            // Inner handler wins while a diff is open: the system back key pops
            // the detail back to the list, not straight to the chat screen.
            BackHandler {
                selectedPath = null
                selectedCommit = null
            }
            if (selectedPath != null) {
                GitDiffScreen(repo, cwd, selectedPath!!, onBack = { selectedPath = null }, modifier = modifier)
            } else {
                GitCommitDetailScreen(repo, cwd, selectedCommit!!, onBack = { selectedCommit = null }, modifier = modifier)
            }
        }

        else -> GitListScreen(
            repo = repo,
            cwd = cwd,
            tab = tab,
            onTabChange = { tab = it },
            onOpenFile = { selectedPath = it },
            onOpenCommit = { selectedCommit = it },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitListScreen(
    repo: AppRepository,
    cwd: String,
    tab: GitTab,
    onTabChange: (GitTab) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenCommit: (GitCommitDto) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd) {
        branch = runCatching { repo.client.gitStatus(cwd) }.getOrNull()?.branch
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
                    Column {
                        Text(
                            branch?.takeIf { it.isNotBlank() } ?: "Git",
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == GitTab.Changes,
                    onClick = { onTabChange(GitTab.Changes) },
                    text = { Text("Changes") },
                )
                Tab(
                    selected = tab == GitTab.Commits,
                    onClick = { onTabChange(GitTab.Commits) },
                    text = { Text("Commits") },
                )
            }
            when (tab) {
                GitTab.Changes -> ChangesList(repo, cwd, onOpenFile)
                GitTab.Commits -> CommitsList(repo, cwd, onOpenCommit)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesList(repo: AppRepository, cwd: String, onOpenFile: (String) -> Unit) {
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

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
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
                "No changes",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitsList(repo: AppRepository, cwd: String, onOpenCommit: (GitCommitDto) -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var commits by remember { mutableStateOf<List<GitCommitDto>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        try {
            val page = repo.client.gitCommits(cwd, limit = COMMITS_PAGE_SIZE, before = if (reset) null else nextCursor)
            if (reset) commits = page.commits else commits = commits + page.commits
            nextCursor = page.nextCursor
            error = null
        } catch (e: Exception) {
            if (reset) error = e.message ?: "获取提交失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(cwd) { load(reset = true) }

    // Fetch the older page when the bottom of the loaded range comes into view.
    val shouldLoadMore by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        nextCursor != null && total > 0 && last >= total - PREFETCH_DISTANCE
    } }
    LaunchedEffect(listState, nextCursor) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                loadingMore = true
                load(reset = false)
                loadingMore = false
            }
    }

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = { scope.launch { load(reset = true) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            error != null && commits.isEmpty() -> Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )

            commits.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            commits.isEmpty() -> Text(
                "No commits",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(commits, key = { it.hash }) { commit ->
                    GitCommitRow(commit, onClick = { onOpenCommit(commit) })
                }
                if (loadingMore) {
                    item(key = "loading-more") {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
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

@Composable
private fun GitCommitRow(commit: GitCommitDto, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            commit.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                commit.shortHash,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "  ${commit.author} · ${commit.date.toFriendlyTime()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (commit.added > 0 || commit.deleted > 0) {
                Text(
                    "+${commit.added}  -${commit.deleted}",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    DiffScaffold(
        title = path,
        onBack = onBack,
        modifier = modifier,
    ) {
        val d = diff
        if (d != null) {
            for (hunk in d.hunks) {
                HunkHeader(hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount)
                for (line in hunk.lines) DiffLine(DiffRow(line.text, line.kindOf()))
            }
            if (d.hunks.isEmpty()) NoDiffText()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitCommitDetailScreen(
    repo: AppRepository,
    cwd: String,
    commit: GitCommitDto,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<GitCommitDiffDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd, commit.hash) {
        runCatching { repo.client.gitCommitDiff(cwd, commit.hash) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "获取提交详情失败" }
    }

    DiffScaffold(
        title = commit.subject,
        subtitle = "${commit.shortHash} · ${commit.author} · ${commit.date.toFriendlyTime()}",
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            error != null -> Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

            detail == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> detail!!.files.forEach { file ->
                Text(
                    "--- ${file.path}",
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 6.dp),
                )
                for (hunk in file.hunks) {
                    HunkHeader(hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount)
                    for (line in hunk.lines) DiffLine(DiffRow(line.text, line.kindOf()))
                }
                if (file.hunks.isEmpty()) {
                    Text(
                        "  (binary or no text diff)",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/** Shared top bar + scrollable body for the two diff detail screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
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
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

@Composable
private fun HunkHeader(oldStart: Int, oldCount: Int, newStart: Int, newCount: Int) {
    Text(
        "@@ -$oldStart,$oldCount +$newStart,$newCount @@",
        style = MonoStyle,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun NoDiffText() {
    Text(
        "No text diff",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

private fun GitDiffLineDto.kindOf(): DiffKind = when (type) {
    "add" -> DiffKind.Added
    "remove" -> DiffKind.Removed
    else -> DiffKind.Context
}

private const val COMMITS_PAGE_SIZE = 20
private const val PREFETCH_DISTANCE = 6
