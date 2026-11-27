#!/usr/bin/env bash
# Runs inside the Android emulator job:
#  1. imaging-core test suite on the device CPU
#  2. installs the APK and drives the real UI through its accessibility labels:
#     launch, resume, shutter latency, rapid shots, every aspect ratio (checks the saved
#     image), settings navigation, 150 Mb/s + frame-rate choices, a video recording
#     (measured bitrate / fps), Night merge, Pro mode, and UI frame smoothness.
set -u
R=results/android
T=.github/device-tests
mkdir -p "$R"
F="$R/functional.txt"
: > "$F"
log() { echo "$*" | tee -a "$F"; }
adb wait-for-device
{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') · Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r') · cpus: $(adb shell nproc | tr -d '\r')"
} > "$R/device.txt"
cat "$R/device.txt"
command -v ffprobe > /dev/null || (sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg > /dev/null) || true

# 1) imaging core tests, compiled with the NDK and run on the device
adb push build-android/lumen_tests /data/local/tmp/lumen_tests > /dev/null
adb shell chmod 755 /data/local/tmp/lumen_tests
adb shell "cd /data/local/tmp && rm -rf out && mkdir out && LUMEN_PERF=1 ./lumen_tests out" | tr -d '\r' | tee "$R/core-tests.txt"

