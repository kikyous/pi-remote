package com.piremote.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.piremote.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner backed by zxing-android-embedded's [DecoratedBarcodeView]
 * (zxing core decoding + its own Camera2 preview, no ML Kit). The view follows
 * device orientation, so the app stays portrait in hand and free to rotate on a
 * tablet — the CaptureActivity lock-to-sensorLandscape bug stays avoided.
 *
 * Fires [onScanned] once with the raw QR payload, then stops. The caller
 * dismisses this screen. [onDismiss] is the back/cancel path.
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val done = remember { AtomicBoolean(false) }
    var flashlightOn by remember { mutableStateOf(false) }
    val barcodeView = remember { DecoratedBarcodeView(context) }

    DisposableEffect(lifecycleOwner) {
        // QR codes only; first result wins (later frames are ignored).
        barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        barcodeView.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text
                if (!text.isNullOrBlank() && done.compareAndSet(false, true)) {
                    mainHandler.post { onScanned(text) }
                }
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
        })

        // Hide the library's built-in viewfinder/status text; the Compose layer
        // below draws our own scrim + frame so the look matches the app theme.
        barcodeView.viewFinder.visibility = View.GONE
        barcodeView.statusView?.visibility = View.GONE

        // Resume/pause with the screen lifecycle. A camera failure (e.g. the
        // permission was revoked mid-flight) is terminal: toast and bail out.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> try {
                    barcodeView.resume()
                } catch (t: Throwable) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.qr_camera_failed, t.message ?: t.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                    onDismiss()
                }
                Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView.pause()
        }
    }

    // Torch is best-effort: some devices lack a flash unit, setTorch* just no-ops.
    LaunchedEffect(flashlightOn) {
        try {
            if (flashlightOn) barcodeView.setTorchOn() else barcodeView.setTorchOff()
        } catch (_: Throwable) {
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { barcodeView },
            modifier = Modifier.fillMaxSize(),
        )

        // Dim everything except the viewfinder window. One EvenOdd path (full
        // screen minus the rounded cut-out) so the scrim hugs the rounded
        // corners exactly — four straight panels would leave the corners dimmed.
        val scrim = Color.Black.copy(alpha = 0.5f)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val r = VIEWFINDER.toPx() / 2f
                    val path = Path().apply {
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(
                            RoundRect(
                                Rect(
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = r,
                                ),
                                cornerRadius = CornerRadius(CORNER_RADIUS.toPx()),
                            ),
                        )
                        fillType = PathFillType.EvenOdd
                    }
                    drawPath(path, scrim)
                },
        )

        // The white frame marking the scan window (matches the cut-out above).
        Box(
            modifier = Modifier
                .size(VIEWFINDER)
                .align(Alignment.Center)
                .border(2.dp, Color.White, RoundedCornerShape(CORNER_RADIUS)),
        )

        // The app is edge-to-edge, so the top hint would sit under the status
        // bar / camera punch-hole. Insets: status bar ∪ display cutout, top only
        // (the hint is a centered banner; left/right cutouts don't apply here).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top),
                )
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.qr_hint), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.qr_detail),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Text(stringResource(R.string.qr_autoconnect), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { flashlightOn = !flashlightOn }) {
                Icon(
                    if (flashlightOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    contentDescription = stringResource(R.string.qr_flashlight),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Size of the scan window and its corner radius — shared by the scrim cut-out and the frame. */
private val VIEWFINDER = 280.dp
private val CORNER_RADIUS = 20.dp
