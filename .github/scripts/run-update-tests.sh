#!/bin/bash
# Update-flow harness driver. MUST run as one shell: reactivecircus/android-emulator-runner
# executes each `script:` line as its own `sh -c`, so backgrounded servers, $! and traps
# do not survive line boundaries there (run 42427827872: server orphaned/died, all
# fixture tests failed on an unreachable server, and the step hung to the 30-min cap).
set -uo pipefail

python3 -m http.server 8799 --directory srv/update-fixtures &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  # Pull evidence while the emulator is still alive (the runner kills it after us).
  # Evidence lives in the APP-PRIVATE external dir (scoped storage on API 29 blocks raw
  # /sdcard paths from the test process - run 42444914399 lost every trace file to that).
  mkdir -p update-evidence
  adb -s emulator-5554 pull /sdcard/Android/data/app.eightcloud.gallery.debug/files/update-evidence/ update-evidence/ || true
  if [ -z "$(ls -A update-evidence 2>/dev/null)" ]; then
    # run-as fallback (debuggable build): tar the dir through exec-out and unpack locally.
    adb -s emulator-5554 exec-out run-as app.eightcloud.gallery.debug tar -cf - files/update-evidence > update-evidence.tar 2>/dev/null || true
    if [ -s update-evidence.tar ]; then tar -xf update-evidence.tar -C update-evidence --strip-components=1 || true; fi
  fi
  adb -s emulator-5554 logcat -d -b all -s UFTrace:I > update-evidence/logcat-uftrace.txt 2>/dev/null || true
  adb -s emulator-5554 logcat -d > update-evidence/logcat.txt 2>/dev/null || true
}
trap cleanup EXIT

sleep 1
adb -s emulator-5554 reverse tcp:8799 tcp:8799
# Guest-side reachability probe (diagnostic output only; the instrumented
# a_fixtureServerReachable test is the decisive in-process check).
adb -s emulator-5554 shell 'toybox wget -q -O- http://127.0.0.1:8799/release-good.json 2>/dev/null | head -c 100' || true

RC=0
# 1) App-side flow, stops at the system install sheet (never replaced under its own
#    instrumentation - the runner lives in the app process).
timeout 15m ./gradlew :app:connectedDebugAndroidTest -PeightcloudVersionCode=2 --stacktrace || RC=$?
# 2) External controller (own package/process): drives the sheet's UPDATE, survives
#    the replacement, asserts vc==3 + stable cert from PackageManager.
if [ "$RC" -eq 0 ]; then
  timeout 10m ./gradlew :install-controller:connectedDebugAndroidTest --stacktrace || RC=$?
fi
# 3) Belt-and-braces host-side assertion of the actual replacement.
if [ "$RC" -eq 0 ]; then
  adb -s emulator-5554 shell dumpsys package app.eightcloud.gallery.debug | grep -m1 'versionCode=3 ' || RC=1
  adb -s emulator-5554 shell mkdir -p /sdcard/Android/data/app.eightcloud.gallery.debug/files/update-evidence || true
  adb -s emulator-5554 shell screencap -p /sdcard/Android/data/app.eightcloud.gallery.debug/files/update-evidence/host-post-replacement.png || true
fi
exit "$RC"
