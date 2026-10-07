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
from tandemd.link import encode, recv_frame, recv_header  # noqa: E402

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
        h, p = recv_frame(s, {"clip": 1 << 26, "file": 1 << 26})
        if h.get("t") == t:
            return h, p
    raise TimeoutError(t)


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
               WAYLAND_DISPLAY="", DISPLAY="")
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

        # phone -> computer file over a bulk connection
        b, h = connect(fp, role="bulk")
        data = os.urandom(300_000)
        send(b, {"t": "file", "id": "f1", "name": "../evil/photo.jpg", "mime": "image/jpeg", "kind": "file"}, data)
        ok = recv_header(b)
        b.close()
        check("a pushed file is acknowledged", ok.get("t") == "file-ok")
        time.sleep(0.5)
        got = os.path.join(dl, "photo.jpg")
        check("…saved under its plain name in the files folder", os.path.exists(got) and open(got, "rb").read() == data)

        # computer -> phone file: offer, the phone pulls it
        src = os.path.join(tmp, "notes.txt")
        with open(src, "w") as f:
            f.write("hello phone\n" * 1000)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        c.sendto(json.dumps({"op": "send-files", "paths": [src]}).encode(), os.path.join(run, "tandem.ctl"))
        offer, _ = read_until(s, "file-offer")
        check("`tandem send` offers the file", offer.get("name") == "notes.txt" and offer.get("size") == 12000)
        b, h = connect(fp, role="bulk", extra={"bulk": offer["id"]})
        fh, payload = recv_frame(b, {"file": 1 << 20})
        send(b, {"t": "file-ok", "id": offer["id"]})
        b.close()
        check("…and streams it when the phone asks", payload == open(src, "rb").read())

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


if __name__ == "__main__":
    main()
