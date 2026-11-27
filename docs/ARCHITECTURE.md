# Architecture

```
            Android (Kotlin, Compose)                         iOS (Swift, SwiftUI)
 Camera2 ─ CameraEngine ─ PhotoPipeline ─┐       ┌─ PhotoPipeline ─ CameraService ─ AVFoundation
 MediaCodec ─ VideoRecorder              │       │                  AVCaptureMovieFileOutput
 ONNX Runtime + NNAPI ─ AiEngine         │       │  AiEngine ─ ONNX Runtime + Core ML
                                         ▼       ▼
                          JNI (lumen_jni.cpp)   bridging header
                                         │       │
                                  core/include/lumen_core.h  (plain C API)
                                         │
                core/src: raw · align · merge · finish · color · astro · dng · lut
```

The apps capture RAW frames and pass them to the core. The core returns RGBA images and DNG files. Everything platform-specific stays in the apps: sessions, encoders, permissions, the photo library and UI.

## Conventions

- CFA position `k = (y & 1) * 2 + (x & 1)`. Normalised signal is `x = (raw − black[k]) / (white − black[k])`.
- Noise model: `variance = S·x + O`, matching Android's `SENSOR_NOISE_PROFILE` and DNG `NoiseProfile`. If the stream gives none, it is estimated from 8×8 block residuals (10th percentile, robust line fit).
- Matrices are row-major. Colour flows camera → XYZ (D50) → sRGB or Display P3.

## Burst merge (HDR+ style)

1. **Exposure plan** (apps). Normal photos use exposures of ≤ 1/30 s and underexpose about 1 EV to protect highlights, with 6, 8 or 10 frames depending on scene brightness. Night mode uses up to 1/8 s with OIS and twice the total exposure, over 8–15 frames.
2. **Reference frame.** The sharpest of the first three frames (Laplacian variance).
3. **Alignment.** The four Bayer planes are averaged into a half-resolution grey image. A Gaussian pyramid is searched per 16×16 tile (stride 8): L2 distance at the coarse levels, L1 at the finest, with ±4 px search per level and three coarse candidates. Offsets are integers, so the CFA phase is kept.
4. **Merge.** Each plane is merged with a streaming, frequency-domain pairwise Wiener filter: `A = |D|² / (|D|² + c·σ²·Σw²)`, with Hann-window overlap-add. Pixels that differ beyond the noise model fall back to the reference, so moving objects do not ghost. An optional spatial Wiener denoise follows.
5. The merged mosaic is kept in float and can be written as a 16-bit DNG with a 1024 pedestal.

## Finishing

White balance with highlight-safe clipping → Malvar-He-Cutler demosaic with CFA-parity mirror borders → camera-to-output matrix → local tone mapping (Mertens fusion of 2–3 synthetic exposures at half resolution, with the gain map upsampled by a log-domain guided filter) → contrast, saturation and gamut/highlight desaturation → chroma noise reduction and noise-aware sharpening. Profiles are Natural, Vivid, Film, Mono and Night. The core also reports the residual noise σ in display units; the apps pass it to the AI denoiser.

## Colour science

DNG ColorMatrix and ForwardMatrix pairs are interpolated by inverse CCT. The Planckian locus follows Kim et al.; tint is Δuv = −tint/3000 (the Adobe convention). Chromatic adaptation is Bradford. Android passes the ISP's AWB gains and colour-correction matrix when white balance is automatic. On iOS the matrices come from the DNG that iOS attaches to every Bayer RAW photo. Manual Kelvin always goes through the core.

## Astro stacking

For each light frame: a background map is built from 32 px cells, stars are detected at 5σ local maxima (MAD noise) and centroided, and spikes are rejected. Stars are then matched to the reference by triangle invariants (6 nearest neighbours, side-ratio pairs). A 2D similarity is found with RANSAC (scale 0.9–1.1) and refined by least squares. Each CFA plane is warped bicubically. Frames are combined by kappa-sigma clip, median, mean or maximum (star trails); clip and median keep a uint16 file cache so memory stays flat. Darks are subtracted, and hot pixels are fixed where an outlier appears in one CFA plane only. Borders are filled from the nearest well-covered pixel. A degree-2 polynomial gradient is removed. Rendering neutralises the sky and applies a PixInsight-style midtone-transfer auto-stretch.

## AI tiling contract

`tools/export_models.py` exports **fixed-shape** graphs: denoise at 256×256 and SR at 192×192 input. They contain only Conv, ConvTranspose, PRelu, Relu, Mul, Concat and Add. The pixel (un)shuffles are one-hot strided convolutions, which is numerically exact. Core ML and NNAPI can then take the whole graph.

The image is cut into cores of `tile − 2·overlap` pixels. Every call receives exactly `tile × tile` pixels: the core plus `overlap` pixels of context, with coordinates clamped to the image edge. Each output pixel comes from the one tile whose core contains it. Since overlap ≥ receptive-field radius, the result equals full-image inference on the edge-extended image; the export script asserts this. Kotlin (`AiEngine.kt`) and Swift (`AiEngine.swift`) implement the same contract.

## Android app

- `CameraCatalog` lists every logical, physical and hidden camera, with its sizes, max-resolution maps, video modes (16:9, DCI, 4:3 open gate, high speed), colour matrices and capabilities.
- `CameraEngine` owns sessions (photo + RAW, video, constrained high-speed, extensions) and turns `Controls` into capture requests: manual exposure, Android 16 priority AE, CCT white balance, OIS, NR/edge modes, the Log tone curve and the pixel mode.
- `PhotoPipeline` streams RAW frames to the native merger on a consumer coroutine. `VideoRecorder` drives MediaCodec + AudioRecord + MediaMuxer, choosing the encoder from `EncoderCatalog`.
- The UI is Compose and the state lives in `CameraViewModel`. Session reconfiguration is serialised with a mutex.

## iOS app

- **Photo modes** use one *physical* lens at a time, because Bayer RAW is only available on single-camera devices. The lens pills switch devices; pinch zoom crosses lenses automatically. **Video and Portrait** use the virtual multi-camera, for seamless zoom and depth.
- `CameraService` (session queue): configuration, manual controls, bracketed Bayer RAW bursts (`AVCapturePhotoBracketSettings` at an exact exposure), movie recording with codec, bitrate and PCM settings, the rotation coordinator, and Camera Control sliders.
- `PhotoPipeline` copies each RAW frame out of its pixel buffer and merges it on a work queue while the next bracket is captured. Results are written to Photos as HEIC with RAW pairs where requested.
- Portrait uses Core Image's depth blur. The depth map and the person matte stay embedded in the file.
- `FrameAnalyzer` samples the native YUV stream (8- or 10-bit) for the scopes. The overlays are produced already rotated for the portrait viewfinder.
