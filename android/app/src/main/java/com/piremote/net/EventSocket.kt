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

    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var attempt = 0
    private var closedByUs = false

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
        attempt = 0
        socket?.cancel()
        socket = null
        connect()
    }

    fun disconnect() {
        closedByUs = true
        connectJob?.cancel()
        connectJob = null
        socket?.close(1000, "client closing")
        socket = null
        _status.value = SocketStatus.Disconnected
    }

    private fun connect() {
        if (connectJob?.isActive == true || _status.value == SocketStatus.Connecting) return
        if (client.baseUrl.isBlank() || client.token.isBlank()) return

        closedByUs = false
        _status.value = SocketStatus.Connecting

        connectJob = scope.launch {
            val request = Request.Builder().url(client.wsUrl()).build()
            client.okHttp().newWebSocket(request, Listener())
        }
    }

    private fun sendSubscribe(socket: WebSocket, sessionId: String) {
        val command = WsSubscribe(sessionId = sessionId, sinceSeq = cursors[sessionId])
        socket.send(client.json.encodeToString(WsSubscribe.serializer(), command))
    }

    private fun scheduleReconnect() {
        if (closedByUs || wanted.isEmpty()) return
        val backoff = min(MAX_BACKOFF_MS, (BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong())
        attempt++
        scope.launch {
            delay(backoff)
            if (!closedByUs && wanted.isNotEmpty()) connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            attempt = 0
            _status.value = SocketStatus.Connected
            // Re-subscribe everything, each from where it left off.
            for (sessionId in wanted) sendSubscribe(webSocket, sessionId)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
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
            socket = null
            _status.value = SocketStatus.Disconnected
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _status.value = SocketStatus.Disconnected
            scheduleReconnect()
        }
    }

    private companion object {
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
