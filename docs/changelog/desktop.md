# Tawny Desktop — changelog

Newest first. Linux AppImage and Flatpak.

## 0.1.11

Changes since 0.1.10. Same web client as Tawny for Android 1.0.3 (build 46, rebuilt from 5908d90).

**New**
- Support Tawny: one sheet from a red heart in the Channels bar, a card under
  the channel list and a button on the update board. Tawny's cat, owlet and
  dog, Buy a coffee (Ko-fi) and Tip in Bitcoin, with a hand-drawn QR code the
  owlet perches on and the amount set large when the tip lands. `…/#support`
  opens it directly.
- Spanish for every screen before the call, picked from this computer's
  language.

**Changed**
- Screen readers hear toasts and connection changes; the viewer count and
  the dimmed screen's wake label are real buttons; sheets hold focus and give
  it back. After a tap no focus ring is drawn; after a key it is.
- Deleting a channel asks in a Tawny sheet instead of the browser's dialog.

**Fixed**
- Enter in the name field no longer re-presses the button focus returns to.

## 0.1.10

Changes since 0.1.9. Same web client as Tawny for Android 1.0.3 (build 46).

**Changed**
- Tip in Bitcoin opens a sheet instead of the strike.me page: pick $2, $5,
  $10 or type your own amount, and Tawny asks the tip address for an invoice
  of exactly that. Scan its QR code with any wallet on your phone, copy it, or
  pay on strike.me instead. When the payment lands, the sheet turns into a
  thank-you. Tawny hears about it from the signed receipt the tip service
  publishes on public Nostr relays (it checks the signature and the invoice),
  and never holds a key or a satoshi. Nothing is contacted until you open the
  sheet.

## 0.1.9

Changes since 0.1.8. Same web client as Tawny for Android 1.0.2 (build 45).

**New**
- Hear who joins: when the Monitor has Announce viewers on, this computer
  plays the same short sound the Monitor does each time another phone or
  computer starts watching, so everyone sharing a camera knows when someone new
  is looking in.
- The first-call walkthrough points at the viewer count, where the list of
  who's here opens.

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
