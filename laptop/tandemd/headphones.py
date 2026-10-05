"""Audio sharing (only while Bluetooth headphones are on): keeps them on ONE Bluetooth link and still lets
you hear both devices.

  phone has the headphones  -> the laptop drops its own link and blocks reconnects, then plays laptop
                               audio into a virtual "via phone" sink and streams it (Opus) to the app,
                               which plays it into the headphones on top of the phone's own audio.
  laptop has the headphones -> the laptop connects to the phone as a Bluetooth speaker (A2DP sink), so
                               phone audio comes out of the laptop's link.
  headphones off            -> no audio is shared either way.

Which of the two carries the other's audio when both could ("the hub") is a setting (`tandem switch`).
Audio needs the network link; over Bluetooth alone the laptop leaves the headphones alone. Laptop audio
then goes both ways at once, over the network and the Bluetooth link, and the phone plays whichever copy of
each frame comes first: a stall on one (Tailscale moving between a relay and a direct path stops traffic
for a few seconds) doesn't cut the sound.
"""
import collections
import ctypes
import ctypes.util
import json
import os
import secrets
import subprocess
import threading
import time

from . import bluez, desktop
from .config import PREFER, log

HB_TIMEOUT = 5.0  # heartbeats arrive every 1 s while the headphones are on the phone
# Heartbeats going quiet isn't the phone letting go: Wi-Fi drops out for a few seconds now and then.
# Taking the headphones back on every gap flapped the owner and, when the phone came back mid-reconnect,
# crashed bluetoothd. The app says hp=false itself when it lets go.
HB_LOST_GRACE = 20.0
RECONNECT_WINDOW = 25.0  # after unblocking, keep asking the headphones to come back this long
PHONE_RETRY = 30.0  # how often to try linking the phone as a source while the laptop owns...
PHONE_RETRY_MAX = 600.0  # ...doubling after each failure up to this
# Failures that mean the bond is broken. Retrying them pops a pairing request on the phone every time.
PHONE_AUTH_ERRORS = ("key-missing", "AuthenticationFailed", "AuthenticationRejected", "auth-failed")
PHONE_LINK_DELAY = 8.0  # at power-on the phone may be claiming the headphones too; let it win first
CLAIM_TIMEOUT = 30.0  # switched to the laptop but it can't reach the headphones: let the phone carry on
UNLINK_RETRY = 10.0  # switching to the phone: how often to drop the laptop's audio link to it
# On the phone, its audio jumped to the laptop's audio link (see `pulled` in tick): after dropping that link,
# keep the headphones on the phone this long while Android moves back to them.
PULLED_GRACE = 5.0
# Handing the audio over: let the streams follow the new default sink this long before the old one goes.
# WirePlumber (linking.pause-playback) pauses every player still playing into a sink that's removed.
HANDOVER_DELAY = 1.0
SWITCH_TIMEOUT = CLAIM_TIMEOUT + 15  # a switch that hasn't landed by then has failed
# After a switch has landed, resume what it paused anyway (a player that wasn't following the default
# sink, a headphone button) this many seconds later, twice.
RESUME_AFTER = (2.0, 6.0)
SINK_NAME = "tandem_via_phone"
CODEC_PCM, CODEC_OPUS = 0, 1
# Audio frames (20 ms each) waiting for one path. A stalled path drops the oldest instead of holding up the
# other; the phone would skip audio this late anyway.
PATH_QUEUE = 10


# ---------------------------------------------------------------- pipewire helpers

def pw_nodes():
    try:
        out = subprocess.run(["pw-dump"], capture_output=True, text=True, timeout=5).stdout
        objs = json.loads(out)
    except Exception:
        return []
    props = {o["id"]: (o.get("info") or {}).get("props") or {} for o in objs if "id" in o}
    nodes = []
    for o in objs:
        if o.get("type") == "PipeWire:Interface:Node":
            p = props[o["id"]]
            nodes.append({"id": o["id"], "name": p.get("node.name", ""), "class": p.get("media.class", ""),
                          "addr": str(p.get("api.bluez5.address", "")).upper(),
                          "dev_addr": str(props.get(p.get("device.id"), {}).get("api.bluez5.address", "")).upper()})
    return nodes


