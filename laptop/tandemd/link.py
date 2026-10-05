"""The link with the phone: Bluetooth (RFCOMM, the phone serves) for pairing and small messages, and TLS
over TCP (the laptop serves) for everything when the two share a network. See docs/PROTOCOL.md.

Messages from either transport are queued for the daemon's main thread (`events`), so features never run
concurrently with each other. Sending is thread-safe.
"""
import base64
import hmac
import json
import queue
import secrets
import socket
import ssl
import subprocess
import threading
import time

from . import sdp
from .config import PROTO_VERSION, SERVICE_UUID, log

HEADER_MAX = 64 * 1024
BT_MAX = 4 * 1024 * 1024  # payloads bigger than this wait for the network (Bluetooth does ~100-200 KB/s)
NET_IDLE = 90.0  # the phone pings every 25 s
BT_PING = 20.0  # we ping over Bluetooth; an idle RFCOMM link otherwise drops after a minute or so
PAIR_WAIT = 120.0  # how long the phone's "use Tandem with this computer?" prompt may take
DENY_HOLD = 3600.0  # after a "Deny" (or "paired with another computer"), leave that phone alone this long...
MISSED_HOLD = 120.0  # ...but ask again soon if nobody answered (`tandem pair` asks right away)
BT_RETRY_MIN, BT_RETRY_MAX = 15.0, 60.0
# Payload limits per message type (the rest carry none). Files on a bulk connection are streamed.
PAYLOAD_MAX = {"clip": 64 * 1024 * 1024, "art": 1 << 20, "notif-icon": 1 << 20, "file": BT_MAX}
NO_PAYLOAD = {}


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(min(n - len(buf), 1 << 16))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return bytes(buf)


def recv_header(sock):
    n = int.from_bytes(recv_exact(sock, 4), "big")
    if n <= 0 or n > HEADER_MAX:
        raise ValueError(f"header of {n} bytes")
    h = json.loads(recv_exact(sock, n))
    if not isinstance(h, dict):
        raise ValueError("header isn't an object")
    return h


def recv_frame(sock, limits=None):
    h = recv_header(sock)
    size = int(h.get("len") or 0)
    cap = (limits or PAYLOAD_MAX).get(h.get("t"), 0)
    if size < 0 or size > cap:
        raise ValueError(f"{h.get('t')}: payload of {size} bytes")
    return h, recv_exact(sock, size) if size else b""


def frame_header(header, size):
    """The bytes before a frame's payload (the length prefix and the JSON header)."""
    h = json.dumps(dict(header, len=size), separators=(",", ":")).encode()
    return len(h).to_bytes(4, "big") + h


def encode(header, payload=b""):
    return frame_header(header, len(payload))


class Conn:
    """One connection, with a lock so frames from different threads never interleave."""

    def __init__(self, sock, via, addr):
        self.sock, self.via, self.addr = sock, via, addr
        self.lock = threading.Lock()
        self.opened = time.monotonic()
        self.closed = False

    def send(self, header, payload=b""):
        with self.lock:
            self.sock.sendall(encode(header, payload))
            if payload:
                self.sock.sendall(payload)

    def close(self):
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def local_addresses():
    """This computer's IP addresses the phone could reach it on (LAN, Tailscale, ...)."""
    try:
        out = json.loads(subprocess.run(["ip", "-j", "addr", "show", "up"], capture_output=True, text=True,
                                        timeout=5).stdout)
    except Exception:
        return []
    v4, v6 = [], []
    for iface in out:
        name = iface.get("ifname", "")
        if name == "lo" or name.startswith(("docker", "br-", "veth", "virbr", "podman", "lxc", "vnet")):
            continue
        for a in iface.get("addr_info", []):
            ip = a.get("local", "")
            if a.get("scope") != "global" or not ip:
                continue
            if a.get("family") == "inet":
                v4.append(ip)
            elif a.get("family") == "inet6" and not a.get("temporary") and not a.get("deprecated"):
                v6.append(ip)
    return v4 + v6


