import CoreGraphics
import Foundation
import onnxruntime_objc

/**
 On-device open-source AI through ONNX Runtime (MIT) with the Core ML execution
 provider (Neural Engine / GPU):
  - lumen_denoise.onnx : FFDNet colour denoiser (KAIR, MIT), tile 256
  - lumen_sr_x4.onnx   : Real-ESRGAN general-x4v3 (BSD-3-Clause), tile 192, for AI zoom
 The models have fixed tile shapes (tools/export_models.py), so Core ML can run
 the whole graph. Tiling matches export_models.py::tiled_run exactly.
 */
final class AiEngine {
    static let denoiseName = "lumen_denoise"
    static let srName = "lumen_sr_x4"
    static let denoiseTile = 256, denoiseOverlap = 32
    static let srTile = 192, srOverlap = 40

    private var env: ORTEnv?
    private var denoiser: ORTSession?
    private var upscaler: ORTSession?
    private let lock = NSLock()

    var hasDenoiser: Bool { Bundle.main.url(forResource: Self.denoiseName, withExtension: "onnx") != nil }
    var hasUpscaler: Bool { Bundle.main.url(forResource: Self.srName, withExtension: "onnx") != nil }

    private func session(_ name: String) throws -> ORTSession {
        guard let path = Bundle.main.path(forResource: name, ofType: "onnx") else {
            throw CameraError.captureFailed("AI model \(name).onnx is not bundled")
        }
        if env == nil { env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning) }
        let opts = try ORTSessionOptions()
        try opts.setIntraOpNumThreads(Int32(max(2, min(6, ProcessInfo.processInfo.activeProcessorCount))))
        try opts.setGraphOptimizationLevel(.all)
        // Neural Engine / GPU when available; ORT falls back to the CPU otherwise.
        try? opts.appendCoreMLExecutionProvider(with: ORTCoreMLExecutionProviderOptions())
        return try ORTSession(env: env!, modelPath: path, sessionOptions: opts)
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
        if denoiser == nil { denoiser = try session(Self.denoiseName) }
        guard let s = denoiser else { return image }
        let src = try RGBAImage(image)
        var dst = RGBAImage(width: src.width, height: src.height)
        var sigmaValue = min(max(sigma, 0), 0.3)
        let sig = try ORTValue(tensorData: NSMutableData(bytes: &sigmaValue, length: 4),
                               elementType: ORTTensorElementDataType.float, shape: [1, 1, 1, 1])
        try tiled(src: src, dst: &dst, scale: 1, tile: Self.denoiseTile, overlap: Self.denoiseOverlap, progress: progress) { input, t in
            let x = try ORTValue(tensorData: input, elementType: ORTTensorElementDataType.float, shape: [1, 3, NSNumber(value: t), NSNumber(value: t)])
            let out = try s.run(withInputs: ["image": x, "sigma": sig], outputNames: ["denoised"], runOptions: nil)
            guard let v = out["denoised"] else { throw CameraError.captureFailed("denoiser returned no output") }
            return try v.tensorData() as Data
        }
        return dst.cgImage(colorSpace: image.colorSpace) ?? image
    }

    /// 4x super-resolution ("AI zoom").
    func upscale4x(_ image: CGImage, progress: (Float) -> Void = { _ in }) throws -> CGImage {
        lock.lock()
        defer { lock.unlock() }
        if upscaler == nil { upscaler = try session(Self.srName) }
        guard let s = upscaler else { return image }
        let src = try RGBAImage(image)
        var dst = RGBAImage(width: src.width * 4, height: src.height * 4)
        try tiled(src: src, dst: &dst, scale: 4, tile: Self.srTile, overlap: Self.srOverlap, progress: progress) { input, t in
            let x = try ORTValue(tensorData: input, elementType: ORTTensorElementDataType.float, shape: [1, 3, NSNumber(value: t), NSNumber(value: t)])
            let out = try s.run(withInputs: ["image": x], outputNames: ["upscaled"], runOptions: nil)
            guard let v = out["upscaled"] else { throw CameraError.captureFailed("upscaler returned no output") }
            return try v.tensorData() as Data
        }
        return dst.cgImage(colorSpace: image.colorSpace) ?? image
    }

    // MARK: - tiling (fixed tile, edge-clamped context)

    private func tiled(src: RGBAImage, dst: inout RGBAImage, scale: Int, tile: Int, overlap: Int,
                       progress: (Float) -> Void, run: (NSMutableData, Int) throws -> Data) throws {
        let W = src.width, H = src.height
        let step = tile - 2 * overlap
        let tilesX = (W + step - 1) / step, tilesY = (H + step - 1) / step
        let plane = tile * tile
        guard let input = NSMutableData(length: plane * 3 * MemoryLayout<Float>.size) else {
            throw CameraError.captureFailed("out of memory")
        }
        let f = input.mutableBytes.bindMemory(to: Float.self, capacity: plane * 3)
        let ow = tile * scale, oplane = ow * ow, o = overlap * scale
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
                let out = try run(input, tile)
                let cw = (cx1 - cx0) * scale, ch = (cy1 - cy0) * scale
                out.withUnsafeBytes { raw in
                    let r = raw.bindMemory(to: Float.self)
                    guard r.count >= 3 * oplane else { return }
                    for y in 0..<ch {
                        for x in 0..<cw {
                            let si = (o + y) * ow + (o + x)
                            dst.set(cx0 * scale + x, cy0 * scale + y, r[si], r[oplane + si], r[2 * oplane + si])
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
