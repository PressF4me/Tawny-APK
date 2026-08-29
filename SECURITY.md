# Security notes — Tawny

Tawny is a two-way pet monitor built from three parts:

1. a **native Android shell** (onboarding, QR pairing, the WebView host);
2. a **web client** bundled in the APK that owns the WebRTC stack;
3. the **Watcher's embedded LAN relay** (`LocalWeb.kt`) and, for remote use, an
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

- `h` is the Watcher's home-LAN address. `parsePairing` accepts it **only** if it
  is an RFC1918 / link-local address; a public IP in a pairing link is dropped
  (and, if that leaves nothing to dial, the link is rejected). `h` is omitted
  entirely when the Watcher has no Wi-Fi.
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
- a **Handheld's** hello must carry a `t` whose `sha256` matches the ticket the
  Watcher registered. No matching ticket ⇒ `close(4008)` (fail-closed on
  Cloudflare; `server.js` honours `REQUIRE_TICKET`, default on);
- a **Watcher's** hello carries `hashT = sha256(t)`. If a live ticket already
  exists for the room it must match (a second party cannot overwrite the
  registration or seize the station slot); otherwise the Watcher registers it.
  Tickets expire after 24 h and a Watcher re-registers the same one on restart.
- `/turn` credentials are issued only to a caller that presents a ticket valid
  for the room, and are rate-limited and origin-checked. They are short-lived
  (~1 h) but **not** otherwise room-scoped — Cloudflare Realtime TURN issues
  generic credentials — so the ticket gate + rate limit are what bound abuse.

The ticket is **admission and abuse control only**. It carries no key material
and does not protect the media. A leaked ticket lets someone attempt to join the
rendezvous room; it does not let them decrypt anything, and there is no
per-Handheld revocation.

## LAN relay (`LocalWeb.kt` · `SignalServer`)

Deliberately omits the ticket / token / Origin checks that the internet-facing
relay has — a LAN pairing does not need them, and adding them would be theatre
against someone already on your Wi-Fi who has the 128-bit key. Hardening that is
in place:

- room id must match `^[0-9a-f]{32}$` exactly; `MAX_ROOMS = 32`;
  `MAX_PER_ROOM = 6` (1 Watcher + 5 Handhelds), one station per room;
- **`MAX_PER_IP = 8`** sockets — one LAN host can no longer fill the room map or
  the peer slots (the room-id regex only checks format, not that a room is real);
- the bundled `AssetHttpServer` is GET-only, `127.0.0.1`-only, resolves paths
  segment-by-segment (rejecting any climb above `web/`, including `....//` and
  `%2e%2e`), and now emits `Content-Security-Policy`, `X-Content-Type-Options:
  nosniff`, and `Referrer-Policy: no-referrer` on every response.

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

On the **cloud** path only (the LAN Watcher *is* the relay, so there is nothing
to check), both ends show a 6-character code derived from
`HMAC(channelKey, "tawny-sas-v1" | sorted DTLS certificate fingerprints)`. The
fingerprints are read from `getStats()` — the *negotiated* certificate, not the
SDP text, so a relay cannot hide a real fingerprint behind a decoy
session-level `a=fingerprint` line. Because the channel key is mixed in and no
relay holds it, a relay that swapped certificates produces a **different** code
on each screen. If the code cannot be computed the Handheld shows a "connection
may be tampered with" warning rather than proceeding silently.

**Limitations, stated plainly:** the SAS assumes the user can compare two
screens. On a genuinely remote session the user is not in the room with the
Watcher, so there is only one screen to look at — the check then degrades to
"does this code look the same as last time / as the sticker on the Watcher".
Confirming once stops the *blocking* prompt for that monitor; the chip stays.
Media is added and the answer is sent before the SAS is shown — it is an
after-the-fact alarm, not a gate.

## Residual risk — still true

- **A compromised rendezvous can attempt a certificate swap.** The SAS detects it
  only if the user actually compares codes; on a one-screen remote session that
  is weak. This is the softest point of the remote path.
- **Pairing codes are bearer credentials.** Anyone who photographs the QR or gets
  the copied link out of a chat app has access to that channel until it is
  deleted and every device re-paired. No per-Handheld revocation; the rendezvous
  ticket's 24 h expiry is the only automatic limit.
- **The channel key lives in `localStorage`** (WebView DOM storage) and app
  prefs, unencrypted at rest. The CSP makes script injection hard but not
  impossible; the key is still readable by anyone with the unlocked device.
- **Peers learn each other's IP addresses** — inherent to WebRTC. With TURN in
  `relay` mode the peers see only the relay's address.
- **A LAN peer can still degrade the Watcher's relay** — the per-IP cap bounds a
  single host to 8 sockets, but several cooperating hosts on the Wi-Fi could
  fill 32 rooms. DoS only, by someone already on your network.
- **Rendezvous rate-limit / ticket state is in memory** (Node reference) or per
  Durable Object (Cloudflare) / per isolate (Deno — pin one region). A restart
  clears the Node one.
- **Node/Deno `server.js` can be run without tickets** (`REQUIRE_TICKET=off`) for
  a LAN-style self-host; in that mode any viewer with the room id is admitted.
- **No audit log** of who joined when, and no alert on a new device pairing.

## Reporting

Open a private security advisory on the repository, or email the address in the
Play listing. Please do not file public issues for exploitable bugs.
