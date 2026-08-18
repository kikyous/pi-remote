package com.piremote.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class QrConnectTest {

    @Test
    fun parsesServerPayload() {
        val connection = parseConnectPayload(
            "piremote://connect?url=http%3A%2F%2F192.168.31.117%3A30150&token=85Ou5U44v-lN0BckrE6QJ5OuMgBAekZQ",
        )
        assertEquals("http://192.168.31.117:30150", connection?.baseUrl)
        assertEquals("85Ou5U44v-lN0BckrE6QJ5OuMgBAekZQ", connection?.token)
    }

    @Test
    fun acceptsBareHostPortInPayload() {
        // The server always sends a full URL, but the parser tolerates the
        // compact form a hand-written QR might carry.
        val connection = parseConnectPayload("piremote://connect?url=192.168.1.10%3A30150&token=tok123")
        assertEquals("http://192.168.1.10:30150", connection?.baseUrl)
    }

    @Test
    fun rejectsForeignPayloads() {
        assertNull(parseConnectPayload("https://example.com/foo"))
        assertNull(parseConnectPayload("piremote://connect?token=only"))
        assertNull(parseConnectPayload("piremote://connect?url=http%3A%2F%2F1.2.3.4%3A5"))
        assertNull(parseConnectPayload("WIFI:T:ADB;S:pad;P:123456;;"))
        assertNull(parseConnectPayload(""))
        assertNull(parseConnectPayload("not a uri at all"))
    }
}
