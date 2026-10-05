# Tandem's look

One look everywhere: the site (tandem.aidenwb.com, in the aidenwb.com repo), the phone app and the
desktop app. The site came first; the apps follow it.

## The idea

Two devices and the seam between them. **The phone is orange, the computer is teal.** Whatever goes
between them is drawn in both. Everything else is ink on fog and paper, so those two colours always
mean something.

## Marks

- `brand/icon.svg`: the app icon. An orange ring and a teal ring touching, on an ink square. The
  Android launcher icon (`ic_launcher_fg.xml`, x1.8 in the 108dp canvas), the store icon and the desktop
  icon (`laptop/tandemd/ui/tandem.svg`) are all this drawing.
- `brand/mark.svg`: the wordmark's mark. The same two rings apart, joined by a short ink stroke. It goes
  next to the word "Tandem" (site header, app headers).
- Notification icon (`ic_tandem.xml`): the icon's two rings in one colour.

## Colour

| token      | light     | dark (apps) | job |
|------------|-----------|-------------|-----|
| fog (bg)   | `#edf0f3` | `#0f1418`   | page |
| paper      | `#ffffff` | `#1b232b`   | cards |
| ink        | `#131a21` | `#edf0f3`   | text, primary buttons, switches that are on |
| mute       | `#56616c` | `#93a1ae`   | secondary text, labels |
| rule       | `#cbd3db` | `#2f3943`   | dividers, switches that are off, a link that's down |
| phone      | `#e0512b` | `#e8613b`   | the phone; things going phone → computer |
| phone-ink  | `#b0391a` | `#f5946f`   | small orange text; "this phone still needs…" |
| phone-wash | `#fbe9e3` | `#35211b`   | the phone's tint |
| pc         | `#12776f` | `#1c9488`   | the computer; things going computer → phone |
| pc-ink     | `#0d5f58` | `#5cc9bd`   | small teal text |
| pc-wash    | `#dff0ee` | `#14302d`   | the computer's tint |

Direction badges (site feature list, app feature rows): orange disc with → (phone to computer), teal
disc with ← (computer to phone), half and half with ↔ (both ways), a hollow orange ring (stays on the
phone).

## Type

- **Familjen Grotesk** 650/700: headings and the wordmark, tight tracking (-0.02em).
- **Atkinson Hyperlegible** 400/700: everything you read.
- **Martian Mono** 500/600: small uppercase labels, buttons, numbers, commands.

All three are SIL OFL; the licences are in `laptop/tandemd/ui/fonts/`. The apps ship static instances
cut from the site's variable fonts.

## Components

- Buttons: Martian Mono 600, 10dp/px corners. Ink fill (main action), 2px ink outline (the rest), orange
  fill only for saying yes to the phone (pairing).
- Cards: paper, 16 corners, flat. No drop shadows in the apps.
- Switches: ink track when on, rule-grey when off. The thumb keeps a ring so the state reads without
  colour.
- The link (phone app `LinkView`, desktop app header): the phone on the left, the computer on the
  right, one lane each for Bluetooth and the network. Up is solid, half orange and half teal. Down is
  grey and dashed.
- The hub switch: a fog pill with "The hub", and Phone / Computer. The chosen side fills with its
  device's colour.

## Rules (check these on every change)

1. No colours outside the table. Orange and teal only ever mean phone and computer.
2. No fonts but the three above (no Inter or Roboto defaults; set the family explicitly).
3. No drop shadows, gradients or blur in the apps. The one split fill is the "both ways" badge and the
   up lanes.
4. State survives grayscale: on/off, up/down and needs are also told by shape (filled vs ring, solid
   vs dashed, a dot).
5. Every control has a pressed state (ripple on Android, `:hover`/`:active` in GTK CSS).
6. Motion only shows a state change (a lane coming up, the hub moving), at most ~350 ms. Nothing loops.
7. Copy: plain sentences, sentence case, no em dashes, say what happens ("Copy on the phone, paste on
   the computer").

## Seeing the screens

- Phone, without a phone: `cd android && ./gradlew testReleaseUnitTest --tests '*ScreensTest*'` builds the
  real main screen in several states with Robolectric and writes PNGs to `android/app/build/screens/`.
- Computer: `TANDEM_APP_SHOT=out.png tandem app` draws the window and quits (`TANDEM_APP_SHOT_PAGE=1` for
  the whole scrolling page, `ADW_DEBUG_COLOR_SCHEME=prefer-light|prefer-dark` to pick a theme, and
  `XDG_RUNTIME_DIR=<dir with a tandem.json>` to show a made-up state).
- Public screenshots (README, stores) use made-up names and addresses, never a real device's.
