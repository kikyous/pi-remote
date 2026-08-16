@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.piremote.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.piremote.data.Connection
import com.piremote.data.SettingsStore
import com.piremote.data.normalizeUrl
import com.piremote.net.PromptImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets) { pingIntervalMillis = 20_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 30_000
    }
    expectSuccess = false
}

/* ---------------- strings ---------------- */

/** Mirrors values/strings.xml (en). TODO(i18n): resolve per device locale. */
private object IosStrings : PlatformStrings {
    override val session: String = "Session"
    override fun sessionFinished(title: String) = "$title finished"

    override val errAbort: String = "Could not abort the run"
    override val errSetTitle: String = "Could not set the title"
    override val errGenerateTitle: String = "Could not generate a title"
    override val errCompact: String = "Could not compact context"
    override val errLoadProjects: String = "Could not load projects"
    override val errLoadSessions: String = "Could not load sessions"
    override val errCreateSession: String = "Could not create session"
    override val errCreateWorkspace: String = "Could not create workspace"
    override val errDelete: String = "Could not delete"
    override val errUnreachable: String = "Server unreachable"

    override val connCameraRequired: String = "Camera permission is required to scan"
    override val connQrInvalid: String = "This QR code is not a connection payload"
    override fun connProtocolMismatch(version: String, protocol: Int, wire: Int) =
        "Bridge $version speaks protocol $protocol, this app speaks $wire. Upgrade whichever is older."
    override fun connConnected(version: String) = "Connected · server $version"
    override fun connFailed(reason: String) = "Connection failed: $reason"
}

actual fun createPlatformServices(): PlatformServices = object : PlatformServices {
    override val strings: PlatformStrings = IosStrings

    // iOS has no foreground service: the app cannot keep the socket alive in
    // the background. Runs pause in the background; ON_START reconnect +
    // resync catches up on return.
    override fun startForeground(runningCount: Int) = Unit
    override fun stopForeground() = Unit
    // TODO(v2): local notification when a run finishes while backgrounded.
    override fun notifyFinished(sessionId: String, title: String, preview: String) = Unit
}

/* ---------------- mutual exclusion ---------------- */

private val globalLock = platform.Foundation.NSRecursiveLock()

actual fun <T> lock(lock: Any, block: () -> T): T {
    globalLock.lock()
    try {
        return block()
    } finally {
        globalLock.unlock()
    }
}

/* ---------------- settings (DataStore) ---------------- */

actual fun createSettingsStore(): SettingsStore {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // TODO(phase-4): verify createWithPath signature against datastore 1.2.1
    // (okio Path vs String) once Xcode is available.
    val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
        val docs = NSFileManager.defaultManager
            .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)
            ?.path
            ?: error("no document directory")
        "$docs/pi_remote_settings.preferences_pb".toPath()
    }
    val urlKey = stringPreferencesKey("base_url")
    val tokenKey = stringPreferencesKey("token")
    return object : SettingsStore {
        override val connection: Flow<Connection> = dataStore.data.map { prefs ->
            Connection(prefs[urlKey].orEmpty(), prefs[tokenKey].orEmpty())
        }
        override suspend fun save(connection: Connection) {
            dataStore.edit { prefs ->
                prefs[urlKey] = normalizeUrl(connection.baseUrl)
                prefs[tokenKey] = connection.token.trim()
            }
        }
    }
}

/* ---------------- network watching ---------------- */

actual fun watchNetworkChanges(onNetworkUp: () -> Unit) {
    // v1: no-op — the socket already reconnects when the app returns to the
    // foreground (ON_START in App.kt).
    // TODO(v2): NWPathMonitor via platform interop.
}

/* ---------------- image decode / picker ---------------- */

actual fun decodeImageScaled(bytes: ByteArray, maxEdge: Int): ImageBitmap? = runCatching {
    val image = Image.makeFromEncoded(bytes) ?: return null
    val longest = maxOf(image.width, image.height)
    if (longest <= maxEdge) return image.toComposeImageBitmap()

    // skiko has no Image.scale; draw through a raster Pixmap instead (the
    // Android actual uses BitmapFactory.inSampleSize, iOS has no such API).
    val scale = maxEdge.toFloat() / longest
    val w = maxOf(1, (image.width * scale).toInt())
    val h = maxOf(1, (image.height * scale).toInt())
    val dst = Bitmap().apply {
        allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
    }
    val pixmap = dst.peekPixels() ?: return null
    image.scalePixels(pixmap, SamplingMode.LINEAR, false)
    Image.makeFromBitmap(dst).toComposeImageBitmap()
}.getOrNull()

@Composable
actual fun imeAnimationTargetBottom(density: Density): Int =
    WindowInsets.ime.getBottom(density)

// TODO(v2): read the platform modifier state; Android reads the native key event.
actual fun isShiftPressed(event: KeyEvent): Boolean = false

@Composable
actual fun rememberImagePicker(onPicked: (List<PromptImage>) -> Unit): () -> Unit {
    // TODO(phase-4): PHPickerViewController interop — reads + re-encodes via
    // decodeImageScaled, same as the Android actual. v1: image attachments
    // unavailable on iOS.
    return { }
}

/* ---------------- QR scanner ---------------- */

@Composable
actual fun QrScanButton(onRequestScan: () -> Unit, modifier: Modifier) {
    // v1: no camera scanner on iOS — manual entry only (ConnectScreen).
    // TODO(v2): AVFoundation AVCaptureMetadataOutput wrapper.
}

@Composable
actual fun QrScannerHost(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    // Unreachable in v1: QrScanButton renders nothing on iOS, so the caller
    // never switches to the host.
}

/* ---------------- dynamic color ---------------- */

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null
