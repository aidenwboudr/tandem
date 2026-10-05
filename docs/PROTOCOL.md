# Tandem protocol (v3)

One computer and one phone. Bluetooth pairing is the root of trust. The two speak the same framed
messages over two transports:

- **Bluetooth (RFCOMM).** The phone serves it, and the computer connects. It's for pairing and small
  messages, and it works without any network.
- **Network (TLS over TCP, port 47800).** The computer serves it, and the phone connects. It's used for
  everything when the two share a network (same Wi-Fi, Tailscale, a hotspot), and it's the only path for
  big things (files, laptop audio).

## Frames

Every message on every connection is a frame:

```
uint32 big-endian  header length (max 64 KiB)
header             UTF-8 JSON object; "t" is the message type, "len" the payload size
payload            header.len bytes (0 for most messages)
```

## Bluetooth

- The service UUID is `7a6d3b40-6e1a-4d2a-9b7e-54616e64656d`. It's a secure (encrypted, bonded) RFCOMM link.
- The computer finds the phone among its bonded devices of class "phone". It looks the UUID up over
  SDP and connects to that channel.
- First frames:
  - computer → phone `hello` `{id, name, kind:"computer", v:3, fp, port, addrs:[...]}`. Here `fp` is the
    SHA-256 (hex) of the computer's TLS certificate (DER), and `addrs` are its current IP addresses.
  - phone → computer `hello` `{id, name, kind:"phone", v:3, state}`, where `state` is one of:
    - `"paired"`: the phone already trusts this computer (same `id` and `fp`, from the same Bluetooth address).
    - `"asking"`: the phone is asking its user. A `pair` message follows.
    - `"busy"`: the phone is paired with a different computer and refuses this one.
- Pairing: phone → computer `pair {ok, reason}` (`reason` is `denied` or `timeout`). If ok, computer → phone `keys {token}`. The token is 32 random
  bytes, base64-encoded, which the phone presents on every network connection. Both sides then store the
  other.
- `unpair` (either way): forget the other side, then close.

## Network

- The computer listens on TCP 47800 on all its addresses, with TLS and a self-signed certificate. The
  phone checks that the certificate's SHA-256 equals the `fp` it paired with.
- The phone's first frame is `auth {id, token, role, bulk?}`. The computer answers `auth {ok}` and closes
  the connection on a mismatch. Nothing before a successful `auth` (or a finished Bluetooth hello) may carry
  a payload. The roles are:
  - `control`: all the messages below. The phone sends `ping` every 25 s, and the computer drops a
    connection that has been quiet for 90 s.
  - `audio`: the computer sends `a {c: codec, s: seq, id: stream}` frames with Opus (`c=1`) or PCM (`c=0`)
    payloads, 20 ms each. When the phone's `hb` says `bta`, the computer also sends the Opus frames over the
    Bluetooth link, and the phone plays each `(id, s)` once, from whichever link brings it first. A network
    stall (Tailscale moving between a relay and a direct path) then doesn't cut the sound. While audio comes
    over Bluetooth the phone sends a `pong` there every 2 s: Android puts a link it hasn't sent on for 7 s into
    sniff mode, which can't carry audio. When nothing has come from either link for 1.5 s, the phone opens a
    fresh audio connection (the computer drops the old one) instead of waiting out TCP's retry backoff.
  - `bulk`: one file transfer. With `bulk: <offer id>` the phone is taking a file the computer offered
    (`file-offer`), and the computer sends it. Without it, the phone sends one `file`. The receiver answers
    `file-ok {id}`, or `file-no {id, error}` if it won't take it.
- Discovery: when the phone has no working address, it broadcasts `where {id}` on UDP 47800. The computer
  answers the sender with `here {id, port}`.
- The computer pushes `addrs {addrs}` over any link when its addresses change.

## Messages

| type | direction | what |
|---|---|---|
| `settings {values, rev}` | both | the feature settings (see below). The higher `rev` (ms since epoch) wins. Both send theirs on connect. |
| `hb {hp, hp_linked, hpname, hpaddr, codec, bta, media, volume, media_access}` | phone → computer | headphone state and the phone's players. 1/s while the headphones are on the phone, otherwise every 15 s. Network only. |
| `ack {owner, streaming, sent, prefer}` | computer → phone | reply to `hb` |
| `switch {to}` | both | make "laptop" or "phone" the hub |
| `cmd {op, id, value}` | computer → phone | media control (play pause toggle next previous volume) |
| `art {key}` + JPEG | phone → computer | album art for the now-playing pill |
| `clip {mime, hash, otp?, manual?}` + data | both | a clipboard copy (text/plain or image/*). `otp` (a sign-in code) and `manual` (sent by hand) go through even when automatic copying is off |
| `status {...}` | both | battery `{level, charging}`, the phone's headphone battery (`hp_battery`, `hp_name`), `dnd` |
| `notif {key, pkg, app, title, text, when, reply, actions}` | phone → computer | a phone notification |
| `notif-icon {pkg}` + PNG | phone → computer | an app icon, sent once per app |
| `notif-gone {key}` | phone → computer | the notification was dismissed on the phone |
| `notif-dismiss {key}`, `notif-reply {key, text}`, `notif-action {key, index}` | computer → phone | acting on one |
| `call {state, name}` | phone → computer | `state` is ringing, offhook or idle |
| `call-cmd {op}` | computer → phone | `mute` (silence the ringer) or `reject` |
| `ring {on}` | both | make the other device ring (find it) |
| `open {url}` | both | open a link on the other device |
| `file-offer {id, name, size, mime}` | computer → phone | the phone opens a `bulk` connection to take it |
| `file {id, name, size, mime, kind}` + data | both, on `bulk` | `kind` is file, screenshot or photo |
| `key {text}` / `key {key}` | computer → phone | typing into the phone (remote keyboard) |
| `dnd {on}` | both | Do Not Disturb changed |
| `ping` / `pong` | both | keepalive |
