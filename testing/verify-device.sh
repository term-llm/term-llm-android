#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TEST_SECRETS_DIR:?Set an external disposable secrets directory}"
testing/prepare-tls.sh
pids=()
cleanup() { for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
python3 testing/push_relay.py --cert "$TEST_SECRETS_DIR/server.pem" --key "$TEST_SECRETS_DIR/server.key" > "$TEST_SECRETS_DIR/relay.log" 2>&1 & pids+=("$!")
mkdir -p "$TEST_SECRETS_DIR/web"
printf '<html><body>Android bridge fixture</body></html>' > "$TEST_SECRETS_DIR/web/index.html"
python3 -m http.server 8899 --bind 0.0.0.0 --directory "$TEST_SECRETS_DIR/web" > "$TEST_SECRETS_DIR/web.log" 2>&1 & pids+=("$!")
if ! adb get-state >/dev/null 2>&1; then
 echo no | avdmanager create avd --force --device pixel_6 --name hardening --package 'system-images;android-35;google_apis;x86_64'
 printf '\nhw.mainKeys=no\n' >> "$HOME/.android/avd/hardening.avd/config.ini"
 emulator -avd hardening -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim > "$TEST_SECRETS_DIR/emulator.log" 2>&1 & pids+=("$!")
 adb wait-for-device
 for i in $(seq 1 180); do [[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && break; sleep 1; done
fi
# The Back regression exercises a real edge gesture, not three-button navigation.
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
sleep 2
./gradlew --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest lintRelease assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.termllm.app android.permission.READ_MEDIA_AUDIO
adb shell am instrument -w com.termllm.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$TEST_SECRETS_DIR/instrumentation.txt"
grep -Eq '^OK \([0-9]+ tests?\)' "$TEST_SECRETS_DIR/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$TEST_SECRETS_DIR/instrumentation.txt"
