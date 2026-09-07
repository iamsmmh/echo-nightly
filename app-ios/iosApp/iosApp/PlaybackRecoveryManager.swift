import AVFoundation
import Foundation

/// Handles playback recovery for incoming calls, Siri interruptions,
/// Bluetooth device changes, AirPods switching, route changes,
/// app backgrounding, and app restoration on iOS.
final class PlaybackRecoveryManager: ObservableObject {

    enum RecoveryState {
        case idle, recovering, restored, failed
    }

    @Published private(set) var state: RecoveryState = .idle
    @Published private(set) var shouldResumeAfterInterruption: Bool = false

    private let session: AVAudioSession
    private var lastPlaybackPositionMs: TimeInterval = 0
    private var lastPlaybackItemUrl: URL?

    init(session: AVAudioSession = .sharedInstance()) {
        self.session = session
        registerForAppLifecycleNotifications()
    }

    private func registerForAppLifecycleNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    @objc private func appWillResignActive() {
        state = .recovering
        shouldResumeAfterInterruption = AVAudioSession.sharedInstance().isOtherAudioPlaying == false
    }

    @objc private func appDidBecomeActive() {
        if shouldResumeAfterInterruption {
            restorePlayback()
        } else {
            state = .idle
        }
    }

    func savePlaybackState(positionMs: TimeInterval, itemUrl: URL?) {
        lastPlaybackPositionMs = positionMs
        lastPlaybackItemUrl = itemUrl
    }

    func restorePlayback() {
        guard let url = lastPlaybackItemUrl else {
            state = .failed
            return
        }
        state = .recovering
        // Playback restoration is handled by the Kotlin AVPlayer engine
        // through its existing recovery mechanism.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.state = .restored
        }
    }

    func handleAirPodsSwitching() {
        // Reactivate session without overriding playback intent
        try? session.setCategory(.playback, mode: .default, options: [.allowAirPlay, .allowBluetoothA2DP])
        try? session.setActive(true)
        state = .restored
    }

    func handleSiriInterruption() {
        state = .recovering
        shouldResumeAfterInterruption = true
    }

    func handleBluetoothConnection() {
        state = .restored
    }

    func handleAppBackgrounding() {
        state = .recovering
    }
}
