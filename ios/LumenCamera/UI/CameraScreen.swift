import AVKit
import SwiftUI

struct CameraScreen: View {
    @ObservedObject var vm: CameraViewModel
    @ObservedObject var motion: DeviceMotion

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                viewfinder
                if vm.mode.isPro, let cfg = vm.config {
                    ProPanel(vm: vm, config: cfg)
                }
                if vm.mode == .portrait {
                    ApertureDial(value: vm.settings.portraitAperture) { vm.settings.portraitAperture = $0 }
                }
                ZoomBar(stops: vm.zoomStops, factor: vm.zoomFactor, rotation: motion.iconRotation) { vm.selectZoom($0) }
                    .padding(.vertical, 6)
                ModeCarousel(modes: CameraViewModel.modes, current: vm.mode, onSelect: { vm.setMode($0) },
                             onMore: { vm.sheet = .more })
                ShutterRow(mode: vm.mode, recording: vm.recording, busy: vm.busy, progress: vm.progress,
                           countdown: vm.countdown, thumbnail: vm.thumbnail, rotation: motion.iconRotation,
                           onShutter: { vm.shutter() },
                           onGallery: { if vm.lastPreview != nil { vm.sheet = .viewer } },
                           onFlip: { vm.flipCamera() })
            }
            if vm.permissionDenied {
                PermissionView()
            }
        }
        .sheet(item: $vm.sheet) { s in
            SheetContent(sheet: s, vm: vm)
                .presentationDetents(s == .viewer ? [.large] : [.medium, .large])
                .presentationBackground(LumenColors.surface)
                .preferredColorScheme(.dark)
        }
        .modifier(HardwareShutter(enabled: vm.ready && vm.sheet == nil, action: { vm.shutter() }))
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
    }

    private var topBar: some View {
        let st = vm.settings
        let raw = vm.config?.bayerRaw == true || vm.config?.proRAW == true
        return TopBar(
            flash: vm.flash, timer: st.timerSeconds,
            aspectLabel: vm.mode.isVideo ? (vm.previewAspect > 0.7 ? "3:4" : "9:16") : st.aspect.rawValue,
            resolutionLabel: resolutionLabel,
            rawOn: st.saveRaw || st.photoEngine == .proRAW, rawAvailable: raw,
            aiOn: st.aiDenoise || st.aiZoom,
            showNight: vm.mode == .photo, nightOn: vm.night, nightSuggested: vm.nightSuggested,
            iconRotation: motion.iconRotation,
            onSettings: { vm.sheet = .settings },
            onFlash: { vm.cycleFlash() },
            onTimer: { vm.cycleTimer() },
            onAspect: { if !vm.mode.isVideo { vm.settings.aspect = st.aspect.next } },
            onResolution: { if !vm.mode.isVideo { vm.settings.maxResolution.toggle() } },
            onRaw: { if raw { vm.settings.saveRaw.toggle() } },
            onAi: {
                let on = !(st.aiDenoise || st.aiZoom)
                vm.settings.aiDenoise = on
                vm.settings.aiZoom = on
            },
            onNight: { vm.toggleNight() })
    }

    private var resolutionLabel: String {
        if vm.mode.isVideo, let m = vm.selectedVideo {
            return m.width >= 3840 ? "4K\(m.fps)" : "\(m.height)p\(m.fps)"
        }
        let usesLumen = vm.settings.photoEngine == .lumen || vm.night || vm.mode == .pro || vm.mode == .astro
        if usesLumen && vm.config?.bayerRaw == true { return "12M" }
        return "\(vm.config?.maxPhotoMP ?? 12)M"
    }

    private var viewfinder: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = min(geo.size.height, w / max(0.3, vm.previewAspect))
            ZStack {
                CameraPreview(service: vm.service,
                              onTap: { vm.tapFocus(at: $0) },
                              onPinch: { vm.pinch($0) },
                              onSwipe: { vm.swipeMode($0) })
                ViewfinderOverlays(grid: vm.settings.grid, level: vm.settings.level, roll: motion.roll,
                                   histogram: vm.settings.histogram, waveform: vm.settings.waveform,
                                   analysis: vm.analysis, focusPoint: vm.focusPoint)
                if let a = vm.astroPreview {
                    Image(uiImage: a).resizable().scaledToFill().allowsHitTesting(false)
                }
                VStack(spacing: 6) {
                    if vm.mode.isPro || vm.mode == .astro {
                        InfoText(text: "ISO \(Int(vm.live.iso)) · \(Format.shutter(vm.live.shutter)) · ƒ" +
                                 String(format: "%.1f", vm.live.aperture) + " · \(Int(vm.live.kelvin))K")
                    }
                    if vm.recording {
                        InfoText(text: String(format: "● REC  %02ld:%02ld", vm.recordSeconds / 60, vm.recordSeconds % 60),
                                 color: LumenColors.record)
                    }
                    if vm.night { InfoText(text: "Night · hold steady") }
                    if vm.mode == .astro && !vm.busy { InfoText(text: "Tripod · \(vm.settings.astroFrames) × \(Format.shutter(vm.settings.astroExposure))") }
                    if vm.thermalWarning { InfoText(text: "iPhone is warm — performance may be limited") }
                    Spacer()
                    if vm.busy || vm.message != nil {
                        VStack(spacing: 6) {
                            Text(vm.busy ? vm.busyText : (vm.message ?? ""))
                                .font(.system(size: 13)).foregroundStyle(.white).multilineTextAlignment(.center)
                            if vm.busy && vm.progress > 0 {
                                ProgressView(value: Double(min(max(vm.progress, 0), 1)))
                                    .tint(LumenColors.accent).frame(width: 160)
                            }
                        }
                        .padding(10)
                        .background(Color.black.opacity(0.67), in: RoundedRectangle(cornerRadius: 10))
                        .padding(12)
                    }
                }
                .padding(.top, 8)
                .allowsHitTesting(false)
            }
            .frame(width: w, height: h)
            .clipped()
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }
}

