"""tandem: your phone and your Linux computer, working as one.

Usage: tandem [command]
  app                    the Tandem window: the link, pairing and every setting
  run                    the daemon (the tandem.service user unit runs this)
  status                 what's linked, battery, clipboard counters (JSON with --json)
  pair                   look for your phone now (pair it over Bluetooth first, and open the app)
  unpair                 forget the phone
  settings               list the feature settings
  set KEY VALUE          change one (the phone app shows the same switches)
  send FILE...           send files to the phone
  open URL               open a link on the phone
  ring [stop]            make the phone ring, even on silent (find it)
  type [TEXT]            type on the phone with this keyboard (needs Tandem's keyboard on the phone)
  screen                 mirror and control the phone's screen (scrcpy over Wireless debugging)
  switch [laptop|phone]  which device carries the other's audio while the headphones are on (no arg: toggle)
  send-clipboard         send what's on this clipboard to the phone now
  ctl JSON               a raw control command (used by the bar mixer)
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time

from .config import CTL, DEFAULTS, STATE, coerce, load_config, read_json, SETTINGS


def ctl(cmd, quiet=False):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    try:
        s.sendto(json.dumps(cmd).encode(), CTL)
        return True
    except OSError as e:
        if not quiet:
            sys.exit(f"tandem: the daemon isn't running ({e}). Start it: systemctl --user start tandem")
        return False


def state():
    st = read_json(STATE)
    if not st or time.time() - st.get("updated", 0) > 15:
        return None
    return st


def status(as_json):
    st = state()
    if not st:
        print("tandem is not running (systemctl --user start tandem)")
        return 1
    if as_json:
        print(json.dumps(st, indent=2))
        return 0
    ln = st["link"]
    print(f"Computer:  {st['name']}")
    if not ln["paired"]:
        print(f"Phone:     not paired ({ln['bt_state']}). Pair your phone with this computer over Bluetooth,")
        print("           then open Tandem on it and accept.")
        return 0
    via = " + ".join(k for k in ("bt", "net") if ln[k]) or "not connected"
    print(f"Phone:     {ln['phone']} · {via.replace('bt', 'Bluetooth').replace('net', 'network ' + str(ln.get('phone_ip') or ''))}")
    b = st.get("phone_battery")
    if b:
        print(f"Battery:   {b['level']}%{' (charging)' if b.get('charging') else ''}")
    if st.get("headphones"):
        hb = st.get("headphones_battery")
        print(f"Headphones: {st.get('headphones_name') or st['headphones']}"
              + (f" · {hb}%" if hb is not None else "") + f" · on: {st.get('owner') or 'neither'} · hub: {st.get('prefer')}")
    c = st.get("clipboard") or {}
    print(f"Clipboard: {c.get('backend') or 'no tool found'} · sent {c.get('to_phone', 0)}, received {c.get('from_phone', 0)}")
    on = [k for k, v in st["settings"].items() if v is True]
    print("On:        " + ", ".join(on))
    return 0


def settings_cmd():
    saved = (read_json(SETTINGS) or {}).get("values") or {}
    for k, d in DEFAULTS.items():
        v = saved.get(k, d)
        print(f"{k:22} {json.dumps(v)}" + ("" if v == d else f"   (default {json.dumps(d)})"))


def screen():
    st = state() or {}
    ip = (st.get("link") or {}).get("phone_ip")
    if not shutil.which("scrcpy") or not shutil.which("adb"):
        sys.exit("tandem screen needs scrcpy and adb (sudo apt install scrcpy adb, or your distro's packages).")
    devs = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    serial = None
    for line in devs.splitlines()[1:]:
        f = line.split()
        if len(f) == 2 and f[1] == "device" and (not ip or f[0].startswith(ip + ":")):
            serial = f[0]
    if not serial:
        # Android's Wireless debugging advertises itself over mDNS; connect to the phone's entry.
        out = subprocess.run(["adb", "mdns", "services"], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if "_adb-tls-connect" in line:
                addr = line.split()[-1]
                if not ip or addr.startswith(ip + ":"):
                    if "connected" in subprocess.run(["adb", "connect", addr], capture_output=True, text=True).stdout:
                        serial = addr
                        break
    if not serial:
        sys.exit("Couldn't reach the phone over adb. On the phone: Settings > System > Developer options >\n"
                 "Wireless debugging > On. The first time, tap 'Pair device with pairing code' and run\n"
                 "  adb pair <ip:port shown there>\n"
                 "on this computer, then run `tandem screen` again.")
    os.execvp("scrcpy", ["scrcpy", "-s", serial, "--window-title", "Phone"])


def type_window():
    """A small window: every key typed in it goes to the phone (through Tandem's keyboard there)."""
    try:
        import gi
        gi.require_version("Gtk", "3.0")
        from gi.repository import Gtk, Gdk
    except (ImportError, ValueError):
        sys.exit("tandem type needs PyGObject (python3-gi) for its window; or pass the text: tandem type 'hello'")
    special = {"BackSpace": "Backspace", "Return": "Enter", "KP_Enter": "Enter", "Tab": "Tab", "Left": "Left",
               "Right": "Right", "Up": "Up", "Down": "Down", "Delete": "Delete", "Escape": "Escape",
               "Home": "Home", "End": "End"}
    win = Gtk.Window(title="Typing on your phone")
    win.set_default_size(420, 120)
    label = Gtk.Label(label="Type here and it goes to your phone.\nOn the phone, pick the Tandem keyboard.")
    label.set_justify(Gtk.Justification.CENTER)
    win.add(label)

    def key(_w, ev):
        name = Gdk.keyval_name(ev.keyval) or ""
        if ev.state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.MOD1_MASK):
            if name.lower() == "v":  # paste: send the clipboard as text
                text = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).wait_for_text()
                if text:
                    ctl({"op": "key", "text": text})
            return True
        if name in special:
            ctl({"op": "key", "key": special[name]})
        else:
            ch = chr(Gdk.keyval_to_unicode(ev.keyval) or 0)
            if ch and ch.isprintable():
                ctl({"op": "key", "text": ch})
        return True
    win.connect("key-press-event", key)
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


