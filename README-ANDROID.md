# Tawny for Android

The Android app is the primary product: a native Kotlin shell (welcome / role /
QR pairing / lifecycle / audio routing) wrapping a WebView that owns the WebRTC
stack. The web client is bundled in the APK and served from a loopback server, so
there is no address to type and `getUserMedia` gets a secure-context origin.

`applicationId` `com.tawny.monitor` · `minSdk` 26 · `targetSdk` 36.

---

## Toolchain

This machine uses a **portable, no-sudo** install under `~/Android/` (see the
`android-toolchain` project note). The essentials:

```sh
export JAVA_HOME=$HOME/Android/jdk17      # Temurin JDK 17
export ANDROID_HOME=$HOME/Android/sdk     # cmdline-tools + platform-tools,
                                         # platforms;android-35, build-tools;35.0.0
```

`android/local.properties` must contain `sdk.dir=$ANDROID_HOME`. The Gradle
wrapper (`android/gradlew`) downloads its own distribution.

## Build

```sh
cd android
./gradlew :app:assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease          # R8 + resource shrink, unsigned unless a keystore is set
./gradlew :app:bundleRelease            # -> app/build/outputs/bundle/release/app-release.aab
```

The `syncWebAssets` task copies `../public/` into `app/src/main/assets/web/` and
writes `config.json` from gradle properties (below) on every build.

### Remote relay (optional)

To build with off-Wi-Fi support, put this in `android/local.properties`
(git-ignored) and rebuild:

```properties
tawny.rendezvousUrl=wss://<your-worker>.workers.dev
tawny.stunUrls=stun:stun.cloudflare.com:3478,stun:stun.l.google.com:19302
tawny.turnMode=auto
```

With none of these set, the app builds **LAN-only** — identical to a build
before remote support existed. Deploy steps for the relay: `app/rendezvous/README.md` in the Tawny Docker repo.

These are the *build's* defaults. An installed app can be pointed elsewhere at
runtime — see **Servers (advanced)** below — including a build with none of
them set, which is how a LAN-only APK gains a remote path without recompiling.

## Servers (advanced)

**Long-press the version stamp** in the bottom-left of any native screen →
Diagnostics → **Servers**. Deliberately behind the same hatch as the flight
recorder: a support surface, not a feature, and a normal user has no business
being shown a WebSocket URL field.

Five fields, all optional, all `SharedPreferences` (`srvRendezvous`, `srvStun`,
`srvTurn`, `srvTurnUser`, `srvTurnPass`): a rendezvous `wss://`/`ws://` URL,
comma-separated `stun:`/`stuns:` URLs, comma-separated `turn:`/`turns:` URLs,
and a TURN username and password. Empty means "use Tawny's", which is also the
reset. Addresses are checked against their scheme before they are saved, and a
value that fails the check later is ignored rather than dialled. If you're
running the Tawny Docker container as your remote setup, leave this screen
completely blank — the container reaches the phone over your LAN and needs no
configuration here.

**The built-in relay is preferred against, never replaced.** If the custom
rendezvous does not answer — two failed dials, or a host that accepts the socket
and never says `welcome` within 8 s, which is what pointing at something that
is not a Tawny relay looks like — the page falls back to the built-in tunnel,
says so, and moves the `/turn` fetch with it (`fallBackToDefault()` in
`public/app.js`). Both ends apply the same rule, so a relay that is genuinely
down sends the Monitor and every Handheld to the same place and they still
meet. The swap is one-way for the session; a new session gives the custom relay
a fresh try. A relay's *own* refusals (4003 full / 4004 monitor already running
/ 4008 pairing expired) are a working relay answering, and never trigger it.

A custom TURN entry goes **ahead of** whatever `/turn` issues rather than
instead of it, so a wrong one costs nothing. Custom STUN replaces the build's
list.

**For tighter privacy [advanced]** is a switch at the top of the same screen
(`srvStrict`). Turning it on asks for confirmation first, and it removes every
safety net above: no built-in rendezvous (not as a fallback, not in the CSP),
no public STUN (blank means none), no relay adopted from a scanned code, no
"Send to Tawny", and WebView Safe Browsing off. A relay that doesn't answer
gets a toast and keeps being redialled; nothing takes over for it. It also adds
TURN **When needed / Always / Never** (`srvTurnMode`; Always sets
`iceTransportPolicy: relay`), whether to ask the rendezvous for `/turn`
credentials (`srvTurnFetch`), and the **Direct Wi-Fi path** (`srvLanPath`).
Turning that path off means the Monitor starts no LAN relay and its code
carries no `h=`. Save refuses only combinations that cannot connect at all,
then lists exactly what the phone will contact. "Use Tawny's servers" clears
all of it. `ws://` relays are now named with their scheme in the CSP, so a
cleartext relay on your own LAN actually gets dialled.

