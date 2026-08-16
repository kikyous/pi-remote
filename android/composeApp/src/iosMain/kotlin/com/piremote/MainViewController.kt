package com.piremote

import androidx.compose.ui.window.ComposeUIViewController
import com.piremote.ui.PiRemoteApp
import platform.UIKit.UIViewController

/**
 * Entry point consumed by the Swift shell (iosApp). Kotlin/Native compiles
 * this into the PiRemote framework; MainViewControllerKt.MainViewController()
 * is the Swift-side handle to the Compose UI root.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    PiRemoteApp()
}
