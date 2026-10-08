#!/usr/bin/python3
"""End-to-end test of the computer's daemon over the network, with a scripted phone (no Bluetooth needed).

Runs the daemon in a scratch config dir, plants a paired phone record (what Bluetooth pairing would have
stored), then talks to it the way the app does and checks what comes back.

    laptop/tests/fake_phone.py
"""
import hashlib
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from tandemd.link import encode, recv_frame  # noqa: E402

PORT = 47911
fails = []


def check(what, ok):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)


def connect(fp, role="control", token="tok", extra=None):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # pinned by hand below, like the app does
    s = ctx.wrap_socket(socket.create_connection(("127.0.0.1", PORT), timeout=5))
    got = hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()
    assert got == fp, "certificate doesn't match the pinned fingerprint"
    s.sendall(encode(dict({"t": "auth", "id": "phone1", "token": token, "role": role}, **(extra or {}))))
    h, _ = recv_frame(s)
    return s, h


def send(s, h, payload=b""):
    s.sendall(encode(h, payload) + payload)


def read_until(s, t, timeout=5):
    s.settimeout(timeout)
    end = time.time() + timeout
    while time.time() < end:
        h, p = recv_frame(s, {"clip": 1 << 16, "x": 1 << 16})
        if h.get("t") == t:
            return h, p
    raise TimeoutError(t)


def phone_xfer(s, header, data, chunk=16384, sha=None):
    """Sends a message as a transfer, the way the app does; the computer's last ack."""
    sha = sha or hashlib.sha256(data).hexdigest()
    xid = hashlib.sha256(sha.encode() + header["id"].encode()).hexdigest()[:32]
    for off in range(0, len(data), chunk):
        h = {"t": "x", "x": xid, "i": off}
        if off == 0:
            h.update(n=len(data), sha=sha, h=header)
        send(s, h, data[off:off + chunk])
    while True:  # an ack per chunk; then done, or "from the start" (have 0) if it came in damaged
        h, _ = read_until(s, "x-ack")
        if h.get("x") == xid and (h.get("done") or h.get("have") == 0):
            return h


def phone_take(s):
    """Takes one transfer from the computer, acking each chunk; (its header, the payload)."""
    buf, meta = bytearray(), None
    while True:
        h, p = read_until(s, "x")
        if h["i"] == 0:
            meta, size, sha = h["h"], h["n"], h["sha"]
            buf = bytearray(size)
        buf[h["i"]:h["i"] + len(p)] = p
        end = h["i"] + len(p)
        done = end >= size
        send(s, {"t": "x-ack", "x": h["x"], "have": end, "done": done})
        if done:
            check("…and it matches its checksum", hashlib.sha256(buf).hexdigest() == sha)
            return meta, bytes(buf)


