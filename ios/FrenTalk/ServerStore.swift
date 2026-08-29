import Foundation
import Combine

/// Holds the address of the self-hosted FrenTalk server.
///
/// The app is a client for your own server, so there is nothing to configure
/// beyond this. The URL is kept in UserDefaults; channel keys live inside the
/// web view's own storage and never pass through Swift.
final class ServerStore: ObservableObject {
    private static let key = "frentalk.serverURL"

    @Published private(set) var url: URL?

    init() {
        if let raw = UserDefaults.standard.string(forKey: Self.key) {
            url = URL(string: raw)
        }
    }

    /// Normalises user input into a URL, defaulting to https.
    /// Returns nil when the text cannot be a server address.
    static func normalise(_ text: String) -> URL? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") {
            s = "https://" + s
        }
        while s.hasSuffix("/") { s.removeLast() }
        guard let u = URL(string: s), let host = u.host, !host.isEmpty else { return nil }
        return u
    }

    /// True when the address will not be a secure context, in which case iOS
    /// refuses the camera and microphone regardless of app permissions.
    static func isInsecure(_ u: URL) -> Bool {
        guard u.scheme?.lowercased() == "http" else { return false }
        let host = u.host?.lowercased() ?? ""
        return !(host == "localhost" || host == "127.0.0.1")
    }

    func save(_ u: URL) {
        UserDefaults.standard.set(u.absoluteString, forKey: Self.key)
        url = u
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: Self.key)
        url = nil
    }
}
