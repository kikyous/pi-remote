package com.piremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import piremote.composeapp.generated.resources.*
import com.piremote.data.Connection
import com.piremote.data.normalizeUrl
import com.piremote.data.parseConnectPayload
import com.piremote.net.PiRemoteClient
import com.piremote.net.WIRE_PROTOCOL
import com.piremote.platform.PlatformStrings
import com.piremote.platform.QrScanButton
import com.piremote.platform.QrScannerHost
import kotlinx.coroutines.launch

/**
 * Where the phone is pointed at the PC.
 *
 * The primary path is scanning the QR code the server prints at startup — the
 * address and token fill in automatically and the connection is verified. The
 * manual fields stay as a fallback for when the screen is hard to scan.
 *
 * Services that have connected before ([recent]) are offered as one-tap
 * reconnects just below the scanner button — tapping one reuses its stored
 * URL + token and verifies the link, so moving between a handful of machines
 * never needs the fields or another scan.
 *
 * The address is normalised on save (bare host, host:port, or full URL all
 * work), and the connection is verified before it is stored, so a typo is
 * caught here rather than surfacing as an empty project list later.
 *
 * Scanning is platform-specific ([QrScanButton]): Android opens the camera
 * scanner (zxing), iOS uses its own AVFoundation session — both implement the
 * same expect declarations.
 */
@Composable
fun ConnectScreen(
    initial: Connection,
    recent: List<Connection>,
    onSave: (Connection) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onCancel: (() -> Unit)?,
    strings: PlatformStrings,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Verify a candidate connection and store it on success. */
    fun attemptConnect(rawUrl: String, rawToken: String) {
        testing = true
        message = null
        scope.launch {
            val normalized = normalizeUrl(rawUrl)
            val probe = PiRemoteClient(normalized, rawToken.trim())
            val result = runCatching { probe.ping() }
            testing = false
            result.onSuccess { ping ->
                // The wire protocol is checked here rather than discovered later as
                // an unparseable frame or an empty screen. Both sides ship together,
                // so a mismatch means one of them was not upgraded.
                if (ping.protocol != WIRE_PROTOCOL) {
                    ok = false
                    message = strings.connProtocolMismatch(ping.version, ping.protocol, WIRE_PROTOCOL)
                    return@onSuccess
                }
                ok = true
                message = strings.connConnected(ping.version)
                onSave(Connection(normalized, rawToken.trim()))
            }.onFailure { err ->
                ok = false
                message = strings.connFailed(err.message ?: strings.errUnreachable)
            }
        }
    }

    /** Handle a raw QR payload: fill the form and verify the connection. */
    fun applyScannedPayload(contents: String) {
        val parsed = parseConnectPayload(contents)
        if (parsed == null) {
            ok = false
            message = strings.connQrInvalid
            return
        }
        url = parsed.baseUrl
        token = parsed.token
        attemptConnect(parsed.baseUrl, parsed.token)
    }

    // The scanner replaces this whole screen while it is open — the camera
    // must get the full display, not a slice of the form's column.
    if (showScanner) {
        QrScannerHost(
            onScanned = {
                showScanner = false
                applyScannedPayload(it)
            },
            onDismiss = { showScanner = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.conn_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(Res.string.conn_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            QrScanButton(onRequestScan = { showScanner = true }, modifier = Modifier.fillMaxWidth())

            // One-tap reconnects for every service this device has reached.
            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.conn_recent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.fillMaxWidth()) {
                        recent.forEach { entry ->
                            RecentConnectionRow(
                                entry = entry,
                                onClick = { attemptConnect(entry.baseUrl, entry.token) },
                                onRemove = { onRemoveRecent(entry.baseUrl) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; message = null },
                label = { Text(stringResource(Res.string.conn_address)) },
                placeholder = { Text("192.168.1.10:30150") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; message = null },
                label = { Text("Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            message?.let {
                Text(
                    it,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedButton(
                enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                onClick = { attemptConnect(url, token) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.conn_manual))
                }
            }

            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

/** Hide the scheme and show the token tail so a service is recognisable. */
private fun displayUrl(baseUrl: String): String =
    baseUrl.removePrefix("http://").removePrefix("https://")

private fun maskedToken(token: String): String {
    val tail = token.takeLast(4)
    return if (tail.length >= 4) "••••" + tail else "••••"
}

@Composable
private fun RecentConnectionRow(
    entry: Connection,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                displayUrl(entry.baseUrl),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                maskedToken(entry.token),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.conn_remove))
        }
    }
}
