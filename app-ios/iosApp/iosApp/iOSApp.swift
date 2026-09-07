import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var audioRouteMonitor = AudioRouteMonitor()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(audioRouteMonitor)
        }
    }
}