def prompt(title, text):
    """A one-line text prompt (for replies) when zenity/kdialog aren't around."""
    import gi
    gi.require_version("Gtk", "3.0")
    from gi.repository import Gtk
    d = Gtk.Dialog(title=title)
    d.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Send", Gtk.ResponseType.OK)
    d.set_default_response(Gtk.ResponseType.OK)
    box = d.get_content_area()
    box.set_spacing(8)
    box.add(Gtk.Label(label=text))
    entry = Gtk.Entry()
    entry.set_activates_default(True)
    box.add(entry)
    d.show_all()
    ok = d.run() == Gtk.ResponseType.OK
    if ok and entry.get_text():
        print(entry.get_text())
        return 0
    return 1


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else "run"
    rest = args[1:]
    if cmd == "run":
        from .daemon import Daemon
        Daemon(load_config()).run()
    elif cmd == "app":
        try:
            from .app import main as app_main
        except (ImportError, ValueError) as e:
            sys.exit(f"tandem app needs GTK 4 and libadwaita for Python ({e}).\n"
                     "Debian/Ubuntu: sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1\n"
                     "Fedora: sudo dnf install python3-gobject gtk4 libadwaita\n"
                     "Arch: sudo pacman -S python-gobject gtk4 libadwaita")
        app_main()
    elif cmd == "status":
        sys.exit(status("--json" in rest))
    elif cmd == "pair":
        ctl({"op": "pair"})
        print("Looking for your phone. Make sure it's paired with this computer in Bluetooth settings and\n"
              "Tandem is open on it, then accept there. `tandem status` shows when it's done.")
    elif cmd == "unpair":
        ctl({"op": "unpair"})
        print("Forgot the phone.")
    elif cmd == "settings":
        settings_cmd()
    elif cmd == "set" and len(rest) == 2:
        if rest[0] not in DEFAULTS:
            sys.exit(f"unknown setting {rest[0]!r}; `tandem settings` lists them")
        ctl({"op": "set", "key": rest[0], "value": coerce(rest[0], rest[1])})
        print(f"{rest[0]} = {json.dumps(coerce(rest[0], rest[1]))}")
    elif cmd == "send" and rest:
        ctl({"op": "send-files", "paths": [os.path.abspath(p) for p in rest]})
    elif cmd == "open" and len(rest) == 1:
        ctl({"op": "open", "url": rest[0]})
    elif cmd == "ring":
        ctl({"op": "ring", "on": not (rest and rest[0] == "stop")})
    elif cmd == "type":
        if rest:
            ctl({"op": "key", "text": " ".join(rest)})
        else:
            type_window()
    elif cmd == "screen":
        screen()
    elif cmd == "switch":
        to = rest[0] if rest else "toggle"
        if to not in ("laptop", "phone", "toggle"):
            sys.exit("usage: tandem switch [laptop|phone]")
        ctl({"op": "switch", "to": to})
        time.sleep(0.3)
        print("the", (state() or {}).get("prefer", "?"), "carries the audio")
    elif cmd == "send-clipboard":
        ctl({"op": "send-clipboard"})
    elif cmd == "ctl" and rest:
        try:
            ctl(json.loads(rest[0]))
        except ValueError as e:
            sys.exit(f"ctl: {e}")
    elif cmd == "prompt" and len(rest) == 2:
        sys.exit(prompt(*rest))
    elif cmd == "cleanup":
        from .headphones import cleanup
        from .config import Peer
        cleanup(load_config()["HEADPHONES"] or Peer().get("headphones", ""))
    else:
        sys.exit(__doc__)
