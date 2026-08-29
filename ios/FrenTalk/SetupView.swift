import SwiftUI

struct SetupView: View {
    @EnvironmentObject private var server: ServerStore
    @State private var text = ""
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            Theme.ink.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 26) {
                HStack(spacing: 14) {
                    PawMark().frame(width: 40, height: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("FRENTALK")
                            .font(.system(size: 24, weight: .bold, design: .monospaced))
                            .kerning(4)
                            .foregroundColor(Theme.cream)
                        Text("Pet Monitor")
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundColor(Theme.dim)
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("SERVER ADDRESS")
                        .font(.system(size: 11, design: .monospaced))
                        .kerning(2)
                        .foregroundColor(Theme.dim)

                    TextField("tawny.example.net", text: $text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .focused($focused)
                        .onSubmit(connect)
                        .font(.system(size: 15, design: .monospaced))
                        .foregroundColor(Theme.cream)
                        .padding(13)
                        .background(Theme.panel)
                        .overlay(Rectangle().stroke(Theme.edge, lineWidth: 1))

                    Text("Where your FrenTalk server is reachable. Use the https address — iOS will not release the camera or microphone otherwise.")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let error {
                    Text(error)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(Theme.cream)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.panel)
                        .overlay(Rectangle().frame(width: 3).foregroundColor(Theme.live),
                                 alignment: .leading)
                }

                Button(action: connect) {
                    Text("CONNECT")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .kerning(2)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Theme.amber)
                        .foregroundColor(Theme.ink)
                }

                Spacer()
            }
            .padding(24)
        }
        .onAppear { focused = true }
    }

    private func connect() {
        guard let u = ServerStore.normalise(text) else {
            error = "That does not look like a server address."
            return
        }
        if ServerStore.isInsecure(u) {
            error = "That is a plain http address. iOS blocks the camera and microphone on insecure pages — put Tailscale Serve or a reverse proxy in front of it and use https."
            return
        }
        error = nil
        server.save(u)
    }
}

struct PawMark: View {
    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height)
            let u = s / 32
            ZStack {
                Circle().frame(width: 13 * u).offset(x: 0, y: 3 * u)
                Circle().frame(width: 6.4 * u).offset(x: -8 * u, y: -5 * u)
                Circle().frame(width: 6.4 * u).offset(x: 8 * u, y: -5 * u)
                Circle().frame(width: 6.4 * u).offset(x: 0, y: -8.5 * u)
            }
            .frame(width: s, height: s)
            .foregroundColor(Theme.amber)
        }
    }
}
