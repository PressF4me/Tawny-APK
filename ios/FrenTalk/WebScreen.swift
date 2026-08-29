import SwiftUI
import WebKit
import AVFoundation

/// Observable state the web view reports back to SwiftUI.
final class WebState: ObservableObject {
    @Published var loadError: String?
    @Published var isLoading = true
    var reload: (() -> Void)?
}

/// Hosts the FrenTalk web app.
///
/// Three things here are not achievable from the page alone on iOS:
///   * `allowsInlineMediaPlayback` - without it the remote video is punted into
///     the native fullscreen player and the controls disappear.
///   * `requestMediaCapturePermissionFor` - WebKit will not run getUserMedia
///     inside a web view without an explicit grant from the host app, on top of
///     the system camera and microphone prompts.
///   * `isIdleTimerDisabled` - the Web Wake Lock API is unreliable in Safari,
///     and a monitor whose screen sleeps is useless.
struct WebScreen: UIViewRepresentable {
    let url: URL
    @ObservedObject var state: WebState

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url, state: state)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsAirPlayForMediaPlayback = false
        // Persistent store, so channel keys survive relaunches.
        config.websiteDataStore = .default()

        let ucc = WKUserContentController()
        ucc.add(context.coordinator, name: "frentalk")
        config.userContentController = ucc

        let web = WKWebView(frame: .zero, configuration: config)
        web.uiDelegate = context.coordinator
        web.navigationDelegate = context.coordinator
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.isOpaque = false
        web.backgroundColor = UIColor(Theme.ink)
        web.scrollView.backgroundColor = UIColor(Theme.ink)
        web.allowsBackForwardNavigationGestures = false

        context.coordinator.web = web
        state.reload = { [weak web] in
            guard let web else { return }
            web.load(URLRequest(url: url))
        }
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        if context.coordinator.url != url {
            context.coordinator.url = url
            web.load(URLRequest(url: url))
        }
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        coordinator.release()
        web.configuration.userContentController
            .removeScriptMessageHandler(forName: "frentalk")
    }

    // MARK: -

    final class Coordinator: NSObject, WKUIDelegate, WKNavigationDelegate, WKScriptMessageHandler {
        var url: URL
        let state: WebState
        weak var web: WKWebView?
        private var isLive = false

        init(url: URL, state: WebState) {
            self.url = url
            self.state = state
            super.init()
            NotificationCenter.default.addObserver(
                self, selector: #selector(willResignActive),
                name: UIApplication.willResignActiveNotification, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(didBecomeActive),
                name: UIApplication.didBecomeActiveNotification, object: nil)
        }

        deinit { release() }

        func release() {
            NotificationCenter.default.removeObserver(self)
            endLive()
        }

        // MARK: media capture

        /// Called when WebKit wants the camera or microphone. iOS prompts for
        /// the app-level permission separately, so granting here does not
        /// bypass the user's choice.
        func webView(_ webView: WKWebView,
                     requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                     initiatedByFrame frame: WKFrameInfo,
                     type: WKMediaCaptureType,
                     decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            guard origin.host == url.host else {   // only our own server may ask
                decisionHandler(.deny)
                return
            }
            decisionHandler(.grant)
        }

        // MARK: bridge

        func userContentController(_ controller: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any],
                  let event = body["event"] as? String else { return }

            switch event {
            case "live":  beginLive()
            case "idle":  endLive()
            default:      break
            }
        }

        private func beginLive() {
            isLive = true
            UIApplication.shared.isIdleTimerDisabled = true
            configureAudioSession()
        }

        private func endLive() {
            isLive = false
            UIApplication.shared.isIdleTimerDisabled = false
            try? AVAudioSession.sharedInstance()
                .setActive(false, options: .notifyOthersOnDeactivation)
        }

        /// `.videoChat` forces the speaker and enables the hardware echo
        /// canceller, which matters because the monitor plays your voice out
        /// loud a metre from its own microphone.
        private func configureAudioSession() {
            let session = AVAudioSession.sharedInstance()
            do {
                try session.setCategory(.playAndRecord,
                                        mode: .videoChat,
                                        options: [.defaultToSpeaker, .allowBluetooth])
                try session.setActive(true)
            } catch {
                NSLog("FrenTalk: audio session failed: \(error.localizedDescription)")
            }
        }

        // MARK: lifecycle

        @objc private func willResignActive() {
            // iOS stops camera capture here no matter what we do; tell the page
            // so it can show honest status.
            web?.evaluateJavaScript(
                "window.dispatchEvent(new Event('frentalk:background'))",
                completionHandler: nil)
        }

        @objc private func didBecomeActive() {
            if isLive { UIApplication.shared.isIdleTimerDisabled = true }
            web?.evaluateJavaScript(
                "window.dispatchEvent(new Event('frentalk:foreground'))",
                completionHandler: nil)
        }

        // MARK: navigation

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            state.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            state.isLoading = false
            state.loadError = nil
        }

        /// Keep the web view pinned to your server. Anything else opens in
        /// Safari rather than inside the app.
        func webView(_ webView: WKWebView,
                     decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let target = action.request.url else {
                decisionHandler(.cancel)
                return
            }
            if target.host == url.host || target.scheme == "about" {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
                UIApplication.shared.open(target)
            }
        }

        func webView(_ webView: WKWebView,
                     didFail navigation: WKNavigation!,
                     withError error: Error) {
            report(error)
        }

        func webView(_ webView: WKWebView,
                     didFailProvisionalNavigation navigation: WKNavigation!,
                     withError error: Error) {
            report(error)
        }

        /// Reported to SwiftUI rather than injected into the page: on a
        /// provisional failure there is no document to inject into, and the
        /// server's own CSP forbids the inline styles such a page would need.
        private func report(_ error: Error) {
            let ns = error as NSError
            guard ns.code != NSURLErrorCancelled else { return }
            state.isLoading = false
            state.loadError = ns.localizedDescription
            endLive()
        }
    }
}
