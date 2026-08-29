# Notes for the Play reviewer

Paste this into Play Console → *App content → App access* (or the review notes
field).

---

**Tawny is a two-way pet monitor. A live session needs two devices** — one as
"the Watcher", one as "the Handheld" — on the same Wi-Fi network (or, if this
build has a relay configured, over the internet). No login or account is
required.

### Every screen can be reached on a single device

1. Launch the app → **welcome screen** (animated illustration).
2. Tap **Get started** → **"This device" role screen**.
3. Tap **The Watcher** → a **camera/microphone disclosure** dialog → allow →
   name the spot → a **pairing QR code** screen appears. This screen is the full
   Watcher setup; it will sit here "Waiting for the Handheld".
4. Back out, tap **The Handheld** → a **microphone disclosure** dialog → then a
   **QR scanner**. Pointing it at the QR from step 3 (shown on a second screen or
   printed) completes pairing and starts a live session.
5. On the pairing screen, **"Show as link"** reveals the pairing URL as text; a
   reviewer can copy it and use **"Paste a link instead"** on the Handheld side
   to pair without a second camera.

### To exercise a live call with two devices

- Device A: Get started → The Watcher → allow camera+mic → name it → leave the QR
  up.
- Device B (same Wi-Fi): Get started → The Handheld → allow mic → scan A's QR.
- B now shows A's camera; **hold "Hold to talk"** to send audio back; **Chime**
  plays a sound on A.

### Notes

- Camera and microphone are used **only during an active session while the app is
  in the foreground**. There is no background capture and no foreground service.
- The `tawny://pair` deep link is intentionally BROWSABLE so any camera app can
  start pairing; every externally-opened link shows a "Connect to this monitor?"
  confirmation first.
- The app loads only bundled HTML/JS from a loopback server; it does not load
  remote web content.
