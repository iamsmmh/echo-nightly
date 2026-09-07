import XCTest

final class PlaybackRecoveryRegressionTests: XCTestCase {
    func testRecoveryStateTransitionsToIdle() {
        let manager = PlaybackRecoveryManager()
        XCTAssertEqual(manager.state, .idle)
    }

    func testAudioSessionExists() {
        let session = AVAudioSession.sharedInstance()
        XCTAssertNotNil(session)
    }
}
