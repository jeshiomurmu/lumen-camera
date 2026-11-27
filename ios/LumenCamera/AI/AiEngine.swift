import CoreGraphics
import CoreML
import Foundation

/**
 On-device open-source AI with Core ML (Neural Engine / GPU):
  - lumen_denoise.mlmodelc : FFDNet colour denoiser (KAIR, MIT), tile 256
  - lumen_sr_x4.mlmodelc   : Real-ESRGAN general-x4v3 (BSD-3-Clause), tile 192, for AI zoom
 Both are converted from the same graphs as the Android ONNX models
 (tools/export_models.py --coreml) and use fixed tile shapes. Tiling matches
 export_models.py::tiled_run exactly.
 */
final class AiEngine {
    static let denoiseName = "lumen_denoise"
    static let srName = "lumen_sr_x4"
    static let denoiseTile = 256, denoiseOverlap = 32
    static let srTile = 192, srOverlap = 40

    private var denoiser: MLModel?
    private var upscaler: MLModel?
    private let lock = NSLock()

    private static func modelURL(_ name: String) -> URL? {
        let candidates = [
            Bundle.main.resourceURL?.appendingPathComponent("Models/\(name).mlmodelc"),
            Bundle.main.url(forResource: name, withExtension: "mlmodelc"),
        ]
        return candidates.compactMap { $0 }.first { FileManager.default.fileExists(atPath: $0.path) }
    }

    var hasDenoiser: Bool { Self.modelURL(Self.denoiseName) != nil }
    var hasUpscaler: Bool { Self.modelURL(Self.srName) != nil }

    private func load(_ name: String) throws -> MLModel {
        guard let url = Self.modelURL(name) else {
            throw CameraError.captureFailed("AI model \(name) is not bundled")
        }
        let cfg = MLModelConfiguration()
        cfg.computeUnits = .all   // Neural Engine when available
        return try MLModel(contentsOf: url, configuration: cfg)
    }

    func release() {
        lock.lock()
        denoiser = nil
        upscaler = nil
        lock.unlock()
    }

    // MARK: - public API

    /// Denoises an image; `sigma` is the noise std-dev in 0...1 display units.
    func denoise(_ image: CGImage, sigma: Float, progress: (Float) -> Void = { _ in }) throws -> CGImage {
        lock.lock()
        defer { lock.unlock() }
        if denoiser == nil { denoiser = try load(Self.denoiseName) }
        guard let model = denoiser else { return image }
        let src = try RGBAImage(image)
        var dst = RGBAImage(width: src.width, height: src.height)
        let sig = try MLMultiArray(shape: [1, 1, 1, 1], dataType: .float32)
        sig[0] = NSNumber(value: min(max(sigma, 0), 0.3))
        try tiled(src: src, dst: &dst, scale: 1, tile: Self.denoiseTile, overlap: Self.denoiseOverlap, progress: progress) { input in
            let features = try MLDictionaryFeatureProvider(dictionary: ["image": input, "sigma": sig])
            let out = try model.prediction(from: features)
            guard let v = out.featureValue(for: "denoised")?.multiArrayValue else {
                throw CameraError.captureFailed("denoiser returned no output")
            }
            return v
        }
        return dst.cgImage(colorSpace: image.colorSpace) ?? image
    }

    /// 4x super-resolution ("AI zoom").
    func upscale4x(_ image: CGImage, progress: (Float) -> Void = { _ in }) throws -> CGImage {
        lock.lock()
        defer { lock.unlock() }
        if upscaler == nil { upscaler = try load(Self.srName) }
        guard let model = upscaler else { return image }
        let src = try RGBAImage(image)
        var dst = RGBAImage(width: src.width * 4, height: src.height * 4)
        try tiled(src: src, dst: &dst, scale: 4, tile: Self.srTile, overlap: Self.srOverlap, progress: progress) { input in
            let features = try MLDictionaryFeatureProvider(dictionary: ["image": input])
            let out = try model.prediction(from: features)
            guard let v = out.featureValue(for: "upscaled")?.multiArrayValue else {
                throw CameraError.captureFailed("upscaler returned no output")
            }
            return v
        }
        return dst.cgImage(colorSpace: image.colorSpace) ?? image
    }

    // MARK: - tiling (fixed tile, edge-clamped context)

