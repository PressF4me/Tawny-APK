# Shipping Tawny to the App Store (stale)

**This target is a stale stub.** The Tawny product has moved to Android-first with a native Kotlin shell. This iOS guidance predates the rebrand and is not actively maintained.

TestFlight internal testing has no review. Public release does, and an iOS app of this kind would face review challenges as outlined below.

---

## The blocking problem

**Guideline 4.2 — Minimum Functionality.** Tawny is a `WKWebView` pointed at a
website. Apple rejects these on sight; the phrase in the rejection is usually
"your app is primarily a repackaged website" or "provides limited functionality
compared to the web experience." A polished native shell around a web view does
not clear this. Neither does adding a settings screen.

**Guideline 2.1 — App Completeness.** A reviewer opens the app, is asked for a
server address they do not have, and stops. That is a dead end, and dead ends get
rejected regardless of 4.2.

Both are fixed by the same change.

### Fix: make it a real native client, and make it work with no server

Two pieces of work:

**1. Replace the web view with native WebRTC.**

Add [`stasel/WebRTC`](https://github.com/stasel/WebRTC) as a Swift Package — a
maintained binary XCFramework of Google's libwebrtc. GoogleWebRTC on CocoaPods is
abandoned; do not use it. Everything the browser was doing has a native
equivalent, and most of it ships with iOS:

| Web app does | Native equivalent |
|---|---|
| `RTCPeerConnection` | `RTCPeerConnectionFactory` (stasel/WebRTC) |
| `WebSocket` signaling | `URLSessionWebSocketTask` — built in |
| `crypto.subtle.digest` | `CryptoKit.SHA256` — built in |
| QR generation | `CIFilter.qrCodeGenerator()` — built in |
| QR scanning | `AVCaptureMetadataOutput` — built in, works on all iOS |
| Chime synthesis | `AVAudioEngine` / `AVAudioPlayerNode` — built in |
| Level meter | `RTCAudioTrack` stats or an `AVAudioEngine` tap |
| `localStorage` channels | `UserDefaults` + Keychain for the keys |

Only one third-party dependency. Realistically 2,000–3,000 lines of Swift. The
signaling protocol and the channel-key derivation stay exactly as they are, so
the existing server and the web client keep working unchanged and interoperate
with the native app.

You also get things the web view cannot do: hardware H.264 encoding, better
battery life, `AVAudioSession` control that actually sticks, and PiP.

**2. Make it work with zero infrastructure.**

This is what turns it from "client for a thing reviewers can't access" into a
standalone app. Add a local mode where one device is the signaling host on the
same Wi-Fi, discovered over Bonjour with `NWListener` / `NWBrowser` (Network
framework). Two iPhones on the same network, no server, no Tailscale, nothing to
install. The reviewer can pair two test devices and the app just works.

The self-hosted server then becomes the *remote access* feature rather than the
only way to use the app — which is a better product anyway, and closes both 4.2
and 2.1.

`NSLocalNetworkUsageDescription` and a `NSBonjourServices` array are already
needed for this; the plist has the former.

---

## Do not market this as a baby monitor

Your original stack was described as a pet/baby monitor. For a personal project
that distinction doesn't matter. For a public App Store listing it does.

A consumer app marketed for infant monitoring invites product-liability exposure
that a hobby project should not carry, and it draws extra scrutiny on safety
claims and reliability. An app that drops a connection is an annoyance for a dog
and something else entirely for a sleeping baby. Keep every word of the listing,
screenshots, and keywords about pets. Don't put "baby" in the keyword field to
catch searches.

---

## Metadata you must produce

None of this is optional, and all of it is separate from code.

**URLs that must be live and reachable** — reviewers click them:
- **Privacy policy URL** — required for every app, no exceptions. GitHub Pages is
  fine. It must describe actual behaviour: no accounts, no analytics, no data
  leaving the user's devices.
- **Support URL** — a real page with a way to contact you. A GitHub repo with
  Issues enabled qualifies.

**Listing:**
- App name, globally unique across the App Store. "Tawny" may be taken —
  check before you get attached. The on-device name comes from
  `CFBundleDisplayName` and can differ.
- Subtitle (30 chars), description, keywords (100 chars), promotional text.
- Category: Utilities, or Lifestyle.
- Age rating questionnaire — this lands at 4+.
- Copyright line, and a real contact name, address, and phone for App Review.

**Screenshots** — the most common thing people forget:
- Required: 6.9" iPhone (or 6.5", depending on current requirements — check App
  Store Connect when you upload, Apple changes these).
- `project.yml` currently sets `TARGETED_DEVICE_FAMILY: "1,2"`, which means the
  app claims iPad support and therefore **requires iPad screenshots and must
  actually work well on iPad**. Set it to `"1"` for iPhone-only unless you intend
  to do the iPad work. This is a one-character fix that saves a rejection.

**App Privacy nutrition label** — declare it truthfully. As built this is "Data
Not Collected": no accounts, no analytics, no third-party SDKs, media never
touches a server you don't control. If you enable `STUN_URLS`, the user's IP
reaches a third-party STUN operator — that is not developer data collection, but
mention it in the privacy policy.

**Export compliance** — `ITSAppUsesNonExemptEncryption` is already `false` in
`Info.plist`, which is correct: TLS and DTLS-SRTP are exempt. You will not need a
CCATS or a self-classification report as long as you add no custom cryptography.

---

## Before you submit

- [ ] Native WebRTC replaces the web view
- [ ] Local-network mode works with no server at all
- [ ] `TARGETED_DEVICE_FAMILY` set to `"1"`, or full iPad support done
- [ ] Bundle ID in a domain you own
- [ ] Privacy policy and support pages live
- [ ] Screenshots at the current required size
- [ ] App Privacy answered as "Data Not Collected"
- [ ] Listing says pets, never babies
- [ ] Tested on a real device with a cold install and permissions denied, then
      granted — reviewers do exactly this
- [ ] Review notes explain the pairing flow in two sentences

Expect 24–48 hours per review round, and expect at least one rejection. Rejections
are a conversation in Resolution Center, not a verdict — you reply, you fix, you
resubmit.

---

## Is it worth it?

Worth asking plainly. If the goal is you and your household using this, internal
TestFlight gives you 100 installs, no review, and none of the above. The App Store
only makes sense if you want strangers using it — and that means committing to the
native rewrite, a support burden, and a privacy policy you keep accurate.

The native WebRTC rewrite is genuinely worth doing on its own merits, though.
Better battery, better video, and it works without a browser in the loop. If you
do it, do it because the app gets better, and treat App Store eligibility as a
side effect.
