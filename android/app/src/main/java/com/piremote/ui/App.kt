package com.piremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    // System back pops the navigation stack instead of exiting the app:
    // Chat → Sessions → Projects; only the root finishes the activity. This
    // mirrors what the top-bar arrows do, so both paths agree.
    BackHandler {
        when (val current = screen) {
            Screen.Projects -> Unit // root — fall through to the system default
            Screen.Settings -> screen = Screen.Projects
            is Screen.Sessions -> screen = Screen.Projects
            is Screen.Chat -> screen = Screen.Sessions(current.project)
            is Screen.Git -> screen = current.backTo
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

    if (!connection.isConfigured) {
        ConnectScreen(
            initial = connection,
            onSave = { scope.launch { settings.save(it) } },
            onCancel = null,
            modifier = modifier,
        )
        return
    }

    when (val current = screen) {
        Screen.Projects -> ProjectListScreen(
            repo = repo,
            onOpenProject = {
                repo.selectProject(it.cwd)
                screen = Screen.Sessions(it)
            },
            onOpenSettings = { screen = Screen.Settings },
            modifier = modifier,
        )

        is Screen.Sessions -> SessionListScreen(
            repo = repo,
            project = current.project,
            onOpenSession = { screen = Screen.Chat(it.id, current.project) },
            onOpenNewSession = { screen = Screen.Chat(it, current.project) },
            onBack = { screen = Screen.Projects },
            modifier = modifier,
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
                    modifier = modifier,
                )
            }

        is Screen.Git -> GitScreen(
            repo = repo,
            cwd = current.cwd,
            onBack = { screen = current.backTo },
            modifier = modifier,
        )

        Screen.Settings -> ConnectScreen(
            initial = connection,
            onSave = {
                scope.launch { settings.save(it) }
                screen = Screen.Projects
            },
            onCancel = { screen = Screen.Projects },
            modifier = modifier,
        )
    }
}