// MARK: - Pro panel

private let isoStops: [Float] = [25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
                                 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800]
private let shutterStops: [Double] = [24000, 16000, 12000, 8000, 6000, 4000, 3000, 2000, 1500, 1000, 750, 500, 350, 250,
                                      180, 125, 90, 60, 45, 30, 20, 15, 10, 8, 6, 4].map { 1 / $0 } + [0.3, 0.5, 0.8, 1, 1.5, 2, 3, 4, 6, 8, 10, 15, 20, 30]

struct ProPanel: View {
    @ObservedObject var vm: CameraViewModel
    let config: CameraService.ConfigResult

    var body: some View {
        let c = vm.controls
        let isoList = isoStops.filter { config.isoRange.contains($0) }
        let shList = shutterStops.filter { config.shutterRange.contains($0) }
        let evList = stride(from: max(-4, config.evRange.lowerBound), through: min(4, config.evRange.upperBound) + 0.001,
                            by: 1.0 / 3.0).map { Float($0) }
        let kList = Array(stride(from: Float(2000), through: 10000, by: 100))
        let focusSteps = 48
        VStack(spacing: 2) {
            ProChips(values: [
                ProValue(param: .iso, value: c.iso.map { "\(Int($0))" } ?? "A", auto: c.iso == nil),
                ProValue(param: .shutter, value: c.shutter.map { Format.shutter($0) } ?? "A", auto: c.shutter == nil),
                ProValue(param: .ev, value: Format.ev(c.ev), auto: abs(c.ev) < 0.01),
                ProValue(param: .focus, value: c.lens.map { focusLabel($0) } ?? "AF", auto: c.lens == nil),
                ProValue(param: .wb, value: c.kelvin.map { "\(Int($0))K" } ?? "AWB", auto: c.kelvin == nil),
            ], selected: vm.proParam,
               onSelect: { vm.selectProParam($0) },
               onReset: { p in
                   var n = c
                   switch p {
                   case .iso: n.iso = nil
                   case .shutter: n.shutter = nil
                   case .ev: n.ev = 0
                   case .focus: n.lens = nil
                   case .wb: n.kelvin = nil; n.tint = 0
                   }
                   vm.setControls(n)
               },
               onPresets: { vm.sheet = .presets })
            switch vm.proParam {
            case .iso:
                if config.customExposure && !isoList.isEmpty {
                    let idx = c.iso.map { v in 1 + (isoList.firstIndex { $0 >= v } ?? isoList.count - 1) } ?? 0
                    RulerDial(labels: ["A"] + isoList.map { "\(Int($0))" }, index: idx) { i in
                        var n = c; n.iso = i == 0 ? nil : isoList[i - 1]; vm.setControls(n)
                    }
                } else { unsupported("Manual ISO is not supported on this camera") }
            case .shutter:
                if config.customExposure && !shList.isEmpty {
                    let idx = c.shutter.map { v in 1 + (shList.firstIndex { $0 >= v * 0.999 } ?? shList.count - 1) } ?? 0
                    RulerDial(labels: ["A"] + shList.map { Format.shutter($0) }, index: idx) { i in
                        var n = c; n.shutter = i == 0 ? nil : shList[i - 1]; vm.setControls(n)
                    }
                } else { unsupported("Manual shutter is not supported on this camera") }
            case .ev:
                let idx = evList.firstIndex { abs($0 - c.ev) < 0.17 } ?? (evList.count / 2)
                RulerDial(labels: evList.map { Format.ev($0) }, index: idx) { i in
                    var n = c; n.ev = evList[i]; vm.setControls(n)
                }
            case .focus:
                if config.manualFocus {
                    let idx = c.lens.map { 1 + Int(($0 * Float(focusSteps)).rounded()) } ?? 0
                    RulerDial(labels: ["AF"] + (0...focusSteps).map { focusLabel(Float($0) / Float(focusSteps)) }, index: idx) { i in
                        var n = c; n.lens = i == 0 ? nil : Float(i - 1) / Float(focusSteps); vm.setControls(n)
                    }
                } else { unsupported("Fixed-focus lens") }
            case .wb:
                if config.manualWB {
                    let idx = c.kelvin.map { v in 1 + (kList.firstIndex { $0 >= v } ?? kList.count - 1) } ?? 0
                    RulerDial(labels: ["AWB"] + kList.map { "\(Int($0))K" }, index: idx) { i in
                        var n = c; n.kelvin = i == 0 ? nil : kList[i - 1]; vm.setControls(n)
                    }
                    if c.kelvin != nil {
                        let tints = Array(stride(from: Float(-50), through: 50, by: 5))
                        RulerDial(labels: tints.map { String(format: "Tint %+d", Int($0)) },
                                  index: tints.firstIndex { $0 >= c.tint } ?? 10) { i in
                            var n = c; n.tint = tints[i]; vm.setControls(n)
                        }
                    } else if vm.live.kelvin > 0 {
                        Text("Auto ≈ \(Int(vm.live.kelvin))K").font(.system(size: 11)).foregroundStyle(LumenColors.textDim)
                    }
                } else { unsupported("Manual white balance is not supported on this camera") }
            case nil:
                EmptyView()
            }
        }
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.6))
    }

    private func focusLabel(_ v: Float) -> String {
        if v >= 0.999 { return "∞" }
        if v <= 0.001 { return "Macro" }
        return String(format: "%.2f", v)
    }

    private func unsupported(_ text: String) -> some View {
        Text(text).font(.system(size: 12)).foregroundStyle(LumenColors.textDim).padding(8)
    }
}

