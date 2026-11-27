# Lumen Camera

[![Build apps](https://github.com/jeshiomurmu/lumen-camera/actions/workflows/build.yml/badge.svg)](https://github.com/jeshiomurmu/lumen-camera/actions/workflows/build.yml)
[![Device tests](https://github.com/jeshiomurmu/lumen-camera/actions/workflows/device-tests.yml/badge.svg)](https://github.com/jeshiomurmu/lumen-camera/actions/workflows/device-tests.yml)
[![Latest release](https://img.shields.io/github/v/release/jeshiomurmu/lumen-camera?label=download)](https://github.com/jeshiomurmu/lumen-camera/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-Android%2010%2B%20%7C%20iOS%2017%2B-black)

**Open-source pro camera for Android and iOS.** It merges bursts of RAW frames the way Google's HDR+ does, stacks the night sky, gives you full manual control, saves 16-bit DNG, records Log / HDR / ProRes video, and runs AI denoise and AI zoom on the phone. Everything is built on one shared C++ imaging core you can test on a laptop.

⭐ If you like the idea, star the repo; it helps other people find it. Phone owners: a [device report](https://github.com/jeshiomurmu/lumen-camera/issues/new?template=device-report.yml) from your phone is the most useful contribution right now (see [CONTRIBUTING.md](CONTRIBUTING.md)).

A pro camera app for **Android** (Kotlin + Jetpack Compose + Camera2) and **iOS** (Swift + SwiftUI + AVFoundation). Both apps share one open-source C++ imaging core. The core does HDR+-style burst merging, night and astro stacking, colour science and DNG writing. Open-source AI models run on the device for denoising and zoom. The interface is an original flagship-style layout: lens pills, a mode carousel, a Pro panel with ruler dials, and a big shutter.

Every hardware capability is reached through **public platform APIs only**, at the highest level each phone exposes. There is no root, no HAL patching, no private iOS APIs and no thermal-limit bypass. [docs/HARDWARE_UNLOCKS.md](docs/HARDWARE_UNLOCKS.md) explains why, and what that means for each feature.

## Features

| | Android | iOS |
|---|---|---|
| **Photo engine** | Lumen burst (RAW align + robust merge) · device ISP · OEM Camera2 extensions (Night, HDR, Bokeh, …) | Lumen burst (Bayer RAW brackets) · Apple pipeline (Photonic Engine / Deep Fusion) · Apple ProRAW |
| **Resolution** | Full sensor (`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`: 50 / 200 MP where offered) | Up to 48 MP (Apple pipeline / ProRAW); 12 MP Bayer RAW for the Lumen burst |
| **Night** | Handheld burst, up to 15 frames, OIS-aware exposure plan | Same plan, using bracketed RAW captures |
| **Astro** (tripod) | Star detection → triangle matching → RANSAC → sigma-clip / median / mean / star-trail stack · dark frames · hot pixels · gradient removal · auto-stretch | same core |
| **Pro (manual)** | ISO, shutter (up to the sensor maximum), EV, manual focus, Kelvin WB + tint, presets · Android 16 shutter/ISO priority | ISO, shutter, EV, lens position, Kelvin WB + tint, presets · Camera Control sliders (ISO, focus, zoom, EV) |
| **RAW** | DNG 1.4 (merged bursts: 16-bit, cleaner than one frame) | Original Apple DNG / ProRAW + Lumen-merged DNG |
| **AI** (open source) | FFDNet denoise, Real-ESRGAN ×4 "AI zoom" via ONNX Runtime + NNAPI | same models converted to Core ML (Neural Engine) |
| **Video** | HEVC / AVC / AV1 / APV (Samsung's pro codec, Android 16) · bitrate up to the encoder maximum · 10-bit HLG · Lumen Log + `.cube` LUT · open gate 4:3 · 120/240 fps high speed | HEVC / H.264 / ProRes 422 HQ / ProRes RAW (iPhone 17 Pro, iOS 26) · bitrate control where iOS allows it · HLG · Apple Log / Apple Log 2 · open gate · strongest stabilization mode |
| **Audio** | AAC 48 kHz stereo, external mics | AAC or 24-bit PCM, external USB-C / Bluetooth mics |
| **Viewfinder** | RGB + luma histogram, focus peaking, zebras, level, grid | + luma waveform |
| **Safety** | Thermal warning; stops recording when severe | Thermal warning; stops recording when critical |

## Test results

Every push runs the core test suite and the real apps on an **Android emulator** and an **iOS Simulator** in GitHub Actions ([device-tests.yml](.github/workflows/device-tests.yml)). Raw logs, screenshots and the captured photo are in [`docs/test-results/`](docs/test-results), which the workflow updates after each run.

Run on commit `503e4ac`, 27 Nov 2026:

| | Android | iOS |
|---|---|---|
| Device | Android 14 emulator (API 34, x86_64, 2 cores) | iPhone 17 Pro Max Simulator, iOS 26.5 (Apple M1 host, arm64) |
| Core test suite | **83 / 83 passed** | **83 / 83 passed** |
| 8-frame low-light burst (handheld shake) | 31.64 → **37.04 dB** PSNR (+5.40 dB) | 31.64 → **37.04 dB** (+5.40 dB) |
| Ghosting (moving object) | error 0.0071 vs 0.0546 naive average | identical |
| Astro: star registration | rotation 0.663° found vs 0.660° true, 0.017 px error | identical |
| Astro: sky noise after stacking | 3.76× lower, 60/60 hot pixels removed | identical |
| 12 MP merge speed | 1.80 s / frame (emulator, 2 threads) | 0.60 s / frame (3 threads) |
| App install + cold launch | ✅ 2.3 s, no crashes | ✅ launches, no crash reports |
| Leave the app and return | ✅ camera reopens | — |
| Shutter → photo saved to DCIM/Lumen | ✅ (emulated camera scene) | — (no camera in the Simulator) |

<p>
<img src="docs/test-results/android/after-resume.png" width="200" alt="Android emulator: live preview with histogram">
<img src="docs/test-results/android/photo.jpg" width="200" alt="Photo captured on the Android emulator">
<img src="docs/test-results/ios/launch.png" width="200" alt="iOS Simulator: camera UI">
</p>

*Android preview with live histogram · photo the emulator saved · the iOS interface (the Simulator has no camera, so its viewfinder is black).*

**What these tests do not cover yet:** emulators and simulators have no real image sensor. The emulator's camera is a synthetic scene and offers no RAW, so the Lumen burst on real RAW, Night, Astro, ProRAW, Log/ProRes video and the AI models have not been run on a physical phone. The algorithms behind them are covered by the core tests above. Results from real phones are very welcome: please open a [device report](https://github.com/jeshiomurmu/lumen-camera/issues/new?template=device-report.yml).

The emulator test also caught a real bug: after leaving and returning to the app, the Android camera stayed closed. It is fixed in 1.0.8.

## Repository layout

```
core/            C++17 imaging core (shared). Public C API: core/include/lumen_core.h
  src/           align, merge, finish, color, astro, dng, lut
  tests/         synthetic-camera test suite + DNG validation (LibRaw)
  tools/         lumen_cli: run the pipeline on DNGs from a PC
android/         Android Studio project (Gradle wrapper included)
ios/             XcodeGen spec (project.yml) + Swift sources
tools/           export_models.py: downloads official weights, exports ONNX
luts/            LumenLog_to_Rec709.cube
docs/            ARCHITECTURE.md, HARDWARE_UNLOCKS.md
```

## Download

Ready-made installers are on the [Releases page](https://github.com/jeshiomurmu/lumen-camera/releases/latest), built by GitHub Actions on every push:

- **Android:** open the `.apk` on your phone and allow installing unknown apps.
- **iOS:** sign and install the `-unsigned.ipa` with [Sideloadly](https://sideloadly.io) or [AltStore](https://altstore.io) using your Apple ID (re-sign every 7 days with a free Apple ID).

## Build

### 1. Imaging core and tests (PC — optional, but a quick sanity check)

```bash
cmake -S core -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
./build/lumen_tests out/            # 82 checks; writes PPM previews + DNGs
pip install rawpy tifffile numpy && python core/tests/validate_dng.py out/
./build/lumen_cli merge --profile vivid --dng merged.dng out.ppm burst_*.dng   # your own uncompressed DNGs
```

### 2. AI models (once, on a PC or Google Colab)

```bash
pip install torch onnx onnxruntime numpy
python tools/export_models.py --out models
cp models/*.onnx android/app/src/main/assets/models/
# iOS: add --coreml to the export, then for each model:
# xcrun coremlcompiler compile models/<name>.mlpackage ios/LumenCamera/Resources/Models/
```

The script downloads the official FFDNet and Real-ESRGAN weights. It exports fixed-tile ONNX graphs that the Neural Engine and NNAPI can run whole, and checks them against PyTorch. Without the models the apps still work; the AI switches simply have no effect.

### 3. Android

Open `android/` in Android Studio (AGP 8.13, Gradle 8.14.3). Install **NDK** and **CMake 3.22.1** from the SDK Manager, then run on a device (Android 10+; Android 16 unlocks the priority-AE and CCT white-balance keys).

### 4. iOS

```bash
brew install xcodegen
cd ios && xcodegen            # generates LumenCamera.xcodeproj
open LumenCamera.xcodeproj    # set your Team under Signing, run on an iPhone
```

You need **Xcode 26** (iOS 26 SDK). The app runs on iOS 17+; features that need a newer iOS are guarded at runtime. For AI on iOS, run the export with `--coreml` and compile the models into `ios/LumenCamera/Resources/Models/` with `xcrun coremlcompiler compile <model>.mlpackage ios/LumenCamera/Resources/Models/`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the roadmap, how to run the core tests without a phone, and good first tasks. Bug reports and feature ideas go in [Issues](https://github.com/jeshiomurmu/lumen-camera/issues/new/choose).

## Design decisions

- **Original UI.** The layout follows flagship camera conventions, but it uses no Samsung (or other) icons, artwork, fonts or trademarked feature names.
- **No root, Xposed or HAL patches.** These are device-specific, unverifiable from source, and can brick a phone or break banking/DRM attestation. The app gets the most each phone allows through public APIs. Those limits are set by the vendor.
- **No thermal bypass.** Throttling protects the battery and the sensor. The app warns you and stops a recording before the system has to.
- **No private iOS APIs.** They break between iOS versions and would get the app rejected.

## Credits

- HDR+ burst pipeline: Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile cameras*, SIGGRAPH Asia 2016 (re-implemented).
- Demosaic: Malvar, He & Cutler 2004. Exposure fusion: Mertens, Kautz & Van Reeth 2007. Guided filter: He, Sun & Tang.
- Planckian locus: Kim et al. Star matching: triangle-invariant matching after Valdes et al. (the idea behind *astroalign*). Auto-stretch: PixInsight-style midtone transfer.
- AI models: **FFDNet** (Kai Zhang et al., KAIR, MIT) and **Real-ESRGAN general-x4v3** (Xintao Wang et al., BSD-3-Clause), run with **ONNX Runtime** (MIT).

Lumen Camera is licensed under Apache-2.0 (see `LICENSE`). Third-party notices are in `NOTICE`.
