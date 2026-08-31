# Handshake probes

Small scripts that speak Tawny's real signalling handshakes, so admission
changes can be checked instead of assumed. They use Node 24's **built-in**
`WebSocket` — the `ws` package is not installed and should not be added.

## `lan-admission.mjs` — the relay inside the app

Does the Monitor's LAN relay actually require proof of the channel key?

```bash
# with a Monitor live on a connected device
adb forward tcp:8820 tcp:8820
KEY=$(adb shell run-as com.tawny.monitor.debug cat shared_prefs/tawny.xml \
      | grep -o 'name="channelKey">[^<]*' | sed 's/.*>//')
node tools/probes/lan-admission.mjs "$KEY" ws://127.0.0.1:8820
```

Expected, and verified on device on 2026-08-29:

```
PASS  a device holding the channel key is admitted — id=6141e97b5a10
PASS  a wrong key is REFUSED — 4008 bad proof
PASS  a socket that never answers is dropped — 4008 no proof
```

Before the challenge-response admission landed, all three were admitted and
handed the live camera and microphone.

## `rendezvous-admission.mjs` — the Cloudflare relay

Can someone who knows only a room id brick a channel?

```bash
node tools/probes/rendezvous-admission.mjs                       # deployed worker
node tools/probes/rendezvous-admission.mjs ws://127.0.0.1:8787   # wrangler dev
```

The interesting assertion is not whether the attacker gets in — the old worker
refused them with `4004` *and re-keyed the room anyway*, locking out the real
Viewer. Each attack is therefore followed by a check that the legitimate Viewer
still connects.

Scores on 2026-08-29: **4/6 against the deployed worker**, **6/6 against the
patched code** under `wrangler dev`.

## `rendezvous-squat.mjs` — the other direction

Proves the key-commitment does not let an attacker permanently claim an *idle*
room, which would be a worse failure than the bug it fixes.

```bash
node tools/probes/rendezvous-squat.mjs                       # deployed worker
node tools/probes/rendezvous-squat.mjs ws://127.0.0.1:8787   # wrangler dev
```

**4/4 against the deployed Worker on 2026-08-29.** If every assertion fails with
close code `1006`, you are almost certainly pointed at a `wrangler dev` that is
not running — check the URL it prints on the first line before believing a
regression.

## `turn-credentials.mjs` — is TURN actually on?

Registers a Monitor in a fresh room, then asks `/turn` with that room's ticket.
PASS means a `turn:` URL came back; a 404 "no turn configured" means the
`TURN_KEY_ID` / `TURN_API_TOKEN` secrets are not set on the Worker. Verified
provisioned against the deployed Worker on 2026-08-29.

## `sas-presentation.mjs` — the right code beside the right phone

The one probe here that speaks no protocol. It lifts `sasReviewKey` /
`sasReviewed` / `markSasReviewed` / `sasPendingViewers()` / `syncStationSas()`
out of `public/app.js` *by source text* — so it cannot drift from what ships —
and runs them against stubbed peers, DOM and `localStorage`.

```bash
node tools/probes/sas-presentation.mjs
```

The invariant, asserted after every call: if the safety-code card is up, the
digits in it are the `sas` of the peer `S.sasAsk` names, and that peer is an
unverified **cloud** viewer. There is no longer a persistent `#sas-chip` in the
rail at all — the probe asserts it stays hidden — and once the Monitor's user has
vouched for the channel's code once (`markSasReviewed`), the card stays down even
for a brand-new cloud viewer.

Covers 1/2/3 cloud viewers, LAN-only, mixed LAN+cloud, a peer whose DTLS has not
settled, the pinned phone leaving mid-prompt, and the once-per-channel latch.
**All assertions green on 2026-08-31.**

## `sas-review-once.mjs` — the Handheld asks once, not every call

The other side of the same card. It lifts `showViewerSas()`, `sasReviewed()` and
`markSasReviewed()` out of `public/app.js` *by source text* and runs them against
a stubbed DOM and `localStorage`.

```bash
node tools/probes/sas-review-once.mjs
```

Asserted: the review card shows on the first cloud connection to a channel; after
the user answers "Looks right" the card and the top-rail chip stay down on every
later connection (checked across ten reconnects, each with a different code, as
WebRTC guarantees); a code that could **not** be computed still shows, because
that is an alarm rather than a review, and a warn state is never remembered as
one; and the once-flag is per channel, so a different monitor still gets its
first ask. **All assertions green on 2026-08-31.**

## `relay-allowlists.mjs` — the four forward lists agree

Every signalling relay (`server.js`, `rendezvous/room.js`,
`rendezvous/deno/main.ts`, `LocalWeb.kt`) keeps its own `RELAY` set of addressed
message `type`s it will forward. A type in `sig()` but missing from one is
dropped in silence on that transport — the drift that broke the lens picker,
remote zoom, pet-name sync and the Light key, one relay at a time.

```bash
node tools/probes/relay-allowlists.mjs
```

Parses the `RELAY` literal out of all four files and asserts they are identical,
and that every type in a curated required list — the call itself, sound, lens,
`meta`, `torch`, and now `battery` (the Monitor's charge mirrored to the
Handhelds) — is present in every one. **All four in step on 2026-08-31.**

## `battery-mirror.mjs` — the Monitor's charge on the Handheld

Lifts `setStationBattery` / `batteryState` / `broadcastBattery` /
`updateBatteryUI` out of `public/app.js` and runs them against a stubbed DOM,
peer set and `sig()`.

```bash
node tools/probes/battery-mirror.mjs
```

Asserts the two things that make the reading trustworthy on the Handheld: the
Monitor re-sends only on a real change (a repeated broadcast at the same percent
and state costs nothing; a 1% step or a plug/unplug always goes out), and the
Handheld chip maps a percent to the right gauge width and the right state class
(`is-charging` on power, `is-low` under 15% unplugged, hidden with no reading).
**All assertions green on 2026-08-31.**

## `chime-routing.mjs` — a chime reaches an audible stream

Lifts `CHIMES` / `chimeSpec` / `playChime` out of `public/app.js`.

```bash
node tools/probes/chime-routing.mjs
```

On the native Monitor a chime must go to the shell (`tellNative('chime', …)`),
never the page's WebAudio: WebAudio output lands on `STREAM_MUSIC`, which Android
keeps muted underneath a call and which the volume keys will not raise while one
is running, so the shell plays the bundled clip on the call's own stream
instead. Asserts the native path sends exactly one `chime` event with a
sanitised slug and never touches WebAudio, an unknown slug falls back to the
default, and a browser Monitor still plays through WebAudio. **Green on
2026-08-31.**

## `video-fit.mjs` — fill the screen, or box the frame

Lifts `screenIsWide` / `idealCaptureSize` / `fitVideo` out of `public/app.js`.

```bash
node tools/probes/video-fit.mjs
```

The full-frame video fills the stage (`cover`, no bars) when the picture and the
screen face the same way — you have turned the phone to match the camera — and
is boxed (`contain`, whole frame) when they don't, so a sideways feed on an
upright phone still shows the whole room. The Monitor's capture takes its long
axis from the orientation too, at a fixed ~540p budget. Both are aspect-driven
only: asserts all four orientation pairings, the square and no-metadata edges,
and that `idealCaptureSize` swaps width/height with the orientation for any
requested size. **Green on 2026-08-31.**

## Running the worker locally

```bash
cd rendezvous && npx wrangler@latest dev --local --port 8787
```
