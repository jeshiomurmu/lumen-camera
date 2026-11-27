# Contributing to Lumen Camera

Thanks for helping! Lumen is a pro camera app for Android and iOS built on one shared C++ imaging core. The fastest ways to help:

1. **Test on your phone.** Install the latest [release](https://github.com/jeshiomurmu/lumen-camera/releases/latest), then open a *Device report* issue with your phone model and what the in-app **Settings → Hardware report** shows. Real-device reports are the most valuable contribution right now.
2. **Send sample RAW bursts.** Uncompressed DNG bursts (night, motion, high contrast) help tune the merge. `core/tools/lumen_cli merge` runs the exact phone pipeline on a PC.
3. **Pick a task** from the roadmap below or from issues labelled `good first issue`.

## Project layout

| Folder | What | Language |
|---|---|---|
| `core/` | Burst align + merge, finishing, colour, astro stacking, DNG | C++17 (plain C API) |
| `android/` | Camera2 + Compose app, JNI bridge | Kotlin |
| `ios/` | AVFoundation + SwiftUI app (XcodeGen) | Swift |
| `tools/` | AI model export (FFDNet, Real-ESRGAN → ONNX / Core ML) | Python |

## Working on the core (no phone needed)

```bash
cmake -S core -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
./build/lumen_tests out/        # must stay at 0 failures
```

The tests run on a physically modelled synthetic camera, so improvements can be measured (PSNR, ghosting, star registration). Please include before/after numbers in your PR.

## Pull requests

- Keep PRs focused; one feature or fix each.
- CI builds the APK and IPA and runs the core tests on an Android emulator and an iOS Simulator for every push. Check that it's green.
- Only public platform APIs: no root, no private iOS APIs, no thermal-limit bypass (see `docs/HARDWARE_UNLOCKS.md`).
- New dependencies must be open source with a permissive licence.

## Roadmap (help wanted)

- [ ] Real-device tuning: exposure plans and noise models per phone
- [ ] Android: AI models on GPU/NPU through QNN or LiteRT delegates, benchmark vs NNAPI
- [ ] iOS: Lumen Log for the Bayer path, ProRes RAW metadata
- [ ] Core: sub-pixel alignment for super-resolution bursts (Wronski et al. 2019)
- [ ] Core: motion-adaptive frame count; HDR bracketing with exposure fusion
- [ ] UI: translations (Bengali, Hindi, Spanish, …), accessibility labels, tablet layout
- [ ] Gallery: in-app RAW editor using the core's finishing pipeline
- [ ] F-Droid / IzzyOnDroid packaging and reproducible builds

By contributing you agree that your contributions are licensed under the Apache-2.0 licence.
