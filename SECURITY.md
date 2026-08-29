# Security notes — Tawny

Tawny is a two-way pet monitor built from three parts:

1. a **native Android shell** (onboarding, QR pairing, the WebView host);
2. a **web client** bundled in the APK that owns the WebRTC stack;
3. the **Monitor's embedded LAN relay** (`LocalWeb.kt`) and, for remote use, an
   optional **rendezvous** (`rendezvous/`, or the `server.js` reference).

Media is always peer-to-peer and DTLS-SRTP encrypted. No relay in any
configuration sees a video frame or the channel key.

## Threat model

The feed is a live camera and microphone inside a home. The adversaries worth
worrying about:

- someone else on the same Wi-Fi (a guest, a compromised IoT device);
- a malicious web page open in a browser on one of the phones;
- an app on the phone firing a crafted `tawny://pair` link;
- for the remote path: whoever operates the rendezvous / TURN service, and
  anyone on the network path to it.

## Channels & pairing

A channel is a name plus a **128-bit key** generated on the device with a CSPRNG.
The key never leaves the device. Every relay is told only
`sha256("tawny-room-v1|" + key)` truncated to 32 hex — an opaque room id that
cannot be reversed to the key or the name, and cannot be enumerated.

Pairing carries the key in a QR / `tawny://pair` link:
`tawny://pair?k=<key>&n=<name>&h=<lan-ip:port>&t=<ticket>`.

- `h` is the Monitor's home-LAN address. `parsePairing` accepts it **only** if it
  is an RFC1918 / link-local address; a public IP in a pairing link is dropped
  (and, if that leaves nothing to dial, the link is rejected). `h` is omitted
  entirely when the Monitor has no Wi-Fi.
- `t` is a short per-pairing **admission ticket** for the rendezvous (below).
- The **in-app scanner** joins immediately — the user aimed the camera on
  purpose. Any **externally-supplied** link (`ACTION_VIEW`, exported + BROWSABLE
  intent-filter) is gated by a "Connect to '<name>'?" dialog that names the
  target and its address and warns when it would replace an existing pairing.
  `handlePairLink` never acts silently.

## Signaling admission — the `hello` handshake

Every relay (LAN and hosted) uses the same addressed message set —
`offer/answer/ice/bye/chime/chime-ack/talking`, each requiring a `to` naming a
peer in the same room; the server stamps `from`. No broadcast primitive exists.

On the **hosted** path (`rendezvous/room.js`, `rendezvous/deno/main.ts`,
`server.js`) a socket is not joined to the room or told about anyone until it has
sent `{type:'hello'}` and passed admission:

- the ticket rides in that first frame, **not** in the URL — query strings land
  in TLS-terminator access logs;
- a **Viewer's** hello must carry a `t` whose `sha256` matches the ticket the
  Monitor registered. No matching ticket ⇒ `close(4008)` (fail-closed on
  Cloudflare; `server.js` honours `REQUIRE_TICKET`, default on);
- a **Monitor's** hello carries `hashT = sha256(t)`. If a live ticket already
  exists for the room it must match (a second party cannot overwrite the
  registration or seize the station slot); otherwise the Monitor registers it.
  Tickets expire after 24 h and a Monitor re-registers the same one on restart.
- `/turn` credentials are issued only to a caller that presents a ticket valid
  for the room, and are rate-limited and origin-checked. They are short-lived
  (~1 h) but **not** otherwise room-scoped — Cloudflare Realtime TURN issues
  generic credentials — so the ticket gate + rate limit are what bound abuse.

The ticket is **admission and abuse control only**. It carries no key material
and does not protect the media. A leaked ticket lets someone attempt to join the
rendezvous room; it does not let them decrypt anything, and there is no
per-Viewer revocation.

## LAN relay (`LocalWeb.kt` · `SignalServer`)

Admission used to be "you sent a well-formed room id", which was no admission at
all. The room id travels in the WebSocket URL over plain `ws://`, so anyone
sharing the Wi-Fi could read one off the wire, join the room, and be handed the
live camera and microphone. Worse, the client attached its *rendezvous* ticket
to that same cleartext hello — so a sniffer got `(room id, ticket)`, which is
exactly what the internet relay admits a viewer on. Someone on your Wi-Fi could
become someone anywhere.

Now:

- Every socket is challenged with a fresh 16-byte nonce and must answer with
  `HMAC-SHA256(channel key, "tawny-lan-v1|" + nonce)`. The key never goes on the
  wire and a captured answer is worthless on the next connection.
- A socket is not placed in a room, not counted against any cap, and not
  announced to anyone until it answers. Unanswered challenges are swept after
  5 s, and are counted against the per-host cap so they cannot be used to
  sidestep it.
- The raw ticket is only ever sent on the TLS-protected cloud transport.
- Frames are capped at 64 KB at the protocol level (`Draft_6455`), and the room
  map entry is created on admission rather than on connection — refused
  connections used to leave permanent empty rooms behind, walking the map toward
  `MAX_ROOMS`.
