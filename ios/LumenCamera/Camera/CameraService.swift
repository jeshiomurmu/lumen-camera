import AVFoundation
import CoreMedia
import UIKit

enum CaptureMode: String, CaseIterable, Identifiable {
    case astro = "ASTRO", portrait = "PORTRAIT", photo = "PHOTO", video = "VIDEO", pro = "PRO", proVideo = "PRO VIDEO"
    var id: String { rawValue }
    var isVideo: Bool { self == .video || self == .proVideo }
    var isPro: Bool { self == .pro || self == .proVideo }
}

/// A physical camera with its zoom factor relative to the 1x wide camera.
struct Lens: Identifiable, Hashable {
    let device: AVCaptureDevice
    let factor: CGFloat
    var id: String { device.uniqueID }
}

/// A zoom pill. Photo modes switch physical lenses (Bayer RAW needs a single
/// camera); video modes drive the virtual multi-camera so zoom is seamless.
struct ZoomStop: Identifiable, Hashable {
    let label: String
    let lensID: String?        // physical lens to use (nil: keep the current device)
    let deviceZoom: CGFloat    // videoZoomFactor on that device
    let factor: CGFloat        // displayed factor (1 = main wide camera)
    var id: String { "\(lensID ?? "-")@\(deviceZoom)" }

    static func label(_ f: CGFloat) -> String {
        if f < 0.95 { return "." + String(Int((f * 10).rounded())) }
        return abs(f - f.rounded()) < 0.05 ? "\(Int(f.rounded()))" : String(format: "%.1f", Double(f))
    }
}

struct VideoModeOption: Hashable, Identifiable {
    let width: Int32
    let height: Int32
    let fps: Int
    var id: String { "\(width)x\(height)@\(fps)" }
    var isOpenGate: Bool { abs(Double(width) / Double(height) - 4.0 / 3.0) < 0.02 }
    var label: String {
        let name: String
        if isOpenGate { name = "Open gate \(width)x\(height)" }
        else if width >= 4096 { name = "DCI 4K" } else if width >= 3840 { name = "4K" }
        else if width >= 1920 { name = "FHD" } else { name = "HD" }
        return "\(name) \(fps)"
    }
}

enum FlashMode: String, Codable { case off, auto, on, torch }

struct ManualControls: Equatable {
    var iso: Float?
    var shutter: Double?      // seconds
    var ev: Float = 0
    var lens: Float?          // lens position 0...1 (0 = closest)
    var kelvin: Float?
    var tint: Float = 0
    var torch = false
    var zoom: CGFloat = 1     // device videoZoomFactor
    var manualExposure: Bool { iso != nil || shutter != nil }
}

struct LiveInfo: Equatable {
    var iso: Float = 0
    var shutter: Double = 0
    var aperture: Float = 0
    var lensPosition: Float = 0
    var kelvin: Float = 0
    var ev100: Float = 10
}

enum CameraError: LocalizedError {
    case noDevice, noRaw, captureFailed(String)
    var errorDescription: String? {
        switch self {
        case .noDevice: return "Camera unavailable"
        case .noRaw: return "This camera does not offer Bayer RAW"
        case .captureFailed(let s): return s
        }
    }
}

/**
 AVFoundation engine: device discovery, session configuration (photo / video /
 portrait), full manual controls, Bayer RAW brackets for the Lumen pipeline,
 ProRAW, and movie recording with codec / bitrate / colour-space control.
 Everything that touches the session or a device runs on `queue`.
 */
final class CameraService: NSObject {
    let session = AVCaptureSession()
    let queue = DispatchQueue(label: "app.lumen.session")
    private let analysisQueue = DispatchQueue(label: "app.lumen.analysis", qos: .utility)
    let photoOutput = AVCapturePhotoOutput()
    let movieOutput = AVCaptureMovieFileOutput()
    private let videoData = AVCaptureVideoDataOutput()

    private(set) var device: AVCaptureDevice?
    private(set) var mode: CaptureMode = .photo
    private weak var previewLayer: AVCaptureVideoPreviewLayer?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var rotationObs: NSKeyValueObservation?
    private var inFlight = Set<NSObject>()
    private var liveTimer: DispatchSourceTimer?
    private var frameCounter = 0
    private var recordContinuation: CheckedContinuation<URL?, Never>?

    var onLive: ((LiveInfo) -> Void)?
    var onAnalysis: ((CVPixelBuffer, Bool) -> Void)?   // buffer, mirrored
    var onControlZoom: ((CGFloat) -> Void)?
    var analysisEnabled = false
    struct AnalysisOptions { var peaking = false, zebras = false, waveform = false }
    var analysisOptions = AnalysisOptions()

    func attach(previewLayer layer: AVCaptureVideoPreviewLayer) {
        previewLayer = layer
        layer.session = session
        layer.videoGravity = .resizeAspectFill
        if #available(iOS 26.0, *) { layer.isDeferredStartEnabled = false }  // preview first
    }

