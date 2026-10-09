# Tawny Docker — changelog

Newest first. The self-hosted server image (`ghcr.io/pressf4me/tawny`).

## 2.2.8

Changes since 2.2.7. Same web client as Tawny for Android 1.0.2 (build 45).

**New**
- Who's here: the first time a browser goes live or starts watching, Tawny asks
  what the others should call you. Tap the viewer count to see everyone on
  that camera, and rename yourself from there. Rides the existing relay
  messages, so the rendezvous needs no change.

**Fixed**
- Computers are listed as Linux PC, Windows PC, Mac or Chromebook, not as
  phones.
- A camera with no name was shown in its Monitor's language ("your pet",
  "Pet camera"); it now shows in the browser's own language.

## 2.2.7

Changes since 2.2.6.

**Fixed**
- Adding or pairing a camera in the web client failed when the page was opened
  over plain `http://` (not `localhost`) or in a browser older than Chrome 92 /
  Firefox 95 / Safari 15.4: Tawny used a browser call those pages do not get.

## 2.2.6

Changes since 2.2.5.

**New**
- An update board in the web client after each update: what changed, a link to
  this page, and a thank-you with the Ko-fi and Bitcoin tips. It shows once per
  version, and not on a fresh install.

## 2.2.5

Changes since 2.2.4.

**Web client**
- "Switch role" on a channel goes straight to the other role instead of showing
  both role cards again.
- A browser Handheld accepts its Monitor's `wss://` pairing links.
- The "paste this relay address" hint is hidden for the default rendezvous.
- The "monitor full", "code expired" and camera/microphone failure messages are
  translated into Spanish.

**Server**
- `/setup` no longer logs the same step every 4 seconds while it waits for
  `tailscale serve`; the setup log stays bounded.
- Phones that connect to the relay but never say hello are closed on time, and
  expired pairing tickets are cleared.
- The image keeps publishing as `ghcr.io/pressf4me/tawny` after the repository
  rename, so existing installs keep getting updates.

**Privacy**
- The privacy page says the safety code is compared once per phone, lists saved
  video clips beside snapshots, and explains how support email is handled.
