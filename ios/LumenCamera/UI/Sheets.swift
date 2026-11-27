import AVFoundation
import SwiftUI

struct SheetContent: View {
    let sheet: Sheet
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        switch sheet {
        case .settings: SettingsSheet(vm: vm)
        case .hardware: HardwareSheet(vm: vm)
        case .more: MoreSheet(vm: vm)
        case .presets: PresetsSheet(vm: vm)
        case .viewer: ViewerSheet(vm: vm)
        }
    }
}

// MARK: - building blocks

private struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(LumenColors.accent)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 18)
            .padding(.bottom, 4)
    }
}

private struct SwitchRow: View {
    let title: String
    var sub: String? = nil
    @Binding var isOn: Bool
    var enabled = true

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundStyle(enabled ? Color.white : LumenColors.textDim)
                if let sub { Text(sub).font(.system(size: 12)).foregroundStyle(LumenColors.textDim) }
            }
        }
        .tint(LumenColors.accent)
        .disabled(!enabled)
        .padding(.vertical, 6)
    }
}

private struct OptionRow<T: Hashable>: View {
    let title: String
    let options: [(String, T)]
    let selected: T
    let onSelect: (T) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 15)).foregroundStyle(.white)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(options.indices, id: \.self) { i in
                        let opt = options[i]
                        let sel = opt.1 == selected
                        Button { onSelect(opt.1) } label: {
                            Text(opt.0)
                                .font(.system(size: 13))
                                .foregroundStyle(sel ? Color.black : Color.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Capsule().fill(sel ? LumenColors.accent : LumenColors.surfaceHigh))
                        }
                    }
                }
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - settings

struct SettingsSheet: View {
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        let st = vm.settings
        let cfg = vm.config
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionTitle(text: "Photo")
                OptionRow(title: "Processing", options: PhotoEngine.allCases.map { ($0.rawValue, $0) }, selected: st.photoEngine) {
                    vm.settings.photoEngine = $0
                }
                OptionRow(title: "Color profile", options: [("Natural", Int32(0)), ("Vivid", Int32(1)), ("Film", Int32(2)), ("Mono", Int32(3))],
                          selected: st.profile) { vm.settings.profile = $0 }
                SwitchRow(title: "Save RAW (DNG)", sub: "Merged bursts give a cleaner, higher bit-depth DNG",
                          isOn: $vm.settings.saveRaw, enabled: cfg?.bayerRaw ?? true)
                SwitchRow(title: "Maximum resolution",
                          sub: "Apple processing / ProRAW: \(cfg?.maxPhotoMP ?? 12) MP now. Lumen bursts use the 12 MP Bayer RAW.",
                          isOn: $vm.settings.maxResolution)
                OptionRow(title: "Burst frames", options: [("Auto", 0), ("4", 4), ("6", 6), ("8", 8), ("10", 10), ("12", 12), ("15", 15)],
                          selected: st.burstFrames) { vm.settings.burstFrames = $0 }
                SwitchRow(title: "AI denoise", sub: vm.ai.hasDenoiser ? "FFDNet (open source), noise-level aware" : "Model not bundled (see README)",
                          isOn: $vm.settings.aiDenoise)
                SwitchRow(title: "AI zoom", sub: vm.ai.hasUpscaler ? "Real-ESRGAN x4 for digital zoom beyond the lenses" : "Model not bundled (see README)",
                          isOn: $vm.settings.aiZoom)
                SwitchRow(title: "Display P3 colors", isOn: $vm.settings.displayP3)
                OptionRow(title: "HEIC quality", options: [("90", 0.90), ("92", 0.92), ("95", 0.95), ("98", 0.98), ("100", 1.0)],
                          selected: st.heicQuality) { vm.settings.heicQuality = $0 }

