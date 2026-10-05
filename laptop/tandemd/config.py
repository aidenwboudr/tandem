"""Paths, the config file, this computer's identity, the paired phone, and the synced feature settings."""
import hashlib
import json
import os
import socket
import ssl
import subprocess
import threading
import time
import uuid

CONFIG_DIR = os.path.dirname(os.environ.get("TANDEM_CONFIG") or os.path.expanduser("~/.config/tandem/config"))
CONFIG = os.environ.get("TANDEM_CONFIG") or os.path.join(CONFIG_DIR, "config")
RUNTIME = os.environ.get("XDG_RUNTIME_DIR", "/tmp")
STATE = os.path.join(RUNTIME, "tandem.json")
CTL = os.path.join(RUNTIME, "tandem.ctl")  # local commands (the CLI, the mixer)
ART_DIR = os.path.join(RUNTIME, "tandem-art")
ICON_DIR = os.path.expanduser("~/.cache/tandem/icons")
PREFER = os.path.join(CONFIG_DIR, "prefer")  # "phone" or "laptop": who carries the audio
IDENTITY = os.path.join(CONFIG_DIR, "identity.json")
CERT = os.path.join(CONFIG_DIR, "cert.pem")
KEY = os.path.join(CONFIG_DIR, "key.pem")
PEER = os.path.join(CONFIG_DIR, "phone.json")  # the paired phone
SETTINGS = os.path.join(CONFIG_DIR, "settings.json")

SERVICE_UUID = "7a6d3b40-6e1a-4d2a-9b7e-54616e64656d"
PROTO_VERSION = 3

# Laptop-only knobs. Everything a person would want to turn on or off lives in the app's settings
# (DEFAULTS below), which both sides share.
CONFIG_DEFAULTS = {
    "PORT": "47800", "OPUS_BITRATE": "160000", "NOTIFY": "1", "CLIP_MAX_MB": "25",
    "FILES_DIR": "", "SCREENSHOTS_DIR": "",
    # Optional: pin the headphones / the phone's Bluetooth address instead of learning them.
    "HEADPHONES": "", "PHONE": "",
    # 1 keeps blueman's "Connected"/"Disconnected" pop-ups, which Tandem otherwise turns off while it runs
    # (its own link to the phone would pop them up on every reconnect).
    "BLUEMAN_POPUPS": "0",
}

# The feature settings. The phone app is where people change them; `tandem set` works too.
DEFAULTS = {
    "clip_to_laptop": True, "clip_from_laptop": True, "clip_secrets": False,
    "audio_share": True, "play_opens_app": False, "play_app": "com.spotify.music",
    "media_controls": True,
    "notif_mirror": False, "notif_excluded": "", "notif_dismiss_sync": True, "notif_reply": True,
    "otp_copy": False,
    "files": True, "open_links": True,
    "calls": False, "call_pause_media": True,
    "battery": True, "battery_low": 15,
    "find_phone": True,
    "screenshots": False, "screenshot_clipboard": True,
    "remote_input": False,
    "lock_on_leave": False, "lock_delay": 30,
    "dnd_sync": False,
    "screen_mirror": False,
}


def log(*a):
    print(*a, flush=True)


def load_config():
    cfg = dict(CONFIG_DEFAULTS)
    try:
        with open(CONFIG) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.split("#", 1)[0].strip().strip('"')
    except FileNotFoundError:
        pass
    cfg["HEADPHONES"] = cfg["HEADPHONES"].upper()
    cfg["PHONE"] = cfg["PHONE"].upper()
    return cfg


def read_json(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_json(path, obj, mode=0o600):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, "w") as f:
        json.dump(obj, f, indent=1)
    os.replace(tmp, path)


def user_dir(kind, fallback):
    """$XDG_<KIND>_DIR (DOWNLOAD, PICTURES), or ~/<fallback>."""
    try:
        out = subprocess.run(["xdg-user-dir", kind], capture_output=True, text=True, timeout=3).stdout.strip()
        if out and out != os.path.expanduser("~"):
            return out
    except (OSError, subprocess.TimeoutExpired):
        pass
    return os.path.expanduser("~/" + fallback)


class Identity:
    """This computer: a random id, a name, and a self-signed TLS certificate the phone pins."""

    def __init__(self):
        ident = read_json(IDENTITY) or {}
        if not ident.get("id"):
            ident = {"id": uuid.uuid4().hex}
            write_json(IDENTITY, ident)
        self.id = ident["id"]
        self.name = ident.get("name") or socket.gethostname()
        if not (os.path.exists(CERT) and os.path.exists(KEY)):
            make_cert()
        with open(CERT) as f:
            der = ssl.PEM_cert_to_DER_cert(f.read())
        self.fp = hashlib.sha256(der).hexdigest()

    def server_context(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(CERT, KEY)
        return ctx


def make_cert():
    os.makedirs(CONFIG_DIR, exist_ok=True)
    r = subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
         "-days", "36500", "-subj", "/CN=tandem", "-keyout", KEY + ".new", "-out", CERT + ".new"],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("tandem: can't make a TLS certificate (is openssl installed?): " + r.stderr.strip())
    os.chmod(KEY + ".new", 0o600)
    os.replace(KEY + ".new", KEY)
    os.replace(CERT + ".new", CERT)


class Peer:
    """The paired phone: {id, name, bt (address), token}. Empty until pairing."""

    def __init__(self):
        self.data = read_json(PEER) or {}

    def __bool__(self):
        return bool(self.data.get("id") and self.data.get("token"))

    def get(self, k, default=None):
        return self.data.get(k, default)

    def save(self, **kv):
        self.data.update(kv)
        write_json(PEER, self.data)

    def forget(self):
        self.data = {}
        try:
            os.unlink(PEER)
        except OSError:
            pass


class Settings:
    """The feature settings both devices share. Whichever side changed them last wins (`rev`)."""

    def __init__(self):
        saved = read_json(SETTINGS) or {}
        self.values = dict(DEFAULTS)
        self.values.update({k: v for k, v in (saved.get("values") or {}).items() if k in DEFAULTS})
        self.rev = int(saved.get("rev") or 0)
        self.lock = threading.Lock()

    def __getitem__(self, k):
        return self.values.get(k, DEFAULTS.get(k))

    def as_message(self):
        return {"t": "settings", "values": self.values, "rev": self.rev}

    def merge(self, values, rev):
        """Takes the other side's settings if they're newer. Returns the keys that changed."""
        with self.lock:
            if int(rev or 0) <= self.rev:
                return []
            changed = [k for k, v in values.items() if k in DEFAULTS and self.values.get(k) != v]
            for k in changed:
                self.values[k] = coerce(k, values[k])
            self.rev = int(rev)
            self._save()
            return changed

    def set(self, k, v):
        with self.lock:
            self.values[k] = coerce(k, v)
            self.rev = max(int(time.time() * 1000), self.rev + 1)  # a clock behind the phone's still wins
            self._save()

    def _save(self):
        write_json(SETTINGS, {"values": self.values, "rev": self.rev})


def coerce(k, v):
    d = DEFAULTS.get(k)
    if isinstance(d, bool):
        if isinstance(v, str):
            return v.strip().lower() in ("1", "true", "on", "yes")
        return bool(v)
    if isinstance(d, int):
        try:
            return int(v)
        except (TypeError, ValueError):
            return d
    return "" if v is None else str(v)
