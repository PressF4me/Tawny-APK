import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var server: ServerStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Server") {
                    Text(server.url?.absoluteString ?? "Not set")
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundColor(Theme.dim)
                    Button("Change server", role: .destructive) { confirmReset = true }
                }

                Section("About") {
                    LabeledContent("Version", value: version)
                    Text("Video and audio travel directly between your devices. This app is a client for your own server.")
                        .font(.footnote)
                        .foregroundColor(Theme.dim)
                }

                Section("Monitor devices") {
                    Text("iOS does not allow any app to capture camera in the background. Keep the monitor device plugged in, on this screen, with Low Power Mode off.")
                        .font(.footnote)
                        .foregroundColor(Theme.dim)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("Change server?", isPresented: $confirmReset) {
                Button("Cancel", role: .cancel) {}
                Button("Change", role: .destructive) {
                    server.clear()
                    dismiss()
                }
            } message: {
                Text("Your channels stay on this device and will still be there if you come back to the same server.")
            }
        }
    }
}
