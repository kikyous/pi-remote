import SwiftUI
import UIKit
import PiRemote

/// Hosts the Compose Multiplatform UI (MainViewController from the Kotlin
/// framework). The keyboard inset is handled inside Compose, so the shell
/// only bridges the view controller into SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}
