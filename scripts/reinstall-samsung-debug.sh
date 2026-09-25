#!/usr/bin/env bash
# Deliberately removes app data. Explicitly authorized for this Samsung test.
set -euo pipefail
apk=${1:-"$HOME/Downloads/ColorBlendr v3.0.1.apk"}
[[ -r "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
command -v adb >/dev/null
adb get-state >/dev/null
adb uninstall com.drdisagree.colorblendr
adb install -g "$apk"
adb shell pm path com.drdisagree.colorblendr
