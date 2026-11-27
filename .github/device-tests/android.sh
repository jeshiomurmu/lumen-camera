#!/usr/bin/env bash
# Runs inside the Android emulator job: core test suite on the device CPU,
# then installs the APK, launches it and tries one photo with the emulated camera.
set -u
R=results/android
mkdir -p "$R"
adb wait-for-device
{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') · Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r') · cpus: $(adb shell nproc | tr -d '\r')"
} > "$R/device.txt"
cat "$R/device.txt"

# 1) imaging core tests, compiled with the NDK and run on the device
adb push build-android/lumen_tests /data/local/tmp/lumen_tests > /dev/null
adb shell chmod 755 /data/local/tmp/lumen_tests
adb shell "cd /data/local/tmp && rm -rf out && mkdir out && LUMEN_PERF=1 ./lumen_tests out" | tr -d '\r' | tee "$R/core-tests.txt"

# 2) install + launch the real APK
adb install -r -g app.apk 2>&1 | tail -1 | tee "$R/install.txt"
adb logcat -c
adb shell am start -W -n app.lumen.camera/.MainActivity | tr -d '\r' | tee "$R/launch.txt"
sleep 12
adb exec-out screencap -p > "$R/launch.png"
PID=$(adb shell pidof app.lumen.camera | tr -d '\r')
echo "running after launch: ${PID:+yes (pid $PID)}${PID:-NO}" | tee -a "$R/launch.txt"

# 3) press the shutter (centre of the shutter row) and wait for the capture
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $NF}')
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input tap $((W / 2)) $((H - H * 9 / 100))
sleep 25
adb exec-out screencap -p > "$R/after-capture.png"
PID2=$(adb shell pidof app.lumen.camera | tr -d '\r')
echo "running after capture: ${PID2:+yes}${PID2:-NO}" | tee -a "$R/launch.txt"
adb shell ls -la /sdcard/DCIM/Lumen 2>&1 | tr -d '\r' | tee "$R/saved-files.txt"
adb logcat -d | grep -iE "lumen|AndroidRuntime|FATAL" | tail -150 > "$R/logcat.txt"
grep -c "FATAL EXCEPTION" "$R/logcat.txt" > "$R/crashes.txt" || true
echo "crashes: $(cat "$R/crashes.txt")"
exit 0
