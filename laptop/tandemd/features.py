"""The laptop's half of the optional features. Each one checks its setting (config.DEFAULTS), which the
phone app (or `tandem set`) turns on and off for both sides."""
import hashlib
import mimetypes
import os
import re
import shutil
import threading
import time

from . import desktop
from .config import ICON_DIR, log, user_dir



def safe_name(name, fallback="file"):
    name = os.path.basename(str(name or "")).strip().replace("\0", "")
    name = re.sub(r"[\x00-\x1f/\\]", "_", name).lstrip(".")
    return name[:200] or fallback


def unique_path(folder, name):
    os.makedirs(folder, exist_ok=True)
    base, ext = os.path.splitext(name)
    path, n = os.path.join(folder, name), 1
    while os.path.exists(path):
        path = os.path.join(folder, f"{base} ({n}){ext}")
        n += 1
    return path


class _Handler:
    """Routes a notification's action/close/reply signals to callbacks."""

    def __init__(self, on_action=None, on_closed=None, on_reply=None):
        self._a, self._c, self._r = on_action, on_closed, on_reply

    def on_action(self, nid, key):
        if self._a:
            self._a(nid, key)

    def on_closed(self, nid, reason):
        if self._c:
            self._c(nid, reason)

    def on_reply(self, nid, text):
        if self._r:
            self._r(nid, text)


# ---------------------------------------------------------------- phone notifications on the laptop

class NotifMirror:
    def __init__(self, d):
        self.d = d
        self.by_key = {}  # phone notification key -> laptop notification id
        self.keys = {}  # laptop id -> phone key
        self.closing = set()  # laptop ids we closed ourselves (not the user)

    def excluded(self, pkg):
        return pkg in {p.strip() for p in self.d.settings["notif_excluded"].split(",") if p.strip()}

    def on_icon(self, h, data):
        pkg = safe_name(h.get("pkg"), "")
        if pkg and data:
            os.makedirs(ICON_DIR, exist_ok=True)
            with open(os.path.join(ICON_DIR, pkg + ".png"), "wb") as f:
                f.write(data)

    def on_notif(self, h):
        s = self.d.settings
        key, pkg = str(h.get("key", "")), str(h.get("pkg", ""))
        if not s["notif_mirror"] or not key or self.excluded(pkg):
            return
        icon = os.path.join(ICON_DIR, safe_name(pkg, "x") + ".png")
        actions = []
        caps = self.d.notifier.capabilities()
        if h.get("reply") and s["notif_reply"]:
            actions.append(("inline-reply" if "inline-reply" in caps else "reply", "Reply"))
        for i, label in enumerate((h.get("actions") or [])[:3]):
            actions.append((f"a{i}", str(label)[:40]))
        title = str(h.get("title") or h.get("app") or "Phone")
        app = str(h.get("app") or "")
        if app and app not in title:
            title = f"{title} · {app}"
        nid = self.d.notifier.notify(title, str(h.get("text") or "")[:1000],
                                     icon=icon if os.path.exists(icon) else "phone", actions=actions,
                                     timeout=-1, replace=self.by_key.get(key, 0),
                                     handler=_Handler(self._action, self._closed, self._reply))
        if nid:
            self.by_key[key] = nid
            self.keys[nid] = key

    def on_gone(self, h):
        nid = self.by_key.pop(str(h.get("key", "")), None)
        if nid and self.d.settings["notif_dismiss_sync"]:
            self.closing.add(nid)
            self.d.notifier.close(nid)

    # signal callbacks (the notifier's thread; they only send)
    def _action(self, nid, action):
        key = self.keys.get(nid)
        if not key:
            return
        if action == "reply":
            threading.Thread(target=self._ask_reply, args=(nid, key), daemon=True).start()
        elif action.startswith("a") and action[1:].isdigit():
            self.d.link.send({"t": "notif-action", "key": key, "index": int(action[1:])})

    def _ask_reply(self, nid, key):
        text = desktop.prompt("Reply", "Reply from the laptop:")
        if text:
            self._reply(nid, text)

    def _reply(self, nid, text):
        key = self.keys.get(nid)
        if key and text:
            self.d.link.send({"t": "notif-reply", "key": key, "text": text})

    def _closed(self, nid, reason):
        key = self.keys.pop(nid, None)
        if nid in self.closing:
            self.closing.discard(nid)
            return
        if key and self.by_key.get(key) == nid:
            self.by_key.pop(key, None)
        # 2 = dismissed by the user (1 = expired, 3 = closed by a call)
        if key and reason == 2 and self.d.settings["notif_dismiss_sync"]:
            self.d.link.send({"t": "notif-dismiss", "key": key})


# ---------------------------------------------------------------- calls

