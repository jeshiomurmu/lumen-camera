import CoreGraphics
import CoreVideo
import Foundation

/// Live scopes computed from the camera stream: RGB + luma histograms, luma
/// waveform, focus peaking and zebras. Images are already rotated to the
/// portrait viewfinder (and mirrored for the front camera).
struct FrameAnalysis {
    var luma: [Float] = []
    var rgb: [[Float]] = []
    var waveform: CGImage?
    var peaking: CGImage?
    var zebras: CGImage?
}

enum FrameAnalyzer {
    static let bins = 64
    static let gridLong = 192

    static func analyze(_ pb: CVPixelBuffer, mirrored: Bool, peaking: Bool, zebras: Bool, waveform: Bool) -> FrameAnalysis? {
        let fmt = CVPixelBufferGetPixelFormatType(pb)
        enum Kind { case yuv8(full: Bool), yuv10(full: Bool), bgra }
        let kind: Kind
        switch fmt {
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange: kind = .yuv8(full: true)
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange: kind = .yuv8(full: false)
        case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, kCVPixelFormatType_422YpCbCr10BiPlanarVideoRange:
            kind = .yuv10(full: false)
        case kCVPixelFormatType_420YpCbCr10BiPlanarFullRange, kCVPixelFormatType_422YpCbCr10BiPlanarFullRange:
            kind = .yuv10(full: true)
        case kCVPixelFormatType_32BGRA: kind = .bgra
        default: return nil   // e.g. Bayer while recording ProRes RAW
        }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }

        let W = CVPixelBufferGetWidth(pb), H = CVPixelBufferGetHeight(pb)
        guard W > 8, H > 8 else { return nil }
        let GW = gridLong, GH = max(8, Int((Double(gridLong) * Double(H) / Double(W)).rounded()))

