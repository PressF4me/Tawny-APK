import SwiftUI

struct RootView: View {
    @EnvironmentObject private var server: ServerStore
    @StateObject private var web = WebState()
    @State private var showSettings = false

    var body: some View {
        Group {
            if let url = server.url {
                ZStack(alignment: .topLeading) {
                    WebScreen(url: url, state: web)
                        .ignoresSafeArea()

                    if let error = web.loadError {
                        FailureView(message: error,
                                    retry: { web.reload?() },
                                    change: { showSettings = true })
                    }

                    // Double-tap the top-left corner for settings. The web UI
                    // puts only a non-interactive status rail there.
                    Color.clear
                        .frame(width: 64, height: 64)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2) { showSettings = true }
                        .accessibilityLabel("FrenTalk settings")
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { showSettings = true }
                }
            } else {
                SetupView()
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView().environmentObject(server)
        }
    }
}

struct FailureView: View {
    let message: String
    let retry: () -> Void
    let change: () -> Void

    var body: some View {
        ZStack {
            Theme.ink.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 18) {
                Text("CANNOT REACH SERVER")
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .kerning(3)
                    .foregroundColor(Theme.amber)

                Text(message)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundColor(Theme.cream)
                    .fixedSize(horizontal: false, vertical: true)

                Text("Check that Tailscale is connected on this device and that the server is running.")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 10) {
                    Button(action: retry) {
                        Text("RETRY")
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .kerning(2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(Theme.amber)
                            .foregroundColor(Theme.ink)
                    }
                    Button(action: change) {
                        Text("SETTINGS")
                            .font(.system(size: 12, design: .monospaced))
                            .kerning(2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .foregroundColor(Theme.cream)
                            .overlay(Rectangle().stroke(Theme.edge, lineWidth: 1))
                    }
                }
                .padding(.top, 4)

                Spacer()
            }
            .padding(28)
            .padding(.top, 40)
        }
    }
}
