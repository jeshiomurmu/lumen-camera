import AVFoundation
import Combine
import SwiftUI
import UIKit

enum ProParam: String, CaseIterable, Identifiable {
    case iso = "ISO", shutter = "SPEED", ev = "EV", focus = "FOCUS", wb = "WB"
    var id: String { rawValue }
}

enum Sheet: String, Identifiable {
    case settings, hardware, more, presets, viewer
    var id: String { rawValue }
}

/// Thread-safe flag (read from capture code, set from the UI).
final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var v = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return v }
        set { lock.lock(); v = newValue; lock.unlock() }
    }
}

@MainActor
final class CameraViewModel: ObservableObject {
    static let modes: [CaptureMode] = [.astro, .portrait, .photo, .video, .pro, .proVideo]

    // ---- state -------------------------------------------------------------------------
    @Published var ready = false
    @Published var permissionDenied = false
    @Published var mode: CaptureMode = .photo
    @Published var front = false
    @Published var night = false
    @Published var nightSuggested = false
    @Published var lenses: [Lens] = []
    @Published var currentLensID: String?
    @Published var zoomStops: [ZoomStop] = []
    @Published var zoomFactor: CGFloat = 1
    @Published var controls = ManualControls()
    @Published var flash: FlashMode = .off
    @Published var proParam: ProParam?
    @Published var live = LiveInfo()
    @Published var recording = false
    @Published var recordSeconds = 0
    @Published var busy = false
    @Published var busyText = ""
    @Published var progress: Float = 0
    @Published var thumbnail: UIImage?
    @Published var lastPreview: UIImage?
    @Published var message: String?
    @Published var previewAspect: CGFloat = 0.75
    @Published var astroPreview: UIImage?
    @Published var countdown = 0
    @Published var sheet: Sheet?
    @Published var presets: [ProPreset] = AppSettings.loadPresets()
    @Published var videoModes: [VideoModeOption] = []
    @Published var selectedVideo: VideoModeOption?
    @Published var analysis: FrameAnalysis?
    @Published var config: CameraService.ConfigResult?
    @Published var hardwareRows: [(String, String)] = []
    @Published var focusPoint: CGPoint?
    @Published var thermalWarning = false
    @Published var settings = AppSettings.load() {
        didSet {
            settings.save()
            settingsChanged(from: oldValue)
        }
    }

    let service = CameraService()
    let ai = AiEngine()
    let motion = DeviceMotion()
    let thermal = ThermalMonitor()
    private(set) lazy var pipeline = PhotoPipeline(camera: service, ai: ai)
    private let astroCancel = CancelFlag()
    private var recordTimer: Timer?
    private var configToken = 0
    private let analysisInFlight = CancelFlag()
    private var bag = Set<AnyCancellable>()
    private var messageTask: Task<Void, Never>?
    private var focusTask: Task<Void, Never>?

    private var usesPhysicalLens: Bool { !front && !mode.isVideo && mode != .portrait }
    private var currentLens: Lens? { lenses.first { $0.id == currentLensID } ?? lenses.first }

    // ---- startup ------------------------------------------------------------------------
    func start() async {
        guard !ready else { return }
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            permissionDenied = true
            return
        }
        _ = await AVCaptureDevice.requestAccess(for: .audio)
        lenses = CameraService.physicalLenses()
        currentLensID = lenses.first { $0.device.deviceType == .builtInWideAngleCamera }?.id ?? lenses.first?.id

