# Privacy

Tandem has no servers, no accounts, no analytics and no ads. Nothing it handles leaves your two devices.
The phone talks only to the one computer it's paired with, and the computer only to that phone.

## How your data travels

- **Over Bluetooth** between the paired devices. The link is encrypted by the Bluetooth bond.
- **Over your own network** (Wi-Fi, a hotspot, or Tailscale), with TLS. The phone checks that the
  computer's certificate is the one it saw during Bluetooth pairing, and the computer only accepts the
  phone that holds the secret it got then. No third party is involved, and nothing is relayed through
  anyone's servers.
- **Discovery:** when the phone can't reach the computer, it asks the local network "where is computer
  &lt;id&gt;?" over UDP broadcast. The computer answers with its port. The id is a random number with no
  personal information in it.

## What the app can access, and why

Every permission belongs to a feature. The app asks for one only when you switch that feature on, and
you can turn any feature off again.

| Permission | Used by | What it does with it |
|---|---|---|
| Bluetooth | pairing, headphones | finds the computer and your headphones |
| Notifications (posting) | always | its own status and pairing notifications |
| Notification access | notifications, sign-in codes, calls, media controls | reads new notifications to show them on the computer, spots one-time codes, learns the caller's name from the dialer, lists media players |
| Phone state | calls | knows when a call rings, is answered and ends. It doesn't read your number or call log |
| Answer calls (optional) | calls | the Decline button |
| Photos | screenshots | notices new screenshots in the Screenshots folder. It doesn't read other photos |
| Do Not Disturb access | Do Not Disturb sync | turns Do Not Disturb on and off |
| Display over other apps | links, play button, automatic clipboard | lets the app open a link or an app from the background. It never draws anything over other apps |
| Device logs (`READ_LOGS`, granted with adb, optional) | automatic clipboard | sees the system's "something was copied" line, so copies go to the computer without a tap. It ignores everything else in the log |
| All apps list | notifications, media controls | app names and icons for the computer |
| Background use, network, boot | always | keeps the link up |

The Tandem keyboard (for typing from the computer) only types what the computer sends. It doesn't
record or send anything you type yourself.

## On the computer

The daemon keeps its pairing, keys and settings in `~/.config/tandem` and logs to the systemd journal.
Files and screenshots from the phone go to your Downloads and Pictures folders, and app icons for
notifications are cached in `~/.cache/tandem`. Nothing is uploaded anywhere.

## Questions

Open an issue at https://github.com/aidenwboudr/tandem/issues.
