@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.piremote.platform

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.piremote.data.Connection
import com.piremote.data.SettingsStore
import com.piremote.data.decodeConnections
import com.piremote.data.encodeConnections
import com.piremote.data.normalizeUrl
import com.piremote.data.updateRecentConnections
import com.piremote.data.withoutConnection
import com.piremote.net.PromptImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSValue
import platform.UIKit.CGRectValue
import platform.UIKit.UIKeyboardFrameEndUserInfoKey
import platform.UIKit.UIKeyboardWillChangeFrameNotification
import platform.UIKit.UIScreen

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
    override fun notifyFinished(sessionId: String, cwd: String?, title: String, preview: String) = Unit
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
    val recentKey = stringPreferencesKey("recent_connections")
    return object : SettingsStore {
        override val connection: Flow<Connection> = dataStore.data.map { prefs ->
            Connection(prefs[urlKey].orEmpty(), prefs[tokenKey].orEmpty())
        }
        override val recentConnections: Flow<List<Connection>> = dataStore.data.map { prefs ->
            decodeConnections(prefs[recentKey].orEmpty())
        }
        override suspend fun save(connection: Connection) {
            dataStore.edit { prefs ->
                val normalized = normalizeUrl(connection.baseUrl)
                val saved = Connection(normalized, connection.token.trim())
                prefs[urlKey] = normalized
                prefs[tokenKey] = saved.token
                val recent = decodeConnections(prefs[recentKey].orEmpty())
                prefs[recentKey] = encodeConnections(updateRecentConnections(recent, saved))
            }
        }
        override suspend fun removeRecentConnection(baseUrl: String) {
            dataStore.edit { prefs ->
                val recent = decodeConnections(prefs[recentKey].orEmpty())
                prefs[recentKey] = encodeConnections(withoutConnection(recent, baseUrl))
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

/* ---------------- keyboard insets ---------------- */

/**
 * CMP 1.11.1 does implement WindowInsets.ime on iOS, but once the scene is
 * lifted above the keyboard its value can read 0 (the keyboard no longer
 * overlaps the lifted view), so the composer's inset is cross-checked
 * against UIKit's own keyboard frame. UIKeyboardWillChangeFrame fires at the
 * START of every show/hide/type-change animation carrying the END frame —
 * the same "jump straight to the target" semantics as Android's
 * imeAnimationTarget. Height is kept in points; converted to px with the
 * caller's density.
 */
private object ImeHeight {
    private val _points = MutableStateFlow(0f)
    val points: StateFlow<Float> = _points
    private var installed = false

    fun ensureInstalled() {
        if (installed) return
        installed = true
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIKeyboardWillChangeFrameNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            val endFrame = (notification?.userInfo?.get(UIKeyboardFrameEndUserInfoKey) as? NSValue)
                ?.CGRectValue()
            _points.value = if (endFrame == null) {
                0f
            } else {
                val screenHeight = UIScreen.mainScreen.bounds.useContents { size.height }
                endFrame.useContents {
                    val top = origin.y
                    if (top >= screenHeight) 0f else (screenHeight - top).toFloat()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun imeAnimationTargetBottom(density: Density): Int =
    maxOf(
        ImeHeight.points.collectAsState().value * density.density,
        WindowInsets.ime.getBottom(density).toFloat(),
    ).roundToInt()

actual fun composerBottomPadding(imeTargetPx: Int, navBarPx: Int, panelVisible: Boolean): Int =
    // CMP lifts the whole scene above the keyboard on iOS, so once the
    // keyboard is up the composer needs no extra bottom inset — the keyboard
    // already covers the home indicator. Pads by it anyway (like Android
    // does) and the input box floats a nav-bar-height above the keyboard.
    if (panelVisible || imeTargetPx > 0) 0 else navBarPx

// TODO(v2): read the platform modifier state; Android reads the native key event.
actual fun isShiftPressed(event: KeyEvent): Boolean = false

@Composable
actual fun rememberImagePicker(onPicked: (List<PromptImage>) -> Unit): () -> Unit {
    // TODO(phase-4): PHPickerViewController interop — reads + re-encodes via
    // decodeImageScaled, same as the Android actual. v1: image attachments
    // unavailable on iOS.
    return { }
}

/* ---------------- dynamic color ---------------- */

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null