def main():
    tmp = tempfile.mkdtemp(prefix="tandem-test-")
    cfgdir = os.path.join(tmp, "config")
    run = os.path.join(tmp, "run")
    os.makedirs(cfgdir)
    os.makedirs(run)
    dl = os.path.join(tmp, "Downloads")
    with open(os.path.join(cfgdir, "config"), "w") as f:
        f.write(f"PORT={PORT}\nNOTIFY=0\nFILES_DIR={dl}\n")
    with open(os.path.join(cfgdir, "phone.json"), "w") as f:
        json.dump({"id": "phone1", "name": "Test Phone", "token": "tok", "bt": "AA:BB:CC:DD:EE:FF"}, f)
    env = dict(os.environ, TANDEM_CONFIG=os.path.join(cfgdir, "config"), XDG_RUNTIME_DIR=run,
               XDG_CACHE_HOME=os.path.join(tmp, "cache"), WAYLAND_DISPLAY="", DISPLAY="")
    log = open(os.path.join(tmp, "daemon.log"), "w")
    d = subprocess.Popen([os.path.join(os.path.dirname(HERE), "tandem"), "run"], env=env, stdout=log,
                         stderr=subprocess.STDOUT)
    try:
        time.sleep(2.5)
        check("daemon is running", d.poll() is None)
        from tandemd import config
        with open(os.path.join(cfgdir, "cert.pem")) as f:
            fp = hashlib.sha256(ssl.PEM_cert_to_DER_cert(f.read())).hexdigest()

        s, h = connect(fp, token="wrong")
        check("a wrong token is refused", h.get("ok") is False)
        s.close()

        # Nothing big from anyone before they've authenticated (it would sit in memory).
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
        x = ctx.wrap_socket(socket.create_connection(("127.0.0.1", PORT), timeout=5))
        x.sendall(encode({"t": "file"}, b"\0" * 1_000_000))
        try:
            closed = x.recv(1) == b""
        except OSError:
            closed = True
        x.close()
        check("a payload before auth gets the connection closed", closed)

        s, h = connect(fp)
        check("the paired phone is accepted", h.get("ok") is True)
        sh, _ = read_until(s, "settings")
        check("the computer sends its settings on connect", sh.get("values", {}).get("files") is True)
        ah, _ = read_until(s, "addrs")
        check("…and its addresses", isinstance(ah.get("addrs"), list))

        values = dict(config.DEFAULTS, notif_mirror=True, battery_low=20)
        send(s, {"t": "settings", "values": values, "rev": int(time.time() * 1000)})
        send(s, {"t": "ping"})
        read_until(s, "pong")
        saved = json.load(open(os.path.join(cfgdir, "settings.json")))["values"]
        check("newer settings from the phone are taken", saved["notif_mirror"] is True and saved["battery_low"] == 20)

        send(s, {"t": "hb", "hp": False, "hp_linked": False, "codec": "opus", "media": []})
        ack, _ = read_until(s, "ack")
        check("a heartbeat gets an ack", "prefer" in ack)

        send(s, {"t": "status", "battery": {"level": 42, "charging": False}})
        send(s, {"t": "notif", "key": "k1", "pkg": "org.example", "app": "Example", "title": "Hi", "text": "there"})
        send(s, {"t": "ping"})
        read_until(s, "pong")
        time.sleep(1.5)
        st = json.load(open(os.path.join(run, "tandem.json")))
        check("the phone's battery shows in the state", (st.get("phone_battery") or {}).get("level") == 42)
        check("the state says the network link is up", st["link"]["net"] is True)

        # phone -> computer file, as a transfer on the control connection (what it does over Bluetooth too)
        data = os.urandom(300_000)
        done = phone_xfer(s, {"t": "file", "id": "f1", "name": "../evil/photo.jpg", "mime": "image/jpeg",
                              "kind": "file"}, data)
        check("a file sent in chunks is acknowledged", done.get("done") is True and done.get("have") == len(data))
        time.sleep(0.5)
        got = os.path.join(dl, "photo.jpg")
        check("…saved under its plain name in the files folder", os.path.exists(got) and open(got, "rb").read() == data)
        bad = phone_xfer(s, {"t": "file", "id": "f2", "name": "x.bin"}, os.urandom(70_000), sha="0" * 64)
        check("…a damaged one is asked for again", bad.get("have") == 0 and not bad.get("done"))

        # computer -> phone file, the same way
        src = os.path.join(tmp, "notes.txt")
        with open(src, "w") as f:
            f.write("hello phone\n" * 1000)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        c.sendto(json.dumps({"op": "send-files", "paths": [src]}).encode(), os.path.join(run, "tandem.ctl"))
        meta, payload = phone_take(s)
        check("`tandem send` sends the file in chunks", meta.get("t") == "file" and meta.get("name") == "notes.txt")
        check("…all of it", payload == open(src, "rb").read())

        # computer -> phone link, and remote keys (on only when the setting is)
        c.sendto(json.dumps({"op": "open", "url": "https://example.org"}).encode(), os.path.join(run, "tandem.ctl"))
        oh, _ = read_until(s, "open")
        check("`tandem open` sends the link", oh.get("url") == "https://example.org")
        c.sendto(json.dumps({"op": "set", "key": "remote_input", "value": True}).encode(), os.path.join(run, "tandem.ctl"))
        sh, _ = read_until(s, "settings")
        check("`tandem set` sends the new settings", sh["values"]["remote_input"] is True)
        c.sendto(json.dumps({"op": "key", "text": "hi"}).encode(), os.path.join(run, "tandem.ctl"))
        kh, _ = read_until(s, "key")
        check("`tandem type` sends keys once remote input is on", kh.get("text") == "hi")

        s.close()
        time.sleep(0.5)
        check("the daemon survives the phone going away", d.poll() is None)
    finally:
        d.terminate()
        d.wait(5)
        log.close()
        if fails:
            print(open(os.path.join(tmp, "daemon.log")).read())
    audio_paths()
    bt_reader()
    hub_fallback()
    transfers()
    print(f"\n{len(fails)} failed" if fails else "\nall passed")
    sys.exit(1 if fails else 0)


