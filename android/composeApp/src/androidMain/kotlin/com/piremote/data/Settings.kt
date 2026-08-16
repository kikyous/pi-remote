package com.piremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pi_remote_settings")

data class Connection(val baseUrl: String, val token: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        val EMPTY = Connection("", "")
    }
}

class SettingsStore(private val context: Context) {
    private val urlKey = stringPreferencesKey("base_url")
    private val tokenKey = stringPreferencesKey("token")

    val connection: Flow<Connection> = context.dataStore.data.map { prefs ->
        Connection(prefs[urlKey].orEmpty(), prefs[tokenKey].orEmpty())
    }

    suspend fun save(connection: Connection) {
        context.dataStore.edit { prefs ->
            prefs[urlKey] = normalizeUrl(connection.baseUrl)
            prefs[tokenKey] = connection.token.trim()
        }
    }
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