    private func tiled(src: RGBAImage, dst: inout RGBAImage, scale: Int, tile: Int, overlap: Int,
                       progress: (Float) -> Void, run: (MLMultiArray) throws -> MLMultiArray) throws {
        let W = src.width, H = src.height
        let step = tile - 2 * overlap
        let tilesX = (W + step - 1) / step, tilesY = (H + step - 1) / step
        let plane = tile * tile
        let input = try MLMultiArray(shape: [1, 3, NSNumber(value: tile), NSNumber(value: tile)], dataType: .float32)
        let f = input.dataPointer.bindMemory(to: Float.self, capacity: plane * 3)
        let ow = tile * scale, o = overlap * scale
        var done = 0
        var cy0 = 0
        while cy0 < H {
            let cy1 = min(H, cy0 + step)
            var cx0 = 0
            while cx0 < W {
                let cx1 = min(W, cx0 + step)
                for ty in 0..<tile {
                    let sy = min(max(cy0 - overlap + ty, 0), H - 1)
                    for tx in 0..<tile {
                        let sx = min(max(cx0 - overlap + tx, 0), W - 1)
                        let p = src.pixel(sx, sy)
                        let i = ty * tile + tx
                        f[i] = Float(p.0) * (1 / 255)
                        f[plane + i] = Float(p.1) * (1 / 255)
                        f[2 * plane + i] = Float(p.2) * (1 / 255)
                    }
                }
                let out = try run(input)
                // honour the output's strides (Core ML may pad rows)
                let st = out.strides.map { $0.intValue }
                let cs = st[1], rs = st[2], xs = st[3]
                let cw = (cx1 - cx0) * scale, ch = (cy1 - cy0) * scale
                guard out.shape.count == 4, out.shape[2].intValue >= ow else { throw CameraError.captureFailed("bad model output") }
                switch out.dataType {
                case .float32:
                    let r = out.dataPointer.bindMemory(to: Float.self, capacity: out.count)
                    for y in 0..<ch {
                        for x in 0..<cw {
                            let si = (o + y) * rs + (o + x) * xs
                            dst.set(cx0 * scale + x, cy0 * scale + y, r[si], r[cs + si], r[2 * cs + si])
                        }
                    }
                default:   // e.g. float16 outputs: slower, index by coordinates
                    for y in 0..<ch {
                        for x in 0..<cw {
                            let yy = NSNumber(value: o + y), xx = NSNumber(value: o + x)
                            dst.set(cx0 * scale + x, cy0 * scale + y,
                                    out[[0, 0, yy, xx]].floatValue, out[[0, 1, yy, xx]].floatValue,
                                    out[[0, 2, yy, xx]].floatValue)
                        }
                    }
                }
                done += 1
                progress(Float(done) / Float(tilesX * tilesY))
                cx0 += step
            }
            cy0 += step
        }
    }
}

/// Simple RGBA8 buffer used by the AI tiler (even width and height).
struct RGBAImage {
    let width: Int
    let height: Int
    let stride: Int
    var data: [UInt8]

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
        stride = width * 4
        data = [UInt8](repeating: 255, count: width * height * 4)
    }

    init(_ image: CGImage) throws {
        width = image.width & ~1
        height = image.height & ~1
        stride = width * 4
        var buf = [UInt8](repeating: 0, count: width * height * 4)
        let cs = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
        let w = width, h = height, st = stride
        let ok = buf.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: st, space: cs,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .none
            // draw at full size; the odd row/column (if any) falls outside the context
            ctx.draw(image, in: CGRect(x: 0, y: h - image.height, width: image.width, height: image.height))
            return true
        }
        if !ok { throw CameraError.captureFailed("cannot read image") }
        data = buf
    }

    @inline(__always) func pixel(_ x: Int, _ y: Int) -> (UInt8, UInt8, UInt8) {
        let i = y * stride + x * 4
        return (data[i], data[i + 1], data[i + 2])
    }

    @inline(__always) mutating func set(_ x: Int, _ y: Int, _ r: Float, _ g: Float, _ b: Float) {
        let i = y * stride + x * 4
        data[i] = UInt8(max(0, min(255, r * 255 + 0.5)))
        data[i + 1] = UInt8(max(0, min(255, g * 255 + 0.5)))
        data[i + 2] = UInt8(max(0, min(255, b * 255 + 0.5)))
        data[i + 3] = 255
    }

    func cgImage(colorSpace: CGColorSpace?) -> CGImage? {
        let cs = colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
        guard let provider = CGDataProvider(data: Data(data) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride, space: cs,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider,
                       decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
