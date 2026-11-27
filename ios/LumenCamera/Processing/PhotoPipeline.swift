import AVFoundation
import CoreImage
import ImageIO
import UIKit
import UniformTypeIdentifiers

struct CaptureOutput {
    var thumbnail: UIImage?
    var preview: UIImage?
    var message: String
}

struct BurstPlan {
    let frames: Int
    let exposure: Double   // seconds
    let iso: Float
}

struct AstroProgress {
    let frame: Int
    let total: Int
    let text: String
    let preview: UIImage?
}

/// A Bayer frame copied out of an AVCapturePhoto (tightly packed uint16).
struct BayerFrame {
    let width: Int
    let height: Int
    let cfa: Int32   // -1 = unknown (use the DNG's CFAPattern)
    let data: [UInt16]
}

/// Shooting context captured on the main actor when the shutter is pressed.
struct ShotContext {
    var settings: AppSettings
    var angle: CGFloat            // capture rotation (0 / 90 / 180 / 270)
    var mirrored: Bool
    var zoom: CGFloat             // digital zoom on the physical lens (RAW is never cropped by iOS)
    var kelvin: Float?
    var tint: Float
    var aspect: PhotoAspect
}

/**
 Photo pipelines:
  - Apple: Photonic Engine / Deep Fusion / Smart HDR (+ ProRAW or Bayer RAW pair, portrait depth blur)
  - Lumen burst: Bayer RAW bracket -> align + robust merge -> finishing -> AI -> HEIC (+ DNG)
  - Pro RAW: one Bayer RAW -> original DNG + Lumen-rendered HEIC
  - Astro: long exposures -> star-aligned stack -> stretch -> HEIC + DNG
 */
final class PhotoPipeline {
    let camera: CameraService
    let ai: AiEngine
    private let workQueue = DispatchQueue(label: "app.lumen.merge", qos: .userInitiated)

    init(camera: CameraService, ai: AiEngine) {
        self.camera = camera
        self.ai = ai
    }

    // MARK: - helpers

    static func cfa(for format: OSType) -> Int32 {
        switch format {
        case kCVPixelFormatType_14Bayer_RGGB: return Int32(LUMEN_CFA_RGGB.rawValue)
        case kCVPixelFormatType_14Bayer_GRBG: return Int32(LUMEN_CFA_GRBG.rawValue)
        case kCVPixelFormatType_14Bayer_GBRG: return Int32(LUMEN_CFA_GBRG.rawValue)
        case kCVPixelFormatType_14Bayer_BGGR: return Int32(LUMEN_CFA_BGGR.rawValue)
        default: return -1
        }
    }

    static func copyBayer(_ photo: AVCapturePhoto) -> BayerFrame? {
        guard let pb = photo.pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let stride = CVPixelBufferGetBytesPerRow(pb)
        guard w > 0, h > 0, stride >= w * 2, let base = CVPixelBufferGetBaseAddress(pb) else { return nil }
        var data = [UInt16](repeating: 0, count: w * h)
        data.withUnsafeMutableBytes { dst in
            guard let d = dst.baseAddress else { return }
            for y in 0..<h { memcpy(d + y * w * 2, base + y * stride, w * 2) }
        }
        return BayerFrame(width: w, height: h, cfa: cfa(for: CVPixelBufferGetPixelFormatType(pb)), data: data)
    }

    /// Stream description from the frame and the DNG metadata iOS attaches to it.
    static func rawSpec(_ f: BayerFrame, dng: LumenDngInfo?) -> RawSpec {
        if let d = dng, d.raw.white_level > 0 {
            let hasNoise = d.has_noise_profile != 0
            return RawSpec(width: f.width, height: f.height, rowStride: f.width * 2,
                           cfa: f.cfa >= 0 ? f.cfa : d.raw.cfa,
                           black: CTuple.floats(d.raw.black_level), white: d.raw.white_level,
                           noiseScale: hasNoise ? CTuple.floats(d.raw.noise_scale) : [0, 0, 0, 0],
                           noiseOffset: hasNoise ? CTuple.floats(d.raw.noise_offset) : [0, 0, 0, 0])
        }
        // No readable DNG: typical iPhone levels; the noise model is estimated by the core.
        let maxV = f.data.max() ?? 4095
        let white: Float = maxV > 4095 ? 16383 : 4095
        let black = (white + 1) * 528 / 4096
        return RawSpec(width: f.width, height: f.height, rowStride: f.width * 2, cfa: max(0, f.cfa),
                       black: [black, black, black, black], white: white,
                       noiseScale: [0, 0, 0, 0], noiseOffset: [0, 0, 0, 0])
    }

