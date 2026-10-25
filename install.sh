#!/usr/bin/env bash
# Installs (or removes) IMDB-TV on an Android TV over the network. No adb binary needed.
# Usage:   ./install.sh 192.168.0.50
#          ./install.sh 192.168.0.50 --uninstall
# First, on the TV: turn on "USB debugging" (or "ADB debugging") in Developer options.
set -euo pipefail
[ $# -ge 1 ] || { echo "usage: $0 <TV IP> [--uninstall]" >&2; exit 2; }
export TV_HOST="$1" MSYS_NO_PATHCONV=1
UNINSTALL="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
VENV="$HERE/tools/venv"
APK="$HERE/apk/IMDB-TV.apk"
APK_URL="https://github.com/mirtera157/IMDB-TV/releases/latest/download/IMDB-TV.apk"
PKG="br.tvvibe.imdbbadge"
step() { printf '\n== %s\n' "$1"; }

# 1. Python + libraries (first run only)
PY="$VENV/bin/python"; [ -x "$PY" ] || PY="$VENV/Scripts/python.exe"
if [ ! -x "$PY" ]; then
    step "Setting up Python (first run only)"
    BASE="$(command -v python3 || command -v python || true)"
    [ -n "$BASE" ] || { echo "Python 3 not found: install it first." >&2; exit 1; }
    "$BASE" -m venv "$VENV"
    PY="$VENV/bin/python"; [ -x "$PY" ] || PY="$VENV/Scripts/python.exe"
    "$PY" -m pip install --quiet -r "$HERE/tools/requirements.txt"
fi

# 2. The app itself: a local build if there is one, else the latest release
if [ "$UNINSTALL" != "--uninstall" ] && [ ! -f "$APK" ]; then
    step "Downloading the latest IMDB-TV.apk"
    mkdir -p "$(dirname "$APK")"
    curl -fL --progress-bar -o "$APK" "$APK_URL"
fi

tv() { "$PY" -I "$HERE/tools/tv.py" "$@"; }

# 3. Connect
step "Connecting to the TV at $TV_HOST (watch the TV: accept 'Allow USB debugging?' and tick 'Always allow')"
tv --probe

if [ "$UNINSTALL" = "--uninstall" ]; then
    step "Removing IMDB-TV"
    tv "settings delete secure enabled_accessibility_services; pm uninstall $PKG"
    echo "Removed."
    exit 0
fi

# 4. Send and install
step "Sending the app to the TV (takes a few minutes)"
"$PY" -I "$HERE/tools/b64push.py" "$APK" /data/local/tmp/imdbtv.apk
step "Installing"
tv 'pm install -r /data/local/tmp/imdbtv.apk; rm -f /data/local/tmp/imdbtv.apk'

# 5. Permission + switch the accessibility service on
# WRITE_SECURE_SETTINGS: the app switches accessibility off and on to wake up the Apple TV app.
step "Switching it on"
tv "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS; settings put secure enabled_accessibility_services $PKG/.BadgeService; settings put secure accessibility_enabled 1"

printf '\nDone! Open the Apple TV app: the IMDb rating appears in the top-right corner of the selected title (about 10 s the first time).\n'
echo "You can turn USB debugging off on the TV now."
