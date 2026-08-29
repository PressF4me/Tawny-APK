#!/usr/bin/env bash
# Install the Tawny desktop launcher for the current user on CachyOS.
# Run it as ./install.sh https://your-server-address

set -euo pipefail

BIN="$HOME/.local/bin"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/scalable/apps"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$BIN" "$APPS" "$ICONS"

install -m 755 "$HERE/tawny-launch" "$BIN/tawny-launch"
install -m 644 "$HERE/tawny.desktop" "$APPS/tawny.desktop"
install -m 644 "$HERE/../public/icon.svg" "$ICONS/tawny.svg"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS" || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
fi

if [ -n "${1:-}" ]; then
  mkdir -p "${XDG_CONFIG_HOME:-$HOME/.config}/tawny"
  printf '%s\n' "$1" > "${XDG_CONFIG_HOME:-$HOME/.config}/tawny/url"
  echo "Saved server address: $1"
else
  echo "No address given. Set one later with: tawny-launch https://your-server"
fi

echo
echo "Installed. Tawny should now appear in your Cinnamon menu."
case ":$PATH:" in
  *":$BIN:"*) ;;
  *) echo "Note: $BIN is not on your PATH."
     echo "  fish:  fish_add_path ~/.local/bin" ;;
esac