                SectionTitle(text: "Video")
                if !vm.videoModes.isEmpty {
                    OptionRow(title: "Resolution & frame rate", options: vm.videoModes.map { ($0.label, $0.id) },
                              selected: vm.selectedVideo?.id ?? st.videoModeKey) { vm.settings.videoModeKey = $0 }
                }
                OptionRow(title: "Codec", options: VideoCodecChoice.allCases.map { (c: VideoCodecChoice) -> (String, VideoCodecChoice) in
                    let ok = cfg.map { cfg in cfg.codecs.isEmpty || cfg.codecs.contains(AVVideoCodecType(rawValue: c.codecRawValue)) } ?? true
                    return (c.rawValue + (ok ? "" : " ✕"), c)
                }, selected: st.videoCodec) { vm.settings.videoCodec = $0 }
                OptionRow(title: "Color", options: VideoColor.allCases.map { ($0.rawValue, $0) }, selected: st.videoColor) {
                    vm.settings.videoColor = $0
                }
                OptionRow(title: cfg?.bitrateControl == false && vm.mode.isVideo ? "Bitrate (set by iOS for this format)" : "Bitrate (Mb/s)",
                          options: [("Auto", 0)] + [20, 50, 100, 150, 200, 300, 400, 600, 800].map { ("\($0)", $0) },
                          selected: st.bitrateMbps) { vm.settings.bitrateMbps = $0 }
                SwitchRow(title: "Video stabilization", sub: "Strongest mode the format supports", isOn: $vm.settings.stabilization)
                SwitchRow(title: "Record audio", sub: "Uses an external USB-C / Bluetooth mic when connected", isOn: $vm.settings.recordAudio)
                SwitchRow(title: "Uncompressed audio (PCM 24-bit)", sub: "When the format allows it", isOn: $vm.settings.pcmAudio)

                SectionTitle(text: "Astro (tripod)")
                OptionRow(title: "Frames", options: [10, 20, 30, 60, 100, 200].map { ("\($0)", $0) }, selected: st.astroFrames) {
                    vm.settings.astroFrames = $0
                }
                let maxExp = cfg?.shutterRange.upperBound ?? 1
                OptionRow(title: String(format: "Exposure per frame (max %.1f s)", maxExp),
                          options: [0.25, 0.5, 1, 2, 4, 8, 15, 30].filter { $0 <= maxExp + 0.01 }.map { (Format.shutter($0), $0) },
                          selected: st.astroExposure) { vm.settings.astroExposure = $0 }
                OptionRow(title: "ISO", options: [400, 800, 1600, 3200, 6400].map { ("\($0)", Float($0)) }, selected: st.astroISO) {
                    vm.settings.astroISO = $0
                }
                OptionRow(title: "Stacking", options: [("Sigma clip", Int32(0)), ("Median", Int32(1)), ("Mean", Int32(2)), ("Star trails", Int32(3))],
                          selected: st.astroStackMode) { vm.settings.astroStackMode = $0 }
                SwitchRow(title: "Align stars", sub: "Compensates Earth's rotation", isOn: $vm.settings.astroAlign)
                OptionRow(title: "Dark frames (you will be asked to cover the lens)", options: [("0", 0), ("5", 5), ("10", 10)],
                          selected: st.astroDarkFrames) { vm.settings.astroDarkFrames = $0 }

                SectionTitle(text: "Viewfinder")
                SwitchRow(title: "Grid", isOn: $vm.settings.grid)
                SwitchRow(title: "Histogram", isOn: $vm.settings.histogram)
                SwitchRow(title: "Waveform", isOn: $vm.settings.waveform)
                SwitchRow(title: "Focus peaking", isOn: $vm.settings.focusPeaking)
                SwitchRow(title: "Zebras (clipping)", isOn: $vm.settings.zebras)
                SwitchRow(title: "Level", isOn: $vm.settings.level)

                SectionTitle(text: "Tools")
                Button("Hardware report") { vm.sheet = .hardware }
                    .buttonStyle(.borderedProminent).tint(LumenColors.surfaceHigh)
                Text("Lumen Camera · imaging core \(String(cString: lumen_core_version())) · open-source models: FFDNet (MIT), Real-ESRGAN (BSD-3)")
                    .font(.system(size: 11)).foregroundStyle(LumenColors.textDim).padding(.top, 14)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
    }
}

