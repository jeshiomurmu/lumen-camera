import CoreMotion
import Foundation

/// Device attitude for the level indicator and for rotating UI icons when the
/// phone is turned (the UI itself stays portrait, like a camera app).
final class DeviceMotion: ObservableObject {
    /// Clockwise icon rotation in degrees (0, 90, -90, 180).
    @Published var iconRotation: Double = 0
    /// Tilt relative to the nearest upright orientation, degrees.
    @Published var roll: Double = 0

    private let manager = CMMotionManager()

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 20.0
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self, let g = motion?.gravity else { return }
            let flatness = abs(g.z)
            if flatness > 0.9 { return }  // lying flat: keep the last orientation
            let a = atan2(g.x, -g.y) * 180 / .pi   // 0 = upright portrait, -90 = top to the left
            func wrap(_ v: Double) -> Double { v > 180 ? v - 360 : (v < -180 ? v + 360 : v) }
            if abs(wrap(a + self.iconRotation)) > 55 {     // hysteresis around the current orientation
                let snapped = (a / 90).rounded() * 90
                self.iconRotation = abs(snapped) >= 180 ? 180 : -snapped
            }
            self.roll = wrap(a + self.iconRotation)
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }
}

/// Thermal state: warn the user, and stop a recording at `.critical`.
/// iOS keeps its own protection; the app never works around it.
final class ThermalMonitor: ObservableObject {
    @Published private(set) var state: ProcessInfo.ThermalState = ProcessInfo.processInfo.thermalState
    private var token: NSObjectProtocol?

    var isWarm: Bool { state == .serious || state == .critical }
    var shouldStopRecording: Bool { state == .critical }

    func start() {
        token = NotificationCenter.default.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification,
                                                       object: nil, queue: .main) { [weak self] _ in
            self?.state = ProcessInfo.processInfo.thermalState
        }
    }

    deinit { if let token { NotificationCenter.default.removeObserver(token) } }
}
