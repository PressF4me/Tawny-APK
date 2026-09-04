# Testing Tawny on iOS without a Mac (stale)

Two routes. Start with the free one — it may be all you need.

---

## Route 1: no Apple account, no build, no cost

The web app already runs in Safari on iOS. To test it right now, open your
Tailscale HTTPS address on the phone. That's it — camera, mic, WebRTC, chimes,
QR pairing all work.

To get a home-screen icon and lose the Safari chrome: **Share → Add to Home
Screen**. `manifest.webmanifest` and the PNG icons are already in `public/`, so
it installs with the right name and paw icon.

**Test in plain Safari first, before you install it.** WebKit has a long history
of camera bugs specifically in standalone home-screen mode — iOS 18.1 broke it
entirely and 18.1.1 fixed it, and camera permission is not always persisted
across launches the way it is in a normal tab. If the monitor role fails to get
the camera after installing to the home screen but works in Safari, that's the
bug, not your setup. The workaround is to remove this line from
`public/index.html`:

```html
<meta name="apple-mobile-web-app-capable" content="yes">
```

The icon still works; the app just opens in Safari instead of standalone.

One related quirk worth knowing: in standalone mode WebKit has revoked camera
permission when the URL fragment changes. Tawny uses the fragment for pairing
links, but it clears it before any camera access is requested, so the ordering
should keep you clear of it. If you see a repeat permission prompt right after
scanning a pairing code, that's what happened.

**What you give up versus the native app:** the screen wake lock is Safari's
implementation rather than `isIdleTimerDisabled`, and you don't get
`AVAudioSession` echo-cancellation tuning. For testing, neither matters.

---

## Route 2: TestFlight, still no Mac

Cost: **$99/year** for the Apple Developer Program. There is no free path to
TestFlight — free provisioning expires after 7 days and needs Xcode anyway.

Build minutes are free if the repo is public. On a private repo, macOS minutes
bill at 10x against the 2,000-minute allowance, so you get roughly 200 macOS
minutes a month — about 15–25 builds. A public repo is the cheaper choice, and
this codebase has no secrets in it.

Everything below happens on Linux and in a browser.

### Step 1 — certificate, on your own machine

```bash
cd tools
CERT_NAME="Your Name" CERT_EMAIL="you@example.com" ./make-ios-certs.sh csr
```

Apple's documentation tells you to create the signing request in Keychain
Access. Keychain Access is just wrapping a standard PKCS#10 request, so openssl
produces an identical one. Upload the `.csr` at
`developer.apple.com/account/resources/certificates/add`, pick **Apple
Distribution**, download the `.cer` next to the script, then:

```bash
./make-ios-certs.sh p12
```

The script passes `-legacy` to `openssl pkcs12`. This is not optional and it is
the single most common way this process fails. OpenSSL 3 defaults to AES-256-CBC
with PBKDF2, which macOS Security.framework cannot read — the CI job fails with
*"MAC verification failed during PKCS12 import (wrong password?)"* and you spend
an afternoon convinced you mistyped a password that was always correct.

### Step 2 — bundle id and provisioning profile, in the browser

At `developer.apple.com/account/resources`:

1. **Identifiers → +** — register your bundle id (`com.yourname.tawny`).
   Enable no capabilities; this app needs none.
2. **Profiles → +** — **App Store Connect** distribution, your identifier, the
   certificate from step 1. Name it something you'll remember; the exact name
   goes into a secret. Download it as `Tawny.mobileprovision` next to the
   script.

### Step 3 — App Store Connect API key

At `appstoreconnect.apple.com/access/integrations/api`, create a key with the
**App Manager** role. Download the `.p8` — **you get exactly one chance**, Apple
never shows it again. Note the Key ID and Issuer ID.

This is better than an app-specific password: no 2FA prompt to babysit, and you
can revoke it independently.

### Step 4 — secrets

```bash
./make-ios-certs.sh secrets
```

That prints the base64 blobs. Add these under **Settings → Secrets and variables
→ Actions** in your GitHub repo:

| Secret | Where it comes from |
|---|---|
| `BUILD_CERTIFICATE_BASE64` | printed by the script |
| `P12_PASSWORD` | the password you chose in step 1 |
| `PROVISIONING_PROFILE_BASE64` | printed by the script |
| `KEYCHAIN_PASSWORD` | printed by the script |
| `APPLE_TEAM_ID` | top right of the developer portal |
| `BUNDLE_ID` | e.g. `com.yourname.tawny` |
| `PROFILE_NAME` | the profile's name, exactly |
| `ASC_KEY_ID` | step 3 |
| `ASC_ISSUER_ID` | step 3 |
| `ASC_PRIVATE_KEY` | the whole contents of the `.p8` |

### Step 5 — build

Push, or run the workflow by hand from the Actions tab. `.github/workflows/ios.yml`
runs XcodeGen, imports the certificate into a throwaway keychain, archives,
exports a signed IPA, and attaches it as an artifact.

Leave **Upload to TestFlight** off for the first run. Get a green build and a
downloadable IPA first — that proves signing works. Then re-run with the box
ticked.

### Step 6 — install

Register the app in App Store Connect (Apps → +, matching your bundle id) before
the first upload, or the upload is rejected. After processing, add yourself as an
**internal tester** and install through the TestFlight app. No review, minutes
not days.

---

## Things that will bite you

**Your Late 2014 Mac mini cannot do this.** It tops out at macOS Monterey, and
current Xcode needs macOS 14+. Even running an older Xcode wouldn't help: Apple
enforces a minimum SDK for anything uploaded to App Store Connect, so a Monterey
build gets rejected at submission. Don't spend a weekend on it.

**Skip the Hackintosh.** It violates Apple's licence, and you'd be debugging
drivers instead of your app.

**Renting a Mac is the fallback, not the default.** Scaleway's Apple silicon
instances are cheap by the hour but bill a 24-hour minimum; MacStadium and
similar start around $85/month. Only worth it if you need the Xcode debugger or
the Simulator interactively. For build-and-ship, CI is free and unattended.

**Certificates expire yearly, profiles too.** When a build suddenly fails
signing after months of working, check the expiry date before anything else.

---

## Which to pick

If you want to use this yourself and hand it to a couple of people, **Route 1**
costs nothing, takes two minutes, and gives you a working pet monitor on the home
screen today.

Route 2 is worth $99 when you want the reliable screen wake lock on a monitor
device that has to stay up for hours, or when you want to hand builds to people
who shouldn't be typing a tailnet URL. It's also the prerequisite for the native
WebRTC rewrite in `APPSTORE.md`, if you go that way.
