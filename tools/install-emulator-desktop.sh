#!/usr/bin/env bash
# Install the Tawny Emulator launcher for the current user.
#
# Works on any XDG desktop; on Hyprland/Omarchy the entry shows up in wofi's
# app list (there is no desktop-icon surface there, so nothing is written to
# ~/Desktop).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$HOME/.local/bin"
APPS="$HOME/.local/share/applications"

mkdir -p "$BIN" "$APPS"

install -m 755 "$HERE/tawny-emulator" "$BIN/tawny-emulator"
sed "s|@BIN@|$BIN|g" "$HERE/tawny-emulator.desktop" > "$APPS/tawny-emulator.desktop"
chmod 644 "$APPS/tawny-emulator.desktop"

command -v update-desktop-database >/dev/null 2>&1 &&
  update-desktop-database "$APPS" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 &&
  gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo "Installed $BIN/tawny-emulator and $APPS/tawny-emulator.desktop"
