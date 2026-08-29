# Tawny for Android

The Android app is the primary product: a native Kotlin shell (welcome / role /
QR pairing / lifecycle / audio routing) wrapping a WebView that owns the WebRTC
stack. The web client is bundled in the APK and served from a loopback server, so
there is no address to type and `getUserMedia` gets a secure-context origin.

`applicationId` `com.tawny.monitor` · `minSdk` 26 · `targetSdk` 35.

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
before remote support existed. Deploy steps for the relay: `rendezvous/README.md`.

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
release build is produced unsigned (useful as a CI artifact). See
`PLAY-SUBMISSION.md` for the full store checklist.

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
  adb shell "am start -a android.intent.action.VIEW -d 'tawny://pair?k=KEY&n=Pet%20camera&h=IP:PORT'"
  ```
  (single-quote the URL so the device shell doesn't eat `&`).
- A real two-phone media test needs hardware.

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