Two things that follow the setting and are easy to miss:

- The page's CSP names the hosts it may reach (`connectSrc` in `LocalWeb.kt`).
  A custom host not in that list is blocked before it ever gets a socket, so
  `ensureAssetServer()` rebuilds the loopback server whenever the host list
  changes. It stays a list, not a wildcard — the directive exists so the channel
  key cannot be posted to an arbitrary host.
- The diagnostics **Send to Tawny** button still posts to the build's own
  `/report`, not the custom relay. That endpoint is the project's support inbox
  and a self-hosted rendezvous does not implement it.

### Release signing

`signingConfigs.release` reads `android/keystore.properties` (git-ignored):

```properties
storeFile=/abs/path/tawny-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

```sh
keytool -genkeypair -v -keystore tawny-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then enrol in **Play App Signing** on the first upload. If the file is absent the
release build is produced unsigned (useful as a CI artifact).

## Install a debug build

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use the emulator. The in-app QR scanner needs a real camera; on the emulator
launch with `-camera-back virtualscene`.

## Testing without two phones

- The Monitor path is fully reachable on one device: welcome → The Monitor →
  allow → name → QR screen.
- On the pairing screen, **Show as link** reveals the `tawny://pair?…` text.
  Feed it to a Viewer with **Paste a link instead**, or from a shell:
  ```sh
  adb shell "am start -a android.intent.action.VIEW -d 'tawny://pair?k=KEY&n=Pet%20camera&h=IP:PORT&c=CODE&e=UNIXSECS'"
  ```
  (single-quote the URL so the device shell doesn't eat `&`). Copy the whole
  thing from **Show as link** — `c` and `e` are the pairing code and its
  deadline, and a link without a `c` the Monitor is currently showing is refused
  as expired. The code rotates every ten minutes, so re-copy it if the test
  drags on.
- A real two-phone media test needs hardware.

## Orientation

The picture follows how the **Monitor** is physically held, in all four
quarters, on the Monitor's own preview and on every Viewer at once — including
mid-session, and including with auto-rotate switched off.

That last case is the whole reason there is code for this. `getUserMedia` hands
the WebView frames already turned to the *window*, so as long as the window
rotates with the phone the page needs to do nothing. With a rotation lock the
window never moves, `screen.orientation` never changes, and a Monitor lying on
its side streams a room lying on its side with nothing at either end able to
see past the window. So `MainActivity` watches the accelerometer and reports two
angles to the page — how the phone is held, and how far the window believes it
has turned, both in degrees clockwise from the phone's natural orientation. The
difference is the correction; the Monitor applies it to its own preview and
publishes it to every Handheld in the `meta` message it already sends.

- The correction is 0 whenever the window tracks the phone, so auto-rotate on
  behaves exactly as it did before.
- A quarter turn also transposes the video element's box, or `object-fit:
  contain` would fit the picture to the stage and *then* turn it past the
  stage's edges, and `overflow: hidden` would crop it.
- The **capture shape** deliberately still follows the window, not the phone:
  after a quarter-turn correction the displayed picture is wide exactly when the
  window was tall. `idealCaptureSize()` is unchanged.
- In a plain browser there is no accelerometer reading to be had, so the
  correction stays 0. A Viewer's own rotation lock is not corrected either —
  that would turn the picture while leaving the rail and controls where they
  are.
- Checking it on device: `orientation device=… window=…` in the diagnostics log
  (long-press the version stamp) is the pair of angles as the shell read them.
  `windowRotationCW()` is the one table to change if a device disagrees.

## Background / screen-off

Camera and mic run **only while the app is foregrounded**. The Monitor keeps the
screen on and has a near-black "dim" mode for a phone on a charger. A native
foreground-service mode for true screen-off streaming would need a libwebrtc
capture path plus the Play `camera|microphone` FGS declaration — not in 1.0.

## Files

```
android/settings.gradle.kts                 project layout (rootProject "Tawny")
android/app/build.gradle.kts                SDK levels, deps, signing, R8, syncWebAssets
android/app/src/main/AndroidManifest.xml    4 permissions, launcher + tawny://pair filter
android/app/src/main/java/com/tawny/monitor/
    MainActivity.kt                         all screens, pairing, lifecycle, WebView bridge
    LocalWeb.kt                             AssetHttpServer + SignalServer (LAN relay)
android/app/src/main/res/                   theme, colours, adaptive icon, network config
android/app/proguard-rules.pro             release keeps (@JavascriptInterface is load-bearing)
```
