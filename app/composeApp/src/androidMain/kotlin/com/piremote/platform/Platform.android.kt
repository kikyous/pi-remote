package com.piremote.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.piremote.R
import com.piremote.data.Connection
import com.piremote.data.SettingsStore
import com.piremote.data.normalizeUrl
import com.piremote.net.PromptImage
import com.piremote.service.AgentForegroundService
import com.piremote.ui.MAX_ATTACHMENTS
import com.piremote.ui.MAX_IMAGE_EDGE
import com.piremote.ui.QrScannerScreen
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Application-context holder, attached from MainActivity.onCreate before any
 * Compose code runs. Kept out of the expect/actual surface: only the actuals
 * (strings, DataStore, notifications) touch it.
 */
object AndroidApp {
    lateinit var context: Context
        private set

    fun attach(context: Context) {
        this.context = context.applicationContext
    }
}

/* ---------------- HTTP engine ---------------- */

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    // Ping keeps the WebSocket alive; the HTTP side shares this client and
    // must not be reaped mid-run.
    install(WebSockets) { pingIntervalMillis = 20_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 30_000
    }
    expectSuccess = false
}

/* ---------------- services / strings ---------------- */

private object AndroidStrings : PlatformStrings {
    private val ctx: Context get() = AndroidApp.context

    override val session: String get() = ctx.getString(R.string.session)
    override fun sessionFinished(title: String) = ctx.getString(R.string.session_finished, title)

    override val errAbort: String get() = ctx.getString(R.string.err_abort)
    override val errSetTitle: String get() = ctx.getString(R.string.err_set_title)
    override val errGenerateTitle: String get() = ctx.getString(R.string.err_generate_title)
    override val errCompact: String get() = ctx.getString(R.string.err_compact)
    override val errLoadProjects: String get() = ctx.getString(R.string.err_load_projects)
    override val errLoadSessions: String get() = ctx.getString(R.string.err_load_sessions)
    override val errCreateSession: String get() = ctx.getString(R.string.err_create_session)
    override val errCreateWorkspace: String get() = ctx.getString(R.string.err_create_workspace)
    override val errDelete: String get() = ctx.getString(R.string.err_delete)
    override val errUnreachable: String get() = ctx.getString(R.string.err_unreachable)

    override val connCameraRequired: String get() = ctx.getString(R.string.conn_camera_required)
    override val connQrInvalid: String get() = ctx.getString(R.string.conn_qr_invalid)
    override fun connProtocolMismatch(version: String, protocol: Int, wire: Int) =
        ctx.getString(R.string.conn_protocol_mismatch, version, protocol, wire)
    override fun connConnected(version: String) = ctx.getString(R.string.conn_connected, version)
    override fun connFailed(reason: String) = ctx.getString(R.string.conn_failed, reason)
}

private object AndroidServices : PlatformServices {
    override val strings: PlatformStrings = AndroidStrings

    override fun startForeground(runningCount: Int) =
        AgentForegroundService.start(AndroidApp.context, runningCount)

    override fun stopForeground() = AgentForegroundService.stop(AndroidApp.context)

    override fun notifyFinished(sessionId: String, title: String, preview: String) =
        AgentForegroundService.notifyFinished(AndroidApp.context, sessionId, title, preview)
}

actual fun createPlatformServices(): PlatformServices = AndroidServices

/* ---------------- mutual exclusion ---------------- */

actual fun <T> lock(lock: Any, block: () -> T): T = synchronized(lock, block)

/* ---------------- settings (DataStore) ---------------- */

private val Context.dataStore by preferencesDataStore(name = "pi_remote_settings")

actual fun createSettingsStore(): SettingsStore = object : SettingsStore {
    private val urlKey = stringPreferencesKey("base_url")
    private val tokenKey = stringPreferencesKey("token")

    override val connection: Flow<Connection> = AndroidApp.context.dataStore.data.map { prefs ->
        Connection(prefs[urlKey].orEmpty(), prefs[tokenKey].orEmpty())
    }

    override suspend fun save(connection: Connection) {
        AndroidApp.context.dataStore.edit { prefs ->
            prefs[urlKey] = normalizeUrl(connection.baseUrl)
            prefs[tokenKey] = connection.token.trim()
        }
    }
}

/* ---------------- network watching ---------------- */

actual fun watchNetworkChanges(onNetworkUp: () -> Unit) {
    val manager = AndroidApp.context.getSystemService(ConnectivityManager::class.java) ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkUp()
    }
    runCatching { manager.registerDefaultNetworkCallback(callback) }
}

/* ---------------- image decode / picker ---------------- */

actual fun decodeImageScaled(bytes: ByteArray, maxEdge: Int): ImageBitmap? = runCatching {
    decodeScaled(bytes, maxEdge)?.asImageBitmap()
}.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun imeAnimationTargetBottom(density: Density): Int =
    WindowInsets.imeAnimationTarget.getBottom(density)

actual fun composerBottomPadding(imeTargetPx: Int, navBarPx: Int, panelVisible: Boolean): Int =
    if (panelVisible) navBarPx else imeTargetPx + navBarPx

actual fun isShiftPressed(event: KeyEvent): Boolean = event.nativeKeyEvent.isShiftPressed

/** inSampleSize decode so a huge photo never materialises at full resolution. */
private fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    return scaleDown(bitmap, maxEdge)
}

private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt(),
        (bitmap.height * scale).toInt(),
        true,
    )
}

/**
 * System photo picker, multi-select: no storage permission needed, returns
 * readable Uris. Images are scaled to [MAX_IMAGE_EDGE] and base64-encoded so
 * the combined payload stays within the server body limit.
 */
@Composable
actual fun rememberImagePicker(onPicked: (List<PromptImage>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val images = uris.mapNotNull { uri ->
                withContext(Dispatchers.IO) { loadPromptImage(context, uri) }
            }
            onPicked(images)
        }
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

private suspend fun loadPromptImage(context: Context, uri: Uri): PromptImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val bitmap = decodeScaled(bytes, MAX_IMAGE_EDGE) ?: return@withContext null
            val out = ByteArrayOutputStream()
            val png = mime.contains("png")
            bitmap.compress(
                if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                com.piremote.ui.IMAGE_QUALITY,
                out,
            )
            PromptImage(
                data = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
                mimeType = if (png) "image/png" else "image/jpeg",
            )
        }.getOrNull()
    }

/* ---------------- QR scanner ---------------- */

@Composable
actual fun QrScanButton(onRequestScan: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onRequestScan()
        } else {
            Toast.makeText(context, R.string.conn_camera_required, Toast.LENGTH_LONG).show()
        }
    }

    Button(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) onRequestScan() else permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        modifier = modifier,
    ) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
        Text(stringResource(R.string.conn_scan), modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Full-screen camera scanner; replaces the calling screen while visible. */
@Composable
actual fun QrScannerHost(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    QrScannerScreen(
        onScanned = onScanned,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/* ---------------- dynamic color ---------------- */

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else {
        null
    }
}
