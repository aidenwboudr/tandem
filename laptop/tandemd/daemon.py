"""The daemon: one main thread runs every feature; the link's threads hand it messages (see link.py)."""
import json
import os
import select
import signal
import socket
import subprocess
import threading
import time

from . import bluez
from .clipboard import Clipboard
from .config import ART_DIR, CTL, STATE, Identity, Peer, Settings, coerce, DEFAULTS, log
from .desktop import Notifier
from .features import Calls, Files, NotifMirror, Status
from .headphones import Headphones, cleanup
from .link import Link


class Daemon:
    def __init__(self, cfg):
        self.cfg = cfg
        self.stop = False
        self.busy = set()  # background bluetooth calls running
        self.ident = Identity()
        self.peer = Peer()
        self.settings = Settings()
        self.notifier = Notifier(cfg["NOTIFY"] == "1")
        self.files = Files(self)
        self.link = Link(self.ident, self.peer, self.settings, int(cfg["PORT"]), self.notify,
                         hooks={"bulk": self.files.on_bulk})
        self.hp = Headphones(self)
        self.clip = Clipboard(self)
        self.notifs = NotifMirror(self)
        self.calls = Calls(self)
        self.status = Status(self)
        self.ctl = None
        self.bluez_owner = None
        self.last_bluez_check = 0.0
        self.handlers = {
            "hb": lambda h, p: self.hp.on_hb(h),
            "switch": lambda h, p: self.hp.set_prefer(h.get("to") or "toggle"),
            "art": lambda h, p: self.save_art(h, p),
            "clip": lambda h, p: self.clip.on_clip(h, p),
            "settings": lambda h, p: self.on_settings(h),
            "status": lambda h, p: self.status.on_status(h),
            "notif": lambda h, p: self.notifs.on_notif(h),
            "notif-icon": lambda h, p: self.notifs.on_icon(h, p),
            "notif-gone": lambda h, p: self.notifs.on_gone(h),
            "call": lambda h, p: self.calls.on_call(h),
            "ring": lambda h, p: self.status.on_ring(h),
            "open": lambda h, p: self.files.on_open(h),
            "file": lambda h, p: self.files.on_file(h, p) if self.settings[
                "screenshots" if h.get("kind") == "screenshot" else "files"] else None,
            "dnd": lambda h, p: self.status.on_dnd(h),
            "pong": lambda h, p: None,
            "_file-done": lambda h, p: self.files.received(h, h["path"]),
            "_sent": lambda h, p: self.notify(f"{h['name']} {'sent to your phone' if h['ok'] else 'did not go through'}"),
            "_up": self.on_link_change,
            "_down": self.on_link_change,
            "_unpaired": lambda h, p: None,
        }

    def notify(self, text, title="Tandem", timeout=4000, **kw):
        return self.notifier.notify(title, text, timeout=timeout, **kw)

    def bg(self, key, fn, *args):
        """Runs a slow Bluetooth call (Connect can take ~10 s when the far end is off) in the background."""
        if key in self.busy:
            return
        self.busy.add(key)

        def run():
            try:
                ok, msg = fn(*args)
                if not ok:
                    log(f"{key}: {msg}")
            finally:
                self.busy.discard(key)
        threading.Thread(target=run, daemon=True).start()

    # -- messages
    def dispatch(self, via, h, p):
        fn = self.handlers.get(h.get("t"))
        if not fn:
            return
        try:
            fn(h, p)
        except Exception as e:  # one bad message never takes the daemon down
            log(f"{h.get('t')}: {e!r}")

    def on_link_change(self, h, p):
        self.status.on_link(h)
        if h["t"] == "_up":
            self.clip.on_link_up()
            self.files.on_link_up(h.get("via"))

    def on_settings(self, h):
        changed = self.settings.merge(h.get("values") or {}, h.get("rev"))
        if changed:
            log("settings from the phone: " + ", ".join(f"{k}={self.settings[k]}" for k in changed))

    def set_setting(self, k, v):
        if k not in DEFAULTS:
            return
        self.settings.set(k, coerce(k, v))
        self.link.send(self.settings.as_message())

    def on_ctl(self, data):
        try:
            cmd = json.loads(data)
        except ValueError:
            return
        op = cmd.get("op")
        if op == "switch":
            self.hp.set_prefer(cmd.get("to") or "toggle")
        elif op == "send-clipboard":
            self.clip.send_now()
        elif op == "send-files":
            self.files.send(cmd.get("paths") or [])
        elif op == "open":
            if not self.link.send({"t": "open", "url": cmd.get("url", "")}):
                self.notify("Your phone isn't connected")
        elif op == "ring":
            if not self.link.send({"t": "ring", "on": cmd.get("on", True)}):
                self.notify("Your phone isn't connected")
        elif op == "key":
            if self.settings["remote_input"]:
                self.link.send({"t": "key", **{k: cmd[k] for k in ("text", "key") if k in cmd}})
        elif op == "set":
            self.set_setting(cmd.get("key"), cmd.get("value"))
        elif op == "unpair":
            self.link.unpair(tell=True)
            log("unpaired")
        elif op == "pair":
            self.link.denied.clear()
            self.link.bt_kick.set()
        elif op in ("play", "pause", "toggle", "next", "previous", "volume"):
            if self.settings["media_controls"]:
                self.link.send(dict(cmd, t="cmd"))

    # -- the loop
    def open_ctl(self):
        try:
            os.unlink(CTL)
        except OSError:
            pass
        self.ctl = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.ctl.bind(CTL)
        os.chmod(CTL, 0o600)

    def check_bluez_restart(self, now):
        """blueman-applet 2.4 doesn't re-register its pairing agent when bluetoothd crashes and comes back,
        so every pairing after that fails with "No agent available". Restart the applet when bluetoothd's
        bus name changes hands."""
        if now - self.last_bluez_check < 5:
            return
        self.last_bluez_check = now
        owner = bluez.owner()
        if owner and self.bluez_owner and owner != self.bluez_owner:
            if subprocess.run(["pgrep", "-x", "blueman-applet"], capture_output=True).returncode == 0:
                log("bluetoothd restarted: restarting blueman-applet so the pairing agent comes back")
                self.bg("restart-blueman", restart_blueman)
            self.link.channel.clear()
            self.link.bt_kick.set()
        if owner:
            self.bluez_owner = owner

    def tick(self):
        now = time.monotonic()
        devs = bluez.devices()
        if devs is not None:  # BlueZ didn't answer (busy pairing, restarting): change nothing this tick
            self.check_bluez_restart(now)
            self.hp.tick(devs, now)
            self.hp.check_switch(now)
            # The phone just connected over Bluetooth for something else: link up now, not on the next retry.
            ph = devs.get((self.peer.get("bt") or "").upper())
            if ph and ph["connected"] and not self.link.bt and self.link.bt_state == "idle":
                self.link.bt_kick.set()
        self.files.tick(now)
        self.status.tick(now)

    def write_state(self):
        st = {"updated": time.time(), "name": self.ident.name, "link": self.link.state(),
              "phone": bool(self.link.net or self.link.bt), "settings": self.settings.values,
              "clipboard": self.clip.state(), **self.hp.state(), **self.status.state()}
        try:
            with open(STATE + ".tmp", "w") as f:
                json.dump(st, f)
            os.replace(STATE + ".tmp", STATE)
        except OSError:
            pass

    def save_art(self, h, data):
        """Album art from the phone (JPEG) -> $XDG_RUNTIME_DIR/tandem-art/<key>.jpg for the now-playing pill."""
        key = "".join(c for c in str(h.get("key", "")) if c.isalnum())[:32]
        if not key or not data:
            return
        os.makedirs(ART_DIR, exist_ok=True)
        path = os.path.join(ART_DIR, key + ".jpg")
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
        old = sorted((os.path.join(ART_DIR, n) for n in os.listdir(ART_DIR) if n.endswith(".jpg")),
                     key=os.path.getmtime)[:-20]
        for p in old:  # keep the 20 newest
            os.unlink(p)

    def run(self):
        for s in (signal.SIGTERM, signal.SIGINT):
            signal.signal(s, lambda *_: setattr(self, "stop", True))
        # A block left over from a crash would keep the headphones off the laptop for good.
        cleanup(self.hp.hp_mac)
        self.open_ctl()
        self.link.start()
        log(f"tandem: {self.ident.name}, " + (f"paired with {self.peer.get('name')}" if self.peer else
                                                "not paired yet (pair your phone over Bluetooth, then open the app)"))
        last_tick = 0.0
        while not self.stop:
            now = time.monotonic()
            if now - last_tick >= 1.0:
                last_tick = now
                try:
                    self.tick()
                except Exception as e:  # never die with the headphones blocked
                    log("tick failed:", repr(e))
                self.write_state()
            try:
                ready = select.select([self.ctl, self.link.wake_r], [], [], 0.25)[0]
            except InterruptedError:
                continue
            if self.ctl in ready:
                try:
                    self.on_ctl(self.ctl.recv(65536))
                except OSError:
                    pass
            for via, h, p in self.link.drain():
                self.dispatch(via, h, p)
        log("stopping")
        self.link.stop = True
        self.hp.stop()
        if self.clip.backend:
            self.clip.backend.stop()
        for path in (STATE, CTL):
            try:
                os.unlink(path)
            except OSError:
                pass


def restart_blueman():
    subprocess.run(["pkill", "-x", "blueman-applet"], capture_output=True)
    for _ in range(20):
        if subprocess.run(["pgrep", "-x", "blueman-applet"], capture_output=True).returncode != 0:
            break
        time.sleep(0.25)
    # Its own transient unit, so restarting tandem doesn't take the applet down with it.
    r = subprocess.run(["systemd-run", "--user", "--collect", "--quiet", "blueman-applet"],
                       capture_output=True, text=True, timeout=15)
    return r.returncode == 0, r.stderr.strip()
