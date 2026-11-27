#!/usr/bin/env bash
# Runs on the macOS runner: core test suite inside an iOS Simulator (Apple silicon),
# then installs the simulator build of the app, launches it and takes a screenshot.
set -u
R=results/ios
mkdir -p "$R"
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
c=[(rt,x) for rt,v in d.items() if "iOS" in rt for x in v if x["name"].startswith("iPhone")]
c.sort(key=lambda t:(t[0],"Pro" in t[1]["name"],t[1]["name"]))
print(c[-1][1]["udid"])')
NAME=$(xcrun simctl list devices | grep "$UDID" | sed 's/ (.*//' | xargs)
RUNTIME=$(xcrun simctl list devices | awk -v u="$UDID" '/^-- /{rt=$0} index($0,u){print rt}' | tr -d '-' | xargs)
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
{
  echo "device: $NAME Simulator · $RUNTIME"
  echo "host: $(sysctl -n machdep.cpu.brand_string) · cpus: $(sysctl -n hw.ncpu) · $(xcodebuild -version 2>/dev/null | sed -n 1p)"
} > "$R/device.txt"
cat "$R/device.txt"

# 1) imaging core tests, compiled for the iOS Simulator and run inside it
mkdir -p "$PWD/sim-out"
SIMCTL_CHILD_LUMEN_PERF=1 xcrun simctl spawn "$UDID" "$PWD/build-ios/lumen_tests" "$PWD/sim-out" | tee "$R/core-tests.txt"

# 2) install + launch the app
xcrun simctl install "$UDID" ios/build-sim/Build/Products/Release-iphonesimulator/Lumen.app
for p in camera microphone photos-add; do xcrun simctl privacy "$UDID" grant "$p" app.lumen.camera || true; done
xcrun simctl launch "$UDID" app.lumen.camera | tee "$R/launch.txt"
sleep 15
xcrun simctl io "$UDID" screenshot "$R/launch.png" > /dev/null
if xcrun simctl spawn "$UDID" launchctl list | grep -q app.lumen.camera; then
  echo "running after launch: yes" | tee -a "$R/launch.txt"
else
  echo "running after launch: NO" | tee -a "$R/launch.txt"
fi
ls ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -i lumen > "$R/crashes.txt" || true
echo "crash reports: $(wc -l < "$R/crashes.txt" | xargs)"
exit 0
