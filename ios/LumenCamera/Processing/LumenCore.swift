import CoreGraphics
import Foundation

// Swift wrappers over the shared C imaging core (core/include/lumen_core.h).

enum CTuple {
    /// Writes floats into a homogeneous C float array imported as a Swift tuple.
    static func set<T>(_ tuple: inout T, _ values: [Float]) {
        withUnsafeMutableBytes(of: &tuple) { raw in
            let p = raw.bindMemory(to: Float.self)
            for i in 0..<min(p.count, values.count) { p[i] = values[i] }
        }
    }

    static func floats<T>(_ tuple: T) -> [Float] {
        withUnsafeBytes(of: tuple) { Array($0.bindMemory(to: Float.self)) }
    }

    static func string<T>(_ tuple: T) -> String {
        withUnsafeBytes(of: tuple) { raw in
            let bytes = raw.bindMemory(to: UInt8.self)
            let end = bytes.firstIndex(of: 0) ?? bytes.count
            return String(decoding: bytes[0..<end], as: UTF8.self)
        }
    }
}

/// One RAW stream (all frames of a burst share it).
struct RawSpec {
    var width: Int
    var height: Int
    var rowStride: Int
    var cfa: Int32
    var black: [Float]
    var white: Float
    var noiseScale: [Float]
    var noiseOffset: [Float]

    var cInfo: LumenRawInfo {
        var i = LumenRawInfo()
        i.width = Int32(width)
        i.height = Int32(height)
        i.row_stride = Int32(rowStride)
        i.cfa = cfa
        i.quad_bayer = 0
        i.white_level = white
        CTuple.set(&i.black_level, black)
        CTuple.set(&i.noise_scale, noiseScale)
        CTuple.set(&i.noise_offset, noiseOffset)
        return i
    }
}

/// Camera color description (DNG ColorMatrix / ForwardMatrix / neutral).
struct ColorSpec {
    var info = LumenColorInfo()

    init(dng: LumenDngInfo) { info = dng.color }
    init() {}

    /// White balance chosen by the user (Kelvin + tint) instead of as-shot.
    func withKelvin(_ kelvin: Float, tint: Float) -> ColorSpec {
        var c = self
        var gains: (Float, Float, Float) = (0, 0, 0)
        var src = info
        let ok = withUnsafeMutableBytes(of: &gains) { raw -> Bool in
            lumen_wb_gains_for_cct(&src, kelvin, tint, raw.bindMemory(to: Float.self).baseAddress) == LUMEN_OK
        }
        if ok {
            c.info.wb_gains = gains
            CTuple.set(&c.info.cam_to_srgb, Array(repeating: 0, count: 9))  // core re-derives for this white
        }
        return c
    }

    var estimatedKelvin: Float {
        var src = info
        return lumen_estimate_cct(&src)
    }
}

struct FinishSpec {
    var p = LumenFinishParams()

    static func profile(_ profile: Int32, p3: Bool, half: Bool) -> FinishSpec {
        var f = FinishSpec()
        lumen_finish_params_for_profile(profile, &f.p)
        f.p.output_p3 = p3 ? 1 : 0
        f.p.half_size = half ? 1 : 0
        return f
    }
}

struct DngMetaSpec {
    var make = "Apple"
    var model = "iPhone"
    var orientation: Int32 = 1
    var exposureTime: Float = 0
    var fNumber: Float = 0
    var focalLength: Float = 0
    var iso: Int32 = 0

    /// Calls `body` with a C struct whose strings stay valid for the call.
    func withC(_ body: (UnsafePointer<LumenDngMeta>) -> Int32) -> Int32 {
        let strings = [make, model, "\(make) \(model)", "Lumen Camera \(String(cString: lumen_core_version()))",
                       DngMetaSpec.dateString()].map { strdup($0) }
        defer { strings.forEach { free($0) } }
        var m = LumenDngMeta()
        m.make = UnsafePointer(strings[0])
        m.model = UnsafePointer(strings[1])
        m.unique_model = UnsafePointer(strings[2])
        m.software = UnsafePointer(strings[3])
        m.datetime = UnsafePointer(strings[4])
        m.orientation = orientation
        m.exposure_time_s = exposureTime
        m.f_number = fNumber
        m.focal_length_mm = focalLength
        m.iso = iso
        return body(&m)
    }

    static func dateString() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }
}

enum LumenDNG {
    /// Parses DNG metadata (black/white level, CFA, color matrices, noise profile).
    static func info(_ data: Data) -> LumenDngInfo? {
        var out = LumenDngInfo()
        let rc = data.withUnsafeBytes { raw -> Int32 in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return LUMEN_ERR_INVALID_ARG }
            return lumen_dng_read_info(base, raw.count, &out)
        }
        return rc == LUMEN_OK ? out : nil
    }
}

