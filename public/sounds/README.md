# Chime sounds

The five clips the Viewer can play on the Monitor to get a pet's attention.
Bundled in the APK (`syncWebAssets` copies this folder into
`android/app/src/main/assets/web/`, minus `_src/`) and fetched same-origin at
runtime — nothing is streamed and nothing is downloaded at use time.

## The set

| Slug | File | Length | Source |
|---|---|---|---|
| `bark` | `bark.ogg` | 1.6 s | recording — `_src/dog toy - dog.mp3` (3 hits) |
| `pspsps` | `pspsps.ogg` | 1.5 s | recording — `_src/pspsps cat.ogg` (1.32–2.86 s) |
| `meow` | `meow.ogg` | 0.8 s | recording — `_src/Meow - cat.ogg` |
| `goodboy` | `goodboy.ogg` | 0.8 s | recording — `_src/goodboy - dog.mp3` |
| `bell` | `bell.ogg` | 1.8 s | FM synthesis (no source file) |

`_src/` holds the original recordings the first four are trimmed and normalised
from. It is the master copy — edit those, or the trim windows in
`tools/gen-chimes.sh`, and re-run that script to rebuild the whole set. `_src/`
is excluded from the APK.

## ⚠️ Licensing — confirm before a public release

The four recordings in `_src/` were supplied by the developer. Every one must be
**your own recording** or **CC0 / public domain** — a Play Store release cannot
ship audio under a licence that requires attribution or forbids commercial use.
Fill this in and keep the proof:

| `_src/` file | Origin | Licence | Link / note |
|---|---|---|---|
| `dog toy - dog.mp3` | _TBC_ | _TBC_ | |
| `pspsps cat.ogg` | _TBC_ | _TBC_ | |
| `Meow - cat.ogg` | _TBC_ | _TBC_ | |
| `goodboy - dog.mp3` | _TBC_ | _TBC_ | |

Good CC0 sources if any need replacing: **freesound.org** (filter *License →
Creative Commons 0*), **Pixabay Sound Effects** (free for commercial use, no
attribution), or your own phone.

## Format

For every shipped clip, and anything that replaces one:

- **Ogg Vorbis**, **mono**, **32 kHz** (`tools/gen-chimes.sh` uses `-q:a 3`)
- **0.3 – 2.0 s**, **under 40 KB** (current set: 8–17 KB each, ~58 KB total)
- levelled to about −1.5 dBFS with a limiter; per-clip trim also lives in the
  `CHIMES` table in `public/app.js`, so a replacement need not match exactly
- Vorbis not Opus: Chromium decodes Vorbis in-process for Web Audio on every
  platform we ship to; Opus-in-Ogg is only guaranteed from Android API 29 and
  `minSdk` is 26

Drop a raw recording into `_src/` (any format ffmpeg reads), point
`tools/gen-chimes.sh` at it, re-run, then rebuild the debug APK so the mirror in
`android/app/src/main/assets/web/sounds/` updates, and commit both copies.

## `bell` stays synthesised

FM synthesis with an inharmonic modulator (the Chowning bell) — how bells have
been made since 1973. No sample beats it at 8 KB, and there is no licence
question. Expression is in `tools/gen-chimes.sh`.

## Failure behaviour

Every slug also has a synthesised fallback in `synthChime()` in `public/app.js`.
If a clip 404s or fails to decode, the Monitor plays the oscillator version
rather than nothing — the Viewer has already been told the chime played, so
silence would be a lie. The failure is written to the diagnostics log.
