import CoreGraphics
import Foundation

enum PhotoEngine: String, Codable, CaseIterable {
    case lumen = "Lumen burst (open-source HDR+)"
    case apple = "Apple pipeline (Deep Fusion / Smart HDR)"
    case proRAW = "Apple ProRAW (48 MP)"
}

enum VideoCodecChoice: String, Codable, CaseIterable {
    case hevc = "HEVC"
    case h264 = "H.264"
    case proRes422HQ = "ProRes 422 HQ"
    case proResRAW = "ProRes RAW"
    case proResRAWHQ = "ProRes RAW HQ"

    /// FourCC raw values (avoids depending on newer SDK symbols).
    var codecRawValue: String {
        switch self {
        case .hevc: return "hvc1"
        case .h264: return "avc1"
        case .proRes422HQ: return "apch"
        case .proResRAW: return "aprn"
        case .proResRAWHQ: return "aprh"
        }
    }

    var isProResRAW: Bool { self == .proResRAW || self == .proResRAWHQ }
}

enum VideoColor: String, Codable, CaseIterable {
    case sdr = "SDR (Rec.709)"
    case hlg = "HDR (HLG 10-bit)"
    case appleLog = "Apple Log"
    case appleLog2 = "Apple Log 2"
}

/// Photo aspect ratio (portrait viewfinder shape); RAW and 4:3 keep the full sensor.
enum PhotoAspect: String, Codable, CaseIterable {
    case r4x3 = "3:4", r16x9 = "9:16", r1x1 = "1:1"

    /// Long side / short side in sensor pixels.
    var sensorRatio: CGFloat {
        switch self {
        case .r4x3: return 4.0 / 3.0
        case .r16x9: return 16.0 / 9.0
        case .r1x1: return 1
        }
    }

    /// Portrait viewfinder width / height.
    var viewfinder: CGFloat { 1 / sensorRatio }

    var next: PhotoAspect {
        switch self {
        case .r4x3: return .r16x9
        case .r16x9: return .r1x1
        case .r1x1: return .r4x3
        }
    }
}

struct ProPreset: Codable, Identifiable, Equatable {
    var id: String { name }
    var name: String
    var iso: Float?
    var shutter: Double?
    var ev: Float
    var lens: Float?
    var kelvin: Float?
    var tint: Float
}

struct AppSettings: Codable, Equatable {
    // photo
    var photoEngine: PhotoEngine = .lumen
    var saveRaw = false
    var burstFrames = 0              // 0 = automatic
    var profile: Int32 = 0           // LUMEN_PROFILE_*
    var aiDenoise = true
    var aiZoom = true
    var displayP3 = true
    var heicQuality: Double = 0.92
    var maxResolution = true
    var aspect: PhotoAspect = .r4x3
    var portraitAperture: Float = 2.8
    // video
    var videoModeKey = ""
    var videoCodec: VideoCodecChoice = .hevc
    var videoColor: VideoColor = .sdr
    var bitrateMbps = 0              // 0 = automatic
    var stabilization = true
    var recordAudio = true
    var pcmAudio = false
    // astro
    var astroFrames = 60
    var astroExposure: Double = 1.0  // seconds, clamped to the device maximum
    var astroISO: Float = 1600
    var astroStackMode: Int32 = 0
    var astroAlign = true
    var astroDarkFrames = 0
    // viewfinder
    var grid = true
    var histogram = true
    var waveform = false
    var focusPeaking = false
    var zebras = false
    var level = true
    var timerSeconds = 0

    static func load() -> AppSettings {
        guard let d = UserDefaults.standard.data(forKey: "settings"),
              let s = try? JSONDecoder().decode(AppSettings.self, from: d) else { return AppSettings() }
        return s
    }

    func save() {
        if let d = try? JSONEncoder().encode(self) { UserDefaults.standard.set(d, forKey: "settings") }
    }

    static func loadPresets() -> [ProPreset] {
        guard let d = UserDefaults.standard.data(forKey: "presets"),
              let p = try? JSONDecoder().decode([ProPreset].self, from: d) else { return [] }
        return p
    }

    static func savePresets(_ p: [ProPreset]) {
        if let d = try? JSONEncoder().encode(p) { UserDefaults.standard.set(d, forKey: "presets") }
    }
}
