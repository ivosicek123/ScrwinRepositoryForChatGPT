import UIKit
import Shared

/// UIKit vstupní bod (náhrada SwiftUI `App`). Registruje Bank iD most (ASWebAuthenticationSession)
/// a Secure Enclave podepisovač do KMP shared – beze změny oproti SwiftUI verzi.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        IosBridge.shared.register(session: BankIdAuthSession(), signer: SecureEnclaveSigner())
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let c = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        c.delegateClass = SceneDelegate.self
        return c
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let model = WalletModel()

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        w.rootViewController = RootViewController(model: model)
        w.makeKeyAndVisible()
        window = w
    }

    /// Při přechodu na pozadí peněženku zamknout (seed nezůstává otevřený v paměti).
    func sceneDidEnterBackground(_ scene: UIScene) { Task { @MainActor in model.lock() } }
}
