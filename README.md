# Tawny — Pet Monitor

Turn two phones into a private pet camera. One phone stays with the pet (**the
Watcher**) and streams its camera and microphone; the other (**the Handheld**)
watches, listens, talks back, and rings a chime. Pairing is a photo of a QR
code — no account, no sign-up.

Video and audio go **peer to peer over WebRTC**, encrypted end to end
(DTLS-SRTP). On your home Wi-Fi nothing leaves the house: one of your phones does
the job a server would.

- **Android app:** [`README-ANDROID.md`](README-ANDROID.md)
- **Remote (off-Wi-Fi) setup:** [`rendezvous/README.md`](rendezvous/README.md)
- **Publishing to Google Play:** [`PLAY-SUBMISSION.md`](PLAY-SUBMISSION.md)
- **Security model & residual risk:** [`SECURITY.md`](SECURITY.md)

---

## How it connects

| Situation | Path | Server involved |
|---|---|---|
| Both phones on the same Wi-Fi | The Watcher runs a tiny signalling relay on the LAN; the Handheld connects straight to it. | none — it's the Watcher phone |
| Phones on different networks *(optional build)* | Both phones dial **out** to a small **rendezvous** service that only introduces them. If they can't reach each other directly, an encrypted **TURN** relay forwards the media (it can't read it). | one small always-on service you deploy — see `rendezvous/` |

No inbound ports are opened on either phone in any configuration.

A **channel** is a name plus a 128-bit key generated on the device. Servers are
told only `sha256(key)`, so channels can't be guessed or enumerated, and the key
never reaches a server. The key rides in the pairing QR / link.

## Repository layout

```
android/                 the Android app (native shell + bundled web client)
public/                  the web client — index.html, style.css, app.js
public/vendor/           qrcode-generator (MIT), vendored for offline use
server.js                self-host reference relay (Node) — signalling + /turn + static
rendezvous/              Cloudflare Worker + Durable Object for the remote path
  worker.js room.js wrangler.toml
  deno/main.ts           single-file alternative for Deno Deploy
  README.md              deploy steps (Cloudflare / Deno / self-hosted coturn)
desktop/                 optional Linux desktop launcher for the web client
PLAY-SUBMISSION.md       Google Play checklist + pre-review audit
privacy-policy.md        privacy policy text (host it, link it in Play Console)
docs/                    data-safety answers, store listing copy, reviewer notes
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
