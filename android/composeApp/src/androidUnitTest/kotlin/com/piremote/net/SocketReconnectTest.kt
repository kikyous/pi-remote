package com.piremote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Regression guard for the background-return reconnect.
 *
 * EventSocket's connectJob owns the receive loop and stays active for the
 * socket's lifetime; reconnectNow() must cancel it before opening a fresh
 * socket, otherwise the isActive guard deadlocks and the app comes back from
 * the background to a permanently loading screen. This test drives the real
 * socket against a minimal raw-TCP WebSocket server and asserts a reconnect
 * actually lands a second connection.
 */
class SocketReconnectTest {

    private class FakeWsServer {
        private val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val connections = AtomicInteger(0)
        val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        private val threads = mutableListOf<Thread>()

        fun start() {
            threads += thread(name = "ws-server") {
                while (!stop.get()) {
                    val s = try { server.accept() } catch (e: Exception) { break }
                    if (s != null) handle(s)
                }
            }
        }

        private fun handle(socket: Socket) {
            connections.incrementAndGet()
            threads += thread(name = "ws-conn") {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    var key: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("Sec-WebSocket-Key:")) key = line.substringAfter(':').trim()
                        if (line.isEmpty()) break
                    }
                    if (key == null) {
                        runCatching { socket.close() }
                        return@thread
                    }
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + guid).toByteArray()),
                    )
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 101 Switching Protocols\r\n".toByteArray())
                        write("Upgrade: websocket\r\n".toByteArray())
                        write("Connection: Upgrade\r\n".toByteArray())
                        write("Sec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                        flush()
                    }
                    // Push a hello snapshot so the client has something to fold.
                    val payload =
                        """{"t":"hello","sessionId":"s1","seq":0,"items":[],"hasMore":false,"oldest":null,"detail":{"id":"s1","cwd":"/tmp"},"status":{}}""".toByteArray()
                    val out = socket.getOutputStream()
                    out.write(byteArrayOf(0x81.toByte()))
                    out.write(payload.size)
                    out.write(payload)
                    out.flush()
                    // Keep the connection open until the test tears down.
                    while (socket.getInputStream().read() != -1 && !stop.get()) Thread.sleep(50)
                } catch (e: Exception) {
                    // connection closed — fine
                } finally {
                    runCatching { socket.close() }
                }
            }
        }

        fun close() {
            stop.set(true)
            runCatching { server.close() }
            threads.forEach { runCatching { it.join(1000) } }
        }
    }

    private suspend fun await(what: String, cond: () -> Boolean) {
        withTimeout(8_000) {
            while (!cond()) delay(50)
        }
        assertTrue("timed out waiting for $what", cond())
    }

    @Test
    fun `reconnectNow opens a fresh socket after the app returns to foreground`() = runBlockingTest {
        val server = FakeWsServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = PiRemoteClient("http://127.0.0.1:${server.port}", "token")
        val socket = EventSocket(client, scope)
        val hellos = java.util.concurrent.atomic.AtomicInteger(0)
        val collector = scope.launch {
            socket.pushes.collect { push -> if (push is Push.Hello) hellos.incrementAndGet() }
        }
        try {
            socket.follow("s1")
            await("first connection") { socket.status.value == SocketStatus.Connected }
            await("first hello") { hellos.get() >= 1 }
            val firstConnections = server.connections.get()
            assertTrue("server saw the first connection", firstConnections >= 1)

            // What App.kt does on ON_START after coming back from the background.
            socket.reconnectNow()

            await("second connection") { server.connections.get() >= firstConnections + 1 }
            await("second hello") { hellos.get() >= 2 }
            assertTrue("socket is connected again", socket.status.value == SocketStatus.Connected)
        } finally {
            collector.cancel()
            scope.cancel()
            server.close()
        }
    }

    private fun runBlockingTest(block: suspend CoroutineScope.() -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
