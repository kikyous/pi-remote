package com.piremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.piremote.data.Connection
import com.piremote.data.normalizeUrl
import com.piremote.net.PiRemoteClient
import kotlinx.coroutines.launch

/**
 * Where the phone is pointed at the PC.
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
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(modifier = modifier) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("连接到 PC", style = MaterialTheme.typography.headlineSmall)
            Text(
                "在 PC 上运行 pi-remote-server，它会打印地址和 token。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; message = null },
                label = { Text("地址") },
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

            Button(
                enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                onClick = {
                    testing = true
                    message = null
                    scope.launch {
                        val normalized = normalizeUrl(url)
                        val probe = PiRemoteClient(normalized, token.trim())
                        val result = runCatching { probe.ping() }
                        testing = false
                        result.onSuccess { ping ->
                            ok = true
                            message = "已连接 · 服务端 ${ping.version}"
                            onSave(Connection(normalized, token.trim()))
                        }.onFailure { err ->
                            ok = false
                            message = "连接失败：${err.message ?: "无法访问"}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("连接")
                }
            }

            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }
        }
    }
}
