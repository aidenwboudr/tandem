"""The desktop side, kept to standard pieces so it works on GNOME, KDE, sway, Hyprland, ...:
freedesktop notifications (gdbus), MPRIS (busctl), logind (loginctl), xdg-open, and a few notification
daemons' Do Not Disturb switches."""
import glob
import os
import re
import shutil
import subprocess
import threading
import time

from .config import RUNTIME, log

NOTIF_DEST = ["--session", "--dest", "org.freedesktop.Notifications", "--object-path", "/org/freedesktop/Notifications"]


def session_env():
    """The service may start before the desktop exported WAYLAND_DISPLAY/DISPLAY; find them then."""
    env = dict(os.environ)
    if not env.get("WAYLAND_DISPLAY"):
        socks = sorted(p for p in glob.glob(os.path.join(RUNTIME, "wayland-*")) if not p.endswith(".lock"))
        if socks:
            env["WAYLAND_DISPLAY"] = os.path.basename(socks[0])
    if not env.get("DISPLAY") and os.path.exists("/tmp/.X11-unix/X0"):
        env["DISPLAY"] = ":0"
    return env


def gvariant_str(s):
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"


def markup_escape(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Notifier:
    """Desktop notifications with actions, closing, and replies. Signals come from a `gdbus monitor`
    child; on_action(id, key), on_closed(id, reason) and on_reply(id, text) run on its reader thread."""

    def __init__(self, enabled=True):
        self.enabled = enabled
        self.gdbus = shutil.which("gdbus")
        self.caps = None
        self.handlers = {}  # notification id -> object with on_action/on_closed/on_reply
        self.lock = threading.Lock()
        self.monitor = None
        if self.gdbus:
            threading.Thread(target=self._monitor, name="tandem-notif", daemon=True).start()

    def capabilities(self):
        if self.caps is None and self.gdbus:
            out = self._call("GetCapabilities")
            self.caps = set(re.findall(r"'([^']*)'", out or "")) if out is not None else None
        return self.caps or set()

    def _call(self, method, *args):
        try:
            r = subprocess.run([self.gdbus, "call", *NOTIF_DEST, "--method", "org.freedesktop.Notifications." + method,
                                *args], capture_output=True, text=True, timeout=5, env=session_env())
        except (OSError, subprocess.TimeoutExpired):
            return None
        return r.stdout if r.returncode == 0 else None

    def notify(self, title, body="", icon="", actions=(), timeout=5000, replace=0, urgency=1, handler=None,
               category="", sync_tag=""):
        """Shows a notification and returns its id (0 if it couldn't). actions: [(key, label)]."""
        if not self.enabled:
            return 0
        if not self.gdbus:
            cmd = ["notify-send", "-a", "Tandem", "-t", str(timeout), "-u", ("low", "normal", "critical")[urgency]]
            if icon:
                cmd += ["-i", icon]
            if sync_tag:
                cmd += ["-h", "string:x-canonical-private-synchronous:" + sync_tag]
            try:
                subprocess.Popen(cmd + [title, body], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 env=session_env())
            except OSError:
                pass
            return 0
        caps = self.capabilities()
        if "body-markup" in caps:
            body = markup_escape(body)
        acts = []
        for k, label in actions:
            acts += [gvariant_str(k), gvariant_str(label)]
        hints = [f"'urgency': <byte {urgency}>"]
        if icon and os.path.isabs(icon):
            hints.append(f"'image-path': <{gvariant_str(icon)}>")
        if category:
            hints.append(f"'category': <{gvariant_str(category)}>")
        if sync_tag:
            hints.append(f"'x-canonical-private-synchronous': <{gvariant_str(sync_tag)}>")
        hints.append("'desktop-entry': <'tandem'>")
        # Typed literals: a bare "-1" would be taken for a command-line option.
        out = self._call("Notify", "'Tandem'", f"uint32 {int(replace)}", gvariant_str(icon or "phone"),
                         gvariant_str(title), gvariant_str(body), "@as [" + ", ".join(acts) + "]",
                         "{" + ", ".join(hints) + "}", f"int32 {int(timeout)}")
        m = re.search(r"uint32 (\d+)", out or "")
        nid = int(m.group(1)) if m else 0
        if nid and handler:
            with self.lock:
                self.handlers[nid] = handler
        return nid

    def close(self, nid):
        if nid and self.gdbus:
            self._call("CloseNotification", f"uint32 {int(nid)}")

    def _monitor(self):
        pat = re.compile(r"org\.freedesktop\.Notifications\.(ActionInvoked|NotificationClosed|NotificationReplied)"
                         r" \(uint32 (\d+), (.*)\)\s*$")
        while True:
            try:
                # Line-buffered: piped, gdbus holds signals back in a 4 KB buffer.
                pre = ["stdbuf", "-oL"] if shutil.which("stdbuf") else []
                p = subprocess.Popen(pre + [self.gdbus, "monitor", "--session", "--dest", "org.freedesktop.Notifications",
                                            "--object-path", "/org/freedesktop/Notifications"],
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=session_env())
                self.monitor = p
                for line in p.stdout:
                    m = pat.search(line)
                    if not m:
                        continue
                    sig, nid, rest = m.group(1), int(m.group(2)), m.group(3)
                    with self.lock:
                        h = self.handlers.get(nid)
                        if sig == "NotificationClosed":
                            self.handlers.pop(nid, None)
                    if not h:
                        continue
                    try:
                        if sig == "ActionInvoked":
                            h.on_action(nid, _unquote(rest))
                        elif sig == "NotificationReplied":
                            h.on_reply(nid, _unquote(rest))
                        else:
                            h.on_closed(nid, int((re.findall(r"\d+", rest) or ["0"])[-1]))  # "uint32 2"
                    except Exception as e:
                        log("notification handler:", repr(e))
                p.wait()
            except OSError as e:
                log("notifications: can't watch actions:", e)
            time.sleep(5)


def _unquote(s):
    s = s.strip()
    if len(s) >= 2 and s[0] in "'\"" and s[-1] == s[0]:
        s = s[1:-1]
    return s.encode("latin-1", "backslashreplace").decode("unicode_escape", "replace") if "\\" in s else s


def prompt(title, text):
    """Asks for one line of text (a reply). None if cancelled or no dialog tool is around."""
    env = session_env()
    for cmd in (["zenity", "--entry", "--title", title, "--text", text],
                ["kdialog", "--title", title, "--inputbox", text],
                ["yad", "--entry", "--title", title, "--text", text]):
        if shutil.which(cmd[0]):
            try:
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=600, env=env)
            except (OSError, subprocess.TimeoutExpired):
                return None
            return r.stdout.rstrip("\n") if r.returncode == 0 and r.stdout.strip() else None
    # Last resort: our own tiny GTK window (needs PyGObject).
    try:
        import sys
        here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        r = subprocess.run([sys.executable, "-c", "import sys; sys.path.insert(0, sys.argv[1]); "
                            "from tandemd.cli import main; sys.argv[1:2] = []; main()", here, "prompt", title, text],
                           capture_output=True, text=True, timeout=600, env=env)
        return r.stdout.rstrip("\n") if r.returncode == 0 and r.stdout.strip() else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def open_target(target):
    try:
        subprocess.Popen(["xdg-open", target], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         env=session_env(), start_new_session=True)
        return True
    except OSError:
        return False