        // sample accessors (8-bit luma 0..255 full range, chroma centred on 0)
        var lumaAt: (Int, Int) -> Float
        var rgbAt: (Int, Int) -> (Float, Float, Float)
        switch kind {
        case .yuv8(let full), .yuv10(let full):
            let ten: Bool = { if case .yuv10 = kind { return true }; return false }()
            guard let yBase = CVPixelBufferGetBaseAddressOfPlane(pb, 0),
                  let cBase = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return nil }
            let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
            let cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
            let cH = CVPixelBufferGetHeightOfPlane(pb, 1)
            let vertSub = cH < H   // 4:2:0 vs 4:2:2
            let yScale: Float = full ? 1 : 255 / 219, yOff: Float = full ? 0 : 16
            let cScale: Float = full ? 1 : 255 / 224
            if ten {
                lumaAt = { x, y in
                    let v = Float(yBase.load(fromByteOffset: y * yStride + x * 2, as: UInt16.self) >> 8)
                    return min(255, max(0, (v - yOff) * yScale))
                }
            } else {
                lumaAt = { x, y in
                    let v = Float(yBase.load(fromByteOffset: y * yStride + x, as: UInt8.self))
                    return min(255, max(0, (v - yOff) * yScale))
                }
            }
            rgbAt = { x, y in
                let yy = lumaAt(x, y)
                let cy = vertSub ? y / 2 : y
                let cb: Float, cr: Float
                if ten {
                    let o = cy * cStride + (x / 2) * 4
                    cb = Float(cBase.load(fromByteOffset: o, as: UInt16.self) >> 8) - 128
                    cr = Float(cBase.load(fromByteOffset: o + 2, as: UInt16.self) >> 8) - 128
                } else {
                    let o = cy * cStride + (x / 2) * 2
                    cb = Float(cBase.load(fromByteOffset: o, as: UInt8.self)) - 128
                    cr = Float(cBase.load(fromByteOffset: o + 1, as: UInt8.self)) - 128
                }
                let u = cb * cScale, v = cr * cScale   // BT.709
                return (min(255, max(0, yy + 1.5748 * v)),
                        min(255, max(0, yy - 0.1873 * u - 0.4681 * v)),
                        min(255, max(0, yy + 1.8556 * u)))
            }
        case .bgra:
            guard let base = CVPixelBufferGetBaseAddress(pb) else { return nil }
            let stride = CVPixelBufferGetBytesPerRow(pb)
            rgbAt = { x, y in
                let o = y * stride + x * 4
                return (Float(base.load(fromByteOffset: o + 2, as: UInt8.self)),
                        Float(base.load(fromByteOffset: o + 1, as: UInt8.self)),
                        Float(base.load(fromByteOffset: o, as: UInt8.self)))
            }
            lumaAt = { x, y in
                let c = rgbAt(x, y)
                return 0.2126 * c.0 + 0.7152 * c.1 + 0.0722 * c.2
            }
        }

        // portrait grid: PW x PH (rotated 90° clockwise like the preview)
        let PW = GH, PH = GW
        var lh = [Float](repeating: 0, count: bins)
        var rh = [[Float]](repeating: [Float](repeating: 0, count: bins), count: 3)
        var lumaGrid = [Float](repeating: 0, count: PW * PH)
        var edge = [Float](repeating: 0, count: PW * PH)
        var wave = [Float](repeating: 0, count: PW * bins)
        var maxEdge: Float = 1
        for gy in 0..<GH {
            let y = min(H - 2, max(1, Int((Double(gy) + 0.5) * Double(H) / Double(GH))))
            for gx in 0..<GW {
                let x = min(W - 2, max(1, Int((Double(gx) + 0.5) * Double(W) / Double(GW))))
                let c = rgbAt(x, y)
                let l = 0.2126 * c.0 + 0.7152 * c.1 + 0.0722 * c.2
                let px = mirrored ? gy : (GH - 1 - gy), py = gx
                let i = py * PW + px
                lumaGrid[i] = l
                let lb = min(bins - 1, Int(l) * bins / 256)
                lh[lb] += 1
                rh[0][min(bins - 1, Int(c.0) * bins / 256)] += 1
                rh[1][min(bins - 1, Int(c.1) * bins / 256)] += 1
                rh[2][min(bins - 1, Int(c.2) * bins / 256)] += 1
                if waveform { wave[(bins - 1 - lb) * PW + px] += 1 }
                if peaking {
                    // full-resolution gradient: in-focus detail is pixel-level
                    let m = abs(lumaAt(x + 1, y) - lumaAt(x - 1, y)) + abs(lumaAt(x, y + 1) - lumaAt(x, y - 1))
                    edge[i] = m
                    maxEdge = max(maxEdge, m)
                }
            }
        }
        func norm(_ a: inout [Float]) {
            let m = max(1, a.max() ?? 1)
            for i in a.indices { a[i] = (a[i] / m).squareRoot() }
        }
        norm(&lh)
        for k in 0..<3 { norm(&rh[k]) }

        var out = FrameAnalysis(luma: lh, rgb: rh)
        if peaking {
            let thr = max(36, maxEdge * 0.45)
            out.peaking = rgbaImage(width: PW, height: PH) { i in edge[i] > thr ? (255, 59, 48, 255) : (0, 0, 0, 0) }
        }
        if zebras {
            out.zebras = rgbaImage(width: PW, height: PH) { i in
                let x = i % PW, y = i / PW
                return lumaGrid[i] > 242 && ((x + y) / 3) % 2 == 0 ? (255, 255, 255, 210) : (0, 0, 0, 0)
            }
        }
        if waveform {
            let m = max(1, wave.max() ?? 1)
            out.waveform = rgbaImage(width: PW, height: bins) { i in
                let v = UInt8(min(255, (wave[i] / m).squareRoot() * 255 * 1.6))
                return (v / 2, v, v / 2 + 40 * (v > 0 ? 1 : 0), v == 0 ? 0 : 230)
            }
        }
        return out
    }

    private static func rgbaImage(width: Int, height: Int, pixel: (Int) -> (UInt8, UInt8, UInt8, UInt8)) -> CGImage? {
        var data = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            let p = pixel(i)
            if p.3 == 0 { continue }
            // premultiplied alpha
            let a = Float(p.3) / 255
            data[i * 4] = UInt8(Float(p.0) * a)
            data[i * 4 + 1] = UInt8(Float(p.1) * a)
            data[i * 4 + 2] = UInt8(Float(p.2) * a)
            data[i * 4 + 3] = p.3
        }
        guard let provider = CGDataProvider(data: Data(data) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}
