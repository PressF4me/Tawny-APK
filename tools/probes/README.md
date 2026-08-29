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
room, which would be a worse failure than the bug it fixes. 4/4 locally.

## Running the worker locally

```bash
cd rendezvous && npx wrangler@latest dev --local --port 8787
```
