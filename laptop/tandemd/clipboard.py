"""The laptop's half of the shared clipboard.

Two backends: Wayland compositors with the data-control protocol (sway, Hyprland, KDE, ...) via
wl-clipboard's `wl-paste --watch`, and X11 (also GNOME on Wayland, through Xwayland's synced clipboard) by
polling xclip. Each side remembers what it last wrote, so a clip never bounces back.
"""
import hashlib
import shutil
import subprocess
import threading
import time

from .config import log
from .desktop import session_env

CLIP_TTL = 15 * 60.0  # a copy the phone couldn't take (asleep, away) is retried this long
TEXT_TYPES = ("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")
X11_POLL = 1.0


def clip_hash(mime, data):
    kind = "text" if mime.startswith("text/") else mime
    return hashlib.sha256(kind.encode() + b"\0" + data).hexdigest()[:16]


def clip_summary(mime, data):
    if mime.startswith("text/"):
        line = data.decode("utf-8", "replace").strip().split("\n", 1)[0]
        return line[:60] + ("…" if len(line) > 60 else "")
    return f"Image ({max(1, len(data) // 1024)} KB)"


class Wayland:
    name = "wayland"

    def __init__(self):
        self.watch = None
        self.started = 0.0

    @staticmethod
    def usable():
        env = session_env()
        if not (env.get("WAYLAND_DISPLAY") and shutil.which("wl-paste") and shutil.which("wl-copy")):
            return False
        # GNOME has no data-control protocol: --watch exits at once with an error.
        try:
            p = subprocess.Popen(["wl-paste", "--watch", "true"], stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, env=env)
            try:
                p.wait(0.6)
                return False
            except subprocess.TimeoutExpired:
                p.terminate()
                p.wait()
                return True
        except OSError:
            return False

    def start_watch(self):
        # wl-paste pipes the clip into the command; drain it so wl-paste never blocks, then say "changed".
        self.watch = subprocess.Popen(["wl-paste", "--watch", "sh", "-c", "cat >/dev/null; echo"],
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=session_env())
        self.started = time.monotonic()

    def changes(self):
        """Blocks until the clipboard changes: True, None for a change to ignore, False when the watcher died."""
        line = self.watch.stdout.readline()
        if not line:
            self.watch.wait()
            return False
        if time.monotonic() - self.started < 1.0:
            return None  # it fires once at start for what's already copied
        return True

    def stop(self):
        if self.watch:
            self.watch.terminate()
            try:
                self.watch.wait(3)
            except subprocess.TimeoutExpired:
                self.watch.kill()

    def _paste(self, *args):
        return subprocess.run(["wl-paste", *args], capture_output=True, timeout=5, env=session_env()).stdout

    def types(self):
        return self._paste("--list-types").decode(errors="replace").split("\n")

    def read(self, mime):
        return self._paste("-n", "-t", mime) if mime in TEXT_TYPES else self._paste("-t", mime)

    def write(self, mime, data):
        # wl-copy forks to serve the clip; with pipes it would hold them open until the next copy.
        p = subprocess.Popen(["wl-copy", "--type", mime], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, env=session_env())
        p.communicate(data, timeout=5)
        return p.returncode == 0


class X11:
    name = "x11"

    def __init__(self):
        self.last = None
        self.alive = True

    @staticmethod
    def usable():
        return bool(session_env().get("DISPLAY") and shutil.which("xclip"))

    def start_watch(self):
        self.alive = True

    def changes(self):
        while self.alive:
            time.sleep(X11_POLL)
            try:
                types = self.types()
                text = next((t for t in TEXT_TYPES if t in types), None)
                img = next((t for t in types if t.startswith("image/")), None)
                sig = clip_hash(text or img or "", self.read(text or img)) if (text or img) else None
            except (OSError, subprocess.TimeoutExpired):
                continue
            if sig and sig != self.last:
                first = self.last is None
                self.last = sig
                if not first:
                    return True
        return False

    def stop(self):
        self.alive = False

    def _xclip(self, *args, data=None):
        return subprocess.run(["xclip", "-selection", "clipboard", *args], input=data, capture_output=True,
                              timeout=5, env=session_env()).stdout

    def types(self):
        return self._xclip("-o", "-t", "TARGETS").decode(errors="replace").split("\n")

    def read(self, mime):
        return self._xclip("-o", "-t", mime)

    def write(self, mime, data):
        # xclip stays around to serve the selection; don't wait for it.
        p = subprocess.Popen(["xclip", "-selection", "clipboard", "-t", mime, "-i"], stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=session_env(),
                             start_new_session=True)
        p.stdin.write(data)
        p.stdin.close()
        self.last = clip_hash(mime, data)
        return True


