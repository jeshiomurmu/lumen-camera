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
if [ -n "$PID" ]; then echo "running after launch: yes (pid $PID)"; else echo "running after launch: NO"; fi | tee -a "$R/launch.txt"

# 3) leave the app and come back (camera must reopen), then press the shutter
dismiss() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; }
dismiss
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell am start -n app.lumen.camera/.MainActivity > /dev/null
sleep 8
dismiss
adb exec-out screencap -p > "$R/after-resume.png"
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $NF}')
W=${SIZE%x*}; H=${SIZE#*x}
T0=$(date +%s)
adb shell input tap $((W / 2)) $((H - H * 9 / 100))
for i in $(seq 1 30); do
  sleep 3
  N=$(adb shell ls /sdcard/DCIM/Lumen 2>/dev/null | tr -d '\r' | grep -ci jpg)
  [ "${N:-0}" -gt 0 ] && break
done
echo "capture time: $(( $(date +%s) - T0 )) s (includes up to 3 s polling)" | tee -a "$R/launch.txt"
dismiss
adb exec-out screencap -p > "$R/after-capture.png"
PID2=$(adb shell pidof app.lumen.camera | tr -d '\r')
if [ -n "$PID2" ]; then echo "running after capture: yes"; else echo "running after capture: NO"; fi | tee -a "$R/launch.txt"
F=$(adb shell ls /sdcard/DCIM/Lumen 2>/dev/null | tr -d '\r' | grep -i jpg | head -1)
[ -n "$F" ] && adb pull "/sdcard/DCIM/Lumen/$F" "$R/photo.jpg" > /dev/null
adb shell ls -la /sdcard/DCIM/Lumen 2>&1 | tr -d '\r' | tee "$R/saved-files.txt"
adb logcat -d | grep -iE "lumen|AndroidRuntime|FATAL" | tail -150 > "$R/logcat.txt"
grep -c "FATAL EXCEPTION" "$R/logcat.txt" > "$R/crashes.txt" || true
echo "crashes: $(cat "$R/crashes.txt")"
exit 0
