#!/usr/bin/env bash
# Builds dist/tandem-phone_<version>_all.deb (the computer side; the waybar mixer is left out, use
# laptop/install.sh --mixer for that). Usage: packaging/build-deb.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')}"
version="${version:-0.0.0}"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
install -Dm755 laptop/tandem "$root/usr/bin/tandem"
mkdir -p "$root/usr/lib/tandem"
cp -r laptop/tandemd "$root/usr/lib/tandem/"
find "$root/usr/lib/tandem" -name __pycache__ -prune -exec rm -rf {} +
mkdir -p "$root/usr/lib/systemd/user"
sed 's|%h/.local/bin/tandem|/usr/bin/tandem|g' laptop/tandem.service > "$root/usr/lib/systemd/user/tandem.service"
install -Dm644 laptop/wireplumber/51-bluez-no-handsfree-unit.conf \
  "$root/usr/share/wireplumber/wireplumber.conf.d/51-bluez-no-handsfree-unit.conf"
install -Dm644 laptop/desktop/tandem.desktop "$root/usr/share/applications/tandem.desktop"
install -Dm644 laptop/desktop/tandem-send.desktop "$root/usr/share/applications/tandem-send.desktop"
install -Dm644 laptop/tandemd/ui/tandem.svg "$root/usr/share/icons/hicolor/scalable/apps/tandem.svg"
install -Dm644 laptop/desktop/tandem-servicemenu.desktop "$root/usr/share/kio/servicemenus/tandem.desktop"
install -Dm644 LICENSE "$root/usr/share/doc/tandem-phone/copyright"
install -Dm644 README.md "$root/usr/share/doc/tandem-phone/README.md"
mkdir -p "$root/DEBIAN"
cat > "$root/DEBIAN/control" <<CTRL
Package: tandem-phone
Version: $version
Architecture: all
Maintainer: Aiden Boudreau <aiden.boudr@gmail.com>
Depends: python3, systemd, bluez, openssl, iproute2
Recommends: python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1, wl-clipboard | xclip, libglib2.0-bin, pipewire-bin, libopus0, zenity
Suggests: scrcpy, adb
Section: utils
Priority: optional
Homepage: https://tandem.aidenwb.com
Description: your Android phone and your Linux computer, working as one
 Shared clipboard, phone notifications and calls on the computer, files and links
 both ways, find my phone, Do Not Disturb sync, typing on the phone, lock on leave,
 and one pair of Bluetooth headphones for both devices. Pairs over Bluetooth.
 This is the computer side (a service, the tandem command and the Tandem window);
 install the Tandem app on the phone.
CTRL
cat > "$root/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
if [ "$1" = configure ]; then
  systemctl --global enable tandem.service >/dev/null 2>&1 || true
  echo "Tandem: start it now with  systemctl --user start tandem  (it starts by itself at your next login)."
fi
POST
cat > "$root/DEBIAN/prerm" <<'PRE'
#!/bin/sh
set -e
if [ "$1" = remove ]; then systemctl --global disable tandem.service >/dev/null 2>&1 || true; fi
PRE
chmod 755 "$root/DEBIAN/postinst" "$root/DEBIAN/prerm"
mkdir -p dist
dpkg-deb --root-owner-group -Zxz --build "$root" "dist/tandem-phone_${version}_all.deb" >/dev/null
echo "built dist/tandem-phone_${version}_all.deb"