    static func exifOrientation(angle: CGFloat, mirrored: Bool) -> UInt32 {
        switch ((Int(angle.rounded()) % 360) + 360) % 360 {
        case 90: return mirrored ? 5 : 6
        case 180: return mirrored ? 4 : 3
        case 270: return mirrored ? 7 : 8
        default: return mirrored ? 2 : 1
        }
    }

    static func uiOrientation(_ exif: UInt32) -> UIImage.Orientation {
        switch exif {
        case 2: return .upMirrored
        case 3: return .down
        case 4: return .downMirrored
        case 5: return .leftMirrored
        case 6: return .right
        case 7: return .rightMirrored
        case 8: return .left
        default: return .up
        }
    }

    static func resize(_ img: CGImage, width: Int, height: Int) -> CGImage? {
        let cs = img.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: width, height: height))
        return ctx.makeImage()
    }

    static func downscaled(_ img: CGImage, maxSide: Int) -> CGImage {
        let s = Double(maxSide) / Double(max(img.width, img.height))
        guard s < 1 else { return img }
        return resize(img, width: max(1, Int(Double(img.width) * s)), height: max(1, Int(Double(img.height) * s))) ?? img
    }

    /// Centre crop in sensor (landscape) pixel space: 4:3 (none), 16:9 or 1:1.
    static func cropAspect(_ img: CGImage, _ aspect: PhotoAspect) -> CGImage {
        let target = aspect.sensorRatio
        let w = CGFloat(img.width), h = CGFloat(img.height)
        let cur = max(w, h) / min(w, h)
        guard abs(cur - target) > 0.01 else { return img }
        var rect: CGRect
        if w >= h {
            rect = target > cur ? CGRect(x: 0, y: 0, width: w, height: (w / target).rounded())
                                : CGRect(x: 0, y: 0, width: (h * target).rounded(), height: h)
        } else {
            rect = target > cur ? CGRect(x: 0, y: 0, width: (h / target).rounded(), height: h)
                                : CGRect(x: 0, y: 0, width: w, height: (w * target).rounded())
        }
        rect.origin = CGPoint(x: ((w - rect.width) / 2).rounded(), y: ((h - rect.height) / 2).rounded())
        return img.cropping(to: rect) ?? img
    }

    /// Digital zoom on the rendered RAW, with optional AI super-resolution.
    func cropZoom(_ img: CGImage, factor: CGFloat, useAI: Bool, progress: (Float) -> Void) -> CGImage {
        let cw = Int(CGFloat(img.width) / factor) & ~1, ch = Int(CGFloat(img.height) / factor) & ~1
        guard cw >= 64, ch >= 64,
              let crop = img.cropping(to: CGRect(x: (img.width - cw) / 2, y: (img.height - ch) / 2, width: cw, height: ch))
        else { return img }
        var src = crop
        if useAI, factor >= 1.8, cw * ch * 16 <= 80_000_000, let up = try? ai.upscale4x(crop, progress: progress) {
            src = up
        }
        return Self.resize(src, width: img.width, height: img.height) ?? img
    }

    private static func dngMeta(_ metadata: [String: Any], orientation: UInt32, exposure: Float?, iso: Int32?) -> DngMetaSpec {
        let tiff = metadata[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        let exif = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        var m = DngMetaSpec()
        m.make = tiff[kCGImagePropertyTIFFMake as String] as? String ?? "Apple"
        m.model = tiff[kCGImagePropertyTIFFModel as String] as? String ?? "iPhone"
        m.orientation = Int32(orientation)
        m.exposureTime = exposure ?? Float(exif[kCGImagePropertyExifExposureTime as String] as? Double ?? 0)
        m.fNumber = Float(exif[kCGImagePropertyExifFNumber as String] as? Double ?? 0)
        m.focalLength = Float(exif[kCGImagePropertyExifFocalLength as String] as? Double ?? 0)
        let isoList = exif[kCGImagePropertyExifISOSpeedRatings as String] as? [Int]
        m.iso = iso ?? Int32(isoList?.first ?? 0)
        return m
    }

    private func finishAndSave(_ image: CGImage, ctx: ShotContext, metadata: [String: Any], comment: String,
                               raw: Data?, prefix: String) async throws -> CaptureOutput {
        let img = Self.cropAspect(image, ctx.aspect)
        let orientation = Self.exifOrientation(angle: ctx.angle, mirrored: ctx.mirrored)
        var exif = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        exif[kCGImagePropertyExifUserComment as String] = comment
        exif[kCGImagePropertyExifPixelXDimension as String] = img.width
        exif[kCGImagePropertyExifPixelYDimension as String] = img.height
        var tiff = metadata[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        tiff[kCGImagePropertyTIFFSoftware as String] = "Lumen Camera \(String(cString: lumen_core_version()))"
        tiff[kCGImagePropertyTIFFOrientation as String] = orientation
        guard let encoded = PhotoLibrary.encode(img, orientation: orientation, exif: exif, tiff: tiff,
                                                quality: ctx.settings.heicQuality) else {
            throw CameraError.captureFailed("encoding failed")
        }
        try await PhotoLibrary.save(photo: encoded.0, uti: encoded.1, raw: raw)
        let o = Self.uiOrientation(orientation)
        let preview = UIImage(cgImage: Self.downscaled(img, maxSide: 2048), scale: 1, orientation: o)
        let thumb = UIImage(cgImage: Self.downscaled(img, maxSide: 256), scale: 1, orientation: o)
        return CaptureOutput(thumbnail: thumb, preview: preview, message: prefix)
    }

    // MARK: - Apple pipeline

    func apple(ctx: ShotContext, flash: FlashMode, mode: CaptureMode, aperture: Float) async throws -> CaptureOutput {
        let out = camera.photoOutput
        let codec: AVVideoCodecType = out.availablePhotoCodecTypes.contains(.hevc) ? .hevc : .jpeg
        let processed: [String: Any] = [AVVideoCodecKey: codec]
        let s: AVCapturePhotoSettings
        var bayer = false
        if ctx.settings.photoEngine == .proRAW, out.isAppleProRAWEnabled, let raw = camera.proRAWFormat {
            s = AVCapturePhotoSettings(rawPixelFormatType: raw, processedFormat: processed)
            s.maxPhotoDimensions = out.maxPhotoDimensions
            s.photoQualityPrioritization = .quality
        } else if ctx.settings.saveRaw, mode != .portrait, let raw = camera.bayerFormat {
            s = AVCapturePhotoSettings(rawPixelFormatType: raw, processedFormat: processed)
            s.photoQualityPrioritization = .speed   // Bayer RAW pairs are not fused
            bayer = true
        } else {
            s = AVCapturePhotoSettings(format: processed)
            s.maxPhotoDimensions = out.maxPhotoDimensions
            s.photoQualityPrioritization = .quality
        }
        if bayer, let d = camera.device {
            let small = d.activeFormat.supportedMaxPhotoDimensions.filter { CameraService.area($0) <= 13_000_000 }
                .max { CameraService.area($0) < CameraService.area($1) }
            if let small { s.maxPhotoDimensions = small }
        }
        let fm: AVCaptureDevice.FlashMode = flash == .on ? .on : (flash == .auto ? .auto : .off)
        if out.supportedFlashModes.contains(fm) { s.flashMode = fm }
        if mode == .portrait, out.isDepthDataDeliveryEnabled {
            s.isDepthDataDeliveryEnabled = true
            s.embedsDepthDataInPhoto = true
            if out.isPortraitEffectsMatteDeliveryEnabled {
                s.isPortraitEffectsMatteDeliveryEnabled = true
                s.embedsPortraitEffectsMatteInPhoto = true
            }
        }
        let photos = try await camera.capture(s)
        guard let main = photos.first(where: { !$0.isRawPhoto }) ?? photos.first,
              let data = main.fileDataRepresentation() else { throw CameraError.captureFailed("no photo data") }
        let rawData = photos.first(where: { $0.isRawPhoto })?.fileDataRepresentation()

        // Portrait: depth-of-field from the depth map + person matte (Core Image).
        if mode == .portrait, let rendered = renderPortrait(data, aperture: aperture) {
            try await PhotoLibrary.save(photo: rendered, uti: .heic, raw: nil)
            return Self.outputFromEncoded(rendered, message: String(format: "Portrait · ƒ%.1f", aperture))
        }
        if ctx.aspect != .r4x3, let cropped = Self.recropEncoded(data, ctx.aspect, quality: ctx.settings.heicQuality) {
            try await PhotoLibrary.save(photo: cropped, uti: .heic, raw: rawData)
            return Self.outputFromEncoded(cropped, message: "Saved")
        }
        try await PhotoLibrary.save(photo: data, uti: codec == .hevc ? .heic : .jpeg, raw: rawData)
        let label = ctx.settings.photoEngine == .proRAW && rawData != nil ? "Saved ProRAW + HEIC" :
            (rawData != nil ? "Saved RAW + HEIC" : "Saved")
        return Self.outputFromEncoded(data, message: label)
    }

    static func outputFromEncoded(_ data: Data, message: String) -> CaptureOutput {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return CaptureOutput(message: message) }
        func thumb(_ side: Int) -> UIImage? {
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceCreateThumbnailWithTransform: true,
                                         kCGImageSourceThumbnailMaxPixelSize: side]
            return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary).map { UIImage(cgImage: $0) }
        }
        return CaptureOutput(thumbnail: thumb(256), preview: thumb(2048), message: message)
    }

    /// Re-encodes an Apple photo with an aspect crop, keeping its metadata.
    static func recropEncoded(_ data: Data, _ aspect: PhotoAspect, quality: Double) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        let props = (CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any]) ?? [:]
        let cropped = cropAspect(img, aspect)
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else { return nil }
        var p = props
        p[kCGImageDestinationLossyCompressionQuality as String] = quality
        p[kCGImagePropertyPixelWidth as String] = cropped.width
        p[kCGImagePropertyPixelHeight as String] = cropped.height
        CGImageDestinationAddImage(dest, cropped, p as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }

    /// Applies Core Image's depth blur (aperture in f-stops) and keeps the depth
    /// map + matte in the file so the effect can be edited again later.
    func renderPortrait(_ data: Data, aperture: Float) -> Data? {
        let context = CIContext()
        guard let filter = context.depthBlurEffectFilter(forImageData: data, options: nil),
              let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        filter.setValue(aperture, forKey: "inputAperture")
        guard let outImage = filter.outputImage,
              let cs = CGColorSpace(name: CGColorSpace.displayP3),
              let cg = context.createCGImage(outImage, from: outImage.extent, format: .RGBA8, colorSpace: cs) else { return nil }
        let props = (CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any]) ?? [:]
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        for type in [kCGImageAuxiliaryDataTypeDisparity, kCGImageAuxiliaryDataTypeDepth, kCGImageAuxiliaryDataTypePortraitEffectsMatte] {
            if let info = CGImageSourceCopyAuxiliaryDataInfoAtIndex(src, 0, type) {
                CGImageDestinationAddAuxiliaryDataInfo(dest, type, info)
            }
        }
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }

    // MARK: - Lumen burst

    /// HDR+-style exposure plan from the live auto-exposure.
    func planBurst(night: Bool, settings: AppSettings, manualISO: Float?, manualShutter: Double?) -> BurstPlan {
        guard let d = camera.device else { return BurstPlan(frames: 6, exposure: 1.0 / 60, iso: 200) }
        let f = d.activeFormat
        let t0 = d.exposureDuration.seconds > 0 ? d.exposureDuration.seconds : 1.0 / 60
        let iso0 = max(d.iso, f.minISO)
        let tMin = f.minExposureDuration.seconds, tMax = f.maxExposureDuration.seconds
        func clampT(_ t: Double) -> Double { min(max(t, tMin), tMax) }
        func clampISO(_ v: Double) -> Float { min(max(Float(v), f.minISO), f.maxISO) }
        if manualISO != nil || manualShutter != nil {
            let t = clampT(manualShutter ?? t0)
            let iso = manualISO ?? clampISO(Double(iso0) * t0 / t)
            let n = settings.burstFrames > 0 ? settings.burstFrames : (t > 0.25 ? 3 : 6)
            return BurstPlan(frames: n, exposure: t, iso: iso)
        }
        let n = Double(d.lensAperture)
        let ev = log2(n * n / t0) - log2(Double(iso0) / 100)
        if night {
            // brighter total exposure, longer frames (OIS), capped for hand-holding
            let target = t0 * Double(iso0) * 2
            let handheld = d.deviceType == .builtInUltraWideCamera ? 0.066 : 0.125
            let t = clampT(min(handheld, max(t0 * 2, 0.033)))
            let frames = settings.burstFrames > 0 ? settings.burstFrames : min(15, max(8, Int((1.2 / t).rounded(.up))))
            return BurstPlan(frames: frames, exposure: t, iso: clampISO(target / t))
        }
        // HDR+: underexpose ~1 EV to protect highlights; merging restores the shadows
        let t = clampT(min(t0, 0.033))
        let frames = settings.burstFrames > 0 ? settings.burstFrames : (ev >= 10 ? 6 : (ev >= 6 ? 8 : 10))
        return BurstPlan(frames: frames, exposure: t, iso: clampISO(Double(iso0) * t0 / t / 2))
    }

    private final class BurstState {
        var burst: BurstMerger?
        var spec: RawSpec?
        var dng: LumenDngInfo?
        var metadata: [String: Any] = [:]
        var captured = 0
        var merged = 0
    }

    func lumenBurst(plan: BurstPlan, night: Bool, ctx: ShotContext, progress: @escaping (String, Float) -> Void) async throws -> CaptureOutput {
        let st = BurstState()
        let group = DispatchGroup()
        let work = workQueue
        try await camera.captureBayer(count: plan.frames, exposure: plan.exposure, iso: plan.iso) { photo in
            guard let frame = Self.copyBayer(photo) else { return }
            if st.burst == nil {
                let dngData = photo.fileDataRepresentation()
                st.dng = dngData.flatMap { LumenDNG.info($0) }
                st.metadata = photo.metadata
                let spec = Self.rawSpec(frame, dng: st.dng)
                st.spec = spec
                st.burst = BurstMerger(spec: spec, robustness: night ? 12 : 10, spatialDenoise: night ? 0.45 : 0.25, referenceMode: 0)
            }
            st.captured += 1
            progress("Capturing \(st.captured)/\(plan.frames)", 0.1 * Float(st.captured) / Float(plan.frames))
            guard let burst = st.burst, frame.width == burst.spec.width, frame.height == burst.spec.height else { return }
            group.enter()
            work.async {
                frame.data.withUnsafeBufferPointer { p in
                    if let b = p.baseAddress { burst.add(b) }
                }
                st.merged += 1
                progress("Merging \(st.merged)/\(plan.frames)", 0.1 + 0.55 * Float(st.merged) / Float(plan.frames))
                group.leave()
            }
        }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in group.notify(queue: work) { c.resume() } }
        guard let burst = st.burst, let spec = st.spec else { throw CameraError.captureFailed("No frames captured") }
        guard let stats = burst.finalize() else { throw CameraError.captureFailed("Merge failed") }
        progress("Finishing", 0.7)

        var color = st.dng.map { ColorSpec(dng: $0) } ?? ColorSpec()
        if let k = ctx.kelvin { color = color.withKelvin(k, tint: ctx.tint) }
        let natural = Int32(LUMEN_PROFILE_NATURAL.rawValue)
        let profile = night && ctx.settings.profile == natural ? Int32(LUMEN_PROFILE_NIGHT.rawValue) : ctx.settings.profile
        let fin = FinishSpec.profile(profile, p3: ctx.settings.displayP3, half: spec.width * spec.height > 60_000_000)
        guard var image = burst.render(color: color, finish: fin) else { throw CameraError.captureFailed("Render failed") }
        let sigma = burst.displaySigma
        if ctx.settings.aiDenoise, ai.hasDenoiser, sigma > 0.008 {
            progress("AI denoise", 0.75)
            if let d = try? ai.denoise(image, sigma: sigma * 1.2, progress: { p in progress("AI denoise", 0.75 + 0.1 * p) }) {
                image = d
            }
        }
        if ctx.zoom > 1.05 {
            image = cropZoom(image, factor: ctx.zoom, useAI: ctx.settings.aiZoom && ai.hasUpscaler) { p in
                progress("AI zoom", 0.85 + 0.1 * p)
            }
        }
        var raw: Data?
        let orientation = Self.exifOrientation(angle: ctx.angle, mirrored: ctx.mirrored)
        if ctx.settings.saveRaw {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("LUMEN_\(UUID().uuidString).dng")
            let meta = Self.dngMeta(st.metadata, orientation: orientation, exposure: Float(plan.exposure), iso: Int32(plan.iso))
            if burst.writeDNG(color: color, meta: meta, to: url) { raw = try? Data(contentsOf: url) }
            try? FileManager.default.removeItem(at: url)
        }
        progress("Saving", 0.97)
        return try await finishAndSave(image, ctx: ctx, metadata: st.metadata,
                                       comment: "Lumen burst: \(stats.frames_merged) frames", raw: raw,
                                       prefix: "Merged \(stats.frames_merged) frames" + (night ? " · Night" : ""))
    }

    // MARK: - Pro: single RAW (original DNG + Lumen render)

    func proRaw(ctx: ShotContext) async throws -> CaptureOutput {
        guard let fmt = camera.bayerFormat else { throw CameraError.noRaw }
        let s = AVCapturePhotoSettings(rawPixelFormatType: fmt)
        s.photoQualityPrioritization = .speed
        let photos = try await camera.capture(s)
        guard let p = photos.first, let frame = Self.copyBayer(p) else { throw CameraError.noRaw }
        let dngData = p.fileDataRepresentation()
        let dng = dngData.flatMap { LumenDNG.info($0) }
        let spec = Self.rawSpec(frame, dng: dng)
        guard let burst = BurstMerger(spec: spec, robustness: 10, spatialDenoise: 0.4, referenceMode: 1) else {
            throw CameraError.captureFailed("out of memory")
        }
        frame.data.withUnsafeBufferPointer { b in if let a = b.baseAddress { burst.add(a) } }
        guard burst.finalize() != nil else { throw CameraError.captureFailed("render failed") }
        var color = dng.map { ColorSpec(dng: $0) } ?? ColorSpec()
        if let k = ctx.kelvin { color = color.withKelvin(k, tint: ctx.tint) }
        let fin = FinishSpec.profile(ctx.settings.profile, p3: ctx.settings.displayP3, half: false)
        guard var image = burst.render(color: color, finish: fin) else { throw CameraError.captureFailed("render failed") }
        let sigma = burst.displaySigma
        if ctx.settings.aiDenoise, ai.hasDenoiser, sigma > 0.008, let d = try? ai.denoise(image, sigma: sigma * 1.2) { image = d }
        if ctx.zoom > 1.05 { image = cropZoom(image, factor: ctx.zoom, useAI: ctx.settings.aiZoom && ai.hasUpscaler) { _ in } }
        return try await finishAndSave(image, ctx: ctx, metadata: p.metadata, comment: "Lumen single RAW",
                                       raw: dngData, prefix: "Saved DNG + HEIC")
    }

    // MARK: - Astro

    private final class AstroState {
        var astro: AstroStacker?
        var spec: RawSpec?
        var dng: LumenDngInfo?
        var metadata: [String: Any] = [:]
        var accepted = 0
        var lights = 0
        var darks = 0
    }

    func astro(ctx: ShotContext, onProgress: @escaping (AstroProgress) -> Void, cancelled: @escaping () -> Bool) async throws -> CaptureOutput {
        guard let d = camera.device else { throw CameraError.noDevice }
        let f = d.activeFormat
        let st = ctx.settings
        let exp = min(st.astroExposure, f.maxExposureDuration.seconds)
        let iso = min(max(st.astroISO, f.minISO), f.maxISO)
        let s = AstroState()
        let work = workQueue
        let group = DispatchGroup()
        let orientation = Self.exifOrientation(angle: ctx.angle, mirrored: ctx.mirrored)
        let uiO = Self.uiOrientation(orientation)
        let total = st.astroFrames

        func ensure(_ frame: BayerFrame, _ photo: AVCapturePhoto) {
            guard s.astro == nil else { return }
            let dngData = photo.fileDataRepresentation()
            s.dng = dngData.flatMap { LumenDNG.info($0) }
            s.metadata = photo.metadata
            let spec = Self.rawSpec(frame, dng: s.dng)
            s.spec = spec
            s.astro = AstroStacker(spec: spec, align: st.astroAlign, mode: st.astroStackMode, darkCount: st.astroDarkFrames)
        }
        func countdown(_ text: String) async throws {
            for i in stride(from: 3, to: 0, by: -1) {
                onProgress(AstroProgress(frame: 0, total: total, text: "\(text) \(i)…", preview: nil))
                try await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        let maxB = max(1, camera.photoOutput.maxBracketedCapturePhotoCount)

        if st.astroDarkFrames > 0 {
            try await countdown("Cover the lens — dark frames in")
            var left = st.astroDarkFrames
            while left > 0, !cancelled() {
                let n = min(maxB, left)
                try await camera.captureBayer(count: n, exposure: exp, iso: iso) { photo in
                    guard let frame = Self.copyBayer(photo) else { return }
                    ensure(frame, photo)
                    guard let a = s.astro else { return }
                    group.enter()
                    work.async {
                        frame.data.withUnsafeBufferPointer { p in if let b = p.baseAddress { _ = a.addDark(b) } }
                        s.darks += 1
                        onProgress(AstroProgress(frame: s.darks, total: st.astroDarkFrames,
                                                 text: "Dark frame \(s.darks)/\(st.astroDarkFrames) (lens covered)", preview: nil))
                        group.leave()
                    }
                }
                left -= n
            }
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in group.notify(queue: work) { c.resume() } }
            try await countdown("Uncover the lens — stacking starts in")
        }

        var left = total
        while left > 0, !cancelled() {
            let n = min(maxB, left)
            try await camera.captureBayer(count: n, exposure: exp, iso: iso) { photo in
                guard let frame = Self.copyBayer(photo) else { return }
                ensure(frame, photo)
                guard let a = s.astro, let spec = s.spec, frame.width == spec.width, frame.height == spec.height else { return }
                group.enter()
                work.async {
                    var rc: Int32 = LUMEN_ERR_INVALID_ARG
                    var stats = LumenAstroFrameStats()
                    frame.data.withUnsafeBufferPointer { p in
                        if let b = p.baseAddress { (rc, stats) = a.addLight(b) }
                    }
                    s.lights += 1
                    if rc == LUMEN_OK { s.accepted += 1 }
                    var preview: UIImage?
                    if s.accepted > 0 && (s.lights % 3 == 0 || s.lights == total) {
                        let color = s.dng.map { ColorSpec(dng: $0) } ?? ColorSpec()
                        if let cg = a.preview(color: color) { preview = UIImage(cgImage: cg, scale: 1, orientation: uiO) }
                    }
                    let text = rc == LUMEN_OK
                        ? String(format: "Frame %ld/%ld · %d stars · rot %.2f°", s.lights, total, stats.stars_matched, stats.rotation_deg)
                        : "Frame \(s.lights): not enough stars, skipped"
                    onProgress(AstroProgress(frame: s.lights, total: total, text: text, preview: preview))
                    group.leave()
                }
            }
            left -= n
        }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in group.notify(queue: work) { c.resume() } }
        guard let a = s.astro, s.accepted > 0 else { throw CameraError.captureFailed("No frames stacked") }
        onProgress(AstroProgress(frame: total, total: total, text: "Stacking \(s.accepted) frames", preview: nil))
        guard a.finalize() else { throw CameraError.captureFailed("Stacking failed") }
        let color = s.dng.map { ColorSpec(dng: $0) } ?? ColorSpec()
        guard let image = a.render(color: color, p3: st.displayP3) else { throw CameraError.captureFailed("Render failed") }
        let totalSeconds = exp * Double(s.accepted)
        var raw: Data?
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("ASTRO_\(UUID().uuidString).dng")
        let meta = Self.dngMeta(s.metadata, orientation: orientation, exposure: Float(totalSeconds), iso: Int32(iso))
        if a.writeDNG(color: color, meta: meta, to: url) { raw = try? Data(contentsOf: url) }
        try? FileManager.default.removeItem(at: url)
        return try await finishAndSave(image, ctx: ctx, metadata: s.metadata,
                                       comment: "Lumen astro stack: \(s.accepted) x \(String(format: "%.2f", exp)) s",
                                       raw: raw, prefix: "Stacked \(s.accepted) frames (\(Int(totalSeconds.rounded())) s total)")
    }
}
