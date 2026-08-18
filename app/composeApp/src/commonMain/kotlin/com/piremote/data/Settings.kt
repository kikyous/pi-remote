package com.piremote.data

import kotlinx.coroutines.flow.Flow

data class Connection(val baseUrl: String, val token: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        val EMPTY = Connection("", "")
    }
}

/**
 * Persistent connection settings, backed by DataStore on both platforms
 * (Android: the `pi_remote_settings` preferences delegate; iOS: a
 * preferences file under the app's document directory).
 */
interface SettingsStore {
    val connection: Flow<Connection>
    suspend fun save(connection: Connection)
}

/**
 * Accept what people actually type: bare host, host:port, or a full URL with a
 * trailing slash. Defaults to the server's port when none is given.
 */
fun normalizeUrl(raw: String): String {
    var value = raw.trim().removeSuffix("/")
    if (value.isEmpty()) return ""
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "http://$value"
    }
    val afterScheme = value.substringAfter("://")
    if (!afterScheme.contains(':')) {
        value = "$value:$DEFAULT_PORT"
    }
    return value
}

const val DEFAULT_PORT = 30150
