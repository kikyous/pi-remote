package com.piremote.data

/**
 * Parses the QR code the server prints at startup. The payload is a URI in
 * the scheme the server generates (see server/src/qr.ts):
 *
 *   piremote://connect?url=http%3A%2F%2F192.168.1.10%3A30150&token=<token>
 *
 * Scanning it fills the connection form — no need to type the 32-char token.
 *
 * Kept free of android.net.Uri so the JVM unit tests run without the Android
 * runtime; the format is ours, so a small hand-rolled parser is all we need.
 */
fun parseConnectPayload(raw: String): Connection? {
    val text = raw.trim()
    val prefix = "piremote://connect?"
    if (!text.startsWith(prefix)) return null
    val query = text.substring(prefix.length)
    if (query.isEmpty()) return null

    val params = query.split('&').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to percentDecode(part.substring(eq + 1))
    }.toMap()

    val url = params["url"] ?: return null
    val token = params["token"] ?: return null
    if (url.isBlank() || token.isBlank()) return null
    return Connection(normalizeUrl(url), token.trim())
}

/** Decode %XX escapes without touching '+' (our payload never form-encodes). */
private fun percentDecode(value: String): String = buildString {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                append(code.toChar())
                i += 3
                continue
            }
        }
        append(c)
        i++
    }
}
