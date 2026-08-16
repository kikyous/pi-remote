# iOS build & device deploy (Pi Remote CMP)

Compose Multiplatform iOS shell. The Kotlin side compiles into a static
`PiRemote.framework`; a thin SwiftUI app (`iosApp/`) hosts it.

## Environment (this machine)

| Component | Version | Why |
|---|---|---|
| macOS | 15.6+ (Sequoia) | Xcode 26.x requires ≥ 15.6; the 2026-era toolchain does not build with Xcode 16 |
| Xcode | 26.3 (Intel build) | Kotlin 2.4.10 targets Xcode 26.4; 26.3 ships an iOS 26 SDK that links `UIViewLayoutRegion` etc. Xcode 16.4 (iOS 18.5 SDK) **fails to link** the Kotlin framework. Install via Xcodes.app (App Store hides Intel Xcode). |
| Kotlin | 2.4.10 (in `gradle/libs.versions.toml` → `settings.gradle.kts`) | Kotlin/Native prebuilt must match; see kotlinlang.org compatibility guide |
| xcodegen | brew-installed | Regenerates `iosApp.xcodeproj` from `iosApp/project.yml` |

Do **not** run `sudo xcode-select -s` (needs a password here). Instead export:

```sh
export DEVELOPER_DIR=/Applications/Xcode-26.3.0.app/Contents/Developer
```

## Layout

- `composeApp/src/iosMain/` — Kotlin iOS code (`MainViewController.kt`, `Platform.ios.kt`)
- `iosApp/` — SwiftUI shell: `iosApp/iOSApp.swift`, `iosApp/ContentView.swift`,
  `project.yml` (xcodegen source of truth), generated `iosApp.xcodeproj`
- iOS quirks handled: ATS `NSAllowsLocalNetworking` (plain-http bridge on LAN),
  `NSLocalNetworkUsageDescription` (iOS 14+ local network prompt),
  `NSCameraUsageDescription` (scanner is v2; permission declared now)
- iOS v1 has no camera scanner — ConnectScreen falls back to manual entry

## Build

```sh
# 1. Kotlin framework (needed once; Xcode's script phase also does this on demand)
cd android
DEVELOPER_DIR=/Applications/Xcode-26.3.0.app/Contents/Developer \
  ./gradlew :composeApp:linkDebugFrameworkIosArm64

# 2. Regenerate the Xcode project after any project.yml change
cd iosApp && xcodegen

# 3. Compile the app (no signing → CI-style check)
cd iosApp
DEVELOPER_DIR=... xcodebuild -project iosApp.xcodeproj -target iosApp \
  -configuration Debug -sdk iphoneos ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build
```

The Xcode project embeds the framework via the
`embedAndSignAppleFrameworkForXcode` Gradle task in a pre-build script phase.

## Signing & device deploy

Requires: Apple ID (free personal team is fine; 7-day cert expiry — re-sign
weekly), iPhone with Developer Mode on (Settings → Privacy & Security →
Developer Mode), USB trust.

1. Xcode → Settings → Accounts → add Apple ID (or `xcodes signin`).
2. Set `DEVELOPMENT_TEAM` in `iosApp/project.yml`, `xcodegen` again.
3. `xcodebuild ... -destination id=<device-udid> -allowProvisioningUpdates build`
   (or open `iosApp.xcodeproj` in Xcode and Run).

## Gotchas

- **Intel Mac**: no `iosX64` target (see `composeApp/build.gradle.kts`), so the
  iOS simulator cannot run here — device-only testing.
- **Kotlin 2.4.10 needs iOS 26 SDK**: linking with Xcode 16.4's iOS 18.5 SDK
  fails with `Undefined symbols: _OBJC_CLASS_$_UIViewLayoutRegion`.
- **Maven mirrors**: `dl.google.com` is unreachable on this network; the Aliyun
  Google mirror is first in `settings.gradle.kts`. Add new iOS artifacts as
  `-iosarm64` coordinates only if the mirror has them.
- **DataStore on iOS**: declare `androidx.datastore:datastore-preferences` in
  `iosMain` too (it ships native artifacts); `createWithPath` wants an okio
  `Path`, so `"...".toPath()`.
- **skiko has no `Image.scale`**: `Platform.ios.kt` scales via
  `Bitmap.allocPixels` + `Image.scalePixels(..., SamplingMode.LINEAR, ...)`.
