# Lumen Camera

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
cp models/*.onnx ios/LumenCamera/Resources/Models/
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

## What was verified, and what needs your hardware

- **Imaging core:** builds warning-free; **82/82 tests pass** on a physically modelled synthetic camera (noise, optics blur, hand shake, moving objects, stars, hot pixels, a satellite trail). For example, an 8-frame low-light burst goes from 31.7 dB to 37.1 dB PSNR. Ghosting error is 0.007 against 0.055 for naive averaging. Star rotation is recovered within 0.002°, and stacking lowers sky noise 4.2×. LibRaw reads all written DNGs.
- **AI export:** ONNX vs PyTorch max error 1e-7. Tiled inference equals full-image inference (max error 6e-8). The Kotlin tiler was run against reference models and gave exact output.
- **Android:** the Kotlin sources were checked with `kotlinc`. The AI module was compiled against API stubs. The JNI layer was compiled against stub headers. The full app was **not built here**: there is no Android SDK in this environment.
- **iOS:** all Swift files pass a syntax check. The newer iOS 18 / iOS 26 API names (Camera Control, capture events, deferred start, ProRes RAW, Apple Log 2) were checked against Apple's documentation. The app was **not compiled here**: Xcode needs a Mac.
- **Not yet done:** real-device runs. Camera behaviour differs between phones, so expect small fixes on first build. Every capability is probed at runtime (see the in-app *Hardware report*), so unsupported options are hidden or reported instead of crashing.

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
