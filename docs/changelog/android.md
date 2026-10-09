# Tawny for Android — changelog

Newest first. Google Play builds.

## 1.0.2 (build 45)

Changes since 1.0.1.

**New**
- Who's here: the first time you go live or start watching, Tawny asks what the
  others should call you. Tap the viewer count on the Monitor or a Viewer to
  see everyone on that camera (the Monitor, each Viewer, and which one is you),
  and rename yourself from there.
- Announce viewers: a Monitor can play a short sound each time a phone starts
  watching, so nobody looks in unannounced. It is off by default; turn it on
  from the Monitor's home or About screen, or when naming the Monitor. A bell
  badge in the corner shows while it is on. Viewers cannot trigger it.
  Everyone already watching hears the same sound on their own phone, so the
  people sharing a camera know when someone new has joined.
- The first-call walkthrough now points at the viewer count, where the list of
  who's here opens.

**Fixed**
- In landscape with three-button navigation, the badges along the top sat under
  the navigation bar and could not be tapped.
- A pet with no name was shown in the Monitor's language: a Spanish phone
  paired to an English Monitor read "Ver a your pet ahora". Unnamed pets now
  show as "your pet" / "tu mascota" in each phone's own language.
- The device list names computers properly (Linux PC, Windows PC, Mac,
  Chromebook) instead of calling every one a phone.
- The update board did not show on a phone that was updated before it was ever
  set up: it was taken for a fresh install. Tawny now asks Android whether it
  was installed or updated, and also shows the board on the welcome and role
  screens.
- Setting up or pairing a camera failed on phones whose Android System
  WebView is older than Chrome 92 (seen on Android 11): Tawny used a browser
  call those versions do not have.

## 1.0.1 (build 44)

Changes since build 42, the first production release.

**New**
- An update board after each update: what changed, a link to this page, and a
  thank-you.

**Fixed**
- A phone paired over the internet no longer starts as a second Monitor after
  going back to the welcome screen.
- The QR scanner no longer leaves the camera on if you back out before it is
  ready.
- Opening a pairing link no longer asks "Connect to …?" again after you change
  the theme or language.

**Privacy**
- The diagnostics log never records an IP address. Addresses are replaced with
  `<lan-ip>` or `<ip>` when written and again when shared.

**Spanish**
- About rows, the Bitcoin tip screen, error messages and the "monitor full",
  "code expired" and camera/microphone failure messages are now translated.

## 1.0.0 (build 42)

First production release on Google Play.