/// Portrait blur strength as an f-number (Core Image depth blur).
struct ApertureDial: View {
    let value: Float
    let onChange: (Float) -> Void
    private let stops: [Float] = [1.4, 1.8, 2, 2.2, 2.8, 3.2, 4, 4.5, 5.6, 6.3, 8, 11, 16]

    var body: some View {
        let idx = stops.firstIndex { $0 >= value - 0.01 } ?? 4
        RulerDial(labels: stops.map { String(format: "ƒ%.1f", $0) }, index: idx) { onChange(stops[$0]) }
            .background(Color.black.opacity(0.6))
    }
}

/// Volume buttons, Action button and Camera Control take photos (iOS 18+).
struct HardwareShutter: ViewModifier {
    let enabled: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onCameraCaptureEvent(isEnabled: enabled) { event in
                if event.phase == .ended { action() }
            }
        } else {
            content
        }
    }
}

struct PermissionView: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "camera.fill").font(.system(size: 40)).foregroundStyle(LumenColors.accent)
            Text("Camera access is off").font(.title3.bold()).foregroundStyle(.white)
            Text("Allow camera access for Lumen in Settings to take photos and videos.")
                .font(.subheadline).foregroundStyle(LumenColors.textDim).multilineTextAlignment(.center)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent).tint(LumenColors.accent).foregroundStyle(.black)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}
