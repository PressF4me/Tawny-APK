# Tawny — Pet Monitor

Turn two phones into a private pet camera. One phone stays with the pet (**the
Monitor**) and streams its camera and microphone; the other (**the Viewer**)
watches, listens, talks back, and rings a chime. Pairing is a photo of a QR
code — no account, no sign-up.

Video and audio go **peer to peer over WebRTC**, encrypted end to end
(DTLS-SRTP). On your home Wi-Fi nothing leaves the house: one of your phones does
the job a server would.

- **Android app:** [`README-ANDROID.md`](README-ANDROID.md)
- **Remote (off-Wi-Fi) setup:** [`rendezvous/README.md`](rendezvous/README.md)
- **Publishing to Google Play:** [`PLAY-SUBMISSION.md`](PLAY-SUBMISSION.md)
- **Security model & residual risk:** [`SECURITY.md`](SECURITY.md)

## Supporting it

Tawny is free, has no ads, no account, and nothing behind a paywall. The only
recurring cost is the rendezvous/TURN relay that lets the two phones find each
other when they are not on the same Wi-Fi.

**<https://ko-fi.com/tawnyone>** — one-off or recurring, and it unlocks nothing
in the app. That is deliberate: Play's Payments policy only permits an external
contribution link when it grants no digital benefit of any kind, and "supporters
get nothing extra" is also the honest version. The same URL is the one shown in
the app's **About** screen and in the web client's footer — if you see a
different one anywhere claiming to be us, it isn't.

---

## How it connects

| Situation | Path | Server involved |
|---|---|---|
| Both phones on the same Wi-Fi | The Monitor runs a tiny signalling relay on the LAN; the Viewer connects straight to it. | none — it's the Monitor phone |
| Phones on different networks *(optional build)* | Both phones dial **out** to a small **rendezvous** service that only introduces them. If they can't reach each other directly, an encrypted **TURN** relay forwards the media (it can't read it). | one small always-on service you deploy — see `rendezvous/` |

No inbound ports are opened on either phone in any configuration.

A **channel** is a name plus a 128-bit key generated on the device. Servers are
told only `sha256(key)`, so channels can't be guessed or enumerated, and the key
never reaches a server. The key rides in the pairing QR / link.

A pairing code is good for **ten minutes**, counted down on the Monitor's own
screen and re-minted in place when it runs out — an old photograph of a QR does
not pair a phone later. Phones that paired inside the window keep working; the
rule is about who can newly join, not how long a session lasts. See
[`SECURITY.md`](SECURITY.md), "Pairing codes expire after ten minutes".

## Repository layout

```
android/                 the Android app (native shell + bundled web client)
public/                  the web client — index.html, style.css, app.js
public/vendor/           qrcode-generator (MIT), vendored for offline use
server.js                self-host reference relay (Node) — signalling + /turn + static
rendezvous/              Cloudflare Worker + Durable Object for the remote path
  worker.js room.js wrangler.toml
  privacy.js             the privacy policy page, served at GET /privacy
  deno/main.ts           single-file alternative for Deno Deploy
  README.md              deploy steps (Cloudflare / Deno / self-hosted coturn)
desktop/                 optional Linux desktop launcher for the web client
PLAY-SUBMISSION.md       Google Play checklist + pre-review audit
privacy-policy.md        privacy policy source text (published from rendezvous/privacy.js)
docs/                    DIRECTION.md (strategy), play-submission-runbook.md, listing notes
SECURITY.md              threat model, hardening, residual risk
```

## Self-hosting the web client

`server.js` (Node 18+) serves `public/` and the signalling relay:

```sh
npm install && node server.js       # http://localhost:8099
```

Browsers only release the camera/mic on `https://` (or `localhost`), so put it
behind TLS — a reverse proxy, or `tailscale serve --bg 8099`. Environment:
`PORT`, `HOST`, `TAWNY_TOKEN`, `ALLOWED_HOSTS`, `STUN_URLS`, `RENDEZVOUS_URL`,
`TURN_MODE`, `TAWNY_TURN_URLS`, `TAWNY_TURN_SECRET`. See `docker-compose.yml`.

No CDN, no web fonts fetched at runtime, no analytics — the app works on a
network with no internet at all.
