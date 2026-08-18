package com.piremote.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
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
    fun notifyFinished(sessionId: String, cwd: String?, title: String, preview: String)
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
 * The keyboard animation's TARGET height (androidx's imeAnimationTarget).
 * Android: exact — the moment a show/hide starts, this is the final inset,
 * which lets the composer jump to its resting position without tracking the
 * animation. iOS: the live keyboard inset (CMP 1.11 has no separate target
 * API there; WindowInsets.ime is driven by the same source as the scene's
 * own keyboard offset, so it stays consistent with the lifted view).
 */
@Composable
expect fun imeAnimationTargetBottom(density: Density): Int

/**
 * Bottom inset for the composer column, given the current keyboard height
 * ([imeTargetPx]), the navigation-bar (home indicator) inset ([navBarPx])
 * and whether the + panel is occupying the bottom.
 *
 * Android (edge-to-edge, no auto offset): keyboard up → nav bar + keyboard,
 * so the composer steps over both; panel → nav bar only. iOS (CMP lifts the
 * scene above the keyboard itself): keyboard up → 0 (the keyboard already
 * covers the home indicator), panel / idle → nav bar only.
 */
expect fun composerBottomPadding(imeTargetPx: Int, navBarPx: Int, panelVisible: Boolean): Int

/** Shift held during a key event (Shift+Enter inserts a newline). */
expect fun isShiftPressed(event: KeyEvent): Boolean

/**
 * Launch the platform photo picker (multi-select) and hand back the picked
 * images as [PromptImage] (already decoded, scaled and base64-encoded for the
 * wire). Returns the launch function.
 */
@Composable
expect fun rememberImagePicker(onPicked: (List<PromptImage>) -> Unit): () -> Unit

/**
 * The "scan a QR code" entry button. Android: asks for camera permission,
 * then invokes [onRequestScan]; iOS: renders nothing (v1 — manual entry only).
 * The caller switches to [QrScannerHost] (full screen) from the callback.
 */
@Composable
expect fun QrScanButton(onRequestScan: () -> Unit, modifier: Modifier = Modifier)

/**
 * Full-screen QR scanner (replaces the calling screen while active).
 * Android: the zxing camera preview; iOS: nothing (v1).
 */
@Composable
expect fun QrScannerHost(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * Platform-neutral mutual exclusion. Android/JVM: the `synchronized` monitor;
 * iOS: a recursive lock. The [lock] argument is just an identity token.
 */
expect fun <T> lock(lock: Any, block: () -> T): T

/**
 * Android 12+ dynamic color; anything else returns null and the caller falls
 * back to the static schemes.
 */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?
