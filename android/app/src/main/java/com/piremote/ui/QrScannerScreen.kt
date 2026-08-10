package com.piremote.ui

import com.piremote.R

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner: CameraX [PreviewView] + ML Kit [Barcode] analysis,
 * rendered entirely in Compose so orientation follows the device and the UI
 * matches the app's Material 3 dark theme.
 *
 * Fires [onScanned] once with the raw QR payload, then stops analysing. The
 * caller dismisses this screen. [onDismiss] is the back/cancel path.
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
    val analyzerExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var torchController: ((Boolean) -> Unit)? by remember { mutableStateOf(null) }

    // First scan wins; later frames (same QR re-detected, or a new QR) are ignored.
    val onDetected: (String) -> Unit = remember {
        { value ->
            if (done.compareAndSet(false, true)) mainHandler.post { onScanned(value) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null

        providerFuture.addListener(
            {
                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build()
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analyzerExecutor, QrAnalyzer(onDetected)) }
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    torchAvailable = camera.cameraInfo.hasFlashUnit()
                    torchController = { enabled -> camera.cameraControl.enableTorch(enabled) }
                    previewViewRef.value?.surfaceProvider?.let { preview.setSurfaceProvider(it) }
                } catch (err: Exception) {
                    Log.e("QrScanner", "camera bind failed", err)
                    error = context.getString(
                        R.string.qr_camera_failed,
                        err.message ?: err.javaClass.simpleName,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            provider?.unbindAll()
            analysis?.clearAnalyzer()
            analyzerExecutor.shutdown()
        }
    }

    // A camera failure is terminal for this screen: surface it and go back.
    LaunchedEffect(error) {
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    // TextureView so the scrim/UI drawn on top layers correctly.
                    view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    view.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    previewViewRef.value = view
                }
            },
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
            IconButton(
                enabled = torchAvailable,
                onClick = {
                    torchOn = !torchOn
                    torchController?.invoke(torchOn)
                },
            ) {
                Icon(
                    if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
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

/**
 * ML Kit analyzer: each frame is fed to the barcode scanner; the first QR code
 * found reports its raw value. Every ImageProxy is closed in onComplete so
 * frames never leak, with KEEP_ONLY_LATEST keeping the pipeline current.
 */
private class QrAnalyzer(private val onDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (!value.isNullOrBlank()) onDetected(value)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
