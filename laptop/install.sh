#!/usr/bin/env bash
# Install (or update) Tandem on this computer: the daemon and its user service, plus "Send to phone" in
# the file manager. On wlroots desktops with waybar it also installs the bar mixer (or pass --no-mixer).
# Safe to re-run; keeps your settings and pairing in ~/.config/tandem.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
mixer=auto
for a in "$@"; do
  case "$a" in
    --no-mixer) mixer=no ;;
    --mixer) mixer=yes ;;
    *) echo "usage: $0 [--mixer|--no-mixer]" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m%s\033[0m\n' "$*"; }
note() { printf '  · %s\n' "$*"; }

# ---------------------------------------------------------------- what's needed
missing=()
for c in busctl openssl ip; do command -v "$c" >/dev/null || missing+=("$c"); done
[ -x /usr/bin/python3 ] || missing+=(python3)
if [ ${#missing[@]} -gt 0 ]; then
  echo "Tandem needs: ${missing[*]}. Install them with your package manager and run this again." >&2
  exit 1
fi
if ! /usr/bin/python3 -c 'import socket; socket.AF_BLUETOOTH' 2>/dev/null; then
  echo "This system's Python has no Bluetooth sockets, so Tandem can't pair over Bluetooth." >&2
  exit 1
fi

say "Optional pieces"
command -v wl-paste >/dev/null || command -v xclip >/dev/null \
  || note "clipboard: install wl-clipboard (Wayland: sway, Hyprland, KDE) or xclip (X11, GNOME)"
command -v gdbus >/dev/null || note "notification buttons (reply, open, decline): install glib2 / libglib2.0-bin (gdbus)"
command -v pw-record >/dev/null && command -v wpctl >/dev/null \
  || note "audio sharing with headphones: needs PipeWire (pw-record, wpctl)"
/usr/bin/python3 -c 'import ctypes.util, sys; sys.exit(0 if ctypes.util.find_library("opus") else 1)' \
  || note "audio sharing: install libopus to compress it (otherwise ~1.5 Mbit/s raw)"
command -v zenity >/dev/null || command -v kdialog >/dev/null || /usr/bin/python3 -c 'import gi' 2>/dev/null \
  || note "replying to messages: install zenity, kdialog, or python3-gi"
command -v scrcpy >/dev/null || note "screen mirroring (tandem screen): install scrcpy and adb"
/usr/bin/python3 -c 'import gi; gi.require_version("Gtk", "4.0"); gi.require_version("Adw", "1")' 2>/dev/null \
  || note "the Tandem window (settings, pairing): install GTK 4 + libadwaita for Python (Debian/Ubuntu: python3-gi gir1.2-gtk-4.0 gir1.2-adw-1)"

# ---------------------------------------------------------------- the daemon
say "Installing"
lib="$HOME/.local/lib/tandem"
rm -rf "$lib/tandemd"
mkdir -p "$lib"
cp -r "$here/tandemd" "$lib/tandemd"
find "$lib/tandemd" -name __pycache__ -prune -exec rm -rf {} +
install -Dm755 "$here/tandem" "$HOME/.local/bin/tandem"
note "tandem -> ~/.local/bin/tandem"

cfg="$HOME/.config/tandem/config"
if [ ! -f "$cfg" ]; then
  mkdir -p "$(dirname "$cfg")"
  cat > "$cfg" <<'EOF'
# Tandem: computer-only settings. Features are switched on and off in the phone app (or `tandem set`).
# PORT=47800              # TCP (and UDP for discovery) on this computer
# FILES_DIR=              # where files from the phone go (default: your Downloads folder)
# SCREENSHOTS_DIR=        # where phone screenshots go (default: Pictures/Phone)
# CLIP_MAX_MB=25
# OPUS_BITRATE=160000
# NOTIFY=1                # 0 = no desktop notifications from Tandem
# HEADPHONES=             # Bluetooth address; normally learned from the phone
# BLUEMAN_POPUPS=0        # 1 keeps blueman's Connected/Disconnected pop-ups (off while Tandem runs)
EOF
fi

# Phones get an audio link only, no hands-free one: when a phone opened one to this computer and it was
# dropped, Android pulled all the phone's audio back to the headphones.
if command -v wpctl >/dev/null; then
  wp="$HOME/.config/wireplumber/wireplumber.conf.d/51-bluez-no-handsfree-unit.conf"
  if ! cmp -s "$here/wireplumber/51-bluez-no-handsfree-unit.conf" "$wp"; then
    install -Dm644 "$here/wireplumber/51-bluez-no-handsfree-unit.conf" "$wp"
    systemctl --user restart wireplumber || true  # audio drops for a second
  fi
fi

# The Tandem window in the app launcher, and "Send to phone" in file managers (Open With, Nautilus
# scripts, Dolphin's menu). Launchers don't always have ~/.local/bin on their PATH: use the full path.
apps="$HOME/.local/share/applications"
bin="$HOME/.local/bin/tandem"
install -Dm644 "$here/tandemd/ui/tandem.svg" "$HOME/.local/share/icons/hicolor/scalable/apps/tandem.svg"
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -qt "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
sed "s|^Exec=tandem |Exec=$bin |" "$here/desktop/tandem.desktop" > "$apps/tandem.desktop"
sed "s|^Exec=tandem |Exec=$bin |" "$here/desktop/tandem-send.desktop" > "$apps/tandem-send.desktop"
note "Tandem in your app launcher (or run: tandem app)"
install -Dm755 "$here/desktop/send-to-phone.sh" "$HOME/.local/share/nautilus/scripts/Send to phone"
install -Dm644 "$here/desktop/tandem-servicemenu.desktop" "$HOME/.local/share/kio/servicemenus/tandem.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$apps" 2>/dev/null || true

install -Dm644 "$here/tandem.service" "$HOME/.config/systemd/user/tandem.service"
systemctl --user daemon-reload
systemctl --user enable tandem.service >/dev/null
systemctl --user restart tandem.service
note "tandem.service (re)started"

# ---------------------------------------------------------------- firewall
port=$(sed -n 's/^PORT=\([0-9]*\).*/\1/p' "$cfg" | tail -1); port=${port:-47800}
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
  if ! firewall-cmd --query-port="$port/tcp" >/dev/null 2>&1; then
    say "Firewall"
    note "firewalld blocks the phone on your network. Open Tandem's port:"
    note "  sudo firewall-cmd --permanent --add-port=$port/tcp --add-port=$port/udp && sudo firewall-cmd --reload"
  fi
elif command -v ufw >/dev/null && LC_ALL=C sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
  say "Firewall"
  note "ufw is on. Open Tandem's port: sudo ufw allow $port"
fi

# ---------------------------------------------------------------- the bar mixer (waybar, optional)
if [ "$mixer" = auto ]; then
  if command -v waybar >/dev/null && [ -d "$HOME/.config/waybar" ]; then mixer=yes; else mixer=no; fi
fi
if [ "$mixer" = yes ]; then
  say "Bar mixer"
  if ! /usr/bin/python3 -c 'import gi; gi.require_version("GtkLayerShell", "0.1")' 2>/dev/null; then
    note "skipped: it needs gtk-layer-shell (e.g. sudo apt install gir1.2-gtklayershell-0.1)"
  else
    install -Dm755 "$here/tandem-mixer" "$HOME/.local/bin/tandem-mixer"
    # waybar-hover.so: the waybar module that gives the audio module a hover hook. Rebuild if a C
    # compiler is around, otherwise use the prebuilt copy (it only needs the GTK3 waybar already loads).
    so="$here/waybar-hover/waybar-hover.so"
    if command -v cc >/dev/null; then "$here/waybar-hover/build.sh" >/dev/null; fi
    # waybar keeps a loaded .so mapped across reloads (dlopen returns the old copy for the same
    # path), so every build gets its own file name; the reload then picks up the new one.
    so_dest="$lib/waybar-hover-$(sha256sum "$so" | cut -c1-12).so"
    install -Dm755 "$so" "$so_dest"
    find "$lib" -name 'waybar-hover*.so' ! -path "$so_dest" -delete
    TANDEM_HOVER_SO="$so_dest" "$HOME/.local/bin/tandem-mixer" --setup-waybar
    install -Dm644 "$here/tandem-mixer.service" "$HOME/.config/systemd/user/tandem-mixer.service"
    systemctl --user daemon-reload
    systemctl --user enable tandem-mixer.service >/dev/null
    systemctl --user restart tandem-mixer.service
    note "tandem-mixer.service (re)started"
  fi
fi

say "Next"
if [ -f "$HOME/.config/tandem/phone.json" ]; then
  note "Already paired. Open Tandem from your app launcher (or \`tandem status\`) to see the link."
else
  note "1. Install the Tandem app on your phone (see the README)."
  note "2. Pair the phone with this computer in Bluetooth settings, like any device."
  note "3. Open Tandem on the phone and say yes when it asks. The Tandem window here shows the link."
fi