class Calls:
    def __init__(self, d):
        self.d = d
        self.nid = 0
        self.paused = []
        self.state = "idle"

    def on_call(self, h):
        state = h.get("state")
        if state == self.state:
            return
        self.state = state
        s = self.d.settings
        if state == "ringing":
            if s["calls"]:
                actions = [("mute", "Silence")]
                if h.get("can_reject"):
                    actions.append(("reject", "Decline"))
                self.nid = self.d.notifier.notify("Incoming call", str(h.get("name") or "Unknown caller"),
                                                  icon="call-start", actions=actions, timeout=0, urgency=2,
                                                  category="call", handler=_Handler(self._action))
            if s["call_pause_media"] and not self.paused:
                self.paused = desktop.pause_all()
                if self.paused:
                    log(f"call: paused {', '.join(self.paused)}")
        elif state == "offhook":
            if s["call_pause_media"] and not self.paused:
                self.paused = desktop.pause_all()
            self._close()
        else:  # idle
            self._close()
            if self.paused:
                desktop.resume(self.paused)
                self.paused = []

    def _close(self):
        if self.nid:
            self.d.notifier.close(self.nid)
            self.nid = 0

    def _action(self, nid, action):
        if action in ("mute", "reject"):
            self.d.link.send({"t": "call-cmd", "op": action})


# ---------------------------------------------------------------- files, screenshots, links

class Files:
    def __init__(self, d):
        self.d = d
        self.queue = []  # paths waiting for the phone (main thread only)
        self.sending = threading.Lock()  # one file at a time, in order

    def folder(self, kind):
        if kind == "screenshot":
            return self.d.cfg["SCREENSHOTS_DIR"] or os.path.join(user_dir("PICTURES", "Pictures"), "Phone")
        return self.d.cfg["FILES_DIR"] or user_dir("DOWNLOAD", "Downloads")

    # -- phone -> laptop
    def on_file(self, h, path):
        """A file from the phone, at `path` (where its transfer left it)."""
        kind = h.get("kind") or "file"
        if not self.d.settings["screenshots" if kind == "screenshot" else "files"]:
            os.unlink(path)
            self.d.link.send({"t": "file-no", "id": h.get("id"), "name": h.get("name"),
                              "error": "turned off on the computer"})
            return
        dest = unique_path(self.folder(kind), safe_name(h.get("name")))
        shutil.move(path, dest + ".part")  # the cache may be on another disk: copy, then appear at once
        os.replace(dest + ".part", dest)
        self.received(h, dest)

    def received(self, h, path):
        kind, mime = h.get("kind") or "file", h.get("mime") or mimetypes.guess_type(path)[0] or ""
        log(f"files: got {os.path.basename(path)} ({kind})")
        if kind == "screenshot" and self.d.settings["screenshot_clipboard"] and self.d.clip and mime.startswith("image/"):
            with open(path, "rb") as f:
                self.d.clip.set_local(mime, f.read())
            text = "Screenshot from your phone · copied"
        else:
            text = os.path.basename(path)

        def act(_nid, key):
            desktop.open_target(path if key == "open" else os.path.dirname(path))
        self.d.notifier.notify("Received from your phone" if kind != "screenshot" else "Screenshot", text,
                               icon=path if mime.startswith("image/") else "document-save",
                               actions=[("open", "Open"), ("folder", "Show folder")], timeout=8000,
                               handler=_Handler(act))

    # -- laptop -> phone
    def send(self, paths):
        for p in paths:
            if not os.path.isfile(p):
                self.d.notify(f"Not a file: {p}")
                continue
            self._offer(p)

    def _offer(self, path):
        if not self.d.link.connected():
            self.queue.append(path)
            self.d.notify(f"{os.path.basename(path)} will go to your phone when it's connected")
            return
        threading.Thread(target=self._send, args=(path,), daemon=True).start()

    def _send(self, path):
        """Over whichever link is up (its own thread: over Bluetooth a big file takes minutes)."""
        name = os.path.basename(path)
        with self.sending:
            try:
                st = os.stat(path)
            except OSError:
                return
            # The same file keeps its id, so a send that broke off carries on where it stopped.
            fid = hashlib.sha256(f"{path}:{st.st_size}:{st.st_mtime_ns}".encode()).hexdigest()[:16]
            ok = self.d.link.send({"t": "file", "id": fid, "name": name, "kind": "file",
                                   "mime": mimetypes.guess_type(path)[0] or "application/octet-stream"}, path=path)
        self.d.link.post("local", {"t": "_sent", "name": name, "ok": ok, "path": path})

    def on_sent(self, h):
        if h["ok"]:
            self.d.notify(f"{h['name']} sent to your phone")
        else:
            self.queue.append(h["path"])
            self.d.notify(f"{h['name']} will go to your phone when it's connected again")

    def on_file_no(self, h):
        self.d.notify(f"{h.get('name') or 'A file'} wasn't taken: {h.get('error') or 'refused'}", title="Your phone")

    def on_link_up(self):
        if self.queue:
            q, self.queue = self.queue, []
            for p in q:
                self._offer(p)

    def on_open(self, h):
        url = str(h.get("url") or "")
        if not re.match(r"^(https?|mailto|tel|geo):", url, re.I):
            return
        if self.d.settings["open_links"]:
            desktop.open_target(url)
            self.d.notify(url[:80], title="Link from your phone", timeout=3000)
        else:
            self.d.notifier.notify("Link from your phone", url[:200], actions=[("open", "Open")], timeout=15000,
                                   handler=_Handler(lambda _n, _k: desktop.open_target(url)))


