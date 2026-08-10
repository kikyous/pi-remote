package com.piremote.ui

import com.piremote.R

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.piremote.data.Connection
import com.piremote.data.normalizeUrl
import com.piremote.data.parseConnectPayload
import com.piremote.net.PiRemoteClient
import kotlinx.coroutines.launch

/**
 * Where the phone is pointed at the PC.
 *
 * The primary path is scanning the QR code the server prints at startup — the
 * address and token fill in automatically and the connection is verified. The
 * manual fields stay as a fallback for when the screen is hard to scan.
 *
 * The address is normalised on save (bare host, host:port, or full URL all
 * work), and the connection is verified before it is stored, so a typo is
 * caught here rather than surfacing as an empty project list later.
 */
@Composable
fun ConnectScreen(
    initial: Connection,
    onSave: (Connection) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showScanner = true else {
            ok = false
            message = context.getString(R.string.conn_camera_required)
        }
    }

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
                ok = true
                message = context.getString(R.string.conn_connected, ping.version)
                onSave(Connection(normalized, rawToken.trim()))
            }.onFailure { err ->
                ok = false
                message = context.getString(
                    R.string.conn_failed,
                    err.message ?: context.getString(R.string.err_unreachable),
                )
            }
        }
    }

    /** Open the scanner, asking for camera permission on first use. */
    fun openScanner() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) showScanner = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /** Handle a raw QR payload: fill the form and verify the connection. */
    fun applyScannedPayload(contents: String) {
        val parsed = parseConnectPayload(contents)
        if (parsed == null) {
            ok = false
            message = context.getString(R.string.conn_qr_invalid)
            return
        }
        url = parsed.baseUrl
        token = parsed.token
        attemptConnect(parsed.baseUrl, parsed.token)
    }

    if (showScanner) {
        QrScannerScreen(
            onScanned = { contents ->
                showScanner = false
                applyScannedPayload(contents)
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.conn_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.conn_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = !testing,
                onClick = ::openScanner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Text(stringResource(R.string.conn_scan), modifier = Modifier.padding(vertical = 4.dp))
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; message = null },
                label = { Text(stringResource(R.string.conn_address)) },
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
                    Text(stringResource(R.string.conn_manual))
                }
            }

            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
