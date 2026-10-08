# Tawny Docker — changelog

Newest first. The self-hosted server image (`ghcr.io/pressf4me/tawny`).

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
