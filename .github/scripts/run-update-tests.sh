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
  # Evidence lives in the app's INTERNAL files dir: adb shell cannot see app-private
  # storage paths on this emulator (FUSE per-uid filtering hid the external-files dir -
  # run 42446867338), so pull through run-as (app uid) as a tar stream.
  mkdir -p update-evidence
  # Screenshots are captured as the SHELL uid into /data/local/tmp (via UiAutomation
  # executeShellCommand in the test) - the one storage path plain adb pull can read.
  for f in $(adb -s emulator-5554 shell 'ls /data/local/tmp/uf-* 2>/dev/null' | tr -d '\r'); do
    case "$f" in /data/local/tmp/uf-*) ;; *) continue;; esac
    timeout 30 adb -s emulator-5554 pull "$f" update-evidence/ || true
  done
  # Trace files live in the app's internal files dir; copy out one by one via run-as
  # (app uid) - tar is not guaranteed on the API 29 image.
  for f in $(adb -s emulator-5554 exec-out run-as app.eightcloud.gallery.debug ls files/update-evidence/ 2>/dev/null | tr -d '\r'); do
    case "$f" in *:*|*/*|"") continue;; esac
    timeout 30 adb -s emulator-5554 exec-out run-as app.eightcloud.gallery.debug cat "files/update-evidence/$f" > "update-evidence/$f" 2>/dev/null || true
  done
  adb -s emulator-5554 logcat -d -b all -s UFTrace:I > update-evidence/logcat-uftrace.txt 2>/dev/null || true
  adb -s emulator-5554 logcat -d > update-evidence/logcat.txt 2>/dev/null || true
  ls -la update-evidence/ || true
  # Console redundancy: the artifact upload validates filenames and fails atomically
  # (run 42449450554 lost everything to one bad name) - the log always survives.
  echo '== logcat-uftrace.txt =='; cat update-evidence/logcat-uftrace.txt 2>/dev/null || true
  for t in update-evidence/trace-*.txt; do [ -f "$t" ] && { echo "== $t =="; cat "$t"; }; done
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
  adb -s emulator-5554 shell screencap -p /data/local/tmp/uf-host-post-replacement.png || true
fi
exit "$RC"
