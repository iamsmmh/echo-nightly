import AVFAudio
import Foundation

/// Owns route-notification lifetime for the iOS host and safely restores the playback session.
/// Playback intent remains owned by the Kotlin player; this monitor never starts playback.
@MainActor
final class AudioRouteMonitor: ObservableObject {
    enum RouteEvent: Equatable {
        case initial
        case oldDeviceUnavailable
        case newDeviceAvailable
        case routeChanged
        case mediaServicesReset
    }

    @Published private(set) var currentOutputs: [String] = []
    @Published private(set) var lastEvent: RouteEvent = .initial

    private let session: AVAudioSession
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []

    init(session: AVAudioSession = .sharedInstance(), center: NotificationCenter = .default) {
        self.session = session
        self.center = center
        refreshOutputs()
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: session, queue: .main
        ) { [weak self] notification in
            Task { @MainActor [weak self] in self?.routeChanged(notification) }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.mediaServicesReset() }
        })
    }

    deinit {
        observers.forEach(center.removeObserver)
    }

    private func routeChanged(_ notification: Notification) {
        let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        let reason = raw.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
        switch reason {
        case .oldDeviceUnavailable:
            // AVPlayer/Kotlin applies the user's pause-on-disconnect preference.
            lastEvent = .oldDeviceUnavailable
        case .newDeviceAvailable:
            lastEvent = .newDeviceAvailable
            reactivateWithoutOverridingPlaybackIntent()
        default:
            lastEvent = .routeChanged
        }
        refreshOutputs()
    }

    private func mediaServicesReset() {
        lastEvent = .mediaServicesReset
        reactivateWithoutOverridingPlaybackIntent()
        refreshOutputs()
    }

    private func reactivateWithoutOverridingPlaybackIntent() {
        do {
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothA2DP])
            try session.setActive(true)
        } catch {
            // A call/Siri interruption may legally deny activation. AVPlayer retries on user play.
        }
    }

    private func refreshOutputs() {
        currentOutputs = session.currentRoute.outputs.map { "\($0.portType.rawValue):\($0.portName)" }
    }
}
