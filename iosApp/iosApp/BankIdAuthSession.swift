import AuthenticationServices
import UIKit
import Shared

/// ASWebAuthenticationSession most pro Bank iD OIDC (ephemeral – žádné sdílené cookies Safari).
final class BankIdAuthSession: NSObject, WebAuthSession, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authenticate(authorizeUrl: String, callbackScheme: String, completion: @escaping (String?, String?) -> Void) {
        guard let url = URL(string: authorizeUrl), url.scheme == "https" else { completion(nil, "Neplatná URL"); return }
        DispatchQueue.main.async {
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { cb, err in
                if let cb = cb { completion(cb.absoluteString, nil) }
                else { completion(nil, (err as? ASWebAuthenticationSessionError)?.code == .canceledLogin ? "Zrušeno" : (err?.localizedDescription ?? "Chyba")) }
                self.session = nil
            }
            s.prefersEphemeralWebBrowserSession = true
            s.presentationContextProvider = self
            self.session = s
            if !s.start() { completion(nil, "Nelze spustit přihlášení") }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }
}
