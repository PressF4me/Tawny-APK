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

# 512 Play Store icon = same art, no extra padding (the ink rounded-rect is in
# the SVG). Maskable safe-zone is generous because the owl sits well inside.
cp "$out/icon-512.png" "$here/docs/play-icon-512.png" 2>/dev/null || \
  { mkdir -p "$here/docs"; cp "$out/icon-512.png" "$here/docs/play-icon-512.png"; }
echo "wrote docs/play-icon-512.png"

# Linux desktop launcher icon
if [ -d "$HOME/.local/share/icons/hicolor/scalable/apps" ]; then
  cp "$src" "$HOME/.local/share/icons/hicolor/scalable/apps/tawny.svg"
  echo "updated desktop icon"
fi
