import SwiftUI

// MARK: - top bar

struct TopBar: View {
    let flash: FlashMode
    let timer: Int
    let aspectLabel: String
    let resolutionLabel: String
    let rawOn: Bool
    let rawAvailable: Bool
    let aiOn: Bool
    let showNight: Bool
    let nightOn: Bool
    let nightSuggested: Bool
    let iconRotation: Double
    let onSettings: () -> Void
    let onFlash: () -> Void
    let onTimer: () -> Void
    let onAspect: () -> Void
    let onResolution: () -> Void
    let onRaw: () -> Void
    let onAi: () -> Void
    let onNight: () -> Void

    private var flashIcon: String {
        switch flash {
        case .off: return "bolt.slash"
        case .auto: return "bolt.badge.automatic"
        case .on: return "bolt.fill"
        case .torch: return "flashlight.on.fill"
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            IconButton(system: "gearshape", tint: .white, rotation: iconRotation, label: "Settings", action: onSettings)
            Spacer(minLength: 2)
            IconButton(system: flashIcon, tint: flash == .off ? .white : LumenColors.accent, rotation: iconRotation,
                       label: "Flash", action: onFlash)
            Spacer(minLength: 2)
            ZStack(alignment: .bottomTrailing) {
                IconButton(system: "timer", tint: timer == 0 ? .white : LumenColors.accent, rotation: iconRotation,
                           label: "Timer", action: onTimer)
                if timer > 0 {
                    Text("\(timer)").font(.system(size: 9, weight: .bold)).foregroundStyle(LumenColors.accent)
                        .offset(x: -6, y: -6)
                }
            }
            Spacer(minLength: 2)
            TextChip(text: aspectLabel, on: false, rotation: iconRotation, action: onAspect)
            Spacer(minLength: 2)
            TextChip(text: resolutionLabel, on: false, rotation: iconRotation, action: onResolution)
            Spacer(minLength: 2)
            TextChip(text: "RAW", on: rawOn, rotation: iconRotation, action: onRaw).opacity(rawAvailable ? 1 : 0.4)
            Spacer(minLength: 2)
            IconButton(system: "sparkles", tint: aiOn ? LumenColors.accent : .white, rotation: iconRotation,
                       label: "AI enhance", action: onAi)
            if showNight {
                Spacer(minLength: 2)
                IconButton(system: nightOn ? "moon.stars.fill" : "moon.stars",
                           tint: nightOn ? LumenColors.accent : (nightSuggested ? Color(red: 1, green: 0.88, blue: 0.54) : .white),
                           rotation: iconRotation, label: "Night", action: onNight)
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 48)
    }
}

struct IconButton: View {
    let system: String
    let tint: Color
    let rotation: Double
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(tint)
                .rotationEffect(.degrees(rotation))
                .animation(.easeInOut(duration: 0.25), value: rotation)
                .frame(width: 38, height: 38)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
    }
}

struct TextChip: View {
    let text: String
    let on: Bool
    let rotation: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(on ? LumenColors.accent : .white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(on ? LumenColors.accent : .white.opacity(0.8), lineWidth: 1))
                .rotationEffect(.degrees(rotation))
                .animation(.easeInOut(duration: 0.25), value: rotation)
        }
    }
}

// MARK: - zoom pills

struct ZoomBar: View {
    let stops: [ZoomStop]
    let factor: CGFloat
    let rotation: Double
    let onSelect: (ZoomStop) -> Void

    var body: some View {
        if stops.isEmpty {
            EmptyView()
        } else {
            let active = stops.min { abs($0.factor - factor) < abs($1.factor - factor) }
            HStack(spacing: 4) {
                ForEach(stops) { s in
                    let sel = s == active
                    Button { onSelect(s) } label: {
                        Text(sel ? label(factor) : s.label)
                            .font(.system(size: sel ? 13 : 11, weight: .bold))
                            .foregroundStyle(sel ? LumenColors.accent : .white)
                            .rotationEffect(.degrees(rotation))
                            .frame(width: sel ? 42 : 34, height: sel ? 42 : 34)
                            .background(Circle().fill(sel ? Color(white: 0.12).opacity(0.85) : Color.black.opacity(0.4)))
                    }
                }
            }
            .padding(4)
            .background(Capsule().fill(Color.black.opacity(0.33)))
            .animation(.easeOut(duration: 0.15), value: factor)
        }
    }

    private func label(_ f: CGFloat) -> String {
        if f < 1 { return String(format: "%.1fx", Double(f)).replacingOccurrences(of: "0.", with: ".") }
        if abs(f - f.rounded()) < 0.05 { return "\(Int(f.rounded()))x" }
        return String(format: "%.1fx", Double(f))
    }
}

// MARK: - mode carousel

struct ModeCarousel: View {
    let modes: [CaptureMode]
    let current: CaptureMode
    let onSelect: (CaptureMode) -> Void
    let onMore: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    Spacer().frame(width: 90)
                    ForEach(modes) { m in
                        let sel = m == current
                        Button { onSelect(m) } label: {
                            Text(m.rawValue)
                                .font(.system(size: 13, weight: sel ? .bold : .medium))
                                .foregroundStyle(sel ? Color.white : LumenColors.textDim)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 7)
                                .background(Capsule().fill(sel ? Color(white: 0.17) : Color.clear))
                        }
                        .id(m)
                    }
                    Button(action: onMore) {
                        Text("MORE").font(.system(size: 13, weight: .medium)).foregroundStyle(LumenColors.textDim)
                            .padding(.horizontal, 14).padding(.vertical, 7)
                    }
                    Spacer().frame(width: 90)
                }
            }
            .frame(height: 44)
            .onAppear { proxy.scrollTo(current, anchor: .center) }
            .onChange(of: current) { _, m in withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(m, anchor: .center) } }
        }
    }
}

