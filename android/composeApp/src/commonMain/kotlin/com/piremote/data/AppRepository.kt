package com.piremote.data

import com.piremote.net.EventSocket
import com.piremote.net.ModelDto
import com.piremote.net.PiRemoteClient
import com.piremote.net.ProjectDto
import com.piremote.net.SessionSummaryDto
import com.piremote.net.WorkspaceDto
import com.piremote.net.SocketStatus
import com.piremote.platform.PlatformServices
import com.piremote.platform.lock
import com.piremote.platform.watchNetworkChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A run that just completed, for the completion notification. */
data class FinishedRun(val sessionId: String, val title: String, val preview: String)

data class BrowseState(
    val projects: List<ProjectDto> = emptyList(),
    val sessions: List<SessionSummaryDto> = emptyList(),
    val selectedCwd: String? = null,
    val loadingProjects: Boolean = false,
    val loadingSessions: Boolean = false,
    val error: String? = null,
)

/**
 * Owns the client, the browse lists, and the per-session stores.
 *
 * Stores are cached so that leaving a session and coming back does not refetch,
 * and so a background session keeps receiving its events. The cache is bounded
 * because each one can hold up to [SessionStore.MAX_ITEMS] parsed entries.
 */
class AppRepository(
    val client: PiRemoteClient,
    private val scope: CoroutineScope,
    private val platform: PlatformServices,
) {
    companion object {
        @kotlin.concurrent.Volatile
        private var instance: AppRepository? = null

        /**
         * Process-wide singleton.
         *
         * A fresh instance per Activity recreation leaks one WebSocket per
         * recreation: the old EventSocket never disconnects, every new one
         * subscribes the same sessions, and the server pushes each update to
         * every connection — the client ends up applying the same deltas
         * twice (streaming output shows every line doubled). The client's
         * mutable fields already allow changing the connection without
         * rebuilding, so one instance for the process is all there should be.
         */
        fun get(platform: PlatformServices): AppRepository {
            instance?.let { return it }
            return lock(this) {
                instance ?: AppRepository(PiRemoteClient("", ""), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), platform)
                    .also { instance = it }
            }
        }

        const val MAX_CACHED_SESSIONS = 5
    }

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _models = MutableStateFlow<List<ModelDto>>(emptyList())
    val models: StateFlow<List<ModelDto>> = _models.asStateFlow()

    /** Insertion-ordered so the oldest entry is the first to evict. */
    private val stores = LinkedHashMap<String, SessionStore>()

    val socket = EventSocket(client, scope)
    val socketStatus: StateFlow<SocketStatus> get() = socket.status

    /** Sessions with a run in flight. Drives the foreground service. */
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** Emitted when a run finishes, for the completion notification. */
    private val _finished = MutableSharedFlow<FinishedRun>(extraBufferCapacity = 16)
    val finished: SharedFlow<FinishedRun> = _finished.asSharedFlow()

    init {
        // Route every event to the store that owns it. Messages carry their
        // sessionId, so a background session stays correct while another is on
        // screen, and a late event can never be applied to the wrong session.
        scope.launch {
            socket.pushes.collect { push ->
                val id = push.sessionId ?: return@collect
                trackRunState(id, push)
                existingStore(id)?.apply(push)
            }
        }

        // Reconnect the socket as soon as the network comes back. The
        // callback itself only acts when a session is being followed.
        watchNetworkChanges { socket.reconnectNow() }
    }

    /**
     * Maintain the running set from the push stream itself.
     *
     * Derived here rather than from the stores so it stays correct for sessions whose
     * screen was never opened, and so the service does not depend on the UI having a
     * store alive.
     *
     * One authoritative field decides it. The old version inferred running-ness from
     * `agent_start`/`agent_settled`, which an extension command never emits — the
     * documented trap that left the app spinning after a slash command.
     */
    private fun trackRunState(sessionId: String, push: com.piremote.net.Push) {
        val running = when (push) {
            is com.piremote.net.Push.Hello -> push.status.running
            is com.piremote.net.Push.Status -> push.status.running
            else -> return
        }

        if (running) {
            updateRunning { it + sessionId }
            return
        }
        if (sessionId !in _running.value) return

        updateRunning { it - sessionId }
        val store = existingStore(sessionId)
        val strings = platform.strings
        val finished = FinishedRun(
            sessionId = sessionId,
            title = store?.state?.value?.detail?.name
                ?: store?.state?.value?.detail?.firstMessage?.take(40)
                ?: strings.session,
            preview = store?.lastAssistantText().orEmpty(),
        )
        _finished.tryEmit(finished)
        // Posted here, not from Compose, for the same reason the service is: the app
        // is usually backgrounded by now.
        platform.notifyFinished(
            finished.sessionId,
            strings.sessionFinished(finished.title),
            finished.preview,
        )
    }

    /**
     * Update the running set and keep the foreground service in step with it.
     *
     * The service exists only while something is running, so a backgrounded app
     * is not kept alive for nothing. On iOS this is a no-op: there is no
     * foreground service, the socket simply pauses in the background and the
     * ON_START reconnect + resync catches up on return.
     */
    private fun updateRunning(transform: (Set<String>) -> Set<String>) {
        val next = transform(_running.value)
        if (next == _running.value) return
        _running.value = next
        if (next.isEmpty()) {
            runCatching { platform.stopForeground() }
        } else {
            runCatching { platform.startForeground(next.size) }
                .onFailure { println("W/PiRemote: foreground start refused: ${it.message}") }
        }
    }

    /** Look up a store without creating one — used by event routing. */
    private fun existingStore(sessionId: String): SessionStore? =
        lock(stores) { stores[sessionId] }

    /** Start following a session's live events; call [stopFollowing] when done. */
    fun startFollowing(sessionId: String) = socket.follow(sessionId)

    fun stopFollowing(sessionId: String) = socket.unfollow(sessionId)

    fun storeFor(sessionId: String): SessionStore = lock(stores) {
        stores.remove(sessionId)?.let { existing ->
            stores[sessionId] = existing
            return@lock existing
        }
        val created = SessionStore(sessionId, client, scope, platform.strings, socket::resync)
        stores[sessionId] = created
        while (stores.size > MAX_CACHED_SESSIONS) {
            val oldest = stores.keys.first()
            stores.remove(oldest)
        }
        return@lock created
    }

    fun refreshProjects() {
        _browse.update { it.copy(loadingProjects = true, error = null) }
        scope.launch {
            try {
                val projects = client.listProjects()
                _browse.update { it.copy(projects = projects, loadingProjects = false) }
            } catch (e: Exception) {
                _browse.update { it.copy(loadingProjects = false, error = e.message ?: platform.strings.errLoadProjects) }
            }
        }
    }

    fun selectProject(cwd: String) {
        _browse.update { it.copy(selectedCwd = cwd, sessions = emptyList()) }
        refreshSessions(cwd)
    }

    fun refreshSessions(cwd: String = _browse.value.selectedCwd.orEmpty()) {
        if (cwd.isBlank()) return
        _browse.update { it.copy(loadingSessions = true, error = null) }
        scope.launch {
            try {
                val sessions = client.listSessions(cwd)
                // Ignore a response for a project the user has already left.
                if (_browse.value.selectedCwd != cwd) return@launch
                _browse.update { it.copy(sessions = sessions, loadingSessions = false) }
            } catch (e: Exception) {
                _browse.update { it.copy(loadingSessions = false, error = e.message ?: platform.strings.errLoadSessions) }
            }
        }
    }

    fun loadModels() {
        if (_models.value.isNotEmpty()) return
        scope.launch {
            runCatching { client.models().models }.onSuccess { _models.value = it }
        }
    }

    /**
     * Create a session in [cwd] and hand back its id.
     *
     * The server returns before the file exists on disk (pi defers writing
     * until the first entry), so the new session is usable immediately but will
     * not appear in a listing until something is sent to it.
     */
    fun createSession(cwd: String, onCreated: (String) -> Unit) {
        scope.launch {
            try {
                val created = client.createSession(cwd)
                refreshSessions(cwd)
                onCreated(created.id)
            } catch (e: Exception) {
                _browse.update { it.copy(error = e.message ?: platform.strings.errCreateSession) }
            }
        }
    }

    /**
     * Create the daily default workspace (`~/pi-cwd-YYYYMMDD` on the server)
     * and hand back its session id, path, and whether the dir was fresh.
     */
    fun createWorkspace(onCreated: (WorkspaceDto) -> Unit) {
        scope.launch {
            try {
                val ws = client.createWorkspace()
                refreshProjects()
                onCreated(ws)
            } catch (e: Exception) {
                _browse.update { it.copy(error = e.message ?: platform.strings.errCreateWorkspace) }
            }
        }
    }

    /** Delete one session; [onResult] receives null on failure (e.g. has forks). */
    fun deleteSession(id: String, cwd: String, onResult: (String?) -> Unit) {
        scope.launch {
            try {
                client.deleteSession(id)
                refreshSessions(cwd)
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: platform.strings.errDelete)
            }
        }
    }

    /** Delete every session in a workspace; [onResult] receives null on success. */
    fun deleteWorkspace(cwd: String, onResult: (String?) -> Unit) {
        scope.launch {
            try {
                client.deleteWorkspace(cwd)
                refreshProjects()
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: platform.strings.errDelete)
            }
        }
    }

    fun clearError() = _browse.update { it.copy(error = null) }
}