    // ---- discovery ----------------------------------------------------------------------
    static func backVirtual() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    static func depthDevice(front: Bool) -> AVCaptureDevice? {
        if front { return AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front) }
        return AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInDualCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back)
    }

    static func front() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
            ?? AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front)
    }

    static func area(_ d: CMVideoDimensions) -> Int { Int(d.width) * Int(d.height) }

    private static func halfTan(_ d: AVCaptureDevice) -> Double {
        tan(Double(d.activeFormat.videoFieldOfView) * .pi / 360.0)
    }

    /// Virtual-device zoom factor at which each constituent camera takes over,
    /// and the factor of the wide camera (display 1x).
    static func constituentZooms(_ v: AVCaptureDevice) -> (zooms: [String: CGFloat], wide: CGFloat) {
        guard v.isVirtualDevice else { return ([v.uniqueID: 1], 1) }
        let cons = v.constituentDevices.sorted { halfTan($0) > halfTan($1) }   // widest first
        let sw = v.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat(truncating: $0) }
        var zooms: [String: CGFloat] = [:]
        if cons.count == sw.count + 1 {
            let at = [CGFloat(1)] + sw
            for (i, d) in cons.enumerated() { zooms[d.uniqueID] = at[i] }
        } else if let first = cons.first {
            let base = halfTan(first)
            for d in cons { zooms[d.uniqueID] = CGFloat(base / max(1e-6, halfTan(d))) }
        }
        let wideID = cons.first { $0.deviceType == .builtInWideAngleCamera }?.uniqueID
        return (zooms, wideID.flatMap { zooms[$0] } ?? 1)
    }

    /// Physical back lenses with display factors (exact switch-over values when known).
    static func physicalLenses() -> [Lens] {
        let devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInUltraWideCamera, .builtInWideAngleCamera, .builtInTelephotoCamera],
            mediaType: .video, position: .back).devices
        guard let wide = devices.first(where: { $0.deviceType == .builtInWideAngleCamera }) else {
            return devices.map { Lens(device: $0, factor: 1) }
        }
        var known: [String: CGFloat] = [:]
        if let v = backVirtual(), v.isVirtualDevice {
            let cz = constituentZooms(v)
            for (id, z) in cz.zooms { known[id] = z / cz.wide }
        }
        let base = halfTan(wide)
        return devices.map { d in
            Lens(device: d, factor: known[d.uniqueID] ?? CGFloat(base / max(1e-6, halfTan(d))))
        }.sorted { $0.factor < $1.factor }
    }

    /// Lens pills for photo modes (physical lenses + digital crops).
    static func photoZoomStops(_ lenses: [Lens]) -> [ZoomStop] {
        var stops = lenses.map { ZoomStop(label: ZoomStop.label($0.factor), lensID: $0.id, deviceZoom: 1, factor: $0.factor) }
        guard let longest = lenses.last else { return stops }
        let wide = lenses.first { $0.device.deviceType == .builtInWideAngleCamera } ?? longest
        for (lens, f) in [(wide, CGFloat(2)), (longest, longest.factor * 2), (longest, longest.factor * 4)] {
            guard !stops.contains(where: { abs($0.factor - f) / f < 0.15 }) else { continue }
            let z = f / lens.factor
            if z <= lens.device.maxAvailableVideoZoomFactor {
                stops.append(ZoomStop(label: ZoomStop.label(f), lensID: lens.id, deviceZoom: z, factor: f))
            }
        }
        return stops.sorted { $0.factor < $1.factor }
    }

    /// Lens pills for a (virtual) device driven by videoZoomFactor.
    static func virtualZoomStops(_ v: AVCaptureDevice, digital: Bool) -> [ZoomStop] {
        let cz = constituentZooms(v)
        var stops = cz.zooms.values.sorted().map { z -> ZoomStop in
            let f = z / cz.wide
            return ZoomStop(label: ZoomStop.label(f), lensID: nil, deviceZoom: z, factor: f)
        }
        if stops.isEmpty { stops = [ZoomStop(label: "1", lensID: nil, deviceZoom: 1, factor: 1)] }
        if digital, let longest = stops.last {
            for f in [CGFloat(2), longest.factor * 2] {
                guard !stops.contains(where: { abs($0.factor - f) / f < 0.15 }) else { continue }
                let z = f * cz.wide
                if z <= v.maxAvailableVideoZoomFactor {
                    stops.append(ZoomStop(label: ZoomStop.label(f), lensID: nil, deviceZoom: z, factor: f))
                }
            }
        }
        return stops.sorted { $0.factor < $1.factor }
    }

    static func videoModes(for d: AVCaptureDevice) -> [VideoModeOption] {
        var out = Set<VideoModeOption>()
        let fpsList = [24, 25, 30, 48, 50, 60, 100, 120, 240]
        for f in d.formats {
            let dims = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            guard dims.width >= 1280 else { continue }
            let ratio = Double(dims.width) / Double(dims.height)
            let dci = dims.width == 4096 && dims.height == 2160
            guard abs(ratio - 16.0 / 9.0) < 0.02 || (abs(ratio - 4.0 / 3.0) < 0.02 && dims.width >= 1920) || dci else { continue }
            let maxFps = f.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 30
            for fps in fpsList where Double(fps) <= maxFps + 0.01 {
                out.insert(VideoModeOption(width: dims.width, height: dims.height, fps: fps))
            }
        }
        return out.sorted { a, b in
            let pa = Int(a.width) * Int(a.height), pb = Int(b.width) * Int(b.height)
            return pa != pb ? pa > pb : a.fps > b.fps
        }
    }

    // ---- configuration ---------------------------------------------------------------------
    struct ConfigResult {
        let previewAspect: CGFloat   // portrait width / height of the stream
        let videoModes: [VideoModeOption]
        let bayerRaw: Bool
        let proRAW: Bool
        let depth: Bool
        let codecs: [AVVideoCodecType]
        let bitrateControl: Bool
        let maxPhotoMP: Int
        let minZoom: CGFloat
        let maxZoom: CGFloat
        let isoRange: ClosedRange<Float>
        let shutterRange: ClosedRange<Double>
        let evRange: ClosedRange<Float>
        let manualFocus: Bool
        let manualWB: Bool
        let customExposure: Bool
        let flashModes: [AVCaptureDevice.FlashMode]
        let hasTorch: Bool
    }

    /// Must run on `queue`.
    func configure(device: AVCaptureDevice, mode: CaptureMode, settings: AppSettings, videoMode: VideoModeOption?) throws -> ConfigResult {
        session.beginConfiguration()
        do {
            try build(device: device, mode: mode, settings: settings, videoMode: videoMode)
        } catch {
            session.commitConfiguration()
            throw error
        }
        session.commitConfiguration()
        setupRotation(device)
        startLiveTimer()

        var codecs: [AVVideoCodecType] = []
        var bitrateControl = false
        if mode.isVideo, let conn = movieOutput.connection(with: .video) {
            codecs = movieOutput.availableVideoCodecTypes
            bitrateControl = movieOutput.supportedOutputSettingsKeys(for: conn).contains(AVVideoCompressionPropertiesKey)
        }
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let maxDims = photoOutput.maxPhotoDimensions
        return ConfigResult(
            previewAspect: dims.width > 0 ? CGFloat(dims.height) / CGFloat(dims.width) : 0.75,
            videoModes: Self.videoModes(for: device),
            bayerRaw: !mode.isVideo && bayerFormat != nil,
            proRAW: !mode.isVideo && photoOutput.isAppleProRAWSupported,
            depth: mode == .portrait && photoOutput.isDepthDataDeliveryEnabled,
            codecs: codecs,
            bitrateControl: bitrateControl,
            maxPhotoMP: mode.isVideo ? 0 : Int((Double(maxDims.width) * Double(maxDims.height) / 1e6).rounded()),
            minZoom: device.minAvailableVideoZoomFactor,
            maxZoom: device.maxAvailableVideoZoomFactor,
            isoRange: device.activeFormat.minISO...max(device.activeFormat.minISO, device.activeFormat.maxISO),
            shutterRange: device.activeFormat.minExposureDuration.seconds...max(device.activeFormat.minExposureDuration.seconds,
                                                                                 device.activeFormat.maxExposureDuration.seconds),
            evRange: device.minExposureTargetBias...max(device.minExposureTargetBias, device.maxExposureTargetBias),
            manualFocus: device.isLockingFocusWithCustomLensPositionSupported,
            manualWB: device.isLockingWhiteBalanceWithCustomDeviceGainsSupported,
            customExposure: device.isExposureModeSupported(.custom),
            flashModes: mode.isVideo ? [] : photoOutput.supportedFlashModes,
            hasTorch: device.hasTorch)
    }

    private func build(device: AVCaptureDevice, mode: CaptureMode, settings: AppSettings, videoMode: VideoModeOption?) throws {
        session.inputs.forEach { session.removeInput($0) }
        session.outputs.forEach { session.removeOutput($0) }
        if #available(iOS 18.0, *) { session.controls.forEach { session.removeControl($0) } }

        self.mode = mode
        session.sessionPreset = mode.isVideo ? .inputPriority : .photo
        // our own colour choices (HLG / Apple Log) must not be overridden
        session.automaticallyConfiguresCaptureDeviceForWideColor = !mode.isVideo
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CameraError.noDevice }
        session.addInput(input)
        self.device = device

        if mode.isVideo {
            if settings.recordAudio, let mic = AVCaptureDevice.default(for: .audio),
               let ai = try? AVCaptureDeviceInput(device: mic), session.canAddInput(ai) {
                session.addInput(ai)
            }
            guard session.canAddOutput(movieOutput) else { throw CameraError.noDevice }
            session.addOutput(movieOutput)
            if #available(iOS 26.0, *), movieOutput.isDeferredStartSupported {
                movieOutput.isDeferredStartEnabled = false   // record as soon as the user taps
            }
            try device.lockForConfiguration()
            if let fmt = pickVideoFormat(device, mode: videoMode, settings: settings) { device.activeFormat = fmt }
            let fps = Int32(videoMode?.fps ?? 30)
            if device.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.maxFrameRate >= Double(fps) - 0.01 }) {
                device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: fps)
                device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: fps)
            }
            applyColorSpace(device, settings.videoColor)
            device.unlockForConfiguration()
            if let conn = movieOutput.connection(with: .video), conn.isVideoStabilizationSupported {
                conn.preferredVideoStabilizationMode = settings.stabilization ? bestStabilization(device) : .off
            }
            // Scopes while recording (iOS 16+ allows a video data output next to the movie
            // output), except ProRes, which fails with both outputs attached.
            if settings.videoCodec == .hevc || settings.videoCodec == .h264 {
                addAnalysisOutput(nativeFormat: true)
            }
        } else {
            guard session.canAddOutput(photoOutput) else { throw CameraError.noDevice }
            session.addOutput(photoOutput)
            photoOutput.maxPhotoQualityPrioritization = .quality
            let dims = device.activeFormat.supportedMaxPhotoDimensions
            if let biggest = dims.max(by: { Self.area($0) < Self.area($1) }) {
                let twelve = dims.filter { Self.area($0) <= 13_000_000 }
                    .max(by: { Self.area($0) < Self.area($1) })
                photoOutput.maxPhotoDimensions = settings.maxResolution ? biggest : (twelve ?? biggest)
            }
            photoOutput.isAppleProRAWEnabled = settings.photoEngine == .proRAW && photoOutput.isAppleProRAWSupported
            if mode == .portrait {
                photoOutput.isDepthDataDeliveryEnabled = photoOutput.isDepthDataDeliverySupported
                photoOutput.isPortraitEffectsMatteDeliveryEnabled = photoOutput.isPortraitEffectsMatteDeliverySupported
            } else {
                photoOutput.isPortraitEffectsMatteDeliveryEnabled = false
                photoOutput.isDepthDataDeliveryEnabled = false
            }
            if photoOutput.isZeroShutterLagSupported { photoOutput.isZeroShutterLagEnabled = true }
            if photoOutput.isResponsiveCaptureSupported { photoOutput.isResponsiveCaptureEnabled = true }
            addAnalysisOutput(nativeFormat: false)
        }
        if #available(iOS 18.0, *) { configureCameraControl(device) }
    }

    private func addAnalysisOutput(nativeFormat: Bool) {
        if !nativeFormat {
            videoData.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        } else {
            videoData.videoSettings = [:]   // device native (8- or 10-bit), no conversion
        }
        videoData.alwaysDiscardsLateVideoFrames = true
        videoData.setSampleBufferDelegate(self, queue: analysisQueue)
        if session.canAddOutput(videoData) { session.addOutput(videoData) }
    }

    private func pickVideoFormat(_ d: AVCaptureDevice, mode: VideoModeOption?, settings: AppSettings) -> AVCaptureDevice.Format? {
        let w = mode?.width ?? 3840, h = mode?.height ?? 2160
        let fps = Double(mode?.fps ?? 30)
        let matches = d.formats.filter { f in
            let dims = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            guard dims.width == w, dims.height == h,
                  f.videoSupportedFrameRateRanges.contains(where: { $0.maxFrameRate >= fps - 0.01 }) else { return false }
            let sub = CMFormatDescriptionGetMediaSubType(f.formatDescription)
            if settings.videoCodec.isProResRAW {
                if #available(iOS 26.0, *) { return sub == kCVPixelFormatType_96VersatileBayerPacked12 }
                return false
            }
            switch settings.videoColor {
            case .sdr: return true
            case .hlg: return f.supportedColorSpaces.contains(.HLG_BT2020)
            case .appleLog: return f.supportedColorSpaces.contains(.appleLog)
            case .appleLog2:
                if #available(iOS 26.0, *) { return f.supportedColorSpaces.contains(.appleLog2) }
                return false
            }
        }
        let tenBit = matches.first {
            let sub = CMFormatDescriptionGetMediaSubType($0.formatDescription)
            return sub == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange || sub == kCVPixelFormatType_422YpCbCr10BiPlanarVideoRange
        }
        let wantTenBit = settings.videoColor != .sdr || settings.videoCodec == .proRes422HQ
        return (wantTenBit ? tenBit : nil) ?? matches.first
    }

    private func applyColorSpace(_ d: AVCaptureDevice, _ color: VideoColor) {
        let spaces = d.activeFormat.supportedColorSpaces
        switch color {
        case .appleLog where spaces.contains(.appleLog): d.activeColorSpace = .appleLog
        case .appleLog2:
            if #available(iOS 26.0, *), spaces.contains(.appleLog2) { d.activeColorSpace = .appleLog2 }
        case .hlg where spaces.contains(.HLG_BT2020): d.activeColorSpace = .HLG_BT2020
        default:
            if spaces.contains(.P3_D65) { d.activeColorSpace = .P3_D65 }
        }
    }

    private func bestStabilization(_ d: AVCaptureDevice) -> AVCaptureVideoStabilizationMode {
        let f = d.activeFormat
        if #available(iOS 18.0, *), f.isVideoStabilizationModeSupported(.cinematicExtendedEnhanced) {
            return .cinematicExtendedEnhanced
        }
        if f.isVideoStabilizationModeSupported(.cinematicExtended) { return .cinematicExtended }
        if f.isVideoStabilizationModeSupported(.cinematic) { return .cinematic }
        return .standard
    }

    private func setupRotation(_ d: AVCaptureDevice) {
        rotationObs = nil
        let rc = AVCaptureDevice.RotationCoordinator(device: d, previewLayer: previewLayer)
        rotation = rc
        let layer = previewLayer
        let apply: (CGFloat) -> Void = { angle in
            DispatchQueue.main.async {
                if let c = layer?.connection, c.isVideoRotationAngleSupported(angle) { c.videoRotationAngle = angle }
            }
        }
        apply(rc.videoRotationAngleForHorizonLevelPreview)
        rotationObs = rc.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { coord, _ in
            apply(coord.videoRotationAngleForHorizonLevelPreview)
        }
    }

    /// Rotation to apply to captures so photos are upright (0 / 90 / 180 / 270).
    var captureRotationAngle: CGFloat { rotation?.videoRotationAngleForHorizonLevelCapture ?? 90 }

    func start() {
        queue.async {
            if !self.session.isRunning { self.session.startRunning() }
            if self.liveTimer == nil, self.device != nil { self.startLiveTimer() }
        }
    }
    func stop() {
        queue.async {
            self.liveTimer?.cancel()
            self.liveTimer = nil
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    // ---- live readouts -----------------------------------------------------------------------
    private func startLiveTimer() {
        liveTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now(), repeating: .milliseconds(200))
        t.setEventHandler { [weak self] in
            guard let self, let d = self.device else { return }
            let g = d.deviceWhiteBalanceGains
            let valid = g.redGain >= 1 && g.greenGain >= 1 && g.blueGain >= 1 &&
                max(g.redGain, g.greenGain, g.blueGain) <= d.maxWhiteBalanceGain
            let kelvin = valid ? d.temperatureAndTintValues(for: g).temperature : 0
            let shutter = d.exposureDuration.seconds
            let n = Double(d.lensAperture)
            let ev = log2(n * n / max(shutter, 1e-6)) - log2(Double(max(d.iso, 1)) / 100.0)
            self.onLive?(LiveInfo(iso: d.iso, shutter: shutter, aperture: d.lensAperture, lensPosition: d.lensPosition,
                                  kelvin: kelvin, ev100: Float(ev)))
        }
        t.resume()
        liveTimer = t
    }

    // ---- controls --------------------------------------------------------------------------
    func apply(_ c: ManualControls, rampZoom: Bool = false) {
        queue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            defer { d.unlockForConfiguration() }
            let z = max(d.minAvailableVideoZoomFactor, min(c.zoom, d.maxAvailableVideoZoomFactor))
            if rampZoom { d.ramp(toVideoZoomFactor: z, withRate: 6) } else if abs(d.videoZoomFactor - z) > 0.001 {
                if d.isRampingVideoZoom { d.cancelVideoZoomRamp() }
                d.videoZoomFactor = z
            }
            let fmt = d.activeFormat
            if c.manualExposure, d.isExposureModeSupported(.custom) {
                let curISO = d.iso, curT = d.exposureDuration.seconds
                var iso = c.iso ?? curISO
                var t = c.shutter ?? curT
                if c.iso != nil && c.shutter == nil { t = curT * Double(curISO) / Double(max(iso, 1)) }  // keep brightness
                if c.shutter != nil && c.iso == nil { iso = Float(Double(curISO) * curT / max(t, 1e-6)) }
                t = min(max(t, fmt.minExposureDuration.seconds), fmt.maxExposureDuration.seconds)
                iso = min(max(iso, fmt.minISO), fmt.maxISO)
                d.setExposureModeCustom(duration: CMTime(seconds: t, preferredTimescale: 1_000_000_000), iso: iso, completionHandler: nil)
            } else if d.isExposureModeSupported(.continuousAutoExposure) {
                d.exposureMode = .continuousAutoExposure
            }
            d.setExposureTargetBias(min(max(c.ev, d.minExposureTargetBias), d.maxExposureTargetBias), completionHandler: nil)
            if let lens = c.lens, d.isLockingFocusWithCustomLensPositionSupported {
                d.setFocusModeLocked(lensPosition: min(max(lens, 0), 1), completionHandler: nil)
            } else if d.isFocusModeSupported(.continuousAutoFocus) {
                d.focusMode = .continuousAutoFocus
            }
            if let k = c.kelvin, d.isLockingWhiteBalanceWithCustomDeviceGainsSupported {
                var g = d.deviceWhiteBalanceGains(for: AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(temperature: k, tint: c.tint))
                let maxG = d.maxWhiteBalanceGain
                g.redGain = min(max(g.redGain, 1), maxG)
                g.greenGain = min(max(g.greenGain, 1), maxG)
                g.blueGain = min(max(g.blueGain, 1), maxG)
                d.setWhiteBalanceModeLocked(with: g, completionHandler: nil)
            } else if d.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                d.whiteBalanceMode = .continuousAutoWhiteBalance
            }
            let torch: AVCaptureDevice.TorchMode = c.torch ? .on : .off
            if d.hasTorch, d.isTorchModeSupported(torch), d.torchMode != torch { d.torchMode = torch }
        }
    }

    func focus(atLayerPoint p: CGPoint, keepManualExposure: Bool) {
        guard let layer = previewLayer else { return }
        let devicePoint = layer.captureDevicePointConverted(fromLayerPoint: p)
        queue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            defer { d.unlockForConfiguration() }
            if d.isFocusPointOfInterestSupported, d.isFocusModeSupported(.autoFocus) {
                d.focusPointOfInterest = devicePoint
                d.focusMode = .autoFocus
            }
            if !keepManualExposure, d.isExposurePointOfInterestSupported, d.isExposureModeSupported(.autoExpose) {
                d.exposurePointOfInterest = devicePoint
                d.exposureMode = .autoExpose
            }
        }
    }

    // ---- photo capture ------------------------------------------------------------------------
    private func rotateConnection() {
        if let c = photoOutput.connection(with: .video), c.isVideoRotationAngleSupported(captureRotationAngle) {
            c.videoRotationAngle = captureRotationAngle
        }
    }

    /// Any photo request (processed, RAW+processed, ProRAW, portrait). Returns all delivered photos.
    func capture(_ settings: AVCapturePhotoSettings) async throws -> [AVCapturePhoto] {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<[AVCapturePhoto], Error>) in
            queue.async {
                self.rotateConnection()
                let delegate = PhotoDelegate()
                delegate.done = { [weak self, weak delegate] photos, error in
                    self?.queue.async { if let delegate { self?.inFlight.remove(delegate) } }
                    if let error { cont.resume(throwing: error) } else { cont.resume(returning: photos) }
                }
                self.inFlight.insert(delegate)
                self.photoOutput.capturePhoto(with: settings, delegate: delegate)
            }
        }
    }

    var bayerFormat: OSType? {
        photoOutput.availableRawPhotoPixelFormatTypes.first { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }
    }

    var proRAWFormat: OSType? {
        photoOutput.availableRawPhotoPixelFormatTypes.first { AVCapturePhotoOutput.isAppleProRAWPixelFormat($0) }
    }

    /// Bayer RAW frames at an exact manual exposure (bracketed captures).
    /// `each` runs on the photo delegate queue, in capture order.
    func captureBayer(count: Int, exposure: Double, iso: Float, each: @escaping (AVCapturePhoto) -> Void) async throws {
        guard let raw = bayerFormat, let d = device else { throw CameraError.noRaw }
        let fmt = d.activeFormat
        let t = CMTime(seconds: min(max(exposure, fmt.minExposureDuration.seconds), fmt.maxExposureDuration.seconds),
                       preferredTimescale: 1_000_000_000)
        let isoC = min(max(iso, fmt.minISO), fmt.maxISO)
        let maxB = max(1, photoOutput.maxBracketedCapturePhotoCount)
        var remaining = count
        while remaining > 0 {
            try Task.checkCancellation()
            let n = min(maxB, remaining)
            let items = (0..<n).map { _ in
                AVCaptureManualExposureBracketedStillImageSettings.manualExposureSettings(exposureDuration: t, iso: isoC)
            }
            let s = AVCapturePhotoBracketSettings(rawPixelFormatType: raw, processedFormat: nil, bracketedSettings: items)
            s.photoQualityPrioritization = .speed   // Bayer RAW is unprocessed
            if photoOutput.isLensStabilizationDuringBracketedCaptureSupported { s.isLensStabilizationEnabled = true }
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                queue.async {
                    self.rotateConnection()
                    let delegate = PhotoDelegate()
                    delegate.each = each
                    delegate.done = { [weak self, weak delegate] _, error in
                        self?.queue.async { if let delegate { self?.inFlight.remove(delegate) } }
                        if let error { cont.resume(throwing: error) } else { cont.resume() }
                    }
                    self.inFlight.insert(delegate)
                    self.photoOutput.capturePhoto(with: s, delegate: delegate)
                }
            }
            remaining -= n
        }
    }

    // ---- movie recording ------------------------------------------------------------------------
    func startRecording(to url: URL, codec: VideoCodecChoice, bitrate: Int, pcmAudio: Bool) throws {
        guard let conn = movieOutput.connection(with: .video) else { throw CameraError.captureFailed("no video connection") }
        if conn.isVideoRotationAngleSupported(captureRotationAngle) { conn.videoRotationAngle = captureRotationAngle }
        var settings: [String: Any] = [:]
        let type = AVVideoCodecType(rawValue: codec.codecRawValue)
        if movieOutput.availableVideoCodecTypes.contains(type) { settings[AVVideoCodecKey] = type }
        let isProRes = codec == .proRes422HQ || codec.isProResRAW
        if bitrate > 0, !isProRes, movieOutput.supportedOutputSettingsKeys(for: conn).contains(AVVideoCompressionPropertiesKey) {
            settings[AVVideoCompressionPropertiesKey] = [AVVideoAverageBitRateKey: bitrate]
        }
        if !settings.isEmpty { movieOutput.setOutputSettings(settings, for: conn) }
        if pcmAudio, let ac = movieOutput.connection(with: .audio),
           movieOutput.supportedOutputSettingsKeys(for: ac).contains(AVFormatIDKey) {
            movieOutput.setOutputSettings([
                AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 2,
                AVLinearPCMBitDepthKey: 24, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ], for: ac)
        }
        movieOutput.startRecording(to: url, recordingDelegate: self)
    }

    func stopRecording() async -> URL? {
        await withCheckedContinuation { (cont: CheckedContinuation<URL?, Never>) in
            queue.async {
                guard self.movieOutput.isRecording else { cont.resume(returning: nil); return }
                self.recordContinuation = cont
                self.movieOutput.stopRecording()
            }
        }
    }

    // ---- Camera Control button (iPhone 16 / 17, iOS 18+) ---------------------------------------
    @available(iOS 18.0, *)
    private func configureCameraControl(_ d: AVCaptureDevice) {
        guard session.supportsControls else { return }
        let zoom = AVCaptureSystemZoomSlider(device: d) { [weak self] factor in self?.onControlZoom?(factor) }
        let ev = AVCaptureSystemExposureBiasSlider(device: d)
        var controls: [AVCaptureControl] = [zoom, ev]
        if d.isExposureModeSupported(.custom) {
            let fmt = d.activeFormat
            let iso = AVCaptureSlider("ISO", symbolName: "camera.aperture", in: fmt.minISO...fmt.maxISO)
            iso.setActionQueue(queue) { [weak self] value in
                guard let dev = self?.device, (try? dev.lockForConfiguration()) != nil else { return }
                dev.setExposureModeCustom(duration: AVCaptureDevice.currentExposureDuration, iso: value, completionHandler: nil)
                dev.unlockForConfiguration()
            }
            controls.append(iso)
        }
        if d.isLockingFocusWithCustomLensPositionSupported {
            let focus = AVCaptureSlider("Focus", symbolName: "scope", in: 0...1)
            focus.setActionQueue(queue) { [weak self] value in
                guard let dev = self?.device, (try? dev.lockForConfiguration()) != nil else { return }
                dev.setFocusModeLocked(lensPosition: value, completionHandler: nil)
                dev.unlockForConfiguration()
            }
            controls.append(focus)
        }
        for c in controls where session.canAddControl(c) { session.addControl(c) }
        session.setControlsDelegate(self, queue: queue)
    }

    // ---- hardware report ------------------------------------------------------------------------
    func hardwareReport() -> [(String, String)] {
        var out: [(String, String)] = []
        let types: [AVCaptureDevice.DeviceType] = [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera,
                                                   .builtInDualCamera, .builtInDualWideCamera, .builtInTripleCamera,
                                                   .builtInTrueDepthCamera, .builtInLiDARDepthCamera]
        let all = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .unspecified).devices
        for d in all {
            out.append((d.localizedName, d.position == .front ? "front" : "back"))
            if d.isVirtualDevice {
                out.append(("  Constituents", d.constituentDevices.map { $0.localizedName }.joined(separator: ", ")))
                out.append(("  Switch-over zoom", d.virtualDeviceSwitchOverVideoZoomFactors.map { String(format: "%.2f", $0.doubleValue) }.joined(separator: ", ")))
            }
            let fmts = d.formats
            let isoMin = fmts.map { $0.minISO }.min() ?? 0, isoMax = fmts.map { $0.maxISO }.max() ?? 0
            let tMin = fmts.map { $0.minExposureDuration.seconds }.min() ?? 0
            let tMax = fmts.map { $0.maxExposureDuration.seconds }.max() ?? 0
            out.append(("  ISO", "\(Int(isoMin))–\(Int(isoMax))"))
            out.append(("  Shutter", "1/\(Int((1 / max(tMin, 1e-9)).rounded())) – \(String(format: "%.2f", tMax)) s"))
            out.append(("  Aperture · FOV", String(format: "ƒ%.2f · %.0f°", d.lensAperture, d.activeFormat.videoFieldOfView)))
            out.append(("  Min focus", d.minimumFocusDistance > 0 ? "\(d.minimumFocusDistance) mm" : "n/a"))
            var best = CMVideoDimensions(width: 0, height: 0)
            for f in fmts {
                for dim in f.supportedMaxPhotoDimensions where Int(dim.width) * Int(dim.height) > Int(best.width) * Int(best.height) {
                    best = dim
                }
            }
            if best.width > 0 {
                let mp = Int(best.width) * Int(best.height) / 1_000_000
                out.append(("  Max photo", "\(best.width)x\(best.height) (\(mp) MP)"))
            }
            let modes = Self.videoModes(for: d)
            if let top = modes.first { out.append(("  Max video", top.label)) }
            let hfr = modes.filter { $0.fps >= 100 }.map { $0.label }
            if !hfr.isEmpty { out.append(("  High frame rate", Array(Set(hfr)).sorted().joined(separator: ", "))) }
            var spaces = Set<String>()
            for f in fmts {
                for c in f.supportedColorSpaces {
                    switch c {
                    case .sRGB: spaces.insert("sRGB")
                    case .P3_D65: spaces.insert("P3")
                    case .HLG_BT2020: spaces.insert("HLG BT.2020")
                    case .appleLog: spaces.insert("Apple Log")
                    default: spaces.insert("Apple Log 2 / other")
                    }
                }
            }
            out.append(("  Color spaces", spaces.sorted().joined(separator: ", ")))
            if #available(iOS 26.0, *) {
                if fmts.contains(where: { CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_96VersatileBayerPacked12 }) {
                    out.append(("  ProRes RAW", "supported"))
                }
            }
            out.append(("", ""))
        }
        if let d = device {
            out.append(("Active camera", d.localizedName))
            out.append(("  Bayer RAW", bayerFormat != nil ? "yes (bracket \(photoOutput.maxBracketedCapturePhotoCount) frames)" : "no"))
            out.append(("  Apple ProRAW", photoOutput.isAppleProRAWSupported ? "yes" : "no"))
            let m = photoOutput.maxPhotoDimensions
            out.append(("  Photo output", "\(m.width)x\(m.height)"))
            if mode.isVideo {
                out.append(("  Codecs", movieOutput.availableVideoCodecTypes.map { $0.rawValue }.joined(separator: ", ")))
            }
        }
        return out
    }
}

