@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.piremote.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDeviceMeta
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.darwin.NSObject
import platform.QuartzCore.CALayer
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.roundToInt

/**
 * iOS QR scanning (v2): AVFoundation camera session hosted in a Compose
 * [UIKitView]. The button requests the camera permission lazily; the scanner
 * screen shows the live preview with a viewfinder hint and a back button.
 *
 * Android's counterpart (zxing) lives in QrScannerScreen.kt — the two are
 * deliberately independent implementations of the same expect declarations.
 */

@Composable
actual fun QrScanButton(onRequestScan: () -> Unit, modifier: Modifier) {
    Button(onClick = onRequestScan, modifier = modifier) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
        Text("Scan to connect", modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
actual fun QrScannerHost(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val scanner = remember { IosQrScanner(onScanned) }
    var permissionError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> scanner.start()
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (granted) scanner.start() else permissionError = true
                    }
                }
            }
            // Denied / Restricted.
            else -> permissionError = true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (permissionError) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera permission is required to scan. Enable it in Settings → Privacy & Security → Camera.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            UIKitView(
                factory = { scanner.makePreviewView() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Hint at the top, back button at the bottom — same layout language as
        // the Android scanner.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Point at the QR code printed by the PC console at startup", color = Color.White)
            Text(
                "The server prints the URL, token and QR code at startup",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            IconButton(onClick = {
                scanner.stop()
                onDismiss()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}

/**
 * Owns the AVCaptureSession: camera input → metadata output (QR only) →
 * [onScanned] on the main thread (first match wins). The preview layer is
 * attached to a dedicated view so [UIKitView] owns the lifecycle.
 */
private class IosQrScanner(private val onScanned: (String) -> Unit) {
    private val session = AVCaptureSession()
    private val output = AVCaptureMetadataOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var running = false

    private val delegate = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputMetadataObjects: List<*>,
            fromConnection: AVCaptureConnection,
        ) {
            for (obj in didOutputMetadataObjects) {
                val code = obj as? AVMetadataMachineReadableCodeObject ?: continue
                val value = code.stringValue
                if (!value.isNullOrEmpty()) {
                    // Delegate fires on a background queue; hand off to main.
                    dispatch_async(dispatch_get_main_queue()) {
                        stop()
                        onScanned(value)
                    }
                    break
                }
            }
        }
    }

    /** Configures the session once; no-op if the camera is unavailable. */
    fun start() {
        if (running) return
        if (session.inputs.isEmpty()) {
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(delegate, platform.darwin.dispatch_get_global_queue(0L, 0u))
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
        if (!session.isRunning()) {
            session.startRunning()
            running = true
        }
    }

    fun stop() {
        if (session.isRunning()) session.stopRunning()
        running = false
    }

    /** The view the preview draws into; sized by UIKitView. */
    fun makePreviewView(): UIView {
        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.frame = CGRectMake(0.0, 0.0, 0.0, 0.0)
        previewLayer = layer
        val view = object : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
            override fun layoutSubviews() {
                super.layoutSubviews()
                layer.frame = bounds
            }
        }
        view.layer.addSublayer(layer)
        return view
    }
}