# ---- helpers -------------------------------------------------------------------------------
dismiss() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; }
shot() { adb exec-out screencap -p > "$R/$1.png"; }
now_ms() { date +%s%3N; }
SIZE=$(adb shell wm size | tr -d '\r' | awk '{print $NF}')
W=${SIZE%x*}; H=${SIZE#*x}
# tap the element whose accessibility label starts with $1 (exact match preferred)
tapd() {
  for attempt in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || { sleep 1; continue; }
    XY=$(python3 - "$1" <<'PY'
import re, sys
want = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
best = None
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    d = re.search(r'content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not d or not b: continue
    desc = d.group(1)
    if desc == want or (best is None and desc.startswith(want)):
        x1, y1, x2, y2 = map(int, b.groups())
        best = ((x1 + x2) // 2, (y1 + y2) // 2)
        if desc == want: break
print(f"{best[0]} {best[1]}" if best else "")
PY
)
    if [ -n "$XY" ]; then adb shell input tap $XY; return 0; fi
    sleep 1
  done
  echo "  (label not found: $1)" >> "$F"
  return 1
}
count() { adb shell "ls /sdcard/DCIM/Lumen 2>/dev/null" | tr -d '\r' | grep -ciE "$1" || true; }
newest() { adb shell "ls -t /sdcard/DCIM/Lumen 2>/dev/null" | tr -d '\r' | grep -iE "$1" | head -1; }
# waits until the number of files matching $1 exceeds $2; prints elapsed ms or "timeout"
wait_new() {
  local t0=$(now_ms)
  for i in $(seq 1 240); do
    [ "$(count "$1")" -gt "$2" ] && { echo $(( $(now_ms) - t0 )); return; }
    sleep 0.25
  done
  echo timeout
}
shutter() { tapd "Shutter" || adb shell input tap $((W / 2)) $((H - H * 9 / 100)); }

# 2) install + launch the real APK
adb install -r -g app.apk 2>&1 | tail -1 | tee "$R/install.txt"
adb logcat -c
adb shell am start -W -n app.lumen.camera/.MainActivity | tr -d '\r' | tee "$R/launch.txt"
sleep 10
dismiss
shot launch
PID=$(adb shell pidof app.lumen.camera | tr -d '\r')
if [ -n "$PID" ]; then echo "running after launch: yes (pid $PID)"; else echo "running after launch: NO"; fi | tee -a "$R/launch.txt"
log "launch: $(grep -E 'TotalTime' "$R/launch.txt" | awk '{print $2}') ms cold start"

# 3) leave and come back: the camera must reopen
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am start -n app.lumen.camera/.MainActivity > /dev/null; sleep 6; dismiss
shot after-resume
log "resume: $( [ -n "$(adb shell pidof app.lumen.camera | tr -d '\r')" ] && echo ok || echo FAILED)"

# 4) shutter latency (press -> file in the gallery) and rapid shots
adb shell dumpsys gfxinfo app.lumen.camera reset > /dev/null
N0=$(count jpg)
shutter
log "photo: tap -> saved $(wait_new jpg "$N0") ms (includes adb polling, 250 ms steps)"
sleep 1
N0=$(count jpg); t0=$(now_ms)
for i in 1 2 3 4 5; do adb shell input tap $((W / 2)) $((H - H * 9 / 100)); sleep 0.3; done
for i in $(seq 1 120); do [ "$(count jpg)" -ge $((N0 + 5)) ] && break; sleep 0.25; done
log "rapid shots: $(( $(count jpg) - N0 ))/5 saved in $(( $(now_ms) - t0 )) ms (taps 300 ms apart)"
shot after-capture
F1=$(newest jpg); [ -n "$F1" ] && adb pull "/sdcard/DCIM/Lumen/$F1" "$R/photo.jpg" > /dev/null

# 5) every aspect ratio: pick it in the top bar, shoot, check the saved image
for A in "3:4" "9:16" "1:1" "Full"; do
  tapd "Aspect ratio" && sleep 1 && tapd "Aspect $A"
  sleep 3
  shot "aspect-${A/:/x}"
  N0=$(count jpg)
  shutter
  r=$(wait_new jpg "$N0")
  f=$(newest jpg)
  if [ -n "$f" ] && [ "$r" != timeout ]; then
    adb pull "/sdcard/DCIM/Lumen/$f" "/tmp/a.jpg" > /dev/null
    read iw ih io < <(python3 $T/jpeginfo.py /tmp/a.jpg)
    ratio=$(python3 -c "print(f'{max($iw,$ih)/min($iw,$ih):.3f}')")
    log "aspect $A: saved ${iw}x${ih} (exif orientation $io) long/short $ratio in $r ms"
  else
    log "aspect $A: NO FILE"
  fi
done
tapd "Aspect ratio" && sleep 1 && tapd "Aspect 3:4"; sleep 2

# 6) settings: open, go into Video, pick 150 Mb/s, list frame rates
tapd "Settings" && sleep 2 && shot settings
tapd "Settings Video" && sleep 2 && shot settings-video
tapd "Setting Frame rate" && sleep 1.5 && shot settings-fps
python3 - >> "$F" <<'PY'
import re, subprocess
subprocess.run(['adb','shell','uiautomator','dump','/sdcard/ui.xml'],capture_output=True)
subprocess.run(['adb','pull','/sdcard/ui.xml','/tmp/ui.xml'],capture_output=True)
xml=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
opts=[d for d in re.findall(r'content-desc="Option ([^"]*fps[^"]*)"',xml)]
print("frame rates offered (this camera): " + (", ".join(opts) if opts else "none found"))
PY
tapd "Setting Frame rate"; sleep 1
tapd "Setting Bitrate" && sleep 1.5 && shot settings-bitrate
tapd "Option 150 Mb/s" && log "bitrate: 150 Mb/s selected" || log "bitrate: 150 option missing"
sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2
dismiss

# 7) video: record ~6 s and measure what was written
tapd "Mode VIDEO"; sleep 4
shot video
M0=$(count mp4)
shutter; sleep 6; shot recording; shutter
r=$(wait_new mp4 "$M0")
v=$(newest mp4)
if [ -n "$v" ] && [ "$r" != timeout ]; then
  adb pull "/sdcard/DCIM/Lumen/$v" "$R/video.mp4" > /dev/null
  if command -v ffprobe > /dev/null; then
    P=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,avg_frame_rate,bit_rate -of default=nw=1 "$R/video.mp4" | tr '\n' ' ')
    log "video: $P"
  fi
  log "video file: $(stat -c %s "$R/video.mp4") bytes"
else
  log "video: NO FILE"
fi
rm -f "$R/video.mp4"

# 8) Night (multi-frame merge) and Pro
tapd "Mode PHOTO"; sleep 3
tapd "Night"; sleep 3
N0=$(count NIGHT)
shutter
r=$(wait_new NIGHT "$N0")
log "night: tap -> merged photo saved $r ms"
n=$(newest NIGHT); [ -n "$n" ] && adb pull "/sdcard/DCIM/Lumen/$n" "$R/night.jpg" > /dev/null
tapd "Night"; sleep 2
tapd "Mode PRO"; sleep 3; tapd "Pro ISO"; sleep 1; shot pro
tapd "Mode PHOTO"; sleep 2

# 9) smoothness of everything above (UI thread + render thread)
adb shell dumpsys gfxinfo app.lumen.camera | tr -d '\r' > /tmp/gfx.txt
log "frames: $(grep -m1 'Total frames rendered' /tmp/gfx.txt | awk -F': ' '{print $2}') rendered, janky $(grep -m1 'Janky frames:' /tmp/gfx.txt | awk -F': ' '{print $2}')"
log "frame time: 50th $(grep -m1 '50th percentile' /tmp/gfx.txt | awk -F': ' '{print $2}'), 90th $(grep -m1 '90th percentile' /tmp/gfx.txt | awk -F': ' '{print $2}'), 99th $(grep -m1 '99th percentile' /tmp/gfx.txt | awk -F': ' '{print $2}')"

PID2=$(adb shell pidof app.lumen.camera | tr -d '\r')
if [ -n "$PID2" ]; then echo "running at the end: yes"; else echo "running at the end: NO"; fi | tee -a "$R/launch.txt"
adb shell ls -la /sdcard/DCIM/Lumen 2>&1 | tr -d '\r' | tee "$R/saved-files.txt"
adb logcat -d | grep -iE "lumen|AndroidRuntime|FATAL" | tail -200 > "$R/logcat.txt"
grep -c "FATAL EXCEPTION" "$R/logcat.txt" > "$R/crashes.txt" || true
log "crashes: $(cat "$R/crashes.txt")"
exit 0
