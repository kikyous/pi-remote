package com.piremote.net

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

enum class SocketStatus { Disconnected, Connecting, Connected }

/**
 * One WebSocket for the whole app, multiplexing every followed session.
 *
 * Messages carry their `sessionId`, so routing never depends on which screen is
 * open — a background session keeps receiving its events, and a slow event for
 * the session you just left can never be applied to the one you are viewing.
 *
 * On reconnect each subscription resumes from the last `seq` it saw, so a phone
 * that dropped Wi-Fi mid-run catches up rather than losing the middle of a turn.
 *
 * The reconnect / epoch machinery below is transport-agnostic; only the session
 * object differs per platform (OkHttp on Android, Darwin on iOS).
 */
class EventSocket(
    private val client: PiRemoteClient,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(SocketStatus.Disconnected)
    val status: StateFlow<SocketStatus> = _status.asStateFlow()

    private val _pushes = MutableSharedFlow<Push>(extraBufferCapacity = 256)
    val pushes: SharedFlow<Push> = _pushes.asSharedFlow()

    /** sessionId → last seq delivered, the resume point after a reconnect. */
    private val cursors = HashMap<String, Long>()
    private val wanted = HashSet<String>()

    @Volatile
    private var socket: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null
    private var attempt = 0
    private var closedByUs = false

    /**
     * Bumped whenever a socket is torn down for replacement (reconnect or
     * disconnect). Every socket is created carrying the epoch current at the
     * time, and a session whose epoch no longer matches drops itself: its late
     * frames / close can neither clobber a newer connection's state nor apply
     * stale frames twice.
     */
    @Volatile
    private var epoch = 0

    /**
     * Drop the resume cursor and re-subscribe, which answers with a fresh snapshot.
     *
     * The refresh button, and the recovery path when the loaded page turns out to be
     * inconsistent. There is nothing to fetch over HTTP: `hello` carries the newest
     * page, the settings and the status in one frame.
     */
    fun resync(sessionId: String) {
        synchronized(this) {
            cursors.remove(sessionId)
            val live = socket
            if (live != null && _status.value == SocketStatus.Connected) sendSubscribe(live, sessionId)
            else reconnectNow()
        }
    }

    fun follow(sessionId: String) {
        synchronized(this) {
            if (!wanted.add(sessionId)) return
            val live = socket
            if (live != null && _status.value == SocketStatus.Connected) {
                sendSubscribe(live, sessionId)
            } else {
                connect()
            }
        }
    }

    fun unfollow(sessionId: String) {
        synchronized(this) {
            if (!wanted.remove(sessionId)) return
            socket?.let { live ->
                live.outgoing.trySend(
                    Frame.Text(client.json.encodeToString(WsUnsubscribe.serializer(), WsUnsubscribe(sessionId = sessionId))),
                )
            }
            if (wanted.isEmpty()) disconnect()
        }
    }

    /** Called when the app returns to the foreground or the network changes. */
    fun reconnectNow() {
        if (wanted.isEmpty()) return
        synchronized(this) {
            // A connection is already coming up; forcing a new one here would
            // open a duplicate socket that races the first.
            if (_status.value == SocketStatus.Connecting) return
            epoch += 1
            attempt = 0
            closedByUs = false
            val old = socket
            socket = null
            _status.value = SocketStatus.Disconnected
            // Cancel the old socket, then connect immediately. Its in-flight
            // frames still arrive after cancel(), but they carry the old epoch
            // and the consumer drops them — so the fresh connection can resume
            // from the same cursor without applying the same deltas twice
            // (doubled streaming output) and without waiting for the close
            // callback to land first.
            old?.cancel()
            connectLocked()
        }
    }

    fun disconnect() {
        synchronized(this) {
            epoch += 1
            closedByUs = true
            connectJob?.cancel()
            connectJob = null
            socket?.cancel()
            socket = null
            _status.value = SocketStatus.Disconnected
        }
    }

    private fun connect() {
        synchronized(this) {
            connectLocked()
        }
    }

    /**
     * The check and the state flip must be atomic with the epoch read: the
     * network watcher can fire on another thread while a connect is already
     * underway, and without the lock both would read Disconnected, both pass,
     * and two sockets end up live — every push arrives twice. The lock makes
     * the second caller see Connecting (or Connected) and bail.
     *
     * Called with `this` already held.
     */
    private fun connectLocked() {
        if (connectJob?.isActive == true || _status.value != SocketStatus.Disconnected) return
        if (client.baseUrl.isBlank() || client.token.isBlank()) return
        closedByUs = false
        _status.value = SocketStatus.Connecting
        val myEpoch = epoch
        connectJob = scope.launch {
            try {
                val session = client.httpClient().webSocketSession { url(client.wsUrl()) }
                synchronized(this@EventSocket) {
                    if (myEpoch != epoch) {
                        // A socket superseded before it finished opening.
                        session.cancel()
                        return@synchronized
                    }
                    socket = session
                    attempt = 0
                    _status.value = SocketStatus.Connected
                    // Re-subscribe everything, each from where it left off.
                    for (sessionId in wanted) sendSubscribe(session, sessionId)
                }
                // Consume frames until the socket closes.
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    onFrame(frame.readText())
                }
                // Normal close (remote or local).
                synchronized(this@EventSocket) {
                    if (myEpoch != epoch) return@synchronized
                    socket = null
                    _status.value = SocketStatus.Disconnected
                    scheduleReconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connect failure or mid-stream error.
                synchronized(this@EventSocket) {
                    if (myEpoch != epoch) return@synchronized
                    socket = null
                    _status.value = SocketStatus.Disconnected
                    scheduleReconnect()
                }
            }
        }
    }

    private fun sendSubscribe(session: DefaultClientWebSocketSession, sessionId: String) {
        val command = WsSubscribe(sessionId = sessionId, sinceSeq = cursors[sessionId])
        session.outgoing.trySend(Frame.Text(client.json.encodeToString(WsSubscribe.serializer(), command)))
    }

    /** One decoded push. Epoch-guarded: a cancelled socket's frames are dropped. */
    private fun onFrame(text: String) {
        val push = runCatching {
            client.json.decodeFromString(Push.serializer(), text)
        }.onFailure { println("E/PiRemoteWS: decode failed: ${text.take(200)} ${it.message}") }
            .getOrNull() ?: return

        push.sessionId?.let { id ->
            push.cursor?.let { seq ->
                synchronized(this) { cursors[id] = seq }
            }
        }
        _pushes.tryEmit(push)
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (closedByUs || wanted.isEmpty()) return
            val backoff = min(MAX_BACKOFF_MS, (BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong())
            attempt++
            // The delayed job is deliberately untracked, so disconnect() can't
            // cancel it: re-check under the lock after the wait. A bumped epoch
            // (disconnect/reconnectNow) or a cleared `wanted` makes a stale
            // schedule self-invalidate instead of opening an unsubscribed socket.
            val myEpoch = epoch
            scope.launch {
                delay(backoff)
                synchronized(this@EventSocket) {
                    if (myEpoch != epoch || closedByUs || wanted.isEmpty()) return@synchronized
                    connectLocked()
                }
            }
        }
    }

    private companion object {
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
