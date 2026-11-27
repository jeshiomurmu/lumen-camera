import SwiftUI

/// Grid, level, focus peaking, zebras, histogram and waveform over the viewfinder.
struct ViewfinderOverlays: View {
    let grid: Bool
    let level: Bool
    let roll: Double
    let histogram: Bool
    let waveform: Bool
    let analysis: FrameAnalysis?
    let focusPoint: CGPoint?

    var body: some View {
        ZStack {
            if let p = analysis?.peaking {
                Image(decorative: p, scale: 1).resizable().interpolation(.none).aspectRatio(contentMode: .fill)
            }
            if let z = analysis?.zebras {
                Image(decorative: z, scale: 1).resizable().interpolation(.none).aspectRatio(contentMode: .fill)
            }
            Canvas { ctx, size in
                let w = size.width, h = size.height
                if grid {
                    var p = Path()
                    for i in 1...2 {
                        p.move(to: CGPoint(x: w * CGFloat(i) / 3, y: 0))
                        p.addLine(to: CGPoint(x: w * CGFloat(i) / 3, y: h))
                        p.move(to: CGPoint(x: 0, y: h * CGFloat(i) / 3))
                        p.addLine(to: CGPoint(x: w, y: h * CGFloat(i) / 3))
                    }
                    ctx.stroke(p, with: .color(.white.opacity(0.35)), lineWidth: 0.7)
                }
                if level && abs(roll) < 30 {
                    let ok = abs(roll) < 1
                    var line = Path()
                    line.move(to: CGPoint(x: -w * 0.15, y: 0))
                    line.addLine(to: CGPoint(x: w * 0.15, y: 0))
                    let t = CGAffineTransform(translationX: w / 2, y: h / 2).rotated(by: CGFloat(-roll * .pi / 180))
                    ctx.stroke(line.applying(t), with: .color(ok ? LumenColors.good : .white.opacity(0.8)), lineWidth: ok ? 3 : 2)
                }
                if let f = focusPoint {
                    let r: CGFloat = 34
                    ctx.stroke(Path(roundedRect: CGRect(x: f.x - r, y: f.y - r, width: 2 * r, height: 2 * r), cornerRadius: 6),
                               with: .color(LumenColors.accent), lineWidth: 1.5)
                }
            }
            VStack {
                HStack {
                    Spacer()
                    if histogram, let a = analysis, !a.luma.isEmpty {
                        HistogramView(analysis: a).frame(width: 112, height: 56)
                    }
                }
                Spacer()
                HStack {
                    if waveform, let wf = analysis?.waveform {
                        Image(decorative: wf, scale: 1).resizable()
                            .frame(width: 112, height: 56)
                            .background(Color.black.opacity(0.5))
                    }
                    Spacer()
                }
            }
            .padding(8)
        }
        .allowsHitTesting(false)
    }
}

struct HistogramView: View {
    let analysis: FrameAnalysis

    var body: some View {
        Canvas { ctx, size in
            let n = analysis.luma.count
            guard n > 0 else { return }
            let bw = size.width / CGFloat(n)
            let colors: [Color] = [Color(red: 1, green: 0.27, blue: 0.23).opacity(0.6),
                                   Color(red: 0.19, green: 0.82, blue: 0.35).opacity(0.6),
                                   Color(red: 0.04, green: 0.52, blue: 1).opacity(0.6)]
            for (ci, hist) in analysis.rgb.enumerated() where ci < 3 {
                var p = Path()
                for i in 0..<min(n, hist.count) {
                    let hgt = CGFloat(hist[i]) * size.height
                    p.addRect(CGRect(x: CGFloat(i) * bw, y: size.height - hgt, width: bw, height: hgt))
                }
                ctx.fill(p, with: .color(colors[ci]))
            }
            var l = Path()
            for i in 0..<n {
                let hgt = CGFloat(analysis.luma[i]) * size.height
                l.addRect(CGRect(x: CGFloat(i) * bw, y: size.height - hgt, width: bw, height: hgt))
            }
            ctx.fill(l, with: .color(.white.opacity(0.55)))
        }
        .background(Color.black.opacity(0.53))
    }
}

struct InfoText: View {
    let text: String
    var color: Color = .white

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 6))
    }
}
