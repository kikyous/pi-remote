#!/bin/bash
# Build, sign and install Pi Remote CMP to the iPhone — fully command line.
# Usage: ./deploy.sh [launch]
#   (no args)   build + sign + install
#   launch      also launch the app on the device
#
# See ios-build.md for the full background (signing facts, pitfalls).

set -euo pipefail
cd "$(dirname "$0")"

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode-26.6.0.app/Contents/Developer}"
DEV="BB650719-9074-5DFC-BED2-F75D6A0D7757"
BUNDLE="com.piremote.cmp.ios"
PROFILE="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/1c7ff226-dfbe-44ec-b375-626c83ae05ac.mobileprovision"
IDENTITY="Apple Development: kikyous@163.com (45RMTN4VYV)"
ENT="/tmp/piremote-ent.plist"
APP="build/Debug-iphoneos/PiRemoteCMP.app"
DEVICECTL="$DEVELOPER_DIR/usr/bin/devicectl"

cat > "$ENT" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>application-identifier</key>
	<string>8QV6UFZ79P.com.piremote.cmp.ios</string>
	<key>com.apple.developer.team-identifier</key>
	<string>8QV6UFZ79P</string>
	<key>get-task-allow</key>
	<true/>
</dict>
</plist>
EOF

echo "==> xcodegen (regenerate project from project.yml)"
xcodegen

echo "==> build (unsigned)"
xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug \
  -sdk iphoneos ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build

echo "==> embed profile + sign"
cp "$PROFILE" "$APP/embedded.mobileprovision"
codesign --force --sign "$IDENTITY" --entitlements "$ENT" --timestamp=none "$APP/PiRemoteCMP"
codesign -v --verify "$APP/PiRemoteCMP"

echo "==> install"
"$DEVICECTL" device install app --device "$DEV" "$APP"

if [ "${1:-}" = "launch" ]; then
  echo "==> launch"
  "$DEVICECTL" device process launch --device "$DEV" "$BUNDLE"
fi

echo "==> done"
