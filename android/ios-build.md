# iOS build / install / debug (Pi Remote CMP)

Compose Multiplatform iOS shell. The Kotlin side compiles into a static
`PiRemote.framework`; a thin SwiftUI app (`iosApp/`) hosts it. Everything
below is verified on this machine (2026-08-16).

## Environment (this machine — real, not aspirational)

| Component | Version | Why |
|---|---|---|
| macOS | **26.6.1** (was 15.3.2; upgraded because…) | Xcode 26.x needs macOS ≥ 15.6 (26.0–26.3) or ≥ 26.2 (26.4+). We run 26.6.1. |
| Xcode | **26.6** (`/Applications/Xcode-26.6.0.app`, Intel build) | Kotlin 2.4.10 targets Xcode 26.4; 26.6 ships an **iOS 26.5 SDK** that links `UIViewLayoutRegion` etc. **Xcode 16.4 (iOS 18.5 SDK) fails to link the Kotlin framework.** Install via **Xcodes.app** — the App Store hides Intel Xcode. |
| Kotlin | 2.4.10 (`settings.gradle.kts`) | K/N prebuilt must match; see kotlinlang.org compatibility guide |
| xcodegen | brew (`xcodegen`) | Regenerates `iosApp.xcodeproj` from `iosApp/project.yml` |
| iPhone | iPhone 14 Pro, iOS 26.6, Developer Mode ON, USB-connected | The only test device (Intel Mac has no iOS simulator: no `iosX64` target) |

Always export (do **not** `sudo xcode-select -s` — it needs a password):

```sh
export DEVELOPER_DIR=/Applications/Xcode-26.6.0.app/Contents/Developer
```

## Layout

- `composeApp/src/iosMain/` — Kotlin iOS code:
  - `MainViewController.kt` — Compose UI entry (`PiRemoteTheme { Surface { PiRemoteApp() } }`,
    **must** stay wrapped in `PiRemoteTheme` or Markdown crashes) + unhandled-exception hook
    that writes `Documents/crash.log`
  - `Platform.ios.kt` — platform actuals (ktor Darwin, DataStore, skia image scale)
  - `QrScanner.ios.kt` — AVFoundation QR scanner (button + full-screen host)
- `iosApp/` — SwiftUI shell: `iOSApp.swift`, `ContentView.swift`, `project.yml`
  (xcodegen source of truth), generated `iosApp.xcodeproj`
- Info.plist (generated from `project.yml`) carries the CMP-required keys:
  `CADisableMinimumFrameDurationOnPhone`, `UILaunchScreen`, plus ATS
  `NSAllowsLocalNetworking`, `NSLocalNetworkUsageDescription`,
  `NSCameraUsageDescription`.

## Build (unsigned, CI-style check)

```sh
cd android/iosApp
export DEVELOPER_DIR=/Applications/Xcode-26.6.0.app/Contents/Developer

# after any project.yml change:
xcodegen

# full app build, no signing:
xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug \
  -sdk iphoneos ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build
```

The Xcode project's pre-build script phase runs
`:composeApp:embedAndSignAppleFrameworkForXcode` (Kotlin framework). The script
exports `JAVA_HOME=/Users/chen/tools/jdk-17.0.20+8/Contents/Home` because
Xcode's script environment has no Java.

## Install to the iPhone (fully command line)

GUI signing (`xcodebuild -allowProvisioningUpdates`) does **not** work here:
the Xcode 26 account session lives only in the GUI process, so xcodebuild
reports "No Account for Team". Instead: build unsigned → embed the
Xcode-managed profile → `codesign` manually → `devicectl install`.

```sh
cd android/iosApp
export DEVELOPER_DIR=/Applications/Xcode-26.6.0.app/Contents/Developer
DEV="BB650719-9074-5DFC-BED2-F75D6A0D7757"          # devicectl list devices
APP=build/Debug-iphoneos/PiRemoteCMP.app
PROFILE="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/1c7ff226-dfbe-44ec-b375-626c83ae05ac.mobileprovision"

# 1. unsigned build
xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug \
  -sdk iphoneos ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build

# 2. embed profile + sign (entitlements in /tmp/ent.plist, see below)
cp "$PROFILE" "$APP/embedded.mobileprovision"
codesign --force --sign "Apple Development: kikyous@163.com (45RMTN4VYV)" \
  --entitlements /tmp/ent.plist --timestamp=none "$APP/PiRemoteCMP"

# 3. install + launch
/Applications/Xcode-26.6.0.app/Contents/Developer/usr/bin/devicectl \
  device install app --device "$DEV" "$APP"
devicectl device process launch --device "$DEV" com.piremote.cmp.ios
```