// MARK: - delegates

extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        frameCounter += 1
        guard analysisEnabled, frameCounter % 6 == 0, let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let mirrored = connection.isVideoMirrored != (device?.position == .front)
        onAnalysis?(pb, mirrored)
    }
}

extension CameraService: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        queue.async {
            // a recording that hit a limit still produced a usable file
            let ok = error == nil || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? false)
            self.recordContinuation?.resume(returning: ok ? outputFileURL : nil)
            self.recordContinuation = nil
        }
    }
}

@available(iOS 18.0, *)
extension CameraService: AVCaptureSessionControlsDelegate {
    func sessionControlsDidBecomeActive(_ session: AVCaptureSession) {}
    func sessionControlsWillEnterFullscreenAppearance(_ session: AVCaptureSession) {}
    func sessionControlsWillExitFullscreenAppearance(_ session: AVCaptureSession) {}
    func sessionControlsDidBecomeInactive(_ session: AVCaptureSession) {}
}

/// Collects the photos of one request; `each` streams them (bursts) as they arrive.
final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    var each: ((AVCapturePhoto) -> Void)?
    var done: (([AVCapturePhoto], Error?) -> Void)?
    private var photos: [AVCapturePhoto] = []
    private var streamed = 0
    private var lastError: Error?

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error { lastError = error; return }
        if let each { streamed += 1; each(photo) } else { photos.append(photo) }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        let err = error ?? lastError
        if each != nil {
            done?([], streamed == 0 ? (err ?? CameraError.captureFailed("no frame delivered")) : nil)
        } else {
            done?(photos, photos.isEmpty ? (err ?? CameraError.captureFailed("no photo delivered")) : nil)
        }
        done = nil
    }
}