def audio_paths():
    """Laptop audio goes over the network and Bluetooth at once: a stalled path must not hold up the other."""
    import threading
    from tandemd.headphones import PATH_QUEUE, AudioPath
    stall, got = threading.Event(), []
    stuck = AudioPath("stuck", lambda h, p: stall.wait())
    fine = AudioPath("fine", lambda h, p: got.append(h["s"]) or True)
    t0 = time.monotonic()
    for seq in range(50):  # frames are 20 ms apart; 2 ms here
        stuck.put({"s": seq}, b"")
        fine.put({"s": seq}, b"")
        time.sleep(0.002)
    check("a stalled audio path doesn't block the sender", time.monotonic() - t0 < 1)
    deadline = time.monotonic() + 2
    while len(got) < 50 and time.monotonic() < deadline:
        time.sleep(0.01)
    check("…and the other path sends every frame", got == list(range(50)))
    check("…while the stalled one keeps only the newest frames", len(stuck.q) <= PATH_QUEUE
          and stuck.q[-1][0]["s"] == 49)
    stall.set()
    import array
    from tandemd.headphones import audible
    check("silence stays off Bluetooth", not audible(bytes(3840)) and not audible(array.array("h", [3] * 1920).tobytes()))
    check("…sound in either channel goes on it", audible(array.array("h", [0, 400] * 960).tobytes())
          and audible(array.array("h", [400, 0] * 960).tobytes()))



def bt_reader():
    """The Bluetooth reader must give up once its connection is closed, even if the socket says it's readable
    and then blocks in recv (an RFCOMM socket did, after the phone app restarted mid-send)."""
    import threading
    from tandemd.link import recv_exact
    a, b = socket.socketpair()
    b.send(b"x")  # `a` stays readable

    class Wedged:
        def fileno(self):
            return a.fileno()

        def recv(self, n, flags=0):
            if flags & socket.MSG_DONTWAIT:
                raise BlockingIOError
            threading.Event().wait()  # a blocking read that never returns

    out = []

    def read():
        try:
            recv_exact(Wedged(), 4, alive=lambda: False)
        except ConnectionError:
            out.append("gave up")

    t = threading.Thread(target=read, daemon=True)
    t.start()
    t.join(2)
    check("a closed Bluetooth link's reader doesn't hang in recv", out == ["gave up"])
    a.close()
    b.close()


def hub_fallback():
    """Without the network the phone stays the hub, and the laptop takes over only while it alone plays."""
    from types import SimpleNamespace
    from tandemd.headphones import NET_FALLBACK, Headphones
    link = SimpleNamespace(net=None)
    st = SimpleNamespace(phone=False, laptop=False)
    hp = SimpleNamespace(d=SimpleNamespace(link=link, notify=lambda *a, **k: None), prefer="phone", hub="phone",
                         net_was=None, net_since=0.0, fallback=False,
                         phone_playing=lambda: st.phone, laptop_alone_plays=lambda now: st.laptop and not st.phone)
    t = [0.0]

    def at(dt, net, phone=False, laptop=False):
        t[0] += dt
        link.net = object() if net else None
        st.phone, st.laptop = phone, laptop
        Headphones.pick_hub(hp, t[0])
        return hp.hub

    w = NET_FALLBACK + 1
    check("with the network the phone is the hub, whatever plays", at(0, True, laptop=True) == "phone")
    check("…a short blip changes nothing", at(1, False, laptop=True) == "phone")
    check("no network and nothing playing: still the phone", at(w, False) == "phone")
    check("…the phone playing: the phone", at(1, False, phone=True, laptop=True) == "phone")
    check("…only the laptop playing: the laptop", at(1, False, laptop=True) == "laptop")
    check("…which keeps them when it goes quiet", at(1, False) == "laptop")
    check("…until the phone plays", at(1, False, phone=True) == "phone")
    at(1, False, laptop=True)
    check("back on the network: the laptop still, for a moment", at(1, True, laptop=True) == "laptop")
    check("…then the phone", at(w, True, laptop=True) == "phone")
    hp.prefer = hp.hub = "laptop"
    hp.fallback = False
    check("the laptop as hub doesn't care", at(w, False, phone=True) == "laptop")


class _Pipe:
    """One link between two in-process Xfers: frames arrive in order on a thread, unless it's dropped."""

    def __init__(self, via, to):
        import queue
        import threading
        self.via, self.to, self.q, self.dead, self.frames = via, to, queue.Queue(), False, 0
        threading.Thread(target=self._run, daemon=True).start()

    def send(self, h, p=b""):
        if self.dead:
            raise OSError("closed")
        self.frames += h.get("t") == "x"
        self.q.put((h, p))

    def _run(self):
        while True:
            h, p = self.q.get()
            if self.dead:
                continue  # in flight when it dropped: lost
            time.sleep(0.002)  # a slow link, so there's time to break it mid-way
            if h["t"] == "x":
                self.to.xfer.on_chunk(self.via, h, p)
            else:
                self.to.xfer.on_ack(h)