// MARK: - hardware

struct HardwareSheet: View {
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 3) {
                Text("Hardware").font(.title3.bold()).foregroundStyle(.white).padding(.vertical, 12)
                ForEach(vm.hardwareRows.indices, id: \.self) { i in
                    let row = vm.hardwareRows[i]
                    if row.0.isEmpty && row.1.isEmpty {
                        Divider().overlay(LumenColors.surfaceHigh).padding(.vertical, 6)
                    } else {
                        HStack(alignment: .top) {
                            Text(row.0).font(.system(size: 12)).foregroundStyle(LumenColors.textDim)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text(row.1).font(.system(size: 12)).foregroundStyle(.white)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .onAppear { vm.loadHardwareReport() }
    }
}

// MARK: - more

struct MoreSheet: View {
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionTitle(text: "Lenses")
                ForEach(vm.lenses) { lens in
                    let on = lens.id == vm.currentLensID && !vm.front
                    Button { vm.selectLens(lens) } label: {
                        HStack {
                            Text("\(lens.device.localizedName)  ·  \(ZoomStop.label(lens.factor))x")
                                .foregroundStyle(on ? LumenColors.accent : .white)
                            Spacer()
                            if on { Image(systemName: "checkmark").foregroundStyle(LumenColors.accent) }
                        }
                        .font(.system(size: 15))
                        .padding(.vertical, 9)
                    }
                }
                SectionTitle(text: "More")
                HStack(spacing: 10) {
                    Button("Hardware") { vm.sheet = .hardware }
                    Button("Settings") { vm.sheet = .settings }
                }
                .buttonStyle(.borderedProminent).tint(LumenColors.surfaceHigh)
                Text("Photo modes use one physical lens at a time (Bayer RAW is per-lens); video and portrait use the multi-camera for seamless zoom and depth.")
                    .font(.system(size: 12)).foregroundStyle(LumenColors.textDim).padding(.top, 14)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
        }
    }
}

// MARK: - presets

struct PresetsSheet: View {
    @ObservedObject var vm: CameraViewModel
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: "Pro presets")
            if vm.presets.isEmpty {
                Text("No presets yet").foregroundStyle(LumenColors.textDim)
            }
            ForEach(vm.presets) { p in
                HStack {
                    Button { vm.applyPreset(p) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.name).font(.system(size: 15)).foregroundStyle(.white)
                            Text(summary(p)).font(.system(size: 12)).foregroundStyle(LumenColors.textDim)
                        }
                    }
                    Spacer()
                    Button("Delete", role: .destructive) { vm.deletePreset(p) }
                }
            }
            HStack {
                TextField("Name", text: $name)
                    .textFieldStyle(.roundedBorder)
                Button("Save current") {
                    let n = name.trimmingCharacters(in: .whitespaces)
                    if !n.isEmpty { vm.savePreset(n); name = "" }
                }
                .buttonStyle(.borderedProminent).tint(LumenColors.accent).foregroundStyle(.black)
            }
            .padding(.top, 8)
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    private func summary(_ p: ProPreset) -> String {
        var parts: [String] = []
        if let iso = p.iso { parts.append("ISO \(Int(iso))") }
        if let s = p.shutter { parts.append(Format.shutter(s)) }
        if let k = p.kelvin { parts.append("\(Int(k))K") }
        if abs(p.ev) > 0.01 { parts.append("EV \(Format.ev(p.ev))") }
        if p.lens != nil { parts.append("MF") }
        return parts.isEmpty ? "Auto" : parts.joined(separator: " · ")
    }
}

// MARK: - viewer

struct ViewerSheet: View {
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        VStack(spacing: 12) {
            if let img = vm.lastPreview {
                Image(uiImage: img).resizable().scaledToFit()
            }
            Button("Open Photos") {
                if let url = URL(string: "photos-redirect://") { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent).tint(LumenColors.surfaceHigh)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}
