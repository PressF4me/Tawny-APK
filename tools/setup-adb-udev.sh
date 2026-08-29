#!/usr/bin/env bash
# One-shot: give this user adb/fastboot access to a plugged-in Android phone.
# Run:  ./tools/setup-adb-udev.sh      (it will sudo for the privileged bits)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
adb="${ANDROID_HOME:-$HOME/Android/sdk}/platform-tools/adb"

echo "1) installing /etc/udev/rules.d/51-android.rules"
sudo install -m 0644 "$here/51-android.rules" /etc/udev/rules.d/51-android.rules

echo "2) reloading udev"
sudo udevadm control --reload-rules
sudo udevadm trigger

echo "3) adding $USER to the 'uucp' group (fallback for non-logind access)"
sudo usermod -aG uucp "$USER" || true

echo "4) restarting the adb server as your user"
"$adb" kill-server 2>/dev/null || true
"$adb" start-server

cat <<EOF

Done. Now:
  - UNPLUG and REPLUG the phone (udev rules only apply on the next connect).
  - If you were just added to 'uucp', log out and back in once.
  - On the phone: pull down the USB notification -> "File transfer" (not
    "Charging only"), then accept the "Allow USB debugging?" prompt.
  - Check with:  $adb devices -l
EOF
