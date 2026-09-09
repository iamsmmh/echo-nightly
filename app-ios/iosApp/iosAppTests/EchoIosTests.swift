import XCTest
import UIKit
@testable import iosApp
import ComposeApp

/**
 * Real device/simulator tests for the iOS host + Kotlin bridge.
 * These run against the actual app (TEST_HOST) in the simulator, which also
 * exercises application startup and Compose root loading.
 */
final class EchoIosTests: XCTestCase {

    /// The Echo brand mark is bundled so the home-screen icon and launch screen match Android.
    func testEchoLogoAssetIsPresent() {
        XCTAssertNotNil(
            UIImage(named: "EchoLogo", in: Bundle.main, compatibleWith: nil),
            "EchoLogo is missing from the app asset catalog"
        )
    }

    /// The Compose Multiplatform root controller builds successfully.
    func testComposeRootLoads() {
        let controller = MainViewKt.MainViewController()
        XCTAssertNotNil(controller)

        // Compose draws through Skia into the root view's layer rather than
        // adding UIKit subviews, and it only builds that hierarchy once the
        // view is attached to a window and laid out -- `loadViewIfNeeded()`
        // on a detached controller leaves it empty.
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.layoutIfNeeded()

        XCTAssertTrue(controller.isViewLoaded)
        XCTAssertNotNil(controller.view)
        XCTAssertFalse(controller.view.bounds.isEmpty)
        XCTAssertEqual(controller.view.window, window)
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
    /// Keychain needs a real application host; simctl's standalone native test
    /// executable does not have access to the simulator's security services.
    func testKeychainRoundtripUpdateDeleteAndNamespaceIsolation() throws {
        let namespace = "keychain-test-" + UUID().uuidString
        let store = IosSecureStorage(name: namespace)
        let other = IosSecureStorage(name: namespace + "-other")
        defer { try? store.remove(key_: "account") }
        XCTAssertNil(try store.get(key: "account"))
        try store.put(key: "account", value: "pass — é 🎵")
        XCTAssertEqual(try IosSecureStorage(name: namespace).get(key: "account"), "pass — é 🎵")
        XCTAssertNil(try other.get(key: "account"))
        try store.put(key: "account", value: "updated")
        XCTAssertEqual(try store.get(key: "account"), "updated")
        try store.remove(key_: "account")
        XCTAssertNil(try store.get(key: "account"))
        try store.remove(key_: "account")
    }
}
