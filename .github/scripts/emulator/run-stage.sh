#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner once the Wear OS emulator has booted.
#   run-stage.sh fake    Stage 1: fake server + the face served on the runner at 127.0.0.1:8765
#   run-stage.sh server  Stage 2: the live catalog at $BASE_URL
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${RUNNER_TEMP:-/tmp}/e2e"
mkdir -p "$out"

adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb install -r -g push-app/build/outputs/apk/debug/push-app-debug.apk

case "${1:?fake or server}" in
  fake)
    # The watch reaches the runner's http.server on its own 127.0.0.1:8765.
    adb reverse tcp:8765 tcp:8765
    python3 "$here/push_app_e2e.py" fake --out "$out" \
      --extra-catalog http://127.0.0.1:8765/extra.json \
      --real-face simple_digital --real-title "Simple Digital"
    ;;
  server)
    python3 "$here/push_app_e2e.py" server --out "$out" --base-url "${BASE_URL:?}" \
      ${WATCHFACE_PUSH_DEVICE_TOKEN:+--device-token-env WATCHFACE_PUSH_DEVICE_TOKEN}
    ;;
  *)
    echo "unknown stage $1" >&2
    exit 2
    ;;
esac
