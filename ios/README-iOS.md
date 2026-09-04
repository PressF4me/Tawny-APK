# Tawny for iOS (stale)

**This target is a stale stub.** The Tawny product has moved to Android-first with a native Kotlin shell and embedded WebRTC stack. This iOS code predates the rebrand and is not maintained.

A native SwiftUI shell around the Tawny web app. The app is a **client for
your own server** — it asks for the server address on first launch and talks to
nothing else.

I could not compile this here. Expect to fix a line or two in Xcode on the first
build.

---

## What being native actually buys you

The web app already runs in Safari, so it's worth being clear about what the
wrapper adds:

- **The screen stays on.** `UIApplication.isIdleTimerDisabled` is set while a
  call is live. The Web Wake Lock API is unreliable in Safari, and a monitor
  whose screen sleeps is useless.
- **Inline video.** Without `allowsInlineMediaPlayback`, iOS hijacks the remote
  video into the native fullscreen player and your controls vanish.
- **A real permission grant.** WebKit refuses `getUserMedia` inside a web view
  unless the host app implements `requestMediaCapturePermissionFor`. The shell
  grants it, but only for your server's origin.
- **Audio routing.** `AVAudioSession` in `.videoChat` mode enables the hardware
  echo canceller and forces the speaker — which matters because the monitor
  plays your voice a metre from its own microphone.
- **Home-screen icon, no Safari chrome, TestFlight distribution.**

### What it does not buy you

**Camera capture in the background is impossible on iOS.** Not for this app, not
for any app — Apple does not grant it outside a few negotiated entitlements.
When the monitor device is backgrounded or its screen locks, video stops. The
`audio` background mode is declared so the *audio* leg survives a screen lock on
the handset side, but the monitor must stay foregrounded, plugged in, with Low
Power Mode off. Same constraint as the browser version. If that's a dealbreaker,
the answer is a dedicated RTSP camera feeding MediaMTX, not an iPhone.

---

## Build

Requires a Mac with Xcode 15+, and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`).

```bash
cd ios
xcodegen generate
open Tawny.xcodeproj
```

Then in Xcode:

1. Select the **Tawny** target → **Signing & Capabilities**.
2. Check **Automatically manage signing** and pick your team.
3. Change the bundle identifier from `com.tawny.monitor` to something in a
   domain you control — `com.yourname.tawny`. It must be globally unique.

Prefer not to install XcodeGen? Create a new iOS App project in Xcode (SwiftUI,
no Core Data, no tests), delete the generated `ContentView.swift`, drag in the
`Tawny/` folder with *Copy items if needed* ticked, and paste the custom keys
from `Info.plist` into the target's Info tab.

Build to a device (not the simulator — it has no camera and WebRTC behaves
differently there).

---

## TestFlight

You need the **Apple Developer Program**, $99/year. There is no free path to
TestFlight; free provisioning installs to your own device but the profile expires
after 7 days.

### 1. Register the app

In [App Store Connect](https://appstoreconnect.apple.com) → **Apps** → **+** →
**New App**. Platform iOS, pick your bundle ID, choose any SKU. The name must be
unique across the whole App Store — "Tawny" may be taken; the display name on
the device comes from `CFBundleDisplayName` and can stay as it is.

### 2. Upload

In Xcode: set the destination to **Any iOS Device (arm64)**, then
**Product → Archive**. When the Organizer opens: **Distribute App → App Store
Connect → Upload**.

Processing takes 5–30 minutes. You'll get an email when the build is ready.

### 3. Choose your testing track

This is the decision that matters:

**Internal testing** — up to 100 testers, who must be users on your App Store
Connect team. **No review.** Builds are available minutes after processing. For a
personal homelab app this is the right answer and you should stop here.

**External testing** — up to 10,000 testers by email or public link. Requires
**Beta App Review**, usually a day or two. Needs a description, contact email,
and instructions for the reviewer.

### 4. If you go external, expect friction

A reviewer opening this app sees a login-less screen asking for a server address
they don't have, and can go no further. That reads as a broken or non-functional
app under **Guideline 2.1**, and web-view wrappers additionally draw
**Guideline 4.2 (minimum functionality)**.

Put this in the review notes, and give them something to connect to — a
temporary Funnel URL, torn down after review:

> Tawny is a client for a self-hosted pet monitoring server, like a Jellyfin
> or Home Assistant client. It requires the user's own server. For review,
> please enter: `https://<temporary-address>` — then tap "+ New channel", name
> it, and choose Monitor to see the camera view.

Internal testing avoids all of this.

### Already handled for you

- `ITSAppUsesNonExemptEncryption` is `false` in `Info.plist`, so you won't be
  asked the export-compliance question on every upload. This is accurate: the app
  uses only standard TLS and DTLS-SRTP, which is exempt.
- Camera, microphone, and local-network usage strings are specific rather than
  generic — vague ones get rejected under Guideline 5.1.1.
- The launch storyboard is replaced by the modern `UILaunchScreen` key.

### Ongoing

TestFlight builds expire after **90 days**. Bump `CURRENT_PROJECT_VERSION` in
`project.yml` (or the Build field) for every upload — App Store Connect rejects a
duplicate build number even if nothing else changed.

---

## How the shell and the page talk

The web app posts messages when it goes live and idle:

```js
window.webkit.messageHandlers.tawny.postMessage({ event: 'live', role: 'station' })
window.webkit.messageHandlers.tawny.postMessage({ event: 'idle' })
```

The shell responds by holding the idle timer and configuring the audio session.
Going the other way, it dispatches `tawny:background` and `tawny:foreground`
on `window` so the page can show honest status when iOS suspends it. All of it is
feature-detected, so `public/app.js` behaves identically in a normal browser.

If you edit the web app, there is nothing to rebuild — the shell loads it from
your server, so `docker compose up -d --build` is the whole deploy.

---

## Files

```
project.yml                    XcodeGen spec
Tawny/TawnyApp.swift           entry point
Tawny/RootView.swift           routes between setup and the web view
Tawny/SetupView.swift          first-run server address entry
Tawny/SettingsView.swift       change server, version info
Tawny/WebScreen.swift          WKWebView host — permissions, audio, idle timer
Tawny/ServerStore.swift        server URL persistence and validation
Tawny/Theme.swift              palette matching the web app
Tawny/Info.plist               permission strings, background modes, ATS
Tawny/Assets.xcassets          app icon, accent, launch colour
```

## Notes

- **Minimum iOS 16.** `requestMediaCapturePermissionFor` needs iOS 15; 16 is set
  for the SwiftUI APIs used. Covers iPhone 8 and later.
- **No third-party dependencies.** Nothing to audit, nothing to break.
- **Settings** are reached by double-tapping the top-left corner of the live
  screen — the web UI puts only a non-interactive status rail there.
- **Self-signed certificates are not accepted.** There is deliberately no
  "trust this certificate" toggle. Use Tailscale Serve, which gives you a real
  one.
- The app pins navigation to your server's host; any other link opens in Safari.
