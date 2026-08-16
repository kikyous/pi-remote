package com.piremote.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.piremote.data.SettingsStore
import com.piremote.net.PromptImage
import io.ktor.client.HttpClient

/**
 * Strings needed OUTSIDE composition (repository/store error fallbacks, the
 * connect screen's probe functions). Composable string access goes through
 * composeResources (`Res.string.*`) instead.
 *
 * Android resolves these from the real string resources; iOS provides the
 * same literals. Formatting follows the `%1$s`-style placeholders from the
 * Android resource files.
 */
interface PlatformStrings {
    val session: String
    fun sessionFinished(title: String): String

    val errAbort: String
    val errSetTitle: String
    val errGenerateTitle: String
    val errCompact: String
    val errLoadProjects: String
    val errLoadSessions: String
    val errCreateSession: String
    val errCreateWorkspace: String
    val errDelete: String
    val errUnreachable: String

    val connCameraRequired: String
    val connQrInvalid: String
    fun connProtocolMismatch(version: String, protocol: Int, wire: Int): String
    fun connConnected(version: String): String
    fun connFailed(reason: String): String
}

/**
 * Platform hooks for the repository and the UI:
 * notifications / foreground keep-alive (Android foreground service; no-op on
 * iOS, where the app cannot run in the background by design) and the
 * non-composable strings above.
 */
interface PlatformServices {
    val strings: PlatformStrings
    fun startForeground(runningCount: Int)
    fun stopForeground()
    fun notifyFinished(sessionId: String, title: String, preview: String)
}

expect fun createPlatformServices(): PlatformServices

/** Ktor engine per platform (OkHttp on Android, Darwin on iOS). */
expect fun createPlatformHttpClient(): HttpClient

/** Persistent connection settings. Android: DataStore; iOS: DataStore file. */
expect fun createSettingsStore(): SettingsStore

/**
 * Reconnect the WebSocket as soon as a usable network appears.
 * Android: ConnectivityManager default-network callback. iOS: NWPathMonitor
 * (v1: no-op — the socket already reconnects on app foreground).
 */
expect fun watchNetworkChanges(onNetworkUp: () -> Unit)

/**
 * Decode an image, downscaled so its longest edge is at most [maxEdge].
 * Android: BitmapFactory + inSampleSize. iOS: Skia `Image.makeFromEncoded`.
 * Returns null when the bytes are not a decodable image.
 */
expect fun decodeImageScaled(bytes: ByteArray, maxEdge: Int): ImageBitmap?

/**
 * Launch the platform photo picker (multi-select) and hand back the picked
 * images as [PromptImage] (already decoded, scaled and base64-encoded for the
 * wire). Returns the launch function.
 */
@Composable
expect fun rememberImagePicker(onPicked: (List<PromptImage>) -> Unit): () -> Unit

/**
 * Entry point to the QR scanner. Android: a button that asks for camera
 * permission and opens the scanner screen. iOS: nothing (v1 — manual entry
 * only); the button row simply does not appear.
 */
@Composable
expect fun QrScanButton(onScanned: (String) -> Unit, modifier: Modifier = Modifier)

/**
 * Android 12+ dynamic color; anything else returns null and the caller falls
 * back to the static schemes.
 */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?
