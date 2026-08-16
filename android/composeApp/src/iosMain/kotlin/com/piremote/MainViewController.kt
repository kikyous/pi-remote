@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.piremote

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.piremote.ui.PiRemoteApp
import com.piremote.ui.PiRemoteTheme
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIViewController

private val crashHookInstalled: Boolean = run {
    setUnhandledExceptionHook { throwable ->
        // Persist the exception (message + stack) before the runtime aborts, so
        // an on-device crash can be diagnosed by reading Documents/crash.log
        // (uncaught Kotlin exceptions never reach the .ips crash report).
        runCatching {
            val docs = NSFileManager.defaultManager
                .URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)
                ?.path
            if (docs != null) {
                val text = throwable.stackTraceToString()
                (text as NSString).writeToFile("$docs/crash.log", true, NSUTF8StringEncoding, null)
            }
        }
    }
    true
}

/**
 * Entry point consumed by the Swift shell (iosApp). Kotlin/Native compiles
 * this into the PiRemote framework; MainViewControllerKt.MainViewController()
 * is the Swift-side handle to the Compose UI root.
 *
 * The theme + Surface wrapper mirrors the Android entry point: without it the
 * root Box has no Material3 scheme and no background, so full-bleed screens
 * show the window background instead of the app's own.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    crashHookInstalled
    PiRemoteTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PiRemoteApp()
        }
    }
}
