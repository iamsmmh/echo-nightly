import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var audioSession = AudioSessionManager()
    @StateObject private var audioRouteMonitor = AudioRouteMonitor()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(audioSession)
                .environmentObject(audioRouteMonitor)
                .ignoresSafeArea()
        }
    }
}
