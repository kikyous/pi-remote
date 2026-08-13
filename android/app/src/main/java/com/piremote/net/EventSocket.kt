package com.piremote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
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
 */
class EventSocket(
    private val client: PiRemoteClient,
    private val scope: CoroutineScope,
) {
    /**
     * Reconnect as soon as a usable network appears.
     *
     * Backoff alone would leave the app waiting out a timer after Wi-Fi comes
     * back; the callback fires the moment there is something to connect over.
     * Registered lazily so a client that never follows anything costs nothing.
     */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    fun watchNetwork(context: android.content.Context) {
        if (networkCallback != null) return
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (wanted.isNotEmpty() && _status.value != SocketStatus.Connected) reconnectNow()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }
    private val _status = MutableStateFlow(SocketStatus.Disconnected)
    val status: StateFlow<SocketStatus> = _status.asStateFlow()

    private val _pushes = MutableSharedFlow<Push>(extraBufferCapacity = 256)
    val pushes: SharedFlow<Push> = _pushes.asSharedFlow()

    /** sessionId → last seq delivered, the resume point after a reconnect. */
    private val cursors = ConcurrentHashMap<String, Long>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var attempt = 0
    private var closedByUs = false

    /**
     * Bumped whenever a socket is torn down for replacement (reconnect or
     * disconnect). Every socket is created carrying the epoch current at the
     * time, and a listener whose epoch no longer matches drops the socket: its
     * late onMessage / onFailure / onClosed callbacks can neither clobber a
     * newer connection's state nor apply stale frames twice.
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
        cursors.remove(sessionId)
        val live = socket
        if (live != null && _status.value == SocketStatus.Connected) sendSubscribe(live, sessionId)
        else reconnectNow()
    }

    fun follow(sessionId: String) {
        if (!wanted.add(sessionId)) return
        val live = socket
        if (live != null && _status.value == SocketStatus.Connected) {
            sendSubscribe(live, sessionId)
        } else {
            connect()
        }
    }

    fun unfollow(sessionId: String) {
        if (!wanted.remove(sessionId)) return
        socket?.send(client.json.encodeToString(WsUnsubscribe.serializer(), WsUnsubscribe(sessionId = sessionId)))
        if (wanted.isEmpty()) disconnect()
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
            // and the listener drops them — so the fresh connection can resume
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
            socket?.close(1000, "client closing")
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
     * The check and the state flip must be atomic with the epoch read:
     * watchNetwork's callback can fire on another thread while a connect is
     * already underway, and without the lock both would read Disconnected, both
     * pass, and two sockets end up live — every push arrives twice. The lock
     * makes the second caller see Connecting (or Connected) and bail.
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
            val request = Request.Builder().url(client.wsUrl()).build()
            client.okHttp().newWebSocket(request, Listener(myEpoch))
        }
    }

    private fun sendSubscribe(socket: WebSocket, sessionId: String) {
        val command = WsSubscribe(sessionId = sessionId, sinceSeq = cursors[sessionId])
        socket.send(client.json.encodeToString(WsSubscribe.serializer(), command))
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

    private inner class Listener(private val myEpoch: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@EventSocket) {
                if (myEpoch != epoch) {
                    // A socket superseded before it finished opening.
                    webSocket.close(1000, "superseded")
                    return
                }
                socket = webSocket
                attempt = 0
                _status.value = SocketStatus.Connected
                // Re-subscribe everything, each from where it left off.
                for (sessionId in wanted) sendSubscribe(webSocket, sessionId)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // A cancelled socket's in-flight frames still land; ignore them so a
            // reconnect does not apply the same deltas twice.
            if (myEpoch != epoch) return

            val push = runCatching {
                client.json.decodeFromString(Push.serializer(), text)
            }.onFailure { android.util.Log.e("PiRemoteWS", "decode failed: ${text.take(200)}", it) }
                .getOrNull() ?: return

            push.sessionId?.let { id ->
                push.cursor?.let { seq -> cursors[id] = seq }
            }
            _pushes.tryEmit(push)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w("PiRemoteWS", "failure: ${t.message}")
            synchronized(this@EventSocket) {
                if (myEpoch != epoch) return
                socket = null
                _status.value = SocketStatus.Disconnected
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@EventSocket) {
                if (myEpoch != epoch) return
                socket = null
                _status.value = SocketStatus.Disconnected
                scheduleReconnect()
            }
        }
    }

    private companion object {
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
