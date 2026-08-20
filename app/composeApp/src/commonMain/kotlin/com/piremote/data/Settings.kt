package com.piremote.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
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

    /**
     * Services this device has successfully connected to, most recent first.
     * Persisted separately from the active [connection] so the connect screen
     * can offer them as one-tap reconnects.
     */
    val recentConnections: Flow<List<Connection>>

    /** Store the active connection and prepend it to the recent list. */
    suspend fun save(connection: Connection)

    /** Forget a service (and its token) from the recent list. */
    suspend fun removeRecentConnection(baseUrl: String)
}

/** Cap for [updateRecentConnections]; keeps the persisted payload tiny. */
const val MAX_RECENT_CONNECTIONS = 10

private val recentJson = Json { ignoreUnknownKeys = true }
private val connectionListSerializer = ListSerializer(Connection.serializer())

/**
 * Prepend [saved] to [current], de-duplicating by baseUrl (the newest token
 * wins), and trim to [MAX_RECENT_CONNECTIONS].
 */
fun updateRecentConnections(current: List<Connection>, saved: Connection): List<Connection> {
    val filtered = current.filterNot { it.baseUrl.equals(saved.baseUrl, ignoreCase = true) }
    return (listOf(saved) + filtered).take(MAX_RECENT_CONNECTIONS)
}

/** Serialize the recent list for DataStore. */
fun encodeConnections(list: List<Connection>): String = runCatching {
    recentJson.encodeToString(connectionListSerializer, list)
}.getOrDefault("[]")

/** Deserialize the recent list; returns empty on garbage/corrupt data. */
fun decodeConnections(raw: String): List<Connection> = runCatching {
    if (raw.isBlank()) emptyList() else recentJson.decodeFromString(connectionListSerializer, raw)
}.getOrDefault(emptyList())

/**
 * Remove a service from [connections] by baseUrl. Used both for the recent
 * list and shared by the platform implementations.
 */
fun withoutConnection(connections: List<Connection>, baseUrl: String): List<Connection> =
    connections.filterNot { it.baseUrl.equals(baseUrl, ignoreCase = true) }

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
