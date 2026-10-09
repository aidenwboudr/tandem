# Tandem protocol (v4)

One computer and one phone. Bluetooth pairing is the root of trust. The two speak the same framed
messages over two transports:

- **Bluetooth (RFCOMM).** The phone serves it, and the computer connects. Pairing happens here, and it
  works without any network.
- **Network (TLS over TCP, port 47800).** The computer serves it, and the phone connects, when the two share
  a network (same Wi-Fi, Tailscale, a hotspot).

Every message can go over either one. A sender uses the network when it's up, else Bluetooth, and a
feature never cares which: the link layer picks. Payloads over 64 KiB, and every file, go as a transfer
(below), which carries on across a change of link.

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
- The phone's first frame is `auth {id, token, role}`. The computer answers `auth {ok}` and closes
  the connection on a mismatch. Nothing before a successful `auth` (or a finished Bluetooth hello) may carry
  a payload. The roles are:
  - `control`: all the messages below. The phone sends `ping` every 25 s, and the computer drops a
    connection that has been quiet for 90 s.
  - `audio`: the computer sends `a {c: codec, s: seq, id: stream}` frames with Opus (`c=1`) or PCM (`c=0`)
    payloads, 20 ms each. When the phone's `hb` says `bta`, the computer also sends the Opus frames you can hear
    (and 1 s after) over the Bluetooth link. The phone clears `bta` during a call, so the headphones' call audio
    has the phone's radio to itself. The phone plays each `(id, s)` once, from whichever link brings it first:
    a network stall (Tailscale moving between a relay and a direct path) then doesn't cut the sound. While audio comes
    over Bluetooth the phone sends a `pong` there every 2 s: Android puts a link it hasn't sent on for 7 s into
    sniff mode, which can't carry audio. When nothing has come from either link for 1.5 s, the phone opens a
    fresh audio connection (the computer drops the old one) instead of waiting out TCP's retry backoff.
- Discovery: when the phone has no working address, it broadcasts `where {id}` on UDP 47800. The computer
  answers the sender with `here {id, port}`.
- The computer pushes `addrs {addrs}` over any link when its addresses change.

## Transfers

A message whose payload is over 64 KiB, and every `file`, goes as numbered chunks on whichever link is up:

- `x {x, i, n?, sha?, h?}` + up to 64 KiB of the payload, starting at byte `i`. `x` is the transfer's id
  (the same message gets the same id, so sending it again carries on). The chunk at `i: 0` also carries
  `n` (the payload's size), `sha` (its SHA-256, hex) and `h` (the message's own header). Chunks are 64 KiB
  on the network and 16 KiB on Bluetooth, with at most 1 MiB and 64 KiB unacknowledged.
- `x-ack {x, have, wait?, done?}`: the receiver has every byte before `have`. Sent for every chunk, and
  every 5 s while a transfer is idle. `have: 0` for a transfer it doesn't know means "start again from
  0" (it lost it, or the result didn't match `sha`). `done` once it has all of it and the checksum matched.
- `x-no {x, error}`: the receiver won't take it (too big for its type), or the sender gave up on it.

When the link changes (the network comes back, or the one it was on drops), the sender goes on from the
last `have`, on whichever link is up. Without any link for a minute it gives up, and the feature tries
again later; the receiver keeps what it had for an hour.

On Bluetooth, a transfer gives way to sound. The sender holds its chunks while its own radio carries
audio, and the receiver answers `wait: true` while its radio does. It sends `wait: false` when that stops.
On the computer that's sound on the headphones, or laptop audio going to the phone over Bluetooth; on
the phone a call or any music playing.

The receiver hands the rebuilt message on as if it had come in one frame. A `file` lands as a file.

## Messages

| type | direction | what |
|---|---|---|
| `settings {values, rev}` | both | the feature settings (see below). The higher `rev` (ms since epoch) wins. Both send theirs on connect. |
| `hb {hp, hp_linked, hpname, hpaddr, codec, bta, media, volume, media_access}` | phone → computer | headphone state and the phone's players. 1/s while the headphones are on the phone, otherwise every 15 s. |
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
| `file {id, name, mime, kind}` + data | both, as a transfer | `kind` is file, screenshot or photo |
| `file-no {id, name, error}` | both | the receiver didn't take a file (turned off there) |
| `x`, `x-ack`, `x-no` | both | a transfer's chunks (see Transfers) |
| `key {text}` / `key {key}` | computer → phone | typing into the phone (remote keyboard) |
| `dnd {on}` | both | Do Not Disturb changed |
| `ping` / `pong` | both | keepalive |
