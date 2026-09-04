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
`tawny://pair?k=<key>&n=<name>&h=<lan-ip:port>&t=<ticket>&c=<code>&e=<expiry>`.

- `h` is the Monitor's home-LAN address. `parsePairing` accepts it **only** if it
  is an RFC1918 / link-local address; a public IP in a pairing link is dropped
  (and, if that leaves nothing to dial, the link is rejected). `h` is omitted
  entirely when the Monitor has no Wi-Fi.
- `t` is a long-lived **admission ticket** for the rendezvous (below). It is a
  different thing from `c`, with a different life.
- `c` and `e` are the pairing code and its deadline — see the next section.
- The **in-app scanner** joins immediately — the user aimed the camera on
  purpose. Any **externally-supplied** link (`ACTION_VIEW`, exported + BROWSABLE
  intent-filter) is gated by a "Connect to '<name>'?" dialog that names the
  target and its address and warns when it would replace an existing pairing.
  `handlePairLink` never acts silently.

## Pairing codes expire after ten minutes

A pairing code is a bearer credential: whoever photographs the QR, or is
forwarded the link out of a chat app, holds the channel key. Ten minutes is how
long that credential may be used to **pair a new phone**. It is not a session
timeout — a phone that finished pairing inside the window keeps working
afterwards, indefinitely, and is never asked to re-pair.

The awkward part of the rule is where it can possibly be enforced. Not by the
bearer: the scanning phone can lie about its clock, or simply be an older build.
Not by the relay either: an expired code hands a relay the same room id and the
same admission ticket as a fresh one, so there is nothing there to tell them
apart. The one party that knows when a code went on screen is the **Monitor** —
and it is also the party that owns the camera and answers every offer, on the
LAN and through the rendezvous alike. So:

- every code carries a random `c` and its deadline `e`;
- the Monitor holds `c` and rotates it every ten minutes **by its own clock**,
  redrawing the QR in place and telling the page (`window.tawnyPairCode`) which
  code now counts. The pairing sheet shows the remaining time in words, so a
  Monitor left sitting on that screen is never displaying a dead code;
- a phone the Monitor has never admitted must present the `c` the Monitor is
  showing *now*, in its `offer`, before `answerPeer` attaches a single track.
  Anything else — no code, a previous code, a code past its deadline, no code on
  the Monitor at all — is refused with `bye {reason:'expired'}`;
- a phone the Monitor **has** admitted is remembered by a 128-bit `pid` the
  phone minted for itself (`tawny.bond.<channel>`, per channel, never derived
  from anything identifying, sent only to the paired Monitor). At most 8 are
  remembered per channel, and the list is dropped when the channel is deleted.

`e` in the link is a courtesy, not the enforcement: it lets the scanning phone
say "this code expired, get a fresh one" immediately rather than dialling into a
refusal, and the native shell shows the same screen whether the refusal came
from that pre-flight check or from the Monitor. Lying about it buys nothing.

**Stated plainly:** this bounds who can *newly pair*, not what a leaked key can
eventually reach. Someone who photographs a live code and uses it inside those
ten minutes is paired for good — as they are today — and someone who holds the
key can still compute the room id and the ticket. Rotating a channel's key,
which is what would actually revoke a leak, still means re-pairing every phone
and is not what this does. What it removes is the standing risk of an old QR
photograph, a screenshot in a chat thread, or a printed code on a fridge staying
live for the life of the channel.

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
- Caps: `MAX_ROOMS = 32`, `MAX_PER_IP = 8`, `MAX_PER_ROOM = 4` (one Monitor plus
  the three Viewers the product allows),
  `MAX_PENDING = 64`.

The bundled asset server on `127.0.0.1` bounds its request line (8 KB), header
count (64), socket timeout (5 s) and thread pool — binding to loopback is not a
UID boundary, so any other app on the device with `INTERNET` can reach it.

## WebView hardening (`MainActivity.showWeb`)

- loads only bundled assets from `http://127.0.0.1:<port>` — no remote web
  content, and `index.html` carries a strict `<meta http-equiv>` CSP
  (`default-src 'none'; script-src 'self'; …; connect-src 'self' ws: wss:
  https:`). The loopback server sends a tighter one still: `connect-src` names
  the rendezvous hosts this install may reach — the build's, plus the one an
  advanced user has set on the Servers screen — rather than a blanket `https:`,
  so an injected script could not post the channel key to an arbitrary host.
  It is a list, never a wildcard, and the server is rebuilt when the list
  changes;
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