/// Renders RGBA8 output of the core into a CGImage.
private func makeImage(width: Int, height: Int, p3: Bool, fill: (UnsafeMutablePointer<UInt8>, Int32) -> Int32) -> CGImage? {
    let stride = width * 4
    var buffer = Data(count: stride * height)
    let rc = buffer.withUnsafeMutableBytes { raw -> Int32 in
        guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return LUMEN_ERR_INVALID_ARG }
        return fill(base, Int32(stride))
    }
    guard rc == LUMEN_OK,
          let provider = CGDataProvider(data: buffer as CFData),
          let cs = CGColorSpace(name: p3 ? CGColorSpace.displayP3 : CGColorSpace.sRGB) else { return nil }
    return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride, space: cs,
                   bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider,
                   decode: nil, shouldInterpolate: false, intent: .defaultIntent)
}

/// HDR+-style burst merge of Bayer RAW frames.
final class BurstMerger {
    private var handle: OpaquePointer?
    let spec: RawSpec

    init?(spec: RawSpec, robustness: Float, spatialDenoise: Float, referenceMode: Int32) {
        self.spec = spec
        var info = spec.cInfo
        var mp = LumenMergeParams()
        lumen_merge_params_default(&mp)
        mp.robustness = robustness
        mp.spatial_denoise = spatialDenoise
        mp.reference_mode = referenceMode
        guard let h = lumen_burst_create(&info, &mp) else { return nil }
        handle = h
    }

    deinit { lumen_burst_destroy(handle) }

    @discardableResult
    func add(_ samples: UnsafePointer<UInt16>) -> Int32 { lumen_burst_add_frame(handle, samples) }

    func finalize() -> LumenBurstStats? {
        var st = LumenBurstStats()
        return lumen_burst_finalize(handle, &st) == LUMEN_OK ? st : nil
    }

    var displaySigma: Float { lumen_burst_display_sigma(handle) }

    func render(color: ColorSpec, finish: FinishSpec) -> CGImage? {
        var c = color.info
        var f = finish.p
        let half = f.half_size != 0
        return makeImage(width: half ? spec.width / 2 : spec.width, height: half ? spec.height / 2 : spec.height,
                         p3: f.output_p3 != 0) { ptr, stride in
            lumen_burst_render(handle, &c, &f, ptr, stride)
        }
    }

    func writeDNG(color: ColorSpec, meta: DngMetaSpec, to url: URL) -> Bool {
        var c = color.info
        return meta.withC { m in
            url.path.withCString { lumen_burst_write_dng(handle, &c, m, $0) }
        } == LUMEN_OK
    }
}

/// Star-aligned stacking for astrophotography.
final class AstroStacker {
    private var handle: OpaquePointer?
    let spec: RawSpec
    private let tempDir: String

    init?(spec: RawSpec, align: Bool, mode: Int32, darkCount: Int) {
        self.spec = spec
        tempDir = FileManager.default.temporaryDirectory.path
        var info = spec.cInfo
        var ap = LumenAstroParams()
        lumen_astro_params_default(&ap)
        ap.align_stars = align ? 1 : 0
        ap.stack_mode = mode
        let h: OpaquePointer? = tempDir.withCString { dir in
            ap.temp_dir = dir  // copied by the stacker
            return lumen_astro_create(&info, &ap)
        }
        guard let h else { return nil }
        handle = h
    }

    deinit { lumen_astro_destroy(handle) }

    func addDark(_ samples: UnsafePointer<UInt16>) -> Int32 { lumen_astro_add_dark(handle, samples) }

    func addLight(_ samples: UnsafePointer<UInt16>) -> (Int32, LumenAstroFrameStats) {
        var st = LumenAstroFrameStats()
        let rc = lumen_astro_add_light(handle, samples, &st)
        return (rc, st)
    }

    func finalize() -> Bool { lumen_astro_finalize(handle) == LUMEN_OK }

    func render(color: ColorSpec, p3: Bool) -> CGImage? {
        var c = color.info
        var f = FinishSpec.profile(Int32(LUMEN_PROFILE_NATURAL.rawValue), p3: p3, half: false).p
        f.sharpen = 0.25
        return makeImage(width: spec.width, height: spec.height, p3: p3) { ptr, stride in
            lumen_astro_render(handle, &c, &f, ptr, stride)
        }
    }

    func preview(color: ColorSpec) -> CGImage? {
        var c = color.info
        return makeImage(width: spec.width / 2, height: spec.height / 2, p3: false) { ptr, stride in
            lumen_astro_preview(handle, &c, ptr, stride)
        }
    }

    func writeDNG(color: ColorSpec, meta: DngMetaSpec, to url: URL) -> Bool {
        var c = color.info
        return meta.withC { m in
            url.path.withCString { lumen_astro_write_dng(handle, &c, m, $0) }
        } == LUMEN_OK
    }
}

enum LumenLUT {
    static func write(to url: URL) -> Bool { url.path.withCString { lumen_write_log_lut($0, 33) } == LUMEN_OK }
}