- Caps: `MAX_ROOMS = 32`, `MAX_PER_IP = 8`, `MAX_PER_ROOM = 6`,
  `MAX_PENDING = 64`.

The bundled asset server on `127.0.0.1` bounds its request line (8 KB), header
count (64), socket timeout (5 s) and thread pool — binding to loopback is not a
UID boundary, so any other app on the device with `INTERNET` can reach it.

## WebView hardening (`MainActivity.showWeb`)

- loads only bundled assets from `http://127.0.0.1:<port>` — no remote web
  content, and `index.html` carries a strict `<meta http-equiv>` CSP
  (`default-src 'none'; script-src 'self'; …; connect-src 'self' ws: wss:
  https:`);
- `onPermissionRequest` grants camera/mic capture only for that host **and**
  only what the OS has already granted the app; `shouldOverrideUrlLoading` /
  `setDownloadListener` pass only `http(s)` to `ACTION_VIEW` (`intent://`,
  `file://` blocked). Note: these checks compare the **host** and ignore the
  port, and the `TawnyNative` JS interface itself is **not** origin-scoped
  (`addJavascriptInterface` exposes it to every frame). Neither is reachable
  today — the WebView loads only bundled first-party assets and has no iframes —
  but the controls are host-level, not true origin isolation.
- cloud-backup rules exclude the prefs file (channel key + tickets) and the
  WebView data directory; device-to-device transfer keeps them.

## Short authentication string (SAS)

On the **cloud** path only (the LAN Monitor *is* the relay, so there is nothing
to check), both ends show a 6-character code derived from
`HMAC(channelKey, "tawny-sas-v1" | sorted DTLS certificate fingerprints)`. The
fingerprints are read from `getStats()` — the *negotiated* certificate, not the
SDP text, so a relay cannot hide a real fingerprint behind a decoy
session-level `a=fingerprint` line. Because the channel key is mixed in and no
relay holds it, a relay that swapped certificates produces a **different** code
on each screen. If the code cannot be computed the Viewer shows a "connection
may be tampered with" warning rather than proceeding silently.

**Limitations, stated plainly:** the SAS assumes the user can compare two
screens. On a genuinely remote session the user is not in the room with the
Monitor, so there is only one screen to look at — the check then degrades to
"does this code look the same as last time / as the sticker on the Monitor".
Confirming once stops the *blocking* prompt for that monitor; the chip stays.
Media is added and the answer is sent before the SAS is shown — it is an
after-the-fact alarm, not a gate.

## Residual risk — still true

- **A compromised rendezvous can attempt a certificate swap.** The SAS detects it
  only if the user actually compares codes; on a one-screen remote session that
  is weak. This is the softest point of the remote path.
- **Pairing codes are bearer credentials.** Anyone who photographs the QR or gets
  the copied link out of a chat app has access to that channel until it is
  deleted and every device re-paired. No per-Viewer revocation; the rendezvous
  ticket's 24 h expiry is the only automatic limit.
- **The channel key lives in `localStorage`** (WebView DOM storage) and app
  prefs, unencrypted at rest. The CSP makes script injection hard but not
  impossible; the key is still readable by anyone with the unlocked device.
- **Peers learn each other's IP addresses** — inherent to WebRTC. With TURN in
  `relay` mode the peers see only the relay's address.
- **A LAN peer can still degrade the Monitor's relay** — the per-IP cap bounds a
  single host to 8 sockets, but several cooperating hosts on the Wi-Fi could
  exhaust the pending-admission slots. DoS only, by someone already on your
  network, and they can no longer reach the camera.
- **Rendezvous rate-limit / ticket state is in memory** (Node reference) or per
  Durable Object (Cloudflare) / per isolate (Deno — pin one region). A restart
  clears the Node one.
- **Node/Deno `server.js` can be run without tickets** (`REQUIRE_TICKET=off`) for
  a LAN-style self-host; in that mode any viewer with the room id is admitted.
- **No audit log** of who joined when, and no alert on a new device pairing.
- **TURN is not provisioned.** `/turn` answers 404, so calls are STUN-only: two
  peers both behind carrier-grade NAT will fail to connect rather than fall back
  to a relay. A failure, not a leak — but it is why "from anywhere" is not
  promised in the store listing.
- **The Monitor's camera stops when its screen turns off.** There is no
  foreground service. The app now detects the loss, tells viewers the monitor is
  paused rather than leaving a frozen frame up, and re-acquires on foreground —
  but an unattended Monitor must be kept awake.

## Reporting

Email **tawnyapp.radar137@passinbox.com**, which is also the contact address on the Play
listing. Please do not file public issues for exploitable bugs.

(This previously pointed at "a private security advisory on the repository".
There is no such repository — the project has no public remote — so that was a
reporting channel that did not exist.)