`/tmp/ent.plist`:

```xml
<plist version="1.0"><dict>
  <key>application-identifier</key><string>8QV6UFZ79P.com.piremote.cmp.ios</string>
  <key>com.apple.developer.team-identifier</key><string>8QV6UFZ79P</string>
  <key>get-task-allow</key><true/>
</dict></plist>
```

### Signing facts (how the IDs were found)

- Certificate: `Apple Development: kikyous@163.com (45RMTN4VYV)` — the label's
  team is **misleading**; the cert's real team (OU) is **`8QV6UFZ79P`**
  (`security find-certificate -c "Apple Development" -p | openssl x509 -subject`).
- Provisioning profile: `iOS Team Provisioning Profile: com.piremote.cmp.ios`
  (Xcode-managed, UUID `1c7ff226-dfbe-44ec-b375-626c83ae05ac`, expires 7 days
  after creation — **re-generate weekly** via GUI Signing & Capabilities, then
  re-run the manual flow with the new UUID).
- Free Personal Team = 7-day cert/profile expiry. When the app stops launching,
  re-sign (open Xcode → Signing & Capabilities once, then re-deploy).

## Debugging (crash diagnosis)

- **Uncaught Kotlin exceptions never appear in .ips crash reports.** The app
  installs a `setUnhandledExceptionHook` (MainViewController.kt) that writes
  message + stack to `Documents/crash.log` before aborting.
- Pull it from the device:

```sh
devicectl device copy from --device "$DEV" \
  --domain-type appDataContainer --domain-identifier com.piremote.cmp.ios \
  --source Documents/crash.log --destination /tmp/crash.log
```

- Crash reports auto-sync to `~/Library/Developer/Xcode/DeviceLogs/iPhone-*/`
  (slow/unreliable) or pull all directly:

```sh
devicectl device copy from --device "$DEV" --domain-type systemCrashLogs \
  --source . --destination /tmp/ioscrashes
```

- Live device syslog: `brew install libimobiledevice` → `idevicesyslog`
  (streams device logs; replay the crash while it runs).

## Gotchas (all hit and fixed on this machine)

- **Kotlin 2.4.10 needs an iOS 26 SDK.** Linking with Xcode 16.4's iOS 18.5
  SDK fails: `Undefined symbols: _OBJC_CLASS_$_UIViewLayoutRegion`.
- **Maven mirrors**: `dl.google.com` is unreachable here; the Aliyun Google
  mirror is first in `settings.gradle.kts`. iOS-native artifacts
  (`-iosarm64`) resolve from there; `datastore-preferences` ships them.
- **DataStore on iOS**: declare `androidx.datastore:datastore-preferences` in
  `iosMain` too; `createWithPath` wants an okio `Path` → `"...".toPath()`.
- **skiko has no `Image.scale`**: `Platform.ios.kt` scales via
  `Bitmap.allocPixels` + `Image.scalePixels(..., SamplingMode.LINEAR, ...)`.
- **`WindowInsets.ime` has no iOS actual in CMP 1.11.1** — referencing it fails
  to compile in `iosMain`. `imeAnimationTargetBottom` returns 0 on iOS.
- **Startup crash** (`PlistSanityCheck` IllegalStateException): Info.plist must
  contain `CADisableMinimumFrameDurationOnPhone: true`.
- **Markdown crash** (`LocalMarkdownTypography read outside PiRemoteTheme`):
  the iOS entry MUST wrap the app in `PiTheme` (`PiRemoteTheme`), like Android's
  MainActivity does.
- **`__preview.dylib` link failure on Intel**: `ENABLE_PREVIEWS=NO` in
  `project.yml`.
- **AVFoundation Kotlin names**: class methods live on `*Meta` types but are
  callable as `AVCaptureDevice.x(...)`; the *top-level extension functions*
  (`authorizationStatusForMediaType`, `requestAccessForMediaType`,
  `NSString.writeToFile`) need explicit imports. `UIView()` is not a
  designated initializer — use `UIView(CGRectMake(...))`.
