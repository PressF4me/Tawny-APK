#!/usr/bin/env bash
# Regenerate the chime clips in public/sounds/.
#
#   bark, pspsps, meow, goodboy  — trimmed/normalised from the original
#                                  recordings in public/sounds/_src/
#   bell                         — pure FM synthesis (Chowning), no source
#
# The _src/ recordings are the masters; edit those (or the trim windows below)
# and re-run. _src/ is excluded from the APK by syncWebAssets.
#
# Usage:   tools/gen-chimes.sh
# Requires: ffmpeg with libvorbis.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/public/sounds"
src="$out/_src"
mkdir -p "$out"

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis || {
  echo "ffmpeg has no libvorbis encoder" >&2; exit 1; }

# Vorbis in Ogg, mono, 32 kHz. Chromium decodes Vorbis in-process for Web Audio
# on every platform we ship to; Opus-in-Ogg is only guaranteed from API 29 and
# minSdk is 26.
enc=(-c:a libvorbis -q:a 3 -ac 1 -ar 32000 -y)

# Trim dead air off both ends, tuck in short fades, level to a consistent
# loudness, catch peaks. Args before the function name are extra ffmpeg input
# options (e.g. -ss / -to to cut a segment out of a longer take).
proc() {
  local slug="$1" infile="$2"; shift 2
  ffmpeg -hide_banner -loglevel error "$@" -i "$src/$infile" -ac 1 -ar 32000 \
    -af "areverse,silenceremove=start_periods=1:start_threshold=-48dB:start_silence=0.04:detection=peak,afade=t=in:st=0:d=0.025,areverse,silenceremove=start_periods=1:start_threshold=-48dB:start_silence=0.03:detection=peak,afade=t=in:st=0:d=0.012,loudnorm=I=-15:TP=-1.5:LRA=11,alimiter=limit=0.92" \
    "${enc[@]}" "$out/$slug.ogg"
  printf '  %-9s <- %s\n' "$slug" "$infile"
}

if [ -d "$src" ]; then
  proc bark    "dog toy - dog.mp3"  -ss 0    -to 1.75
  proc pspsps  "pspsps cat.ogg"     -ss 1.32 -to 2.86
  proc meow    "Meow - cat.ogg"
  proc goodboy "goodboy - dog.mp3"
else
  echo "  (public/sounds/_src/ missing — skipping the recorded clips)" >&2
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
