#!/usr/bin/env bash
# Regenerate the chime clips in public/sounds/.
#
#   bark, pspsps, meow, goodboy  — trimmed/normalised from the original
#                                  recordings (see below)
#   bell                         — pure FM synthesis (Chowning), no source
#
# The original recordings are the masters; edit those (or the trim windows
# below) and re-run. They are not in the repository: the stock-library licence
# covers them inside the app, not as loose files, and one is an internal
# recording. Keep them in public/sounds/_src/ (git-ignored) or point
# TAWNY_SOUNDS_SRC at wherever they live. Without them only the bell is rebuilt;
# the committed .ogg clips are what ships.
#
# Usage:   tools/gen-chimes.sh
#          TAWNY_SOUNDS_SRC=/path/to/recordings tools/gen-chimes.sh
# Requires: ffmpeg with libvorbis.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/public/sounds"
src="${TAWNY_SOUNDS_SRC:-$out/_src}"
mkdir -p "$out"

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis || {
  echo "ffmpeg has no libvorbis encoder" >&2; exit 1; }

# Vorbis in Ogg, mono, 32 kHz. Chromium decodes Vorbis in-process for Web Audio
# on every platform we ship to; Opus-in-Ogg is only guaranteed from API 29 and
# minSdk is 26.
enc=(-c:a libvorbis -q:a 3 -ac 1 -ar 32000 -y)

# Trim dead air off both ends, tuck in short fades, level to a consistent
# loudness, catch peaks. $3 is an optional extra filter chain spliced in before
# the loudness stage (e.g. a highpass for a quiet, hissy source); the rest of
# the args are ffmpeg input options (-ss / -to to cut a segment out).
proc() {
  local slug="$1" infile="$2" pre="$3"; shift 3
  ffmpeg -hide_banner -loglevel error "$@" -i "$src/$infile" -ac 1 -ar 32000 \
    -af "areverse,silenceremove=start_periods=1:start_threshold=-48dB:start_silence=0.04:detection=peak,afade=t=in:st=0:d=0.025,areverse,silenceremove=start_periods=1:start_threshold=-48dB:start_silence=0.03:detection=peak,afade=t=in:st=0:d=0.012,${pre},loudnorm=I=-15:TP=-1.5:LRA=11,alimiter=limit=0.92" \
    "${enc[@]}" "$out/$slug.ogg"
  printf '  %-9s <- %s\n' "$slug" "$infile"
}

if [ -d "$src" ]; then
  proc bark    "dog toy - dog.mp3"   anull                -ss 0    -to 1.75
  proc meow    "meow - cat.wav"      anull                -ss 5.35 -to 6.40
  proc goodboy "goodboy - dog.mp3"   anull
  # Internal recording (not the old freesound CC BY clip — see sounds/README.md),
  # recorded at a normal level, so no special pre-filter is needed before
  # loudnorm; -ss/-to picks out the first of the two "psp psp psp" repeats.
  proc pspsps  "pspsps - internal.m4a" anull              -ss 1.0  -to 2.20
else
  echo "  (no recordings at $src — skipping the recorded clips; set TAWNY_SOUNDS_SRC)" >&2
fi

# ---------------------------------------------------------------- bell (synth)
#
# Chowning FM: a carrier phase-modulated by an inharmonic partial (ratio 1.408)
# with its own faster-decaying index. The inharmonicity is what makes it read as
# struck metal rather than a sine beep.
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i "aevalsrc='0.85*exp(-3.1*t)*sin(2*PI*784*t + 4.2*exp(-4.4*t)*sin(2*PI*1104*t))':d=1.8:s=44100" \
  -af "afade=t=in:st=0:d=0.004,alimiter=limit=0.95" \
  "${enc[@]}" "$out/bell.ogg"
printf '  %-9s <- FM synthesis\n' bell

echo
ls -l "$out"/*.ogg | awk '{printf "  %-30s %6.1f KB\n", $NF, $5/1024}'
