import AVFoundation
import Foundation

/// Manages AVAudioSession configuration, interruption handling, and route changes
/// for reliable playback on iOS.
@MainActor
final class AudioSessionManager: ObservableObject {

    enum RouteEvent: Equatable {
        case initial
        case oldDeviceUnavailable
        case newDeviceAvailable
        case routeChanged
        case mediaServicesReset
    }

    @Published private(set) var isActive: Bool = false
    @Published private(set) var isInterrupted: Bool = false
    @Published private(set) var shouldResume: Bool = false

    private let session: AVAudioSession
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []

    init(session: AVAudioSession = .sharedInstance(), center: NotificationCenter = .default) {
        self.session = session
        self.center = center
        setupObservers()
        activateSession()
    }

    deinit {
        observers.forEach(center.removeObserver)
    }

    private func setupObservers() {
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: .main
        ) { [weak self] notification in
            self?.handleInterruption(notification)
        })
    }

    private func activateSession() {
        do {
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothA2DP, .duckOthers])
            try session.setActive(true)
            isActive = true
        } catch {
            isActive = false
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo else { return }
        let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt
        guard let type = AVAudioSession.InterruptionType(rawValue: typeValue ?? 0) else { return }
        switch type {
        case .began:
            isInterrupted = true
            shouldResume = session.isOtherAudioPlaying == false
        case .ended:
            isInterrupted = false
            if let optionValue = info[AVAudioSessionInterruptionOptionKey] as? UInt,
               AVAudioSession.InterruptionOptions(rawValue: optionValue).contains(.shouldResume) {
                shouldResume = true
            }
            if shouldResume {
                try? session.setActive(true)
            }
        @unknown default:
            break
        }
    }

    func deactivateSession() {
        try? session.setActive(false)
        isActive = false
    }

    func reactivateWithoutOverridingPlaybackIntent() {
        do {
            try session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothA2DP])
            try session.setActive(true)
            isActive = true
        } catch {
            // Interruption may legally deny activation; AVPlayer retries.
        }
    }

    func handleIncomingCall() {
        isInterrupted = true
        shouldResume = false
        deactivateSession()
    }

    func handleRouteChange(oldDeviceUnavailable: Bool, newDeviceAvailable: Bool) {
        if oldDeviceUnavailable {
            deactivateSession()
        }
        if newDeviceAvailable {
            reactivateWithoutOverridingPlaybackIntent()
        }
    }
}