# ---------------------------------------------------------------- MPRIS (media players)

def _busctl_user(*args, timeout=3):
    try:
        r = subprocess.run(["busctl", "--user", *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def mpris_players():
    out = _busctl_user("call", "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames")
    return re.findall(r'"(org\.mpris\.MediaPlayer2\.[^"]+)"', out or "")


def mpris_status(name):
    out = _busctl_user("get-property", name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player",
                       "PlaybackStatus")
    m = re.search(r'"(\w+)"', out or "")
    return m.group(1) if m else ""


def mpris_call(name, method):
    return _busctl_user("call", name, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", method) is not None


def pause_all():
    """Pauses every playing player; returns the ones it paused (to resume later)."""
    paused = []
    for name in mpris_players():
        if mpris_status(name) == "Playing" and mpris_call(name, "Pause"):
            paused.append(name)
    return paused


def resume(names):
    for name in names:
        if mpris_status(name) == "Paused":
            mpris_call(name, "Play")


# ---------------------------------------------------------------- session lock

def session_id():
    """This user's graphical session (a user service isn't in one, so `loginctl lock-session` alone fails)."""
    try:
        out = subprocess.run(["loginctl", "list-sessions", "--no-legend"], capture_output=True, text=True,
                             timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    uid = str(os.getuid())
    best = None
    for line in out.splitlines():
        f = line.split()
        if len(f) < 2 or f[1] != uid:
            continue
        sid = f[0]
        try:
            props = subprocess.run(["loginctl", "show-session", sid, "-p", "Type", "-p", "State"],
                                   capture_output=True, text=True, timeout=5).stdout
        except (OSError, subprocess.TimeoutExpired):
            continue
        if "Type=wayland" in props or "Type=x11" in props:
            if "State=active" in props:
                return sid
            best = best or sid
    return best


def locked():
    sid = session_id()
    if not sid:
        return False
    try:
        out = subprocess.run(["loginctl", "show-session", sid, "-p", "LockedHint"], capture_output=True, text=True,
                             timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return False
    return "LockedHint=yes" in out


def lock_screen():
    sid = session_id()
    if sid:
        subprocess.run(["loginctl", "lock-session", sid], capture_output=True, timeout=5)
        for _ in range(8):
            time.sleep(0.25)
            if locked():
                return True
    # Compositors without a logind lock hook (sway without swayidle's `lock`): run a locker ourselves.
    for cmd in (["swaylock", "-f"], ["hyprlock"], ["i3lock"]):
        if shutil.which(cmd[0]):
            subprocess.Popen(cmd, env=session_env(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
            return True
    return False


# ---------------------------------------------------------------- Do Not Disturb

def _run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=3, env=session_env())
        return r.stdout.strip() if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def _running(name):
    return subprocess.run(["pgrep", "-u", str(os.getuid()), "-x", name], capture_output=True).returncode == 0


def dnd_backend():
    if _running("mako") and shutil.which("makoctl"):
        return "mako"
    if _running("swaync") and shutil.which("swaync-client"):
        return "swaync"
    if _running("dunst") and shutil.which("dunstctl"):
        return "dunst"
    if _running("gnome-shell") and shutil.which("gsettings"):
        return "gnome"
    return None


def dnd_get(backend):
    if backend == "mako":
        out = _run(["makoctl", "mode"])
        return None if out is None else "do-not-disturb" in out.split()
    if backend == "swaync":
        out = _run(["swaync-client", "-D", "-sw"])
        return None if out is None else out == "true"
    if backend == "dunst":
        out = _run(["dunstctl", "is-paused"])
        return None if out is None else out == "true"
    if backend == "gnome":
        out = _run(["gsettings", "get", "org.gnome.desktop.notifications", "show-banners"])
        return None if out is None else out == "false"
    return None


def dnd_set(backend, on):
    if backend == "mako":
        _run(["makoctl", "mode", "-a" if on else "-r", "do-not-disturb"])
    elif backend == "swaync":
        _run(["swaync-client", "-dn" if on else "-df", "-sw"])
    elif backend == "dunst":
        _run(["dunstctl", "set-paused", "true" if on else "false"])
    elif backend == "gnome":
        _run(["gsettings", "set", "org.gnome.desktop.notifications", "show-banners", "false" if on else "true"])


# ---------------------------------------------------------------- battery + sound

def battery():
    """{"level": %, "charging": bool} of the laptop's battery, or None on a desktop."""
    for d in sorted(glob.glob("/sys/class/power_supply/*")):
        try:
            if open(os.path.join(d, "type")).read().strip() != "Battery":
                continue
            level = int(open(os.path.join(d, "capacity")).read().strip())
            status = open(os.path.join(d, "status")).read().strip()
        except (OSError, ValueError):
            continue
        return {"level": level, "charging": status in ("Charging", "Full")}
    return None


SOUNDS = ["/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga",
          "/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga",
          "/usr/share/sounds/freedesktop/stereo/complete.oga"]


class Ringer:
    """Loops a loud sound until stopped or `seconds` pass (find my laptop)."""

    def __init__(self):
        self.stop_at = 0.0
        self.thread = None

    def start(self, seconds=30):
        self.stop_at = time.monotonic() + seconds
        if self.thread and self.thread.is_alive():
            return
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_at = 0.0

    def _loop(self):
        sound = next((s for s in SOUNDS if os.path.exists(s)), None)
        player = next((p for p in ("pw-play", "paplay", "canberra-gtk-play") if shutil.which(p)), None)
        while time.monotonic() < self.stop_at:
            if sound and player:
                args = [player, "--id", "phone-incoming-call"] if player == "canberra-gtk-play" else [player, sound]
                subprocess.run(args, capture_output=True, timeout=10, env=session_env())
            else:
                time.sleep(1)
