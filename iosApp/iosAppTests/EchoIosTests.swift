import XCTest
@testable import iosApp
import ComposeApp

/**
 * Real device/simulator tests for the iOS host + Kotlin bridge.
 * These run against the actual app (TEST_HOST) in the simulator, which also
 * exercises application startup and Compose root loading.
 */
final class EchoIosTests: XCTestCase {

    /// The Compose Multiplatform root controller builds successfully.
    func testComposeRootLoads() {
        let controller = MainViewKt.MainViewController()
        XCTAssertNotNil(controller)
        controller.loadViewIfNeeded()
        XCTAssertNotNil(controller.view)
        XCTAssertFalse(controller.view.subviews.isEmpty)
    }

    /// The shared playback graph initializes and the audio session is
    /// configured for music playback (AVAudioSessionCategoryPlayback).
    func testAudioSessionConfiguredForPlayback() {
        XCTAssertEqual(
            EchoIosBridge.shared.audioSessionCategory(),
            "AVAudioSessionCategoryPlayback"
        )
    }

    /// The local library and active extension are reachable through the bridge.
    func testBridgeLibraryAndExtension() {
        XCTAssertGreaterThanOrEqual(EchoIosBridge.shared.libraryCount(), 0)
        XCTAssertNotNil(EchoIosBridge.shared.activeExtensionId())
    }

    /// Importing an empty list must be a safe no-op.
    func testImportFilesEmptyIsNoop() {
        EchoIosBridge.shared.importFiles(paths: [])
        XCTAssertEqual(EchoIosBridge.shared.libraryCount(), 0)
    }
}
