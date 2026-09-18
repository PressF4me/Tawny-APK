#!/usr/bin/env bash
# Regenerate Tawny's raster icons from public/icon.svg.
#
# Needs: rsvg-convert (librsvg) and ImageMagick `convert`.
#   Arch/CachyOS:  sudo pacman -S librsvg imagemagick
#
# The Android adaptive icon is pure vector (res/drawable/ic_launcher_*.xml) and
# does not need regenerating here. This script produces the web / Play Store
# PNGs only.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$here/public/icon.svg"
out="$here/public"

command -v rsvg-convert >/dev/null || { echo "install librsvg (rsvg-convert)"; exit 1; }

for size in 192 512; do
  rsvg-convert -w "$size" -h "$size" "$src" -o "$out/icon-$size.png"
  echo "wrote public/icon-$size.png"
done

# Play rejects an icon with an alpha channel on some paths and renders it
# unpredictably on others; flatten to be certain there is none.
if command -v magick >/dev/null; then
  magick "$out/icon-512.png" -background none -alpha remove -alpha off \
    "$out/icon-512.png"
fi

# 512 Play Store icon.
#
# The art is deliberately FULL-BLEED and opaque: the old SVG drew its own
# rounded rectangle with transparent corners, and Play applies its own rounded
# mask on top, so the store card showed a visibly double-rounded icon with a
# halo. Play's own guidance is a square with no corner radius and no alpha.
cp "$out/icon-512.png" "$here/docs/play-icon-512.png" 2>/dev/null || \
  { mkdir -p "$here/docs"; cp "$out/icon-512.png" "$here/docs/play-icon-512.png"; }
echo "wrote docs/play-icon-512.png"

# Maskable 512 for the web manifest. Android crops a maskable icon to a circle
# of 40% of its width, which would clip the cat and the dog; this copy scales
# the art (furthest cream pixel 32.3 of 64 from the centre of its bounding box,
# 34,31.94) into that circle, on the same full-bleed pink.
python3 - "$src" "$out/icon-maskable.svg" <<'PY'
import re, sys
svg = open(sys.argv[1]).read()
bg = re.search(r'<rect[^>]*fill="#D24B6D"[^>]*/>', svg).group(0)
art = svg.replace(bg, '')
inner = art[art.index('>', art.index('<svg')) + 1:art.rindex('</svg>')]
defs = re.search(r'<defs>.*?</defs>', inner, re.S)
defs = defs.group(0) if defs else ''
inner = inner.replace(defs, '')
s = 0.4 * 64 / 32.3
open(sys.argv[2], 'w').write(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">'
    + defs + bg + f'<g transform="translate(32,32) scale({s:.4f}) translate(-34,-31.94)">'
    + inner + '</g></svg>')
PY
rsvg-convert -w 512 -h 512 "$out/icon-maskable.svg" -o "$out/icon-maskable-512.png"
rm -f "$out/icon-maskable.svg"
if command -v magick >/dev/null; then
  magick "$out/icon-maskable-512.png" -background none -alpha remove -alpha off \
    "$out/icon-maskable-512.png"
fi
echo "wrote public/icon-maskable-512.png"

# Linux desktop launcher icon
if [ -d "$HOME/.local/share/icons/hicolor/scalable/apps" ]; then
  cp "$src" "$HOME/.local/share/icons/hicolor/scalable/apps/tawny.svg"
  echo "updated desktop icon"
fi