**One code per connection.** The code is derived from the DTLS certificates of
one `RTCPeerConnection`, so a Monitor with three Handhelds on the cloud path has
*three* different codes at once. Each is held on its own peer record, never in a
shared slot: the Monitor's review card asks about one phone at a time (naming how
many are queued behind it), and answering "Disconnect it" drops that one
Handheld, not the session. The code is **not** shown anywhere else — there is no
persistent rail chip; a code that sat over the picture on every connection was
clutter the live screen did not need.

**Reviewed once per monitor, on both ends.** WebRTC mints a fresh DTLS
certificate for every `RTCPeerConnection` and the app persists none, so the code
legitimately differs on every call — it cannot degrade to "the same code as last
time". An earlier build re-showed it on every connect and warned that the code
had CHANGED whenever it failed to match, which from the second session onward was
every time; that false alarm only trained users to dismiss it. Now the review
card is shown on the **first** cloud connection to a channel — on the Handheld,
and on the Monitor — and once that user answers "Looks right" it never returns
for that channel. The acknowledgement is a per-channel `tawny.sasok.<id>` flag in
`localStorage`, cleared when the channel is deleted. The trade-off is explicit
and the same on both ends: a certificate swap attempted *after* that first review
is not surfaced unless it also stops the code from being computed at all — in
which case the "connection may be tampered with" warning still fires on every
affected call. In particular, a *new* outside Handheld joining a channel whose
code has already been vouched for is not re-reviewed.

**Limitations, stated plainly:** the SAS assumes the user can compare two
screens. On a genuinely remote session the user is not in the room with the
Monitor, so there is only one screen to look at, and the check is weak there.
Media is added and the answer is sent before the SAS is shown — it is an
after-the-fact alarm, not a gate.

## Residual risk — still true

- **A compromised rendezvous can attempt a certificate swap.** The SAS detects it
  only if the user actually compares codes; on a one-screen remote session that
  is weak. Both ends also review the code only on the first connection to a
  channel, so a swap on a later reconnect — or against a new outside phone on an
  already-vouched channel — is not surfaced unless the code cannot be computed at
  all. This is the softest point of the remote path.
- **Pairing codes are bearer credentials, for ten minutes.** A code now stops
  admitting new phones ten minutes after the Monitor shows it (above), so an old
  photograph of a QR or a link left in a chat thread no longer pairs. Inside
  that window it still does: anyone who catches a *live* code is paired for good,
  because the channel key travels in it. There is still no per-Viewer
  revocation, and deleting the channel and re-pairing every phone is still the
  only way to undo a leak.
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
- **A custom relay is another operator in the threat model.** The Servers screen
  (behind the diagnostics hatch) lets an advanced user point the app at their
  own rendezvous and TURN. Whoever runs that service gets exactly what the
  operator of the built-in one gets, and no more: the opaque room id, the
  admission ticket, and the ability to attempt a certificate swap that the SAS
  is there to catch. It never sees the channel key or a video frame. Two
  practical notes: `ws://` is accepted for a relay on your own network and the
  save warns that the signalling is then readable on the path (the media stays
  DTLS-SRTP either way), and the relay is *preferred*, not substituted — if it
  will not answer, the session falls back to the built-in tunnel rather than
  losing the remote path — an availability choice, not a security one.
- **Node/Deno `server.js` can be run without tickets** (`REQUIRE_TICKET=off`) for
  a LAN-style self-host; in that mode any viewer with the room id is admitted.
- **No audit log** of who joined when, and no alert on a new device pairing.
- **TURN depends on Cloudflare being up, and on a bandwidth allowance.** TURN is
  provisioned (Cloudflare Realtime): `/turn` issues short-lived credentials to a
  caller holding a ticket valid for the room, so two peers both behind
  carrier-grade NAT now fall back to a relay instead of failing. Media through
  that relay is still DTLS-SRTP end to end — the relay forwards packets it
  cannot read, and stores nothing. The residual points are availability and
  cost, not confidentiality: a Cloudflare Realtime outage takes the relay path
  down with it (LAN and direct-P2P calls are unaffected), and the free tier
  covers 1 TB/month of relayed egress — roughly 1,400–2,000 hours of relayed
  video — after which it bills at $0.05/GB. `turnMode:"auto"` keeps the relay out
  of the path entirely whenever a direct one exists, so only the genuinely
  CGNAT-bound calls draw on it.
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