def bt_node(mac, classes):
    """A device's Bluetooth node, by address. Names differ between PipeWire versions
    (bluez_output.AA_BB_... vs bluez_output.AA:BB:...), so they're not matched. PipeWire 1.6 puts a
    loopback in front of a Bluetooth sink: the node with the address is "Audio/Sink/Internal", and the
    sink apps play to only has it through its device."""
    nodes = pw_nodes()
    for key in ("addr", "dev_addr"):
        for n in nodes:
            if n[key] == mac and n["class"] in classes:
                return n
    return None


def find_node(prefix, media_class=None):
    for n in pw_nodes():
        if n["name"].startswith(prefix) and (media_class is None or n["class"] == media_class):
            return n
    return None


def default_sink_name():
    try:
        out = subprocess.run(["wpctl", "inspect", "@DEFAULT_AUDIO_SINK@"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return ""
    for line in out.splitlines():
        line = line.strip().lstrip("* ")
        if line.startswith("node.name = "):
            return line.split("=", 1)[1].strip().strip('"')
    return ""


def set_default_sink(node_id):
    subprocess.run(["wpctl", "set-default", str(node_id)], capture_output=True, timeout=5)


# ---------------------------------------------------------------- opus

class Opus:
    def __init__(self, bitrate):
        lib = ctypes.CDLL(ctypes.util.find_library("opus") or "libopus.so.0")
        lib.opus_encoder_create.restype = ctypes.c_void_p
        lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        lib.opus_encode.restype = ctypes.c_int32
        lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
        lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
        err = ctypes.c_int()
        self.lib = lib
        self.enc = lib.opus_encoder_create(48000, 2, 2049, ctypes.byref(err))  # OPUS_APPLICATION_AUDIO
        if err.value != 0 or not self.enc:
            raise RuntimeError(f"opus_encoder_create failed ({err.value})")
        lib.opus_encoder_ctl(ctypes.c_void_p(self.enc), ctypes.c_int(4002), ctypes.c_int32(bitrate))  # SET_BITRATE
        self.out = ctypes.create_string_buffer(1500)

    def encode(self, pcm):  # 960 stereo s16le frames = 20 ms
        n = self.lib.opus_encode(self.enc, pcm, 960, self.out, 1500)
        if n < 0:
            raise RuntimeError(f"opus_encode failed ({n})")
        return self.out.raw[:n]

    def close(self):
        if self.enc:
            self.lib.opus_encoder_destroy(self.enc)
            self.enc = None


# ---------------------------------------------------------------- laptop audio -> phone

class AudioPath:
    """One way to the phone (its audio connection, or the Bluetooth link), fed from a short queue by its own
    thread, so one path stalling never holds up the other."""

    def __init__(self, name, send):
        self.send = send  # send(header, payload): False if there was no connection to send on
        self.q = collections.deque(maxlen=PATH_QUEUE)
        self.cv = threading.Condition()
        self.sent = 0
        threading.Thread(target=self._run, name="tandem-audio-" + name, daemon=True).start()

    def put(self, header, payload):
        with self.cv:
            self.q.append((header, payload))
            self.cv.notify()

    def _run(self):
        while True:
            with self.cv:
                while not self.q:
                    self.cv.wait()
                header, payload = self.q.popleft()
            if self.send(header, payload):
                self.sent += 1


class Streamer:
    """A virtual sink (pw-record posing as Audio/Sink) whose audio goes to the phone: over its audio
    connection, and over the Bluetooth link too when the phone takes audio there (`bt`)."""

    def __init__(self, link, bitrate, description):
        self.link, self.bitrate, self.description = link, bitrate, description
        self.proc = None
        self.codec = None
        self.bt = False  # the phone said (hb `bta`) it plays audio frames that come over Bluetooth
        self.net_path = AudioPath("net", self._send_net)
        self.bt_path = AudioPath("bt", lambda h, p: self.link.send(h, p, via="bt"))

    @property
    def sent(self):
        return self.net_path.sent + self.bt_path.sent

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def ensure(self, codec):
        if self.running() and codec == self.codec:
            return False
        self.stop()
        self.codec = codec
        props = ("{ media.class=Audio/Sink node.name=%s node.description=\"%s\" "
                 "node.latency=960/48000 }" % (SINK_NAME, self.description))
        self.proc = subprocess.Popen(
            ["pw-record", "-P", props, "--rate", "48000", "--channels", "2", "--format", "s16",
             "--latency", "20ms", "-"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        threading.Thread(target=self._pump, args=(self.proc, codec), daemon=True).start()
        return True

    def _send_net(self, header, payload):
        conn = self.link.audio
        if not conn:
            return False
        try:
            conn.send(header, payload)
            return True
        except OSError:
            if self.link.audio is conn:  # a newer connection may have replaced it already
                self.link.audio = None
            conn.close()
            return False

    def _pump(self, proc, codec):
        enc = None
        if codec == "opus":
            try:
                enc = Opus(self.bitrate)
            except Exception as e:
                log("opus unavailable, sending PCM:", e)
        frame = 960 * 4  # 20 ms
        kind = CODEC_OPUS if enc else CODEC_PCM
        # The phone keeps the newest frame of a stream: `seq` orders them, `id` tells a new stream (seq from 0).
        sid = secrets.randbelow(1 << 31)
        seq = 0
        f = proc.stdout
        try:
            while True:
                buf = f.read(frame)
                if not buf or len(buf) < frame:
                    break
                # Bluetooth carries Opus only: raw PCM (1.5 Mbit/s) is more than it can take.
                net, bt = self.link.audio, self.bt and enc and self.link.bt
                if net or bt:
                    header = {"t": "a", "c": kind, "s": seq, "id": sid}
                    payload = enc.encode(buf) if enc else buf
                    if net:
                        self.net_path.put(header, payload)
                    if bt:
                        self.bt_path.put(header, payload)
                seq = (seq + 1) & 0xFFFFFFFF
        finally:
            if enc:
                enc.close()

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None


# ---------------------------------------------------------------- the policy

class Headphones:
    def __init__(self, d):
        self.d = d  # the daemon: cfg, link, settings, peer, notify, bg
        self.hb = None  # last heartbeat (+ "time")
        self.owner = None
        self.reconnect_until = 0.0
        self.last_hp_try = 0.0
        self.last_phone_try = 0.0
        self.phone_fails = 0
        self.phone_needs_pairing = False
        self.hp_sink_id = None
        self.prefer = load_prefer()
        self.claim_since = None  # prefer=laptop: since when the laptop has been trying to get the headphones
        self.last_unlink_try = 0.0
        self.unlink_logged = False
        self.pulled_until = 0.0
        self.phone_hp_linked = False
        self.last_sink_check = 0.0
        self.phone_linked_by_us = False
        self.hp_was_connected = False
        self.sink_defaulted = False
        self.loopback = None
        self.phone_linked = False
        self.blocked_by_us = False
        self.hp_battery = None
        self.switch = None  # a hub switch under way (see set_prefer)
        self.handover_at = 0.0  # when the new default sink was picked (HANDOVER_DELAY)
        self.streamer = Streamer(d.link, int(d.cfg["OPUS_BITRATE"]), "Headphones (via phone)")

    @property
    def hp_mac(self):
        return self.d.cfg["HEADPHONES"] or self.d.peer.get("headphones", "")

    def same_headphones(self, hb, devs):
        """Whether the phone's headphones are ours too (paired with this computer). Learns them the first time."""
        addr = str(hb.get("hpaddr") or "").upper()
        if not addr:
            return True  # an old app that doesn't say which
        if addr == self.hp_mac:
            return True
        d = devs.get(addr)
        if self.d.cfg["HEADPHONES"] or not (d and d["paired"]):
            return False
        log(f"headphones: {hb.get('hpname') or addr} ({addr}), paired with both")
        self.d.peer.save(headphones=addr, headphones_name=hb.get("hpname") or d["name"])
        return True

    @property
    def phone_mac(self):
        return self.d.cfg["PHONE"] or (self.d.peer.get("bt") or "").upper()

    def on_hb(self, msg):
        msg["time"] = time.monotonic()
        if not self.hb or self.hb.get("hp") != msg.get("hp"):
            log(f"phone: headphones {'on it' if msg.get('hp') else 'not on it'} ({msg.get('hpname', '?')})")
        self.hb = msg
        self.streamer.bt = bool(msg.get("bta"))
        self.send_ack()

    def send_ack(self):
        # "headphones": which ones the computer shares, so the phone reports those if it has several.
        self.d.link.send({"t": "ack", "owner": self.owner, "streaming": self.streamer.running(),
                          "sent": self.streamer.sent, "prefer": self.prefer, "headphones": self.hp_mac}, via="net")

    def set_prefer(self, to):
        if to == "toggle":
            to = "phone" if self.prefer == "laptop" else "laptop"
        if to not in ("laptop", "phone"):
            return
        save_prefer(to)
        self.claim_since = None
        self.last_unlink_try = 0.0
        if to == self.prefer:
            return
        self.prefer = to
        log(f"switch: the {to} carries the audio now")
        if self.owner in ("laptop", "phone") and self.owner != to:
            # What plays now should still play once it's over (see check_switch). One notification for the
            # whole switch: blueman's pop-ups are off while Tandem runs (Daemon.quiet_blueman).
            media = (self.hb or {}).get("media") or []
            self.switch = {"to": to, "started": time.monotonic(), "landed": None, "resumed": [],
                           "playing": desktop.mpris_playing(),
                           "phone_playing": [m["id"] for m in media if m.get("playing") and m.get("id")]}
        self.d.notify(f"Switching to the {to}", title="Headphones")
        self.send_ack()

    def set_owner(self, owner):
        if owner == self.owner:
            return
        self.owner = owner
        log("owner ->", owner)
        if not self.switch:  # a switch said "Switching to ..." already, and check_switch speaks up if it fails
            self.notify_owner()

    def notify_owner(self):
        self.d.notify({"phone": "On the phone · laptop audio goes through it",
                       "laptop": "On the laptop · phone audio comes through it",
                       None: "Off · nothing shared"}[self.owner], title="Headphones")

    def check_switch(self, now):
        sw = self.switch
        if not sw:
            return
        if not sw["landed"]:
            # On the laptop it has landed once the phone's audio comes here too (or can't).
            if self.owner == sw["to"] and (sw["to"] == "phone" or self.phone_linked or not self.phone_hp_linked
                                           or self.phone_needs_pairing):
                sw["landed"] = now
                log(f"switch: on the {sw['to']} after {now - sw['started']:.1f} s")
            elif now - sw["started"] > SWITCH_TIMEOUT or self.prefer != sw["to"]:
                log(f"switch to the {sw['to']} didn't land: on {self.owner or 'neither'}")
                self.end_switch()
                if self.prefer == sw["to"]:
                    self.notify_owner()
            return
        due = [t for t in RESUME_AFTER if now - sw["landed"] >= t and t not in sw["resumed"]]
        if due:
            sw["resumed"] += due
            self.d.bg("resume", self.resume, sw["playing"], sw["phone_playing"])
        if len(sw["resumed"]) == len(RESUME_AFTER):
            self.end_switch()

    def end_switch(self):
        self.switch = None

    def resume(self, players, phone_players):
        """Plays again what a switch paused: only what was playing when it started."""
        resumed = desktop.resume(players)
        hb = self.hb
        if hb and time.monotonic() - hb["time"] < HB_TIMEOUT and self.d.settings["media_controls"]:
            media = {m.get("id"): m for m in hb.get("media") or []}
            for pkg in phone_players:
                if pkg in media and not media[pkg].get("playing") \
                        and self.d.link.send({"t": "cmd", "op": "play", "id": pkg}, via="net"):
                    resumed.append(pkg)
        if resumed:
            log("switch: resumed " + ", ".join(resumed))
        return True, ""

    def off(self, devs):
        """Audio sharing is turned off (or not set up): give everything back."""
        self.streamer.stop()
        hp = devs.get(self.hp_mac) if self.hp_mac else None
        if hp and hp["blocked"] and self.blocked_by_us:
            bluez.set_blocked(hp["path"], False)
        self.blocked_by_us = False
        if self.loopback:
            self.loopback.terminate()
            self.loopback = None
        self.owner = None
        self.phone_linked = False

    # -- one pass of the policy
    def tick(self, devs, now):
        self.phone_linked = False  # set below only while the laptop owns and the phone streams to it
        net = bool(self.d.link.net)
        hb = self.hb if net and self.hb and now - self.hb["time"] < HB_TIMEOUT else None
        if hb and not self.same_headphones(hb, devs):
            hb = None  # other Bluetooth audio on the phone (a car, a speaker, an intercom): none of our business
        hp = devs.get(self.hp_mac) if self.hp_mac else None
        self.hp_battery = hp.get("battery") if hp and hp["connected"] else None
        if not self.d.settings["audio_share"] or not self.hp_mac:
            self.off(devs)
            return
        ph = devs.get(self.phone_mac) if self.phone_mac else None
        if not hb and self.hb and self.hb.get("hp") and now - self.hb["time"] < HB_LOST_GRACE \
                and self.same_headphones(self.hb, devs):
            hb = self.hb  # quiet, but it last said it has them: hold on
            if not self.hb.get("quiet_logged"):
                self.hb["quiet_logged"] = True
                log(f"phone went quiet; still treating it as the owner for {HB_LOST_GRACE:.0f} s")
        phone_active = bool(hb and hb.get("hp"))  # the headphones are the phone's audio output
        self.phone_hp_linked = bool(hb and hb.get("hp_linked", hb.get("hp")))
        if self.prefer == "laptop":
            laptop_has = bool(hp and hp["connected"] and not hp["blocked"])
            if laptop_has or self.claim_since is None:
                self.claim_since = now
            # The laptop takes the headphones even while the phone plays to them; it only lets the phone
            # carry on if it can't reach them.
            phone_owns = phone_active and now - self.claim_since > CLAIM_TIMEOUT
            pulled = False
        else:
            # On the phone, and its audio went to the laptop's audio link while its headphones stayed
            # connected. That's the Bluetooth link between the two dropping and coming back: BlueZ reconnects
            # the phone's audio link by itself and Android plays to the newest device. Taking the headphones
            # for it and switching back moved them every time the link blipped (2026-10-05, every 20-40 s
            # for minutes). The phone hasn't let go: keep them there and drop that audio link again.
            pulled = (self.owner == "phone" and not phone_active and self.phone_hp_linked
                      and bool(ph and (ph["transport"] or now < self.pulled_until)))
            phone_owns = phone_active or pulled

        if phone_owns:
            self.set_owner("phone")
            if pulled and ph["transport"]:
                if now >= self.pulled_until:
                    log("phone's audio jumped to the laptop's link: dropping it, the headphones stay on the phone")
                    self.unlink_logged = True
                self.pulled_until = now + PULLED_GRACE
            self.unlink_phone(ph, now)
            self.hp_was_connected = False
            if self.streamer.ensure(hb.get("codec", "opus")):
                log("streaming laptop audio to the phone")
                self.sink_defaulted = False
                self.handover_at = now
            if not self.sink_defaulted:
                n = find_node(SINK_NAME)
                if n:
                    set_default_sink(n["id"])
                    self.sink_defaulted = True
                    self.handover_at = now
            # Drop the headphones once the laptop's streams have moved to the phone (HANDOVER_DELAY); if the
            # sink never shows up, after a few seconds anyway.
            if now - self.handover_at < (HANDOVER_DELAY if self.sink_defaulted else 3.0):
                return
            if hp and not hp["blocked"] and "connect-hp" in self.d.busy:
                # Blocking a device in the middle of Connect segfaults bluetoothd (5.85, seen twice).
                # Cancel the connect and block once it has returned.
                self.d.bg("disconnect-hp", bluez.dev_call, hp["path"], "Disconnect")
            elif hp and not hp["blocked"]:
                log("phone has the headphones: dropping the laptop's link")
                bluez.set_blocked(hp["path"], True)  # BlueZ disconnects and refuses reconnects
                self.blocked_by_us = True
            elif hp and hp["connected"]:
                self.d.bg("disconnect-hp", bluez.dev_call, hp["path"], "Disconnect")
            return

        # Switching to the laptop: keep laptop audio going through the phone until the laptop has them.
        claiming = phone_active and self.prefer == "laptop"
        if self.streamer.running() and not claiming:
            log("phone let go of the headphones: stopping the stream")
            self.streamer.stop()
        if hp and hp["blocked"]:
            log("unblocking the headphones")
            self.set_owner(None)
            bluez.set_blocked(hp["path"], False)
            self.blocked_by_us = False
            self.reconnect_until = now + RECONNECT_WINDOW
            return  # BlueZ re-probes the device; pick up next tick

        if not hp or not hp["connected"]:
            self.set_owner(None)
            self.hp_was_connected = False
            # After unblocking, or while switched to the laptop and the headphones are on (the phone says so).
            claim = self.prefer == "laptop" and self.phone_hp_linked
            if hp and (now < self.reconnect_until or claim) and now - self.last_hp_try > 6:
                self.last_hp_try = now
                self.d.bg("connect-hp", bluez.dev_call, hp["path"], "Connect")
            self.unlink_phone(ph, now)
            return

        # The laptop has the headphones.
        self.set_owner("laptop")
        if not self.hp_was_connected:
            self.hp_was_connected = True
            # At power-on the phone may still be claiming them; after a switch to the laptop, link it at once.
            self.last_phone_try = now - PHONE_RETRY + (0 if self.prefer == "laptop" else PHONE_LINK_DELAY)
            self.phone_fails = 0  # a new session: start with short retries again
            self.reconnect_until = 0.0
            self.default_to_headphones()
            self.handover_at = time.monotonic()
        elif now - self.last_sink_check > 5:
            self.last_sink_check = now
            # WirePlumber restarting rebuilds the sink without the headphones disconnecting, and the default
            # falls back to the speakers. Pick the headphones again whenever their sink is new.
            n = bt_node(self.hp_mac, ("Audio/Sink",))
            if n and n["id"] != self.hp_sink_id:
                self.hp_sink_id = n["id"]
                if default_sink_name() != n["name"]:
                    log("headphones' sink came back: making it the default again")
                    set_default_sink(n["id"])
        if self.streamer.running() and now - self.handover_at >= HANDOVER_DELAY:
            # Only once the streams have followed the default to the headphones (see HANDOVER_DELAY).
            log("the laptop has the headphones: stopping the stream to the phone")
            self.streamer.stop()
        if self.phone_needs_pairing and ph and (ph["connected"] or not ph["paired"]):
            self.phone_needs_pairing = False  # it reconnected, or was removed to be paired again
            self.phone_fails = 0
        if ph and ph["paired"] and self.prefer == "phone" and self.phone_hp_linked:
            # Switched to the phone and it's connected to the headphones, but its audio comes here. Drop the
            # laptop's audio link: Android falls back to the headphones, the app reports them, and the branch
            # above hands over.
            if ph["transport"] and now - self.last_unlink_try > UNLINK_RETRY:
                self.last_unlink_try = now
                log("switching to the phone: dropping the laptop's audio link to it")
                self.d.bg("unlink-phone-a2dp", bluez.dev_call, ph["path"], "DisconnectProfile", "s", bluez.A2DP_SOURCE)
        elif ph and ph["paired"] and not self.phone_needs_pairing:
            linked = ph["transport"]
            retry = min(PHONE_RETRY * 2 ** self.phone_fails, PHONE_RETRY_MAX)
            if not linked and now - self.last_phone_try > retry:
                self.last_phone_try = now
                self.phone_linked_by_us = True
                self.d.bg("link-phone", self.link_phone, ph["path"])
            if linked:
                node = bt_node(self.phone_mac, ("Stream/Output/Audio", "Audio/Source"))
                if node:
                    self.ensure_phone_playback(node)
            self.phone_linked = bool(linked)

    def default_to_headphones(self):
        for _ in range(10):
            n = bt_node(self.hp_mac, ("Audio/Sink",))
            if n:
                self.hp_sink_id = n["id"]
                if default_sink_name() != n["name"]:
                    set_default_sink(n["id"])
                return
            time.sleep(0.3)

    def link_phone(self, path):
        ok, msg = bluez.dev_call(path, "ConnectProfile", "s", bluez.A2DP_SOURCE)
        if not ok:
            self.phone_fails += 1
            if any(e in msg for e in PHONE_AUTH_ERRORS):
                self.phone_needs_pairing = True
                log("the phone no longer has the laptop's pairing key: not retrying until they're paired again")
                self.d.notify("The phone forgot this computer. Remove it in Bluetooth settings and pair again "
                              "to hear phone audio here.", timeout=15000, title="Headphones")
        else:
            self.phone_fails = 0
            log("phone linked: its audio now plays through the laptop")
            # No hands-free link to drop: the laptop doesn't offer the hands-free-unit role (see
            # wireplumber/51-bluez-no-handsfree-unit.conf). Dropping one made Android move all the phone's
            # audio back to the headphones.
        return ok, msg

    def unlink_phone(self, ph, now):
        """The laptop carries the phone's audio only while it has the headphones. Any other audio link from
        the phone goes, whoever made it: after the Bluetooth link between the two drops (it does, every
        few minutes on some adapters), BlueZ connects the phone's audio link again by itself, and Android
        then plays to the laptop, its newest device, instead of whatever it was playing to."""
        if self.loopback:
            self.loopback.terminate()
            self.loopback = None
        # Only the audio link: the Bluetooth link itself also carries Tandem's messages.
        if ph and ph["transport"]:
            if not self.unlink_logged:
                self.unlink_logged = True
                log("unlinking the phone's audio from the laptop" if self.phone_linked_by_us else
                    "the phone's audio link came to the laptop by itself: dropping it")
            if now - self.last_unlink_try > 2.0:
                self.last_unlink_try = now
                self.d.bg("unlink-phone", bluez.dev_call, ph["path"], "DisconnectProfile", "s", bluez.A2DP_SOURCE)
        else:
            self.phone_linked_by_us = False
            self.unlink_logged = False

    def ensure_phone_playback(self, node):
        # PipeWire plays a phone's A2DP stream straight to the default sink unless
        # bluez5.media-source-role=input; in that case loop it back ourselves.
        if node["class"] == "Audio/Source" and not (self.loopback and self.loopback.poll() is None):
            self.loopback = subprocess.Popen(
                ["pw-loopback", "-n", "tandem-phone", "--capture-props",
                 "target.object=%s node.target=%s" % (node["name"], node["name"])],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self):
        self.streamer.stop()
        if self.loopback:
            self.loopback.terminate()
        cleanup(self.hp_mac)

    def state(self):
        hb = self.hb if self.hb and time.monotonic() - self.hb["time"] < HB_TIMEOUT else None
        st = {"owner": self.owner, "phone_has_headphones": bool(hb and hb.get("hp")),
              "streaming": self.streamer.running(), "packets_sent": self.streamer.sent,
              "packets_sent_net": self.streamer.net_path.sent, "packets_sent_bt": self.streamer.bt_path.sent,
              "phone_linked": self.phone_linked, "phone_mac": self.phone_mac, "prefer": self.prefer,
              "phone_hp_linked": self.phone_hp_linked, "headphones": self.hp_mac,
              "headphones_name": self.d.peer.get("headphones_name", "")}
        if hb:
            st["phone_media"] = hb.get("media", [])
            st["phone_volume"] = hb.get("volume")
            st["phone_media_access"] = hb.get("media_access", True)
        return st


def load_prefer():
    try:
        with open(PREFER) as f:
            v = f.read().strip()
        return v if v in ("laptop", "phone") else "phone"
    except OSError:
        return "phone"


def save_prefer(v):
    try:
        os.makedirs(os.path.dirname(PREFER), exist_ok=True)
        with open(PREFER + ".tmp", "w") as f:
            f.write(v + "\n")
        os.replace(PREFER + ".tmp", PREFER)
    except OSError as e:
        log("can't save the switch setting:", e)


def cleanup(hp_mac):
    if not hp_mac:
        return
    dev = (bluez.devices() or {}).get(hp_mac)
    if dev and dev["blocked"]:
        bluez.set_blocked(dev["path"], False)
        log("unblocked the headphones")
