package com.piremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piremote.data.AppRepository
import com.piremote.data.Connection
import com.piremote.data.SettingsStore
import com.piremote.net.PiRemoteClient
import com.piremote.net.ProjectDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Projects : Screen
    data class Sessions(val project: ProjectDto) : Screen
    /** Carries the project so Back knows where it came from. */
    data class Chat(val sessionId: String, val project: ProjectDto) : Screen
    /** Git changes/diffs for the repo [cwd]; back returns to [backTo]. */
    data class Git(val cwd: String, val backTo: Screen) : Screen
    data object Settings : Screen
}

@Composable
fun PiRemoteApp(openSessionId: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val connectionFlow = remember { MutableStateFlow(Connection.EMPTY) }
    val connection by connectionFlow.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings.connection.collect {
            connectionFlow.value = it
            loaded = true
        }
    }

    // One client and one repository for the whole app; the client's fields are
    // mutable so changing the connection does not orphan the session stores.
    val client = remember { PiRemoteClient("", "") }
    val repo = remember { AppRepository(client, scope, context.applicationContext) }

    LaunchedEffect(connection) {
        client.baseUrl = connection.baseUrl
        client.token = connection.token
        if (connection.isConfigured) {
            repo.refreshProjects()
            repo.loadModels()
        }
    }

    val models by repo.models.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Projects) }

    // Root snackbar for one-shot feedback that outlives a single screen
    // (e.g. "workspace created"), drawn over whatever is on top.
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // System back pops the navigation stack instead of exiting the app:
    // Chat → Sessions → Projects. On the root the handler is disabled so the
    // system default (finishing the activity) applies — a registered BackHandler
    // always consumes the key, it never falls through.
    BackHandler(enabled = screen !is Screen.Projects) {
        when (val current = screen) {
            Screen.Settings -> screen = Screen.Projects
            is Screen.Sessions -> screen = Screen.Projects
            is Screen.Chat -> screen = Screen.Sessions(current.project)
            is Screen.Git -> screen = current.backTo
            Screen.Projects -> Unit // unreachable: handler is disabled here
        }
    }

    // Returning to the foreground: reconnect, then bring the visible session up
    // to date. The socket resumes from its last seq, but anything that happened
    // while the process was frozen is only guaranteed by a refetch.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                repo.socket.reconnectNow()
                (screen as? Screen.Chat)?.let { repo.storeFor(it.sessionId).refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Opened from a completion notification.
    LaunchedEffect(openSessionId) {
        val id = openSessionId ?: return@LaunchedEffect
        val cwd = repo.browse.value.sessions.firstOrNull { it.id == id }?.cwd
        val project = repo.browse.value.projects.firstOrNull { it.cwd == cwd }
        if (project != null) screen = Screen.Chat(id, project)
    }

    if (!loaded) return

    Box(modifier) {
        when {
            !connection.isConfigured -> {
                ConnectScreen(
                    initial = connection,
                    onSave = { scope.launch { settings.save(it) } },
                    onCancel = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> when (val current = screen) {
                Screen.Projects -> ProjectListScreen(
                    repo = repo,
                    onOpenProject = {
                        repo.selectProject(it.cwd)
                        screen = Screen.Sessions(it)
                    },
                    onOpenSettings = { screen = Screen.Settings },
                    onNewWorkspace = {
                        repo.createWorkspace { ws ->
                            val project = ProjectDto(
                                cwd = ws.cwd,
                                name = ws.cwd.substringAfterLast('/'),
                                sessionCount = 0,
                                lastModified = java.time.Instant.now().toString(),
                            )
                            screen = Screen.Chat(ws.id, project)
                            snackbarScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ws.created) "已创建工作区 ${ws.cwd}" else "已复用工作区 ${ws.cwd}",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

        is Screen.Sessions -> SessionListScreen(
            repo = repo,
            project = current.project,
            onOpenSession = { screen = Screen.Chat(it.id, current.project) },
            onOpenNewSession = { screen = Screen.Chat(it, current.project) },
            onBack = { screen = Screen.Projects },
            modifier = Modifier.fillMaxSize(),
        )

        is Screen.Chat ->
            // `key` forces a fresh composition per session rather than reusing
            // one that still holds the previous session's scroll and state.
            key(current.sessionId) {
                ChatScreen(
                    store = repo.storeFor(current.sessionId),
                    models = models,
                    onFollow = repo::startFollowing,
                    onUnfollow = repo::stopFollowing,
                    onBack = { screen = Screen.Sessions(current.project) },
                    onOpenGit = { screen = Screen.Git(current.project.cwd, current) },
                    onNewSession = {
                        // Same directory as the current session; back from the
                        // new one lands on the same project list.
                        repo.createSession(current.project.cwd) { newId ->
                            screen = Screen.Chat(newId, current.project)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

        is Screen.Git -> GitScreen(
            repo = repo,
            cwd = current.cwd,
            onBack = { screen = current.backTo },
            modifier = Modifier.fillMaxSize(),
        )

        Screen.Settings -> ConnectScreen(
            initial = connection,
            onSave = {
                scope.launch { settings.save(it) }
                screen = Screen.Projects
            },
            onCancel = { screen = Screen.Projects },
            modifier = Modifier.fillMaxSize(),
        )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
