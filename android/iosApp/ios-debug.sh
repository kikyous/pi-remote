#!/bin/bash
# iOS device log / crash tools.
#   ./ios-debug.sh stream    Live device syslog, PiRemoteCMP only (Ctrl-C to stop)
#   ./ios-debug.sh all       Live device syslog, everything
#   ./ios-debug.sh crash     Pull crash.log (app's uncaught-exception hook) + new .ips reports
#   ./ios-debug.sh watch     Live log (app only) AND auto-print Documents/crash.log when it appears
#
# Requires: libimobiledevice (brew install libimobiledevice) + device paired via Xcode.

set -euo pipefail
cd "$(dirname "$0")"

DEV="BB650719-9074-5DFC-BED2-F75D6A0D7757"
BUNDLE="com.piremote.cmp.ios"
DEVDIR="/Applications/Xcode-26.6.0.app/Contents/Developer/usr/bin"
DEVICECTL="$DEVDIR/devicectl"
TMP="${TMPDIR:-/tmp}/piremote-debug"
mkdir -p "$TMP"

stream_app() {
    echo "==> live syslog for $BUNDLE (Ctrl-C to stop)"
    idevicesyslog -u "$DEV" | grep -iE "$BUNDLE|PiRemote|piremote"
}

cmd="stream"
[ $# -gt 0 ] && cmd="$1"

case "$cmd" in
stream)  stream_app ;;
all)     echo "==> live syslog, everything (Ctrl-C to stop)"; idevicesyslog -u "$DEV" ;;
crash)
    echo "==> Documents/crash.log (uncaught Kotlin exception hook)"
    rm -f "$TMP/crash.log"
    "$DEVICECTL" device copy from --device "$DEV" \
        --domain-type appDataContainer --domain-identifier "$BUNDLE" \
        --source Documents/crash.log --destination "$TMP/crash.log" >/dev/null 2>&1 \
        && cat "$TMP/crash.log" \
        || echo "(no crash.log — the app has not crashed since the hook was installed)"
    echo
    echo "==> newest .ips crash reports (pulling all)"
    rm -rf "$TMP/ips"; mkdir -p "$TMP/ips"
    "$DEVICECTL" device copy from --device "$DEV" \
        --domain-type systemCrashLogs --source . --destination "$TMP/ips" >/dev/null 2>&1
    ls -t "$TMP/ips"/PiRemoteCMP-*.ips 2>/dev/null | head -3 \
        || echo "(no PiRemoteCMP crash reports)"
    ;;
watch)
    # Stream the app's logs; every 2s also pull crash.log so a crash is
    # surfaced with its message without manual steps.
    echo "==> watching $BUNDLE logs + crash.log (Ctrl-C to stop)"
    idevicesyslog -u "$DEV" | grep --line-buffered -iE "$BUNDLE|PiRemote|piremote" &
    LOGPID=$!
    trap 'kill $LOGPID 2>/dev/null' EXIT
    last_mtime=""
    while true; do
        sleep 2
        rm -f "$TMP/crash.log"
        "$DEVICECTL" device copy from --device "$DEV" \
            --domain-type appDataContainer --domain-identifier "$BUNDLE" \
            --source Documents/crash.log --destination "$TMP/crash.log" >/dev/null 2>&1 || continue
        mtime=$(stat -f %m "$TMP/crash.log" 2>/dev/null || echo "")
        if [ -n "$mtime" ] && [ "$mtime" != "$last_mtime" ]; then
            last_mtime="$mtime"
            echo "=== CRASH detected ($(date '+%H:%M:%S')) ==="
            head -30 "$TMP/crash.log"
            echo "=== end crash ==="
        fi
    done
    ;;
*) echo "usage: ./ios-debug.sh {stream|all|crash|watch}"; exit 1 ;;
esac
