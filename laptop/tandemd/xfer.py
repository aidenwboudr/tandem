"""Big messages over whichever link is up: files, clipboard images, app icons.

A payload bigger than INLINE_MAX (and every file) goes as a transfer: numbered chunks (`x`) on the network
link when it's up, else on Bluetooth, and the receiver says how much it has so far (`x-ack`). When the link
changes or drops mid-way, the sender carries on from there on whichever link is up then. The receiver
rebuilds the original message, checks it against its SHA-256, and hands it on as if it had come in one
frame. See docs/PROTOCOL.md.

On Bluetooth a transfer gives way to sound. That link shares the radio with the headphones (and with laptop
audio going to the phone), and a transfer on it made the sound cut out. So while this device's radio carries
audio the sender holds its chunks, and the receiver tells the sender to wait (`wait` in its acks) while its
own does.
"""
import hashlib
import json
import os
import threading
import time

from .config import log

INLINE_MAX = 64 * 1024  # payloads up to this go as one frame
CHUNK_MAX = 64 * 1024
CHUNK = {"net": 64 * 1024, "bt": 16 * 1024}
# Bytes in flight before an ack. Bluetooth's is small: frames queued behind it (heartbeats, audio) wait.
WINDOW = {"net": 1 << 20, "bt": 64 * 1024}
GIVE_UP = 60.0  # no link at all for this long: the sender gives up (the feature tries again later)
STALL = 15.0  # nothing back for this long with chunks out: send again from what the receiver has
REACK = 5.0  # an idle receiver repeats where it is this often, in case an ack was lost with its link
PARTIAL_TTL = 3600.0
MAX_SIZE = {"clip": 64 << 20, "art": 1 << 20, "notif-icon": 1 << 20, "file": 1 << 40}
AS_FILE = {"file"}  # handed on as the path of the received file; the rest as bytes


def sha256(data=None, path=None):
    h = hashlib.sha256()
    if path is None:
        h.update(data)
    else:
        with open(path, "rb") as f:
            for block in iter(lambda: f.read(1 << 20), b""):
                h.update(block)
    return h.hexdigest()


class _Out:
    def __init__(self, xid, header, size, sha, read, wanted):
        self.xid, self.header, self.size, self.sha, self.read, self.wanted = xid, header, size, sha, read, wanted
        self.acked = 0  # the receiver has everything before this
        self.next = 0  # the next byte to send
        self.sent_first = False  # chunk 0 (with the message's header) went on the current link
        self.wait = False  # the receiver asked us to hold off (its radio is busy with audio)
        self.conn = None  # the link the last chunk went on
        self.result = None  # True once the receiver has it all, or why not
        self.state = "sending"
        self.progress_at = time.monotonic()
        self.cv = threading.Condition()


class _In:
    def __init__(self, xid, header, size, sha, path):
        self.xid, self.header, self.size, self.sha, self.path = xid, header, size, sha, path
        self.fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o600)
        self.have = 0  # everything before this is here
        self.ahead = {}  # start -> end of chunks that came past `have` (they can, after a switch of link)
        self.seen = self.acked_at = time.monotonic()
        self.wait = False


