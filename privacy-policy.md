# Tawny — Privacy Policy

_Last updated: 2026-09-18_

Tawny is a two-way pet monitor. One device (**the Monitor**) stays with your pet
and sends its camera and microphone; one or more other devices (**Viewers**)
watch and talk back. This policy explains what the app does and does not do with
your information.

## The short version

- **No account. No sign-up.** The app never asks for your name, email, or phone
  number.
- **No analytics, no advertising, no tracking.** The app contains no third-party
  analytics, crash-reporting, or advertising SDKs.
- **Your video and audio are not recorded or stored** by us, anywhere. They flow
  directly between your own devices, encrypted.
- On your home Wi-Fi, **nothing leaves your home** — one of your phones acts as
  the server.

## What the app accesses on your device

| Data / permission | Why | Leaves the device? |
|---|---|---|
| **Camera** | The Monitor streams live video of your pet. The Viewer uses the camera only to scan the Monitor's pairing QR code. | Video is sent, encrypted, only to your paired Viewer(s). Never to us. |
| **Microphone** | The Monitor streams room sound; the Viewer sends your voice when you hold "talk". | Audio is sent, encrypted, only to the paired device(s). Never to us. |
| **Local network** | To discover and connect your two phones. | Stays on your Wi-Fi. |
| **Photos you save** ("Snapshot") | Saved to your device's Pictures folder, in a "Tawny" album. | No. |
| **Diagnostics log** | An optional, deliberately out-of-the-way log (long-press the small version number) for working out why a connection failed. Records hashed room identifiers, never your key. | Only if you tap **Send to Tawny** — see "Sending a diagnostics report" below. Otherwise it stays on your device and is excluded from cloud backup. |
| **Pairing key** | A random 128-bit key, generated on your device, that identifies your private channel. Stored in the app's private storage. | No — it is never sent to any server. Servers only ever see an irreversible hash of it. |

The app requests camera and microphone access only when you set up a role, and
only after showing you a screen explaining what they are for.

## How a connection is made

Video and audio use **WebRTC** and are encrypted end to end with DTLS-SRTP.

- **Same Wi-Fi:** one of your phones runs a tiny signalling relay on the local
  network. No server on the internet is involved, and no data leaves your home.
- **From away:** both devices dial out to a small "rendezvous" service whose
  only job is to introduce them to each other. That service:
  - never receives your pairing key (only an irreversible hash of it);
  - never receives video or audio frames;
  - may, when the two devices cannot reach each other directly, relay the
    **encrypted** media stream (a TURN relay). It cannot decrypt it, and it does
    not store it.
  - briefly processes the devices' **IP addresses**, as any internet connection
    must, to route packets. These are not logged to identify you and are not
    shared with anyone.

- **To find a direct path**, each device may ask a public **STUN** server what
  its public IP address is. The Play release uses Cloudflare's
  (`stun.cloudflare.com`) and Google's (`stun.l.google.com`). A STUN server sees
  the device's IP address and nothing else: no key, no room, no media.

The rendezvous service for the Play release is operated by the developer of this
listing, on Cloudflare's infrastructure (Cloudflare Workers for the rendezvous,
Cloudflare Realtime for the TURN relay); contact details are below. Cloudflare
processes the connection data described above on the developer's behalf.

The app draws its screens in Android's own **System WebView**. On most devices
the WebView may contact Google for its own services, such as Safe Browsing,
under Google's privacy policy. The app itself sends Google nothing.

## Using your own servers

The diagnostics screen (long-press the version number) has a **Servers**
screen where you can point the app at a rendezvous, STUN and TURN servers of
your own. Those servers then receive what Tawny's would (IP addresses, hashed
room identifiers, encrypted media when relaying), and they are operated by
whoever runs them, not by us. Normally, Tawny's servers stay behind yours as a
fallback, used only if yours do not answer.

The same screen has **For tighter privacy [advanced]**. With it on, the app
contacts only the servers you type there and nothing else:

- no Tawny rendezvous or TURN relay, not even as a fallback;
- no public STUN (a blank STUN field means none);
- no relay picked up from a scanned pairing code;
- no **Send to Tawny** button for diagnostics;
- the WebView's Safe Browsing checks are turned off.

If your servers are wrong or unreachable, the app does not connect over the
internet at all. It never falls back to ours.

## Sending a diagnostics report

The diagnostics screen has a **Send to Tawny** button. It does nothing unless you
tap it. When you do, the app sends **that one diagnostics log**, together with
your device model, your Android version and the app's version number, to the
developer's rendezvous service over an encrypted connection, to help work out why
a connection failed.

- It is never sent automatically, only when you tap the button.
- It contains no account, name, email or advertising identifier — there are
  none in the app — and the log records only hashed room identifiers, never your
  pairing key.
- Reports are held for at most 30 days and then deleted automatically.
- If you would rather not send it through the app, the same screen offers
  "Send another way" (your device's normal share sheet) and "Copy", so you can
  send it by email or not at all.

## Children

Tawny is not directed to children under 13 and does not knowingly collect
information from them.

## Security

Media is encrypted in transit (DTLS-SRTP). Every connection made over the
internet to a monitor shows a short safety code on both screens for you to
compare, which detects a tampered relay. A fresh code is drawn for each
connection, so compare it each time rather than expecting the same one twice.
Pairing codes are like a key to your channel — only share them
with devices you own, and re-pair if a code may have leaked.

## Changes

If this policy changes materially, the "Last updated" date above will change and
the new version will ship with an app update.

## Supporting Tawny

Supporting Tawny is entirely optional and unlocks nothing — every feature is
available to everyone, whether or not anyone ever contributes.

- **Ko-fi.** The About screen and the web client's footer carry one external
  link, to **<https://ko-fi.com/tawnyone>**. Following it opens your browser;
  nothing about you is sent there by the app. Any payment page reached that way
  is operated by Ko-fi under its own privacy policy, not by us.
- **Bitcoin (Lightning).** The **Tip in Bitcoin** screen shows a Lightning
  Address (`loustrikes@strike.me`). Its button asks Android to open that address
  in whatever Lightning wallet you have installed; the wallet, not Tawny, does
  everything from there. Tawny sets no amount, holds no funds, includes no
  wallet, and never handles a key, an invoice or a payment. If you have no
  wallet installed, the screen simply shows the address and a QR code. Nothing
  about you is sent anywhere by opening this screen.

## Contact

Questions or a data-deletion request: **tawnyapp.radar137@passinbox.com**

This file is the source text. The **published** copy is
`rendezvous/privacy.js`, which the rendezvous Worker serves at `GET /privacy` —
that is the URL Play Console gets. (`~/Documents/Tawny ship/privacy-policy/
index.html` is an older standalone copy, kept only as a GitHub Pages fallback.)
Edit one and update the others, and bump "Last updated" in all of them.

There is generally nothing for us to delete, because we do not collect or store
your personal data. Clearing the app's data (Android Settings → Apps → Tawny →
Storage) removes the pairing key and all local state from that device.
