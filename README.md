# Tandem

Your Android phone and your Linux computer, working as one. Copy on one and paste on the other, see
your phone's notifications and calls on the computer, send files and links both ways, find your phone,
type on it from your keyboard, and share one pair of Bluetooth headphones between both. Every feature
is a switch in the app, and they're all optional.

Tandem is free software (GPL-3.0). There's no account, no cloud and no server: the two devices only
talk to each other.

## How it connects

1. **Pair over Bluetooth.** Pair your phone with your computer in Bluetooth settings, like any device.
   That pairing is Tandem's trust: the computer finds the Tandem app on the phone you paired, and the
   phone asks you once whether to allow it.
2. **Bluetooth carries the small things.** These are the clipboard, notifications, calls, battery,
   find-my-phone and keys. They keep working with no Wi-Fi at all, as long as the two are in range.
3. **The network carries everything when it can.** When both are on the same Wi-Fi, a hotspot, or the
   same [Tailscale](https://tailscale.com) network, the phone also connects to the computer over TLS. The
   computer's key is pinned during Bluetooth pairing, and the phone authenticates with a secret it got
   then. Big files and audio need this connection.

Nothing else can connect: the computer only accepts the paired phone, and the phone only trusts the
paired computer's key. The details are in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Features

| Feature | What it does | Off by default? |
|---|---|---|
| **Clipboard** | Copy on one, paste on the other (text and images). Password-manager copies stay put. | on |
| **Sign-in codes** | One-time codes from texts and notifications go straight to the computer's clipboard. | off |
| **Notifications** | Phone notifications show on the computer. Reply to messages, use their buttons, and dismiss them on either side. You can choose apps to leave out. | off |
| **Calls** | A ringing call shows on the computer, with Silence and Decline. | off |
| **Pause music for calls** | The computer's media pauses while you're on a call and resumes after. | on |
| **Files** | Share → *Send to computer* on the phone. On the computer, `tandem send FILE` or *Send to phone* in the file manager. | on |
| **Links** | Share a link to Tandem and it opens in the computer's browser (`tandem open URL` goes the other way). | on |
| **Screenshots** | New phone screenshots land in the computer's Pictures/Phone folder and on its clipboard. | off |
| **Battery** | The phone's (and your headphones') battery on the computer, with a low-battery warning. | on |
| **Find my phone** | `tandem ring` rings the phone at full volume even on silent. The app can ring the computer too. | on |
| **Do Not Disturb sync** | Turn it on or off on one and the other follows. | off |
| **Type on the phone** | `tandem type` sends your keyboard to the phone (through the Tandem keyboard). | off |
| **Screen mirroring** | `tandem screen` shows and controls the phone with [scrcpy](https://github.com/Genymobile/scrcpy). | off |
| **Lock on leave** | The computer locks when your phone leaves Bluetooth range. | off |
| **One pair of headphones** | With Bluetooth headphones on, they stay on one link and you still hear both devices. [More below](#one-pair-of-headphones-for-both). | on |
| **Phone media on the computer** | Control the phone's players from the computer. | on |
| **Play button opens your music app** | When your music app is closed, the headphones' play button opens it and starts playing. | off |

Change them in the app, or on the computer with `tandem settings` and `tandem set KEY on|off`. Both
devices share the same settings.

## Install

### On the computer (Linux)

You need Python 3 (the system one), BlueZ, systemd and OpenSSL, which are on nearly every desktop
distribution. A few features use extra tools; the installer tells you which ones are missing:

- **clipboard:** `wl-clipboard` (Wayland desktops: KDE, sway, Hyprland…) or `xclip` (X11, and GNOME)
- **notification buttons:** `gdbus` (part of GLib)
- **replying to messages:** `zenity`, `kdialog`, or PyGObject
- **audio sharing:** PipeWire and `libopus`
- **screen mirroring:** `scrcpy` and `adb`

```sh
git clone https://github.com/aidenwboudr/tandem
tandem/laptop/install.sh
```

The installer puts `tandem` in `~/.local/bin` and starts the `tandem` user service. It also adds *Send to
phone* to file managers. Run it again to update. If a firewall is on (firewalld, ufw), it prints the
command that lets the phone reach port 47800.

### On the phone (Android 13+)

Install the APK from [Releases](https://github.com/aidenwboudr/tandem/releases), open Tandem, and allow
Bluetooth and notifications. Then:

1. Pair the phone with your computer in Bluetooth settings (if they aren't already).
2. Keep Tandem open. Within a few seconds the computer finds it, and Tandem asks
   *Pair with your-computer?* Tap **Allow**.
3. Turn on the features you want. Each one asks for what it needs when you switch it on.

Tap *Let Tandem run in the background* too, so the link survives the phone sleeping.

#### Automatic clipboard from the phone (optional, one command)

Android only lets the app on screen read the clipboard. Without extra setup, phone copies go to the
computer when you tap **Send clipboard** in Tandem's notification, or share text to Tandem. To send every
copy automatically, run this once from a computer with adb:

```sh
adb shell pm grant com.aidenwb.tandem android.permission.READ_LOGS
```

Then open Tandem and allow it to read device logs when Android asks. It only watches for the system's
"something was copied" line. KDE Connect uses the same trick. After a reboot, open Tandem once so Android
asks again.

## The computer's commands

```
tandem status            what's connected, battery, clipboard counters
tandem settings          the feature switches;  tandem set KEY VALUE  changes one
tandem send FILE...      send files to the phone
tandem open URL          open a link on the phone
tandem ring [stop]       make the phone ring
tandem type [TEXT]       type on the phone (a small window, or the text you give)
tandem screen            mirror the phone's screen
tandem pair / unpair     look for the phone now / forget it
tandem switch [laptop|phone]   which device carries the other's audio
```

Logs: `journalctl --user -u tandem -f` on the computer, and `adb logcat -s Tandem` on the phone.

## One pair of headphones for both

Bluetooth headphones that connect to two devices at once (multipoint) often glitch when both play. With
this feature on, Tandem keeps your headphones on **one** link and still lets you hear both devices:

| Headphones are on… | What happens |
|---|---|
| only the computer or only the phone | normal |
| both, **phone is the hub** (the default) | the computer drops its link; its audio goes over the network to the phone, which mixes it into the headphones |
| both, **computer is the hub** | the computer keeps the headphones and plays the phone's audio as a Bluetooth speaker; calls still ring on the phone |

Switch the hub from the app's notification, the app, or `tandem switch`. This feature needs the network
link and PipeWire. The computer's audio arrives about 150 to 250 ms late, so video on the computer drifts
a little while the phone is the hub. Leave multipoint on in your headphones' app.

The installer also stops the computer from offering phones a hands-free link
(`laptop/wireplumber/51-bluez-no-handsfree-unit.conf`). When a phone opened one and it was dropped,
Android moved all the phone's audio back to the headphones.

## Extras for waybar (optional)

On wlroots desktops with waybar, the installer also sets up a hover mixer on the audio module, a
replacement for pavucontrol. It shows per-app volumes, devices, the phone's players and the hub switch,
plus a now-playing pill in the middle of the bar. Skip it with `install.sh --no-mixer`, and remove it with
`tandem-mixer --restore-waybar`. Settings live in `~/.config/tandem/mixer.json`.

## Desktop support

| | GNOME | KDE Plasma | sway / Hyprland / wlroots | X11 desktops |
|---|---|---|---|---|
| Clipboard | ✓ (xclip through Xwayland) | ✓ | ✓ | ✓ |
| Notifications with buttons | ✓ | ✓ (inline reply) | ✓ (mako, swaync, dunst) | ✓ |
| Do Not Disturb sync | ✓ | not yet | mako, swaync, dunst | dunst |
| Lock on leave | ✓ | ✓ | with swayidle, or swaylock/hyprlock | ✓ with a logind-aware locker |

## Limits

- One phone and one computer. Pairing with a different computer means unpairing first.
- Over Bluetooth only, files up to 4 MB go through; bigger ones wait until the two share a network.
- Do Not Disturb sync: on Android 15 and later an app can only turn off the Do Not Disturb it turned on
  itself, so DND you switch on by hand on the phone stays on until you turn it off there.
- Lock-on-leave uses Bluetooth range (roughly 10 m through walls), so turning Bluetooth off on the phone
  locks the computer too.
- The phone's battery saver can delay messages while it sleeps. Exempt Tandem (the app offers it), and
  copies from the computer are retried for 15 minutes.

## Building

- **Computer:** nothing to build. The daemon is plain Python (`laptop/tandemd`). The end-to-end test is
  `laptop/tests/fake_phone.py`.
- **App:** `android/build.sh` (JDK 17+ and the Android SDK). The first build makes a signing key at
  `~/.config/tandem/android-release.jks`; keep it, because updates must be signed with the same key.

## Compared with KDE Connect

Tandem does fewer things, and aims to do them reliably:

- Pairing comes from Bluetooth, so it works with no Wi-Fi, and across networks over Tailscale.
- Audio sharing for one pair of headphones.
- One small Python daemon that works on any desktop, rather than a desktop-specific app.
