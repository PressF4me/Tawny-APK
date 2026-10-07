# Tawny for Android — changelog

Newest first. Google Play builds.

## 1.0.2 (build 45)

Changes since 1.0.1.

**Fixed**
- The update board did not show on a phone that was updated before it was ever
  set up: it was taken for a fresh install. Tawny now asks Android whether it
  was installed or updated, and also shows the board on the welcome and role
  screens.

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
