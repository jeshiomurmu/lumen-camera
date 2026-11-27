import SwiftUI

@main
struct LumenApp: App {
    @StateObject private var vm = CameraViewModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            CameraScreen(vm: vm, motion: vm.motion)
                .preferredColorScheme(.dark)
                .task { await vm.start() }
                .onChange(of: phase) { _, p in
                    switch p {
                    case .active: vm.onActive()
                    case .background: vm.onBackground()
                    default: break
                    }
                }
        }
    }
}
