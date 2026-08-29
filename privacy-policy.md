# Tawny — Privacy Policy

_Last updated: 2026-08-29_

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
| **Diagnostics log** | An optional, deliberately out-of-the-way log (long-press the small version number) for working out why a connection failed. Records hashed room identifiers, never your key. | No, unless you choose to send it. Excluded from cloud backup. |
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

The rendezvous service for the Play release is operated by the developer of this
listing; contact details are below.

## Children

Tawny is not directed to children under 13 and does not knowingly collect
information from them.

## Security

Media is encrypted in transit (DTLS-SRTP). The first connection made over the
internet to a monitor shows a short safety code on both screens for you to
compare, which detects a tampered relay; if that code ever changes later, you
are asked to check it again. Pairing codes are like a key to your channel — only share them
with devices you own, and re-pair if a code may have leaked.

## Changes

If this policy changes materially, the "Last updated" date above will change and
the new version will ship with an app update.

## Contact

Questions or a data-deletion request: **tawnyapp.radar137@passinbox.com**

The published copy of this policy is `~/Documents/Tawny ship/privacy-policy/index.html`;
keep the two in step if you edit one.

There is generally nothing for us to delete, because we do not collect or store
your personal data. Clearing the app's data (Android Settings → Apps → Tawny →
Storage) removes the pairing key and all local state from that device.
