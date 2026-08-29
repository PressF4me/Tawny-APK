import SwiftUI

@main
struct FrenTalkApp: App {
    @StateObject private var server = ServerStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(server)
                .preferredColorScheme(.dark)
        }
    }
}