class _Side:
    def __init__(self, folder):
        from tandemd.xfer import Xfer
        self.net = self.bt = None
        self.got = []
        self.xfer = Xfer(self, folder)

    def send(self, h, payload=b""):
        for c in (self.net, self.bt):
            if c:
                try:
                    c.send(h, payload)
                    return True
                except OSError:
                    pass
        return False

    def drop(self, c):
        c.dead = True
        if c is self.net:
            self.net = None
        if c is self.bt:
            self.bt = None

    def post(self, via, h, p):
        self.got.append((via, h, p))


def transfers():
    """Big messages in chunks over whichever link is up (xfer.py)."""
    import threading
    from tandemd import xfer
    xfer.GIVE_UP, xfer.STALL = 2.0, 1.0
    tmp = tempfile.mkdtemp(prefix="tandem-xfer-")
    a, b = _Side(os.path.join(tmp, "a")), _Side(os.path.join(tmp, "b"))

    def link(via):
        ab, ba = _Pipe(via, b), _Pipe(via, a)
        setattr(a, via, ab)
        setattr(b, via, ba)
        return ab, ba

    def cut(via):
        for side in (a, b):
            c = getattr(side, via)
            if c:
                side.drop(c)

    def wait_for(cond, t=10):
        end = time.monotonic() + t
        while not cond() and time.monotonic() < end:
            time.sleep(0.02)
        return cond()

    def sending(header, data, **kw):
        out = []
        th = threading.Thread(target=lambda: out.append(a.xfer.send(header, data, **kw)), daemon=True)
        th.start()
        return th, out

    # Over Bluetooth, then the network comes up and Bluetooth drops mid-way: it carries on there, all of it.
    ab, _ = link("bt")
    data = os.urandom(400_000)
    th, out = sending({"t": "clip", "mime": "image/png", "hash": "h1"}, data)
    wait_for(lambda: ab.frames >= 5)
    link("net")
    cut("bt")
    th.join(10)
    check("a transfer carries on when the link changes mid-way", out == [True])
    check("…and arrives whole, once", [(h["t"], p) for _, h, p in b.got] == [("clip", data)])

    # Sound on the receiver's radio: Bluetooth chunks wait until it stops; the network doesn't.
    cut("net")
    ab, _ = link("bt")
    busy = [True]
    b.xfer.radio_busy = lambda: busy[0]
    b.got.clear()
    th, out = sending({"t": "clip", "mime": "image/png", "hash": "h2"}, os.urandom(300_000))
    time.sleep(1.5)
    held = ab.frames
    time.sleep(1.0)
    check("Bluetooth chunks wait while the receiver's radio plays audio", 0 < held == ab.frames and not out)
    busy[0] = False
    th.join(10)
    check("…and go once it stops", out == [True] and len(b.got) == 1)

    # ...and the sender's own radio.
    a.xfer.radio_busy = lambda: True
    th, out = sending({"t": "clip", "mime": "image/png", "hash": "h3"}, os.urandom(100_000))
    time.sleep(1.0)
    check("…or the sender's", not out and a.xfer.state()[0]["state"] == "waits for the audio to stop")
    link("net")
    th.join(10)
    check("…but not on the network", out == [True])
    a.xfer.radio_busy = lambda: False
    cut("net")

    # No longer wanted (a newer clipboard copy): it stops, and the receiver drops its part.
    b.got.clear()
    want = [True]
    a.xfer.radio_busy = lambda: True
    th, out = sending({"t": "clip", "mime": "image/png", "hash": "h4"}, os.urandom(200_000), wanted=lambda: want[0])
    time.sleep(0.3)
    a.xfer.radio_busy = lambda: False
    wait_for(lambda: b.xfer.inc)
    want[0] = False
    th.join(5)
    check("a transfer nobody wants any more stops", out == [False] and wait_for(lambda: not b.xfer.inc, 3))

    # The link goes away for good: the sender gives up; sent again later, it carries on where it stopped.
    data = os.urandom(600_000)
    before = ab.frames
    th, out = sending({"t": "file", "id": "f9", "name": "big.bin"}, data)
    wait_for(lambda: ab.frames - before >= 15)
    cut("bt")
    th.join(10)
    have = next(iter(b.xfer.inc.values())).have if b.xfer.inc else 0
    check("with no link the sender gives up", out == [False] and have > 0)
    ab, _ = link("bt")
    th, out = sending({"t": "file", "id": "f9", "name": "big.bin"}, data)
    th.join(15)
    path = b.got[-1][2] if b.got else ""
    check("…and sent again, it carries on from what arrived",
          out == [True] and ab.frames < 600_000 // 16384 and open(path, "rb").read() == data)

if __name__ == "__main__":
    main()
