# Chime sounds

The five clips the Viewer can play on the Monitor to get a pet's attention.
Bundled in the APK (`syncWebAssets` copies this whole folder into
`android/app/src/main/assets/web/`) and fetched same-origin at runtime — nothing
is streamed and nothing is downloaded at use time.

**All five are synthesised from scratch by `tools/gen-chimes.sh` (ffmpeg,
mathematical expressions only).** Nothing here was recorded, sampled, or taken
from a sound library, so there is no licence or attribution question hanging
over the app. Re-run that script to regenerate any of them.

## The set

| Slug | File | Length | State |
|---|---|---|---|
| `bark` | `bark.ogg` | 0.35 s | 🔴 **PLACEHOLDER** |
| `pspsps` | `pspsps.ogg` | 0.62 s | 🟢 final |
| `meow` | `meow.ogg` | 0.75 s | 🔴 **PLACEHOLDER** |
| `goodboy` | `goodboy.ogg` | 0.72 s | 🔴 **PLACEHOLDER — not speech at all** |
| `bell` | `bell.ogg` | 1.80 s | 🟢 final |

Format for every file, and for anything that replaces one:

- **Ogg Vorbis**, **mono**, **32 kHz**, roughly `ffmpeg -q:a 1`
- **0.3 – 2.0 s**, and **under 40 KB** (the current set is 4–8 KB each, 27 KB
  total — there is plenty of headroom)
- peak-normalised to about −0.1 dBFS; per-clip level trim lives in the `CHIMES`
  table in `public/app.js`, so a replacement does not have to match exactly
- Vorbis rather than Opus on purpose: Chromium decodes Vorbis in-process for Web
  Audio on every platform we ship to, whereas Opus-in-Ogg is only guaranteed
  from Android API 29 and `minSdk` is 26

```sh
ffmpeg -i your-recording.wav -c:a libvorbis -q:a 1 -ac 1 -ar 32000 bark.ogg
```

## What is actually finished

**`pspsps` and `bell` are done and can ship.** They are not approximations of
the real thing — they *are* the real thing:

- `pspsps` — the tongue/lip call for a cat is a broadband sibilant, and
  band-passed white noise under a sharp-attack envelope is exactly that sound.
  Three bursts, the third held slightly longer, which is the rhythm people use.
- `bell` — FM synthesis with an inharmonic modulator (the Chowning bell). This
  is how bells have been synthesised since 1973; there is no sample that would
  do it better at 7 KB.

## What still needs replacing

**`bark`, `meow` and `goodboy` are placeholders.** They are wired, audible and
testable, and they are the right length and shape — but nobody will mistake them
for an animal. Do not ship them to the Play Store as they are.

- **`bark`** — a pitch-collapsing buzz (340 → 150 Hz) with a broadband transient
  on the front. It has the envelope of a "woof" and none of the throat. Vocal
  folds plus a resonating snout are not something a few oscillators reach.
- **`meow`** — a rise-then-fall pitch glide through two formants. It reads as a
  small animal cry in the abstract, not as a cat.
- **`goodboy`** — ⚠️ **this does not say anything.** It is two voice-shaped
  syllables carrying the falling prosody of the phrase. It was going to be
  rendered with `espeak-ng`, but this machine has no TTS at all (no `espeak-ng`,
  no `flite`, no `pico2wave`; the only `libespeak-ng` reference on the box is a
  dangling speech-dispatcher module). Even with espeak it would have been a
  robot voice — for a phrase whose entire job is to sound warm to a dog, a real
  recording of the owner's voice is the right answer, and by some distance the
  easiest of the three to obtain.

### Where to get replacements

Anything you drop in must be **CC0 / public domain**, or your own recording.
Avoid "free" sound sites that require attribution or forbid commercial use —
this ships in a Play Store app.

- **freesound.org** — filter the search by **License → Creative Commons 0**.
  Deep catalogue of real dog and cat vocalisations. An account is needed to
  download.
- **Pixabay Sound Effects** (`pixabay.com/sound-effects/`) — its own licence,
  free for commercial use with no attribution. Search "dog bark", "cat meow".
- Your own phone. For `goodboy` especially: record yourself saying it to the
  actual dog, trim to under a second, run the ffmpeg line above. It will beat
  anything from a library, because the dog already knows the voice.

After swapping a file in, re-run the debug build so `syncWebAssets` copies it
into `android/app/src/main/assets/web/sounds/`, and commit both copies — that
mirror is tracked in git.

## Failure behaviour

Every slug also has a synthesised fallback in `synthChime()` in
`public/app.js`. If a clip 404s or fails to decode, the Monitor plays the
oscillator version instead of nothing — the Viewer has already been told the
chime played, so silence would be a lie. The failure is written to the
diagnostics log (long-press the version stamp).