// MARK: - shutter row

struct ShutterRow: View {
    let mode: CaptureMode
    let recording: Bool
    let busy: Bool
    let progress: Float
    let countdown: Int
    let thumbnail: UIImage?
    let rotation: Double
    let onShutter: () -> Void
    let onGallery: () -> Void
    let onFlip: () -> Void

    var body: some View {
        HStack {
            Button(action: onGallery) {
                ZStack {
                    Circle().fill(Color(white: 0.13))
                    if let t = thumbnail {
                        Image(uiImage: t).resizable().scaledToFill()
                            .rotationEffect(.degrees(rotation))
                            .frame(width: 52, height: 52).clipShape(Circle())
                    }
                    Circle().stroke(Color.white, lineWidth: 1.5)
                }
                .frame(width: 52, height: 52)
            }
            .accessibilityLabel("Last capture")
            Spacer()
            ShutterButton(mode: mode, recording: recording, busy: busy, progress: progress, countdown: countdown, action: onShutter)
            Spacer()
            Button(action: onFlip) {
                Image(systemName: "arrow.triangle.2.circlepath.camera")
                    .font(.system(size: 20))
                    .foregroundStyle(.white)
                    .rotationEffect(.degrees(rotation))
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(Color.white.opacity(0.2)))
            }
            .accessibilityLabel("Switch camera")
        }
        .padding(.horizontal, 34)
        .frame(height: 112)
    }
}

struct ShutterButton: View {
    let mode: CaptureMode
    let recording: Bool
    let busy: Bool
    let progress: Float
    let countdown: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(Color.white, lineWidth: 4).frame(width: 74, height: 74)
                if busy && progress > 0 {
                    Circle().trim(from: 0, to: CGFloat(min(max(progress, 0), 1)))
                        .stroke(LumenColors.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 74, height: 74)
                }
                let fill: Color = mode.isVideo ? LumenColors.record : (mode == .astro ? Color(red: 0.87, green: 0.89, blue: 1) : .white)
                if recording {
                    RoundedRectangle(cornerRadius: 6).fill(fill).frame(width: 28, height: 28)
                } else {
                    Circle().fill(busy ? fill.opacity(0.5) : fill).frame(width: 60, height: 60)
                }
                if countdown > 0 {
                    Text("\(countdown)").font(.system(size: 26, weight: .bold)).foregroundStyle(.black)
                }
            }
            .frame(width: 78, height: 78)
            .animation(.easeInOut(duration: 0.18), value: recording)
        }
        .accessibilityLabel(mode.isVideo ? (recording ? "Stop recording" : "Record") : "Take photo")
    }
}

// MARK: - pro controls

struct ProValue: Identifiable {
    let param: ProParam
    let value: String
    let auto: Bool
    var id: String { param.rawValue }
}

struct ProChips: View {
    let values: [ProValue]
    let selected: ProParam?
    let onSelect: (ProParam) -> Void
    let onReset: (ProParam) -> Void
    let onPresets: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(values) { v in
                    let sel = v.param == selected
                    VStack(spacing: 1) {
                        Text(v.param.rawValue).font(.system(size: 10)).foregroundStyle(LumenColors.textDim)
                        Text(v.value).font(.system(size: 13, weight: .bold)).foregroundStyle(v.auto ? Color.white : LumenColors.accent)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10).fill(sel ? Color(white: 0.17) : Color.black.opacity(0.27)))
                    .onTapGesture { onSelect(v.param) }
                    .onLongPressGesture { onReset(v.param) }
                }
                Button(action: onPresets) {
                    VStack(spacing: 1) {
                        Text("PRESET").font(.system(size: 10)).foregroundStyle(LumenColors.textDim)
                        Image(systemName: "list.bullet").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.27)))
                }
            }
            .padding(.horizontal, 8)
        }
    }
}

/// Horizontal "ruler" dial: drag to move through `labels`.
struct RulerDial: View {
    let labels: [String]
    let index: Int
    let onIndex: (Int) -> Void
    @State private var startIndex: Int?
    private let spacing: CGFloat = 13

    var body: some View {
        let idx = min(max(index, 0), max(0, labels.count - 1))
        VStack(spacing: 2) {
            Text(labels.isEmpty ? "" : labels[idx])
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(LumenColors.accent)
            Canvas { ctx, size in
                let cx = size.width / 2
                for i in labels.indices {
                    let x = cx + CGFloat(i - idx) * spacing
                    if x < 0 || x > size.width { continue }
                    let major = i % 3 == 0
                    var p = Path()
                    p.move(to: CGPoint(x: x, y: major ? 4 : 12))
                    p.addLine(to: CGPoint(x: x, y: size.height - 4))
                    ctx.stroke(p, with: .color(i == idx ? LumenColors.accent : .white.opacity(major ? 0.9 : 0.45)),
                               lineWidth: i == idx ? 3 : 1.5)
                }
            }
            .frame(height: 34)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { v in
                        if startIndex == nil { startIndex = idx }
                        let steps = Int((-v.translation.width / spacing).rounded())
                        let n = min(max((startIndex ?? idx) + steps, 0), labels.count - 1)
                        if n != idx {
                            UISelectionFeedbackGenerator().selectionChanged()
                            onIndex(n)
                        }
                    }
                    .onEnded { _ in startIndex = nil }
            )
        }
        .padding(.vertical, 4)
    }
}
