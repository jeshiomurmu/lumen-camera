# Getting the most out of the camera hardware

Lumen reaches every capability that the phone vendor exposes through public APIs. This page maps common "unlock" requests to what the app does, and explains where the limit is the vendor's and not the app's.

## What the app uses

| Wish | Android | iOS |
|---|---|---|
| Manual ISO / shutter / focus / WB | Full `SENSOR_INFO_SENSITIVITY_RANGE` / `EXPOSURE_TIME_RANGE` (the longest shutter varies by phone; the app reads it), lens diopters, CCT + tint (Android 16) or gains + CCM | `minISO…maxISO`, `min…maxExposureDuration` (about a second at most on iPhones; the app reads the exact value), lens position, Kelvin + tint via device gains |
| Full-sensor photos | `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` (50 / 200 MP RAW + JPEG where offered) | 48 MP via `maxPhotoDimensions` and ProRAW |
| RAW | `RAW_SENSOR` / `RAW10` → DNG; merged bursts are 16-bit | Bayer RAW (12 MP), ProRAW (48 MP) |
| Highest video modes | Every size × fps the camera advertises, including 4:3 open gate, DCI 4K, 8K where offered, and 120/240 fps constrained high-speed | Every format the device advertises, including 4:3 open-gate and high-frame-rate formats on Pro models |
| Bitrate | Up to the encoder's reported maximum (shown in Settings) | `AVVideoAverageBitRateKey` where iOS accepts it; ProRes is intra-frame anyway |
| 10-bit / Log | HLG10 dynamic-range profile (API 33+); Lumen Log tone curve + `.cube` LUT | HLG, Apple Log, Apple Log 2 (iOS 26), ProRes 422 HQ / ProRes RAW |
| Pro codecs | APV 4:2:2 10-bit (Samsung, Android 16), AV1 where hardware exists | ProRes, ProRes RAW |
| Multi-frame night / HDR | Lumen burst merge, plus the OEM's own extensions (Night, HDR, Bokeh, Auto, Face retouch) | Lumen burst merge, plus Apple's Photonic Engine / Deep Fusion |
| Astrophotography | Stacks any number of exposures, each up to the sensor maximum | Stacks any number of exposures, each up to the per-frame limit iOS sets |

The in-app **Hardware report** lists exactly what your phone exposes.

## What the app deliberately does not do

**Root, Magisk/Xposed modules, Shizuku camera hacks, HAL or `build.prop` patches.** Tools that "unlock 4K60, 50 MP or 60 s shutters" work by editing vendor files or hooking the camera service on one specific firmware. They cannot be verified from source. They break on updates, can boot-loop or brick a phone, and trip integrity checks used by banking and DRM apps. When a vendor hides a mode, it is usually because that mode is unstable or overheats on that hardware.

**Disabling thermal throttling.** Throttling protects the battery, the image sensor and you. Lumen shows a warning when the phone gets warm, and stops a recording at the severe/critical level before the system has to.

**Private iOS APIs.** They change without notice between iOS versions, can crash the camera daemon, and get an app rejected from the App Store. Everything the iOS app does uses public AVFoundation, Core Image and Photos APIs.

## Practical tips for maximum quality

- **Sharpest photos:** Lumen engine with RAW on. Hold still for the ~0.5 s burst. Merged DNGs have far less noise than single RAW frames.
- **Night:** turn on Night (moon icon) when it lights up. Brace the phone; frames up to 1/8 s are aligned.
- **Milky Way:** use a tripod and Astro mode, 60 frames at the longest exposure, ISO 1600–3200. Take 5–10 dark frames with the lens covered, and keep *Align stars* on. Choose *Star trails* for circular trails.
- **Video for grading:** 10-bit Log (Apple Log / Lumen Log), the highest bitrate, and the LUT (`luts/LumenLog_to_Rec709.cube` for Android footage; Apple publishes the Apple Log LUTs).
- **Heat:** high-bitrate 4K60/120 and 8K make phones hot quickly. Short clips, shade, and no case help more than any software setting.
