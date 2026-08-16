package com.piremote.net

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Guard the URL building: a regression here breaks every request. */
class UrlBuildingTest {

    private fun builtUrl(baseUrl: String, path: String): String {
        val builder = HttpRequestBuilder().apply {
            this.method = HttpMethod.Get
            url("${baseUrl.trimEnd('/')}/api/v1/$path")
        }
        val parsed = builder.url
        return parsed.buildString()
    }

    @Test
    fun `port from base url is preserved`() {
        val u = builtUrl("http://localhost:30150", "ping")
        assertEquals("http://localhost:30150/api/v1/ping", u)
    }

    @Test
    fun `default scheme is http`() {
        val u = builtUrl("http://192.168.1.10:30150", "sessions?cwd=x")
        assertNotNull(u)
        assertEquals("http://192.168.1.10:30150/api/v1/sessions?cwd=x", u)
    }

    @Test
    fun `normalizeUrl adds the default port`() {
        assertEquals("http://localhost:30150", com.piremote.data.normalizeUrl("localhost"))
        assertEquals("http://localhost:30150", com.piremote.data.normalizeUrl("http://localhost"))
        assertEquals("http://localhost:30150", com.piremote.data.normalizeUrl("localhost/"))
        assertEquals("http://192.168.1.10:30150", com.piremote.data.normalizeUrl("192.168.1.10"))
        // An explicit port is respected as typed.
        assertEquals("http://localhost:8080", com.piremote.data.normalizeUrl("localhost:8080"))
    }
}

/** Build the URL exactly as PiRemoteClient does (url { takeFrom(...) }). */
class UrlBuildingProbe(private val baseUrl: String) {
    fun build(path: String): String {
        val builder = HttpRequestBuilder().apply {
            this.method = HttpMethod.Get
            url { takeFrom("${baseUrl.trimEnd('/')}/api/v1/$path") }
        }
        return builder.url.buildString()
    }
}

class UrlBuildingProbeTest {
    @Test
    fun `request url keeps host port and path`() {
        val probe = UrlBuildingProbe("http://localhost:30150")
        val u = probe.build("ping")
        println("PROBE_URL=$u")
        assertEquals("http://localhost:30150/api/v1/ping", u)
    }

    @Test
    fun `query strings survive`() {
        val probe = UrlBuildingProbe("http://192.168.1.10:30150")
        val u = probe.build("sessions?cwd=a%2Fb")
        assertEquals("http://192.168.1.10:30150/api/v1/sessions?cwd=a%2Fb", u)
    }
}
