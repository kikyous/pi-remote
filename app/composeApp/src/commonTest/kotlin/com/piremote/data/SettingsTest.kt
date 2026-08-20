package com.piremote.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTest {

    private fun conn(url: String, token: String) = Connection(normalizeUrl(url), token)

    @Test
    fun updatePrependsNewestFirst() {
        val a = conn("192.168.1.10", "t1")
        val b = conn("192.168.1.11", "t2")
        val list = updateRecentConnections(
            updateRecentConnections(emptyList(), a),
            b,
        )
        assertEquals(listOf("http://192.168.1.11:30150", "http://192.168.1.10:30150"), list.map { it.baseUrl })
    }

    @Test
    fun updateDedupesByBaseUrlKeepingNewToken() {
        val old = conn("192.168.1.10", "t1")
        val newer = conn("http://192.168.1.10:30150", "t2")
        val list = updateRecentConnections(listOf(old), newer)
        assertEquals(1, list.size)
        assertEquals("t2", list.single().token)
    }

    @Test
    fun updateCapsAtMax() {
        val list = (1..MAX_RECENT_CONNECTIONS + 5).fold(emptyList<Connection>()) { acc, i ->
            updateRecentConnections(acc, conn("192.168.1.$i", "t$i"))
        }
        assertEquals(MAX_RECENT_CONNECTIONS, list.size)
        // Newest (highest index) is kept first.
        assertEquals("192.168.1.${MAX_RECENT_CONNECTIONS + 5}", list.first().baseUrl.removePrefix("http://").substringBefore(':'))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val list = listOf(conn("host-a", "tokA"), conn("host-b:" + DEFAULT_PORT, "tokB"))
        assertEquals(list, decodeConnections(encodeConnections(list)))
    }

    @Test
    fun decodeGarbageReturnsEmpty() {
        assertTrue(decodeConnections("").isEmpty())
        assertTrue(decodeConnections("not json {").isEmpty())
        assertTrue(decodeConnections("[]").isEmpty())
    }

    @Test
    fun withoutConnectionRemovesByUrlCaseInsensitive() {
        val list = listOf(conn("192.168.1.10", "t1"), conn("192.168.1.11", "t2"))
        val left = withoutConnection(list, "HTTP://192.168.1.10:30150")
        assertEquals(1, left.size)
        assertEquals("t2", left.single().token)
    }
}
