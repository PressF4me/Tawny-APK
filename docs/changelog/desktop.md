# Tawny Desktop — changelog

Newest first. Linux AppImage and Flatpak.

## 0.1.8

Changes since 0.1.7.

**Fixed**
- A camera with no name was shown in its Monitor's language ("your pet",
  "Pet camera"), so a Spanish-language Tawny Desktop read English. It now
  shows in this computer's language.

## 0.1.7

Changes since 0.1.6. Same web client as Tawny for Android 1.0.2 (build 45).

**New**
- Who's here: the first time you start watching, Tawny asks what the others
  should call you. Tap the viewer count to see everyone on that camera and
  rename yourself.

**Fixed**
- This computer is listed as a Linux PC or Windows PC, not as a phone.

## 0.1.6

Changes since 0.1.5.

**Fixed**
- The web client shared with the Android app no longer depends on a browser
  call that older web engines lack (it broke camera setup on some Android 11
  phones). Tawny Desktop's own engine already had it, so nothing changes in
  how the app behaves here.

## 0.1.5

Changes since 0.1.4.

**Fixed**
- The update board did not show after an update if Tawny had never been set up
  on this computer: it was taken for a fresh install. The app now tells the
  page when an earlier run's profile is already on disk, and the board shows
  over the welcome screen.

## 0.1.4

Changes since 0.1.3.

**New**
- An update board after each update: what changed, a link to this page, and a
  thank-you with the Ko-fi and Bitcoin tips. It shows once per version, and not
  on a fresh install.

## 0.1.3

Changes since 0.1.2.

**Fixed**
- The AppImage no longer crashes at launch on distributions with a newer NSS
  than the build machine (Arch, CachyOS, Fedora). It bundles the NSS modules it
  loads at runtime, including the root-certificate store.
- A `ws://` rendezvous server (on a LAN or for testing) can now be reached.
  Before, the page's security policy blocked it.
- "Switch role" on a channel goes straight to the other role instead of showing
  both role cards again.
- Pairing links that name a `wss://` relay are accepted.
- The "paste this relay address" hint is hidden when the address is the default
  rendezvous, which a stock Tawny app already uses.

**Spanish**
- The "monitor full", "code expired" and camera/microphone failure messages are
  translated.
