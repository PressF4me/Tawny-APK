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

# Linux desktop launcher icon
if [ -d "$HOME/.local/share/icons/hicolor/scalable/apps" ]; then
  cp "$src" "$HOME/.local/share/icons/hicolor/scalable/apps/tawny.svg"
  echo "updated desktop icon"
fi