class Clipboard:
    def __init__(self, d):
        self.d = d
        self.max = int(float(d.cfg["CLIP_MAX_MB"]) * 1024 * 1024)
        self.backend = None
        self.last_hash = None  # the clip both sides already have
        self.pending = None  # the newest laptop copy the phone hasn't taken yet
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.stats = {"to_phone": 0, "from_phone": 0, "last_to_phone": None, "last_from_phone": None}
        threading.Thread(target=self._watch_loop, name="tandem-clip-watch", daemon=True).start()
        threading.Thread(target=self._sender, name="tandem-clip-send", daemon=True).start()

    # -- laptop -> phone
    def _watch_loop(self):
        warned = False
        while not self.d.stop:
            backend = Wayland() if Wayland.usable() else X11() if X11.usable() else None
            if not backend:
                if not warned:
                    warned = True
                    log("clipboard: no way to read it (install wl-clipboard on Wayland, or xclip on X11/GNOME)")
                time.sleep(15)
                continue
            if not self.backend or self.backend.name != backend.name:
                log(f"clipboard: using {backend.name}")
            self.backend = backend
            try:
                backend.start_watch()
                while not self.d.stop:
                    changed = backend.changes()
                    if changed is False:
                        break  # wl-paste exited (compositor restart): start again
                    if changed:
                        self._grab()
            except (OSError, subprocess.TimeoutExpired) as e:
                log("clipboard: watcher failed:", e)
            backend.stop()
            time.sleep(3)

    def _grab(self, force=False):
        # clip_from_laptop: "laptop copies go to the phone" (the settings are named from the phone's side)
        if not (self.d.settings["clip_from_laptop"] or force) or not self.backend:
            return
        b = self.backend
        try:
            types = b.types()
            if "x-kde-passwordManagerHint" in types and not self.d.settings["clip_secrets"]:
                if b.read("x-kde-passwordManagerHint").strip() == b"secret":
                    log("clipboard: a password manager copy stays on the laptop")
                    return
            text = next((t for t in TEXT_TYPES if t in types), None)
            image = "image/png" if "image/png" in types else next((t for t in types if t.startswith("image/")), None)
            if text:
                mime, data = "text/plain", b.read(text)
            elif image:
                mime, data = image, b.read(image)
            else:
                return
        except (OSError, subprocess.TimeoutExpired) as e:
            log("clipboard: can't read it:", e)
            return
        if not data:
            return
        h = clip_hash(mime, data)
        if h == self.last_hash and not force:
            return  # the phone's own copy, which we just put here
        if len(data) > self.max:
            log(f"clipboard: {len(data) // 1024} KB is over CLIP_MAX_MB, not sending it")
            return
        self.last_hash = h
        with self.lock:
            self.pending = {"mime": mime, "data": data, "hash": h, "since": time.monotonic(), "manual": force}
        self.wake.set()

    def send_now(self):
        threading.Thread(target=self._grab, kwargs={"force": True}, daemon=True).start()

    def _sender(self):
        while not self.d.stop:
            self.wake.wait(10)
            self.wake.clear()
            with self.lock:
                p = self.pending
            if not p:
                continue
            if time.monotonic() - p["since"] > CLIP_TTL:
                log("clipboard: the phone didn't take the last copy in time; dropped it")
                with self.lock:
                    if self.pending is p:
                        self.pending = None
                continue
            # A big copy (an image) goes in chunks, minutes over Bluetooth: a newer copy replaces it.
            if not self.d.link.send({"t": "clip", "mime": p["mime"], "hash": p["hash"], "manual": p["manual"]},
                                    p["data"], wanted=lambda: self.pending is p):
                if not p.get("failed"):
                    p["failed"] = True
                    log(f"clipboard: phone not connected; retrying for {CLIP_TTL / 60:.0f} min")
                continue
            self.stats["to_phone"] += 1
            self.stats["last_to_phone"] = time.time()
            log(f"clipboard -> phone: {p['mime']}, {len(p['data'])} bytes")
            with self.lock:
                if self.pending is p:
                    self.pending = None

    def on_link_up(self):
        self.wake.set()

    # -- phone -> laptop
    def on_clip(self, h, data):
        mime = str(h.get("mime", ""))
        if not data or not (mime.startswith("text/") or mime.startswith("image/")):
            return
        # A one-time code the phone spotted (otp_copy), or one sent by hand (Send clipboard, Share): they come
        # even with automatic copying (clip_to_laptop) off.
        otp = bool(h.get("otp"))
        if not otp and not h.get("manual") and not self.d.settings["clip_to_laptop"]:
            return
        self.set_local(mime, data)
        if otp:
            self.d.notify(f"Code {data.decode('utf-8', 'replace')} copied", title="From your phone",
                          sync_tag="tandem-clip", timeout=8000)
        else:
            self.d.notify(clip_summary(mime, data), title="From your phone", sync_tag="tandem-clip", timeout=1800)

    def set_local(self, mime, data):
        b = self.backend
        if not b:
            log("clipboard: no clipboard tool, can't take the phone's copy")
            return False
        self.last_hash = clip_hash(mime, data)
        try:
            ok = b.write("text/plain;charset=utf-8" if mime.startswith("text/") else mime, data)
        except (OSError, subprocess.TimeoutExpired) as e:
            log("clipboard: can't set it:", e)
            ok = False
        if ok:
            self.stats["from_phone"] += 1
            self.stats["last_from_phone"] = time.time()
            log(f"clipboard <- phone: {mime}, {len(data)} bytes")
        return ok

    def state(self):
        return dict(self.stats, backend=self.backend.name if self.backend else None, pending=bool(self.pending),
                    to_phone_on=self.d.settings["clip_from_laptop"], from_phone_on=self.d.settings["clip_to_laptop"])
