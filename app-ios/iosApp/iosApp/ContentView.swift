import SwiftUI
import UIKit
import ComposeApp

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewKt.MainViewController()
        controller.view.backgroundColor = UIColor(red: 18 / 255, green: 18 / 255, blue: 18 / 255, alpha: 1)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
