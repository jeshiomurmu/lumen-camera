import SwiftUI

/// Original Lumen palette (dark, high-contrast camera UI).
enum LumenColors {
    static let accent = Color(red: 1.0, green: 0.784, blue: 0.239)      // #FFC83D
    static let record = Color(red: 1.0, green: 0.231, blue: 0.188)      // #FF3B30
    static let good = Color(red: 0.204, green: 0.78, blue: 0.349)       // #34C759
    static let surface = Color(red: 0.102, green: 0.102, blue: 0.11)    // #1A1A1C
    static let surfaceHigh = Color(red: 0.165, green: 0.165, blue: 0.18) // #2A2A2E
    static let textDim = Color(red: 0.722, green: 0.722, blue: 0.745)   // #B8B8BE
    static let chip = Color.black.opacity(0.27)
}

enum Format {
    static func shutter(_ s: Double) -> String {
        if s <= 0 { return "--" }
        if s >= 0.3 { return String(format: s >= 1 && abs(s - s.rounded()) < 0.05 ? "%.0f\"" : "%.1f\"", s) }
        return "1/\(Int((1 / s).rounded()))"
    }

    static func ev(_ v: Float) -> String { String(format: "%+.1f", v) }
}