class Link:
    def __init__(self, ident, peer, settings, port, notify, hooks):
        self.ident, self.peer, self.settings, self.port = ident, peer, settings, port
        self.notify = notify  # notify(text, title=...) for pairing prompts
        self.hooks = hooks  # {"bulk": fn(conn, auth_header)} runs on the connection's own thread
        self.bt = None  # Conn
        self.net = None  # Conn, role control
        self.audio = None  # Conn, role audio
        self.events = queue.Queue()
        self.wake_r, self.wake_w = socket.socketpair()
        self.wake_r.setblocking(False)
        self.stop = False
        self.bt_kick = threading.Event()
        self.denied = {}  # bt address -> monotonic time to leave it alone until
        self.channel = {}  # bt address -> RFCOMM channel (from SDP)
        self.addrs = local_addresses()
        self.bt_state = "idle"  # for `tandem status`: idle, looking, connecting, pairing, linked
        self.phone_ip = None

    # ------------------------------------------------------------ plumbing
    def start(self):
        for fn, name in ((self._bt_loop, "bt"), (self._tls_server, "tls"), (self._discovery, "disc"),
                         (self._addr_watch, "addrs"), (self._bt_keepalive, "bt-ping")):
            threading.Thread(target=fn, name="tandem-" + name, daemon=True).start()

    def post(self, via, header, payload=b""):
        self.events.put((via, header, payload))
        try:
            self.wake_w.send(b"\0")
        except OSError:
            pass

    def drain(self):
        try:
            while self.wake_r.recv(4096):
                pass
        except (BlockingIOError, OSError):
            pass
        out = []
        while True:
            try:
                out.append(self.events.get_nowait())
            except queue.Empty:
                return out

    def up(self):
        return {"bt": bool(self.bt), "net": bool(self.net)}

    def connected(self):
        return bool(self.bt or self.net)

    def send(self, header, payload=b"", via=None):
        """Sends over the network if it's up, else Bluetooth (small payloads only). False if neither could."""
        order = [via] if via else ["net", "bt"]
        for v in order:
            c = self.net if v == "net" else self.bt
            if not c or (v == "bt" and len(payload) > BT_MAX):
                continue
            try:
                c.send(header, payload)
                return True
            except OSError as e:
                log(f"link: {v} send failed ({e}); dropping that connection")
                self._drop(c)
        return False

    def _drop(self, c):
        c.close()
        if c is self.net:
            self.net = None
            self.post("link", {"t": "_down", "via": "net"})
        elif c is self.bt:
            self.bt = None
            self.post("link", {"t": "_down", "via": "bt"})
        elif c is self.audio:
            self.audio = None

    def unpair(self, tell=True):
        if tell:
            self.send({"t": "unpair"})
        for c in (self.net, self.bt, self.audio):
            if c:
                self._drop(c)
        bt = self.peer.get("bt")
        if bt:
            self.denied[bt] = time.monotonic() + DENY_HOLD  # don't ask to pair again right away
        self.peer.forget()
        self.channel.clear()

    # ------------------------------------------------------------ Bluetooth
    def _bt_loop(self):
        delay = BT_RETRY_MIN
        while not self.stop:
            ok = False
            try:
                ok = self._bt_once()
            except Exception as e:  # never let the loop die
                log("link: bluetooth:", repr(e))
            if ok:
                delay = BT_RETRY_MIN
            else:
                delay = min(delay * 1.5, BT_RETRY_MAX)
            self.bt_kick.wait(BT_RETRY_MIN if ok else delay)
            self.bt_kick.clear()

    def _bt_once(self):
        """One attempt: connect to the paired phone (or look for one to pair with) and serve the link
        until it drops. True if it got as far as a working link."""
        if not hasattr(socket, "AF_BLUETOOTH"):
            self.bt_state = "unsupported (this Python has no Bluetooth sockets)"
            return False
        now = time.monotonic()
        if self.peer and self.peer.get("bt"):
            cands = [(self.peer.get("bt"), self.peer.get("name"))]
            cands = [c for c in cands if self.denied.get(c[0], 0) < now]
        else:
            from .bluez import bonded_phones
            cands = [(a, n) for a, n in bonded_phones() if self.denied.get(a, 0) < now]
            self.bt_state = "looking" if cands else "no phone paired over Bluetooth"
        for addr, name in cands:
            ch = self.channel.get(addr)
            if ch is None:
                try:
                    ch = sdp.rfcomm_channel(addr, SERVICE_UUID)
                except OSError:
                    continue  # off or out of range
                if ch is None:
                    continue  # no Tandem app (or not running) on that phone
                self.channel[addr] = ch
            self.bt_state = "connecting"
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            s.settimeout(15)
            try:
                s.connect((addr, ch))
            except OSError as e:
                s.close()
                self.channel.pop(addr, None)  # the app may have restarted on another channel
                log(f"link: bluetooth connect to {name or addr} failed: {e}")
                continue
            conn = Conn(s, "bt", addr)
            try:
                if self._bt_handshake(conn, addr, name):
                    self._serve(conn)
                    return True
            except (OSError, ValueError) as e:
                log(f"link: bluetooth with {name or addr}: {e}")
            conn.close()
            if self.bt is conn:
                self._drop(conn)
        if self.bt_state in ("connecting", "pairing"):
            self.bt_state = "idle"
        return False

    def _bt_handshake(self, conn, addr, name):
        conn.sock.settimeout(15)
        conn.send({"t": "hello", "v": PROTO_VERSION, "id": self.ident.id, "name": self.ident.name,
                   "kind": "computer", "fp": self.ident.fp, "port": self.port, "addrs": self.addrs})
        h, _ = recv_frame(conn.sock, NO_PAYLOAD)
        if h.get("t") != "hello" or h.get("kind") != "phone" or not h.get("id"):
            raise ValueError("not a Tandem phone")
        state = h.get("state")
        if state == "busy":
            log(f"link: {h.get('name') or addr} is paired with another computer")
            self.denied[addr] = time.monotonic() + DENY_HOLD
            if self.peer.get("bt") == addr:
                log("link: it no longer counts this computer as paired; forgetting it")
                self.peer.forget()
            return False
        if state == "asking":
            self.bt_state = "pairing"
            log(f"link: asking {h.get('name')} to pair")
            self.notify(f"Confirm on {h.get('name') or 'your phone'} to pair it with this computer.",
                        title="Tandem: pair with your phone")
            conn.sock.settimeout(PAIR_WAIT)
            p, _ = recv_frame(conn.sock, NO_PAYLOAD)
            if p.get("t") != "pair" or not p.get("ok"):
                if p.get("reason") == "timeout":
                    log("link: nobody answered on the phone; asking again in a while")
                    self.denied[addr] = time.monotonic() + MISSED_HOLD
                    return False
                log("link: the phone said no to pairing")
                self.denied[addr] = time.monotonic() + DENY_HOLD
                self.notify("Pairing was declined on the phone. Run `tandem pair` to ask again.", title="Tandem")
                return False
            self._give_keys(conn, h, addr)
            self.notify(f"Paired with {h.get('name')}. Turn features on and off in the Tandem app.",
                        title="Tandem")
        elif state == "paired":
            if not self.peer or self.peer.get("id") != h["id"]:
                # The phone trusts us but we lost (or never had) its record: hand it fresh keys.
                self._give_keys(conn, h, addr)
        else:
            raise ValueError(f"unexpected pairing state {state!r}")
        self.peer.save(name=h.get("name") or self.peer.get("name"), bt=addr)
        return True

    def _give_keys(self, conn, h, addr):
        token = base64.b64encode(secrets.token_bytes(32)).decode()
        conn.send({"t": "keys", "token": token})
        self.peer.save(id=h["id"], name=h.get("name") or "Phone", bt=addr, token=token, paired=time.time())
        log(f"link: paired with {h.get('name')} ({addr})")

    # ------------------------------------------------------------ network (TLS server)
    def _tls_server(self):
        ctx = self.ident.server_context()
        while not self.stop:
            try:
                try:
                    srv = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
                    srv.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    srv.bind(("::", self.port))
                except OSError:
                    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    srv.bind(("0.0.0.0", self.port))
                srv.listen(8)
                log(f"link: listening on TCP {self.port}")
                while not self.stop:
                    s, addr = srv.accept()
                    threading.Thread(target=self._tls_conn, args=(ctx, s, addr), daemon=True).start()
            except OSError as e:
                log(f"link: can't listen on TCP {self.port}: {e}; retrying")
                time.sleep(10)

    def _tls_conn(self, ctx, raw, addr):
        ip = addr[0].removeprefix("::ffff:")
        raw.settimeout(10)
        try:
            s = ctx.wrap_socket(raw, server_side=True)
            h, _ = recv_frame(s, NO_PAYLOAD)  # nothing big from anyone before they've authenticated
        except (OSError, ValueError, ssl.SSLError):
            raw.close()
            return
        conn = Conn(s, "net", ip)
        ok = (h.get("t") == "auth" and bool(self.peer) and h.get("id") == self.peer.get("id")
              and hmac.compare_digest(str(h.get("token", "")), str(self.peer.get("token", ""))))
        try:
            conn.send({"t": "auth", "ok": ok, "name": self.ident.name if ok else ""})
        except OSError:
            ok = False
        if not ok:
            log(f"link: refused a connection from {ip} (not the paired phone)")
            conn.close()
            return
        role = h.get("role")
        if role == "audio":
            s.settimeout(3)  # a vanished phone must not stall the audio pump for minutes
            old, self.audio = self.audio, conn
            if old:
                old.close()
            self._hold(conn)
            if self.audio is conn:
                self.audio = None
            return
        if role == "bulk":
            s.settimeout(60)
            try:
                self.hooks["bulk"](conn, h)
            finally:
                conn.close()
            return
        self.phone_ip = ip
        self._serve(conn)

    def _hold(self, conn):
        """An audio connection: nothing comes back on it; just notice when it closes."""
        while not conn.closed:
            try:
                if not conn.sock.recv(1024):
                    break
            except (socket.timeout, TimeoutError):
                continue
            except OSError:
                break
        conn.close()

    def _serve(self, conn):
        """Reads a control connection (Bluetooth or network) until it drops."""
        via = conn.via
        old = self.bt if via == "bt" else self.net
        if via == "bt":
            self.bt = conn
            self.bt_state = "linked"
        else:
            self.net = conn
        if old and old is not conn:
            old.close()
        log(f"link: {via} up ({conn.addr})")
        self.post("link", {"t": "_up", "via": via})
        try:
            conn.send(self.settings.as_message())
            conn.send({"t": "addrs", "addrs": self.addrs, "port": self.port})
            conn.sock.settimeout(NET_IDLE if via == "net" else None)
            while not self.stop:
                h, p = recv_frame(conn.sock)
                t = h.get("t")
                if t == "ping":
                    conn.send({"t": "pong"})
                    continue
                if t == "unpair":
                    log("link: the phone unpaired")
                    self.notify("Your phone unpaired from this computer.", title="Tandem")
                    self.post("link", {"t": "_unpaired"})
                    self.unpair(tell=False)
                    return
                self.post(via, h, p)
        except (OSError, ValueError) as e:
            if not conn.closed:
                log(f"link: {via} down ({e})")
        finally:
            if (self.bt if via == "bt" else self.net) is conn:
                self._drop(conn)
            else:
                conn.close()
            if via == "bt":
                self.bt_state = "idle"

    # ------------------------------------------------------------ discovery + addresses
    def _discovery(self):
        """Answers the phone's "where are you?" broadcasts on the LAN (UDP, same port)."""
        while not self.stop:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(("0.0.0.0", self.port))
                while not self.stop:
                    data, addr = s.recvfrom(2048)
                    try:
                        msg = json.loads(data)
                    except ValueError:
                        continue
                    if isinstance(msg, dict) and msg.get("t") == "where" and msg.get("id") == self.ident.id:
                        s.sendto(json.dumps({"t": "here", "id": self.ident.id, "port": self.port}).encode(), addr)
            except OSError as e:
                log(f"link: discovery: {e}")
                time.sleep(30)

    def _bt_keepalive(self):
        while not self.stop:
            time.sleep(BT_PING)
            c = self.bt
            if c and time.monotonic() - c.opened > BT_PING:
                try:
                    c.send({"t": "ping"})
                except OSError:
                    self._drop(c)

    def _addr_watch(self):
        while not self.stop:
            time.sleep(10)
            now = local_addresses()
            if now and now != self.addrs:
                self.addrs = now
                self.send({"t": "addrs", "addrs": now, "port": self.port})

    def state(self):
        return {"bt": bool(self.bt), "net": bool(self.net), "audio": bool(self.audio), "bt_state": self.bt_state,
                "paired": bool(self.peer), "phone": self.peer.get("name"), "phone_bt": self.peer.get("bt"),
                "phone_ip": self.phone_ip if self.net else None, "addrs": self.addrs, "port": self.port}