# ---------------------------------------------------------------- battery, find my device, DND, lock

class Status:
    LOW_RESET = 5

    def __init__(self, d):
        self.d = d
        self.phone = {}
        self.low_alerted = set()  # "phone", "headphones"
        self.last_sent = None
        self.last_send_at = 0.0
        self.ringer = desktop.Ringer()
        self.ring_nid = 0
        self.dnd_backend = None
        self.dnd_checked = 0.0
        self.dnd_local = None  # the laptop's DND as last seen
        self.bt_down_since = None
        self.bt_up_since = None

    def on_status(self, h):
        self.phone.update({k: h[k] for k in ("battery", "hp_battery", "dnd", "hp_name") if k in h})
        s = self.d.settings
        if not s["battery"]:
            return
        b = h.get("battery") or {}
        self._low("phone", "Your phone", b.get("level"), b.get("charging"))
        hb = h.get("hp_battery")
        if hb is not None:
            self._low("headphones", h.get("hp_name") or "Your headphones", hb, False)

    def _low(self, who, label, level, charging):
        if level is None:
            return
        th = self.d.settings["battery_low"]
        if charging or level > th + self.LOW_RESET:
            self.low_alerted.discard(who)
        elif level <= th and who not in self.low_alerted:
            self.low_alerted.add(who)
            self.d.notify(f"{label} is at {level}%", title="Battery low", timeout=10000)

    def on_ring(self, h):
        if not h.get("on"):
            self.ringer.stop()
            self.d.notifier.close(self.ring_nid)
            return
        if not self.d.settings["find_phone"]:
            return
        self.ringer.start(30)
        self.ring_nid = self.d.notifier.notify("Your phone is looking for this computer", "", icon="audio-volume-high",
                                               actions=[("stop", "Stop")], timeout=30000, urgency=2,
                                               handler=_Handler(lambda *_: self.ringer.stop(),
                                                                lambda *_: self.ringer.stop()))

    def on_dnd(self, h):
        if not self.d.settings["dnd_sync"] or not self.dnd_backend:
            return
        on = bool(h.get("on"))
        if on != self.dnd_local:
            desktop.dnd_set(self.dnd_backend, on)
            self.dnd_local = on
            log(f"dnd: {'on' if on else 'off'} (from the phone)")

    def on_link(self, h):
        now = time.monotonic()
        if h.get("via") != "bt":
            if h["t"] == "_up":
                self.last_sent = None  # send our status on the new link
            return
        if h["t"] == "_up":
            self.bt_down_since = None
            self.bt_up_since = now
            self.last_sent = None
        else:
            # Only count it as "walked away" if the link had been up a while.
            if self.bt_up_since and now - self.bt_up_since > 10:
                self.bt_down_since = now
            self.bt_up_since = None

    def tick(self, now):
        s = self.d.settings
        # Lock when the phone leaves Bluetooth range.
        if self.bt_down_since and s["lock_on_leave"] and not self.d.link.bt:
            if now - self.bt_down_since >= max(5, s["lock_delay"]):
                self.bt_down_since = None
                if not desktop.locked():
                    log("lock: the phone left; locking the screen")
                    desktop.lock_screen()
        elif not s["lock_on_leave"]:
            self.bt_down_since = None
        # DND: watch the laptop's own switch.
        if s["dnd_sync"] and now - self.dnd_checked > 5:
            self.dnd_checked = now
            if self.dnd_backend is None:
                self.dnd_backend = desktop.dnd_backend() or ""
            if self.dnd_backend:
                cur = desktop.dnd_get(self.dnd_backend)
                if cur is not None and cur != self.dnd_local:
                    first = self.dnd_local is None
                    self.dnd_local = cur
                    if not first:
                        self.d.link.send({"t": "dnd", "on": cur})
                        log(f"dnd: {'on' if cur else 'off'} (sent to the phone)")
        # Our battery for the phone's notification.
        if self.d.link.connected() and now - self.last_send_at > 60:
            st = {"battery": desktop.battery(), "dnd": self.dnd_local if s["dnd_sync"] else None}
            if st != self.last_sent or now - self.last_send_at > 300:
                if self.d.link.send(dict(st, t="status")):
                    self.last_sent = st
                    self.last_send_at = now

    def state(self):
        return {"phone_battery": self.phone.get("battery"), "headphones_battery": self.phone.get("hp_battery"),
                "dnd_backend": self.dnd_backend, "dnd": self.dnd_local}