        let svc = service
        svc.onLive = { @Sendable [weak self] li in
            Task { @MainActor in self?.onLive(li) }
        }
        let busyFlag = analysisInFlight
        svc.onAnalysis = { @Sendable [weak self] pb, mirrored in
            guard !busyFlag.value else { return }
            busyFlag.value = true
            let o = svc.analysisOptions
            let a = FrameAnalyzer.analyze(pb, mirrored: mirrored, peaking: o.peaking, zebras: o.zebras, waveform: o.waveform)
            busyFlag.value = false
            Task { @MainActor in self?.analysis = a }
        }
        svc.onControlZoom = { @Sendable [weak self] z in
            Task { @MainActor in self?.onControlZoom(z) }
        }
        NotificationCenter.default.publisher(for: AVCaptureSession.wasInterruptedNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.toast("Camera interrupted") }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: AVCaptureSession.runtimeErrorNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.service.start() }
            .store(in: &bag)
        thermal.$state.receive(on: RunLoop.main).sink { [weak self] _ in self?.onThermal() }.store(in: &bag)

        ready = true
        motion.start()
        thermal.start()
        updateAnalysisOptions()
        reconfigure(resetZoom: true)
        service.start()
        UIApplication.shared.isIdleTimerDisabled = true
    }

    func onActive() {
        guard ready else { return }
        motion.start()
        service.start()
        UIApplication.shared.isIdleTimerDisabled = true
    }

    func onBackground() {
        if recording { stopRecording() }
        astroCancel.value = true
        motion.stop()
        service.stop()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func onLive(_ li: LiveInfo) {
        live = li
        let suggest = li.ev100 < 3.5 && mode == .photo && !night
        if suggest != nightSuggested { nightSuggested = suggest }
    }

    private func onThermal() {
        thermalWarning = thermal.isWarm
        if thermal.shouldStopRecording && recording {
            stopRecording()
            toast("Recording stopped: the phone is too hot")
        }
    }

    private func onControlZoom(_ z: CGFloat) {
        controls.zoom = z
        if usesPhysicalLens {
            zoomFactor = z * (currentLens?.factor ?? 1)
        } else {
            let base = zoomStops.first { abs($0.factor - 1) < 0.01 }?.deviceZoom ?? 1
            zoomFactor = z / base
        }
    }

    // ---- session ------------------------------------------------------------------------
    private func targetDevice() -> AVCaptureDevice? {
        if front {
            return mode == .portrait ? (CameraService.depthDevice(front: true) ?? CameraService.front()) : CameraService.front()
        }
        switch mode {
        case .video, .proVideo: return CameraService.backVirtual() ?? currentLens?.device
        case .portrait: return CameraService.depthDevice(front: false) ?? currentLens?.device
        default: return currentLens?.device ?? CameraService.backVirtual()
        }
    }

    private func pickVideoMode(_ modes: [VideoModeOption]) -> VideoModeOption? {
        modes.first { $0.id == settings.videoModeKey }
            ?? modes.first { $0.width == 3840 && $0.height == 2160 && $0.fps == 30 }
            ?? modes.first { $0.width == 1920 && $0.fps == 30 }
            ?? modes.first
    }

    func reconfigure(resetZoom: Bool = false) {
        guard ready, let device = targetDevice() else { return }
        configToken += 1
        let token = configToken
        let mode = self.mode, st = settings
        let modes = mode.isVideo ? CameraService.videoModes(for: device) : videoModes
        let vmode = mode.isVideo ? pickVideoMode(modes) : nil
        let stops = usesPhysicalLens ? CameraService.photoZoomStops(lenses)
            : CameraService.virtualZoomStops(device, digital: !front && mode != .portrait)
        zoomStops = stops
        if resetZoom {
            if usesPhysicalLens {
                controls.zoom = 1
                zoomFactor = currentLens?.factor ?? 1
            } else {
                let one = stops.first { abs($0.factor - 1) < 0.01 } ?? stops.first
                controls.zoom = one?.deviceZoom ?? 1
                zoomFactor = one?.factor ?? 1
            }
        }
        if mode.isVideo { videoModes = modes; selectedVideo = vmode }
        let c = applyTorch(controls)
        let svc = service
        svc.queue.async { [weak self] in
            do {
                let result = try svc.configure(device: device, mode: mode, settings: st, videoMode: vmode)
                svc.apply(c)
                Task { @MainActor in
                    guard let self, token == self.configToken else { return }
                    self.config = result
                    if !mode.isVideo { self.videoModes = result.videoModes }
                    self.previewAspect = mode.isVideo ? result.previewAspect : st.aspect.viewfinder
                    if st.photoEngine == .proRAW && !result.proRAW && !mode.isVideo {
                        self.toast("Apple ProRAW is not offered by this camera")
                    } else if mode == .astro && !result.bayerRaw {
                        self.toast("Astro stacking needs a camera with Bayer RAW")
                    } else if mode == .portrait && !result.depth {
                        self.toast("Depth is not available on this camera")
                    }
                }
            } catch {
                Task { @MainActor in self?.toast("Camera error: \(error.localizedDescription)") }
            }
        }
    }

    private func applyTorch(_ c: ManualControls) -> ManualControls {
        var c = c
        c.torch = flash == .torch
        return c
    }

    private func settingsChanged(from old: AppSettings) {
        updateAnalysisOptions()
        let streams = old.photoEngine != settings.photoEngine || old.maxResolution != settings.maxResolution ||
            old.videoModeKey != settings.videoModeKey || old.videoCodec != settings.videoCodec ||
            old.videoColor != settings.videoColor || old.stabilization != settings.stabilization ||
            old.recordAudio != settings.recordAudio
        if old.aspect != settings.aspect && !mode.isVideo { previewAspect = settings.aspect.viewfinder }
        if streams { reconfigure() }
    }

    private func updateAnalysisOptions() {
        let s = settings
        service.analysisOptions = .init(peaking: s.focusPeaking, zebras: s.zebras, waveform: s.waveform)
        service.analysisEnabled = s.histogram || s.focusPeaking || s.zebras || s.waveform
        if !service.analysisEnabled { analysis = nil }
    }

    // ---- lenses & zoom ----------------------------------------------------------------------
    func selectZoom(_ s: ZoomStop) {
        if let id = s.lensID, id != currentLensID, usesPhysicalLens {
            currentLensID = id
            controls.zoom = s.deviceZoom
            zoomFactor = s.factor
            reconfigure()
        } else {
            controls.zoom = s.deviceZoom
            zoomFactor = s.factor
            service.apply(applyTorch(controls), rampZoom: true)
        }
    }

    func pinch(_ scale: CGFloat) {
        guard scale.isFinite, scale > 0 else { return }
        if usesPhysicalLens, let first = lenses.first, let last = lenses.last {
            let maxF = min(last.factor * 6, max(30, last.factor * 2))
            let target = min(max(zoomFactor * scale, first.factor), maxF)
            let lens = lenses.last { $0.factor <= target * 1.03 } ?? first
            zoomFactor = target
            controls.zoom = max(1, target / lens.factor)
            if lens.id != currentLensID {
                currentLensID = lens.id
                reconfigure()
            } else {
                service.apply(applyTorch(controls))
            }
        } else {
            let base = zoomStops.first { abs($0.factor - 1) < 0.01 }?.deviceZoom ?? 1
            let minZ = config?.minZoom ?? 1
            let maxZ = min(config?.maxZoom ?? 10, base * 30)
            controls.zoom = min(max(controls.zoom * scale, minZ), maxZ)
            zoomFactor = controls.zoom / base
            service.apply(applyTorch(controls))
        }
    }

    func flipCamera() {
        guard !recording, !busy else { return }
        front.toggle()
        if front && mode == .astro { mode = .photo }
        reconfigure(resetZoom: true)
    }

    func selectLens(_ lens: Lens) {
        front = false
        if mode.isVideo || mode == .portrait { mode = .photo }
        currentLensID = lens.id
        reconfigure(resetZoom: true)
        sheet = nil
    }

    // ---- modes ------------------------------------------------------------------------------
    func setMode(_ m: CaptureMode) {
        guard !recording, !busy, m != mode else { return }
        let wasPhysical = usesPhysicalLens
        mode = m
        proParam = nil
        if m != .photo { night = false }
        if !m.isPro {
            controls.iso = nil
            controls.shutter = nil
            controls.lens = nil
            controls.kelvin = nil
            controls.tint = 0
            controls.ev = 0
        }
        if m == .astro && front { front = false }
        if flash == .torch && !m.isVideo { flash = .off }
        reconfigure(resetZoom: wasPhysical != usesPhysicalLens || m == .portrait)
    }

    func swipeMode(_ dir: Int) {
        guard let i = Self.modes.firstIndex(of: mode) else { return }
        let j = min(max(i + dir, 0), Self.modes.count - 1)
        if j != i { setMode(Self.modes[j]) }
    }

    func toggleNight() {
        night.toggle()
        nightSuggested = false
    }

    // ---- controls ---------------------------------------------------------------------------
    func setControls(_ c: ManualControls) {
        controls = c
        service.apply(applyTorch(c))
    }

    func selectProParam(_ p: ProParam) { proParam = proParam == p ? nil : p }

    func tapFocus(at layerPoint: CGPoint) {
        service.focus(atLayerPoint: layerPoint, keepManualExposure: mode.isPro && controls.manualExposure)
        focusPoint = layerPoint
        focusTask?.cancel()
        focusTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if !Task.isCancelled { self?.focusPoint = nil }
        }
    }

    func cycleFlash() {
        switch flash {
        case .off: flash = mode.isVideo ? .torch : .auto
        case .auto: flash = .on
        case .on: flash = .torch
        case .torch: flash = .off
        }
        service.apply(applyTorch(controls))
    }

    func cycleTimer() {
        settings.timerSeconds = settings.timerSeconds == 0 ? 3 : (settings.timerSeconds == 3 ? 10 : 0)
    }

    func savePreset(_ name: String) {
        let c = controls
        var list = presets.filter { $0.name != name }
        list.append(ProPreset(name: name, iso: c.iso, shutter: c.shutter, ev: c.ev, lens: c.lens, kelvin: c.kelvin, tint: c.tint))
        presets = list
        AppSettings.savePresets(list)
        toast("Preset \"\(name)\" saved")
    }

    func applyPreset(_ p: ProPreset) {
        var c = controls
        c.iso = p.iso
        c.shutter = p.shutter
        c.ev = p.ev
        c.lens = p.lens
        c.kelvin = p.kelvin
        c.tint = p.tint
        setControls(c)
        sheet = nil
    }

    func deletePreset(_ p: ProPreset) {
        presets.removeAll { $0.name == p.name }
        AppSettings.savePresets(presets)
    }

    func loadHardwareReport() {
        let svc = service
        let extra: [(String, String)] = [
            ("Imaging core", String(cString: lumen_core_version())),
            ("AI models", "denoise \(ai.hasDenoiser ? "✓" : "–") · AI zoom \(ai.hasUpscaler ? "✓" : "–")"),
            ("Thermal state", "\(ProcessInfo.processInfo.thermalState.rawValue) (0 nominal … 3 critical)"),
            ("", ""),
        ]
        svc.queue.async { [weak self] in
            let rows = svc.hardwareReport()
            Task { @MainActor in self?.hardwareRows = extra + rows }
        }
    }

    func toast(_ text: String) {
        message = text
        messageTask?.cancel()
        messageTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if !Task.isCancelled, self?.message == text { self?.message = nil }
        }
    }

    // ---- shutter ----------------------------------------------------------------------------
    func shutter() {
        if busy {
            if mode == .astro { astroCancel.value = true }
            return
        }
        if mode.isVideo {
            if recording { stopRecording() } else { startRecording() }
            return
        }
        Task {
            for i in stride(from: settings.timerSeconds, to: 0, by: -1) {
                countdown = i
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            countdown = 0
            await takePhoto()
        }
    }

    private func shotContext() -> ShotContext {
        ShotContext(settings: settings, angle: service.captureRotationAngle, mirrored: front,
                    zoom: usesPhysicalLens ? controls.zoom : 1,
                    kelvin: mode.isPro ? controls.kelvin : nil, tint: controls.tint, aspect: settings.aspect)
    }

    private func progressHandler() -> @Sendable (String, Float) -> Void {
        { @Sendable [weak self] text, p in
            Task { @MainActor in
                self?.busyText = text
                self?.progress = p
            }
        }
    }

    private func takePhoto() async {
        guard !busy else { return }
        busy = true
        busyText = "Capturing"
        progress = 0
        defer {
            busy = false
            busyText = ""
            progress = 0
        }
        let ctx = shotContext()
        let raw = config?.bayerRaw == true
        do {
            let out: CaptureOutput
            switch mode {
            case .astro:
                guard raw else { throw CameraError.noRaw }
                astroCancel.value = false
                let flag = astroCancel
                out = try await pipeline.astro(ctx: ctx, onProgress: { @Sendable [weak self] p in
                    Task { @MainActor in
                        self?.busyText = p.text
                        self?.progress = p.total > 0 ? Float(p.frame) / Float(p.total) : 0
                        if let pv = p.preview { self?.astroPreview = pv }
                    }
                }, cancelled: { flag.value })
            case .portrait:
                out = try await pipeline.apple(ctx: ctx, flash: flash, mode: .portrait, aperture: settings.portraitAperture)
            case .pro where settings.saveRaw && raw && settings.photoEngine != .proRAW:
                out = try await pipeline.proRaw(ctx: ctx)
            default:
                let lumen = (settings.photoEngine == .lumen || night || mode == .pro) && settings.photoEngine != .proRAW
                if lumen && raw && flash != .on {
                    let plan = pipeline.planBurst(night: night, settings: settings,
                                                  manualISO: mode == .pro ? controls.iso : nil,
                                                  manualShutter: mode == .pro ? controls.shutter : nil)
                    out = try await pipeline.lumenBurst(plan: plan, night: night, ctx: ctx, progress: progressHandler())
                } else {
                    out = try await pipeline.apple(ctx: ctx, flash: flash, mode: mode, aperture: 0)
                }
            }
            thumbnail = out.thumbnail ?? thumbnail
            lastPreview = out.preview ?? lastPreview
            astroPreview = nil
            toast(out.message)
        } catch {
            astroPreview = nil
            toast("Capture failed: \(error.localizedDescription)")
        }
    }

    // ---- video ------------------------------------------------------------------------------
    private func autoBitrate(_ m: VideoModeOption, _ codec: VideoCodecChoice) -> Int {
        let bpp: Double = codec == .h264 ? 0.5 : 0.35
        let v = Double(m.width) * Double(m.height) * Double(min(m.fps, 60)) * bpp
        return Int(min(max(v, 8_000_000), 800_000_000))
    }

    private func startRecording() {
        guard let vmode = selectedVideo ?? videoModes.first else { toast("No video format"); return }
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("VID_\(f.string(from: Date())).mov")
        let st = settings
        let bitrate = st.bitrateMbps > 0 ? st.bitrateMbps * 1_000_000 : autoBitrate(vmode, st.videoCodec)
        let svc = service
        busy = true
        svc.queue.async { [weak self] in
            do {
                try svc.startRecording(to: url, codec: st.videoCodec, bitrate: bitrate, pcmAudio: st.pcmAudio)
                Task { @MainActor in
                    guard let self else { return }
                    self.busy = false
                    self.recording = true
                    self.recordSeconds = 0
                    self.recordTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                        Task { @MainActor in self?.recordSeconds += 1 }
                    }
                    let rate = self.config?.bitrateControl == true && st.videoCodec != .proRes422HQ && !st.videoCodec.isProResRAW
                        ? " · \(bitrate / 1_000_000) Mb/s" : ""
                    self.toast("\(vmode.label) · \(st.videoCodec.rawValue)\(rate) · \(st.videoColor.rawValue)")
                }
            } catch {
                Task { @MainActor in
                    self?.busy = false
                    self?.toast("Recording failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func stopRecording() {
        recordTimer?.invalidate()
        recordTimer = nil
        recording = false
        busy = true
        busyText = "Saving video"
        Task {
            if let url = await service.stopRecording() {
                if let t = await PhotoLibrary.videoThumbnail(url) { thumbnail = t; lastPreview = t }
                do { try await PhotoLibrary.saveVideo(url) } catch { toast("Save failed: \(error.localizedDescription)") }
            } else {
                toast("Recording failed")
            }
            busy = false
            busyText = ""
        }
    }
}