class Xfer:
    def __init__(self, link, folder):
        self.link, self.folder = link, folder
        self.radio_busy = lambda: False  # set by the daemon: is this device's radio carrying audio?
        self.out = {}  # xid -> _Out
        self.inc = {}  # xid -> _In
        self.done = {}  # xid -> (when, size, finished): what came in, so a resent chunk isn't taken twice
        self.lock = threading.Lock()
        os.makedirs(folder, mode=0o700, exist_ok=True)
        for name in os.listdir(folder):  # partials don't outlive the daemon
            try:
                os.unlink(os.path.join(folder, name))
            except OSError:
                pass
        threading.Thread(target=self._housekeeping, name="tandem-xfer", daemon=True).start()

    # ------------------------------------------------------------ sending
    def send(self, header, data=None, path=None, wanted=None):
        """Sends one message with a big payload (bytes, or the file at `path`) and waits until the other side
        has all of it. False if it didn't get there: no link for GIVE_UP seconds, refused, or no longer
        `wanted()`. Run it on a thread that can wait (minutes, over Bluetooth)."""
        f = open(path, "rb") if path is not None else None
        try:
            if f:
                size = os.fstat(f.fileno()).st_size

                def read(off, n):
                    f.seek(off)
                    return f.read(n)
            else:
                size = len(data)

                def read(off, n):
                    return data[off:off + n]
            sha = sha256(data, path)
            # The same message gets the same id, so sending it again carries on where the last try stopped.
            xid = hashlib.sha256((json.dumps(header, sort_keys=True) + sha).encode()).hexdigest()[:32]
            o = _Out(xid, dict(header), size, sha, read, wanted)
            with self.lock:
                if xid in self.out:
                    return False  # already on its way
                self.out[xid] = o
            try:
                ok = self._run(o)
            finally:
                with self.lock:
                    self.out.pop(xid, None)
            name = header.get("name") or header.get("t")
            if ok is True:
                log(f"xfer: sent {name} ({size} bytes)")
            else:
                log(f"xfer: {name} didn't go: {ok}")
            return ok is True
        finally:
            if f:
                f.close()

    def _run(self, o):
        lost_at = None
        while True:
            with o.cv:
                if o.result is not None:
                    return o.result
            if o.wanted and not o.wanted():
                self.link.send({"t": "x-no", "x": o.xid, "error": "cancelled"})
                return "cancelled"
            net, bt = self.link.net, self.link.bt
            conn, via = (net, "net") if net else (bt, "bt") if bt else (None, None)
            now = time.monotonic()
            if conn is None:
                o.state = "waits for the connection"
                lost_at = lost_at or now
                if now - lost_at > GIVE_UP:
                    return "not connected"
                self._nap(o)
                continue
            lost_at = None
            if conn is not o.conn:  # what went on the old link may never arrive: from what it has, here
                o.conn = conn
                o.next, o.sent_first = o.acked, o.acked > 0
                o.progress_at = now
            if via == "bt" and (o.wait or self.radio_busy()):
                o.state = "waits for the audio to stop"
                o.progress_at = now
                self._nap(o)
                continue
            o.state = "sending"
            more = o.next < o.size or not o.sent_first
            if more and o.next - o.acked < WINDOW[via]:
                n = min(CHUNK[via], o.size - o.next)
                h = {"t": "x", "x": o.xid, "i": o.next}
                if o.next == 0:
                    h.update(n=o.size, sha=o.sha, h=o.header)
                chunk = o.read(o.next, n)
                try:
                    conn.send(h, chunk)
                except OSError as e:
                    log(f"xfer: {via} send failed ({e}); dropping that connection")
                    self.link.drop(conn)
                    continue
                o.next += n
                o.sent_first = True
                continue
            if now - o.progress_at > STALL:
                o.next, o.sent_first = o.acked, o.acked > 0
                o.progress_at = now
                continue
            self._nap(o)

    @staticmethod
    def _nap(o):
        with o.cv:
            if o.result is None:
                o.cv.wait(0.5)

    def on_ack(self, h):
        xid = str(h.get("x") or "")
        o = self.out.get(xid)
        if h.get("t") == "x-no":
            if o:
                with o.cv:
                    o.result = str(h.get("error") or "refused")
                    o.cv.notify_all()
            with self.lock:
                p = self.inc.pop(xid, None)
            if p:  # the sender gave up on it
                self._discard(p)
            return
        if not o:
            return
        with o.cv:
            have = min(max(int(h.get("have") or 0), 0), o.size)
            if have < o.acked:  # it lost what it had: from there again
                o.next, o.sent_first = have, have > 0
            o.acked = have
            o.next = max(o.next, have)
            o.wait = bool(h.get("wait"))
            o.progress_at = time.monotonic()
            if h.get("done"):
                o.result = True
            o.cv.notify_all()

    # ------------------------------------------------------------ receiving
    def on_chunk(self, via, h, payload):
        """A chunk, on the thread reading that link."""
        xid, i = str(h.get("x") or ""), int(h.get("i") or 0)
        busy = via == "bt" and self.radio_busy()
        reply = finished = None
        with self.lock:
            p = self.inc.get(xid)
            if p is None:
                d = self.done.get(xid)
                meta = h.get("h") if isinstance(h.get("h"), dict) else {}
                size = int(h.get("n") or 0)
                if d:
                    if d[2]:  # done already: the sender missed that
                        reply = {"t": "x-ack", "x": xid, "have": d[1], "done": True}
                elif i != 0 or "n" not in h:
                    reply = {"t": "x-ack", "x": xid, "have": 0}  # we don't have it (any more): from the start
                elif not h.get("sha") or not 0 <= size <= MAX_SIZE.get(meta.get("t"), -1):
                    reply = {"t": "x-no", "x": xid, "error": f"won't take {size} bytes of {meta.get('t')!r}"}
                else:
                    p = self.inc[xid] = _In(xid, meta, size, str(h["sha"]), os.path.join(self.folder, xid))
            if p is not None:
                end = i + len(payload)
                if 0 <= i and end <= p.size:
                    if payload:
                        os.pwrite(p.fd, payload, i)
                    if i <= p.have:
                        p.have = max(p.have, end)
                    else:
                        p.ahead[i] = max(p.ahead.get(i, 0), end)
                    while True:
                        caught = [s for s in p.ahead if s <= p.have]
                        if not caught:
                            break
                        for s in caught:
                            p.have = max(p.have, p.ahead.pop(s))
                p.seen = time.monotonic()
                if p.have >= p.size:
                    finished = self.inc.pop(xid)
                    self.done[xid] = (time.monotonic(), p.size, False)
                else:
                    p.wait = busy
                    p.acked_at = p.seen
                    reply = {"t": "x-ack", "x": xid, "have": p.have, "wait": busy}
        if reply:
            self.link.send(reply)
        if finished:
            self._finish(via, finished)

    def _finish(self, via, p):
        os.close(p.fd)
        if sha256(path=p.path) != p.sha:
            log(f"xfer: {p.header.get('name') or p.header.get('t')} came in damaged; asking for it again")
            os.unlink(p.path)
            with self.lock:
                self.done.pop(p.xid, None)
            self.link.send({"t": "x-ack", "x": p.xid, "have": 0})
            return
        with self.lock:
            self.done[p.xid] = (time.monotonic(), p.size, True)
        self.link.send({"t": "x-ack", "x": p.xid, "have": p.size, "done": True})
        if p.header.get("t") in AS_FILE:
            payload = p.path  # the feature moves it where it goes
        else:
            with open(p.path, "rb") as f:
                payload = f.read()
            os.unlink(p.path)
        self.link.post(via, p.header, payload)

    def _discard(self, p):
        try:
            os.close(p.fd)
            os.unlink(p.path)
        except OSError:
            pass

    def _housekeeping(self):
        while True:
            time.sleep(1)
            now = time.monotonic()
            with self.lock:
                parts = list(self.inc.values())
                for xid in [k for k, v in self.done.items() if now - v[0] > PARTIAL_TTL]:
                    del self.done[xid]
            if not parts:
                continue
            busy = self.radio_busy() if any(p.wait for p in parts) else False
            for p in parts:
                if now - p.seen > PARTIAL_TTL:
                    with self.lock:
                        self.inc.pop(p.xid, None)
                    self._discard(p)
                elif (p.wait and not busy) or now - p.acked_at > REACK:
                    p.wait = p.wait and busy
                    p.acked_at = now
                    self.link.send({"t": "x-ack", "x": p.xid, "have": p.have, "wait": p.wait})

    def state(self):
        return [{"name": o.header.get("name") or o.header.get("t"), "size": o.size, "sent": o.acked,
                 "state": o.state} for o in list(self.out.values())]
