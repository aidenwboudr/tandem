"""tandem app: a window for the link, pairing and every setting (GTK 4 + libadwaita).

It talks to the daemon the way the CLI does: it reads the state file the daemon rewrites every second,
and sends commands to its control socket. Settings changed here sync to the phone, and the other way
round. The look follows docs/BRAND.md.
"""
import math
import os
import shutil
import subprocess
import sys
import time

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("PangoCairo", "1.0")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango, PangoCairo  # noqa: E402

from .cli import ctl, state  # noqa: E402
from . import __version__  # noqa: E402
from .config import CONFIG, CONFIG_DEFAULTS, DEFAULTS, SETTINGS, load_config, read_json, user_dir  # noqa: E402

APP_ID = "com.aidenwb.Tandem"
UI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui")
SITE = "https://tandem.aidenwb.com"

TO_PC, TO_PHONE, BOTH, LOCAL = "to-pc", "to-phone", "both", "local"

# The same switches as the phone app, worded from this side. (section, [(key, direction, title, text,
# [(sub key, title)])]); the phone app's MainActivity has them in the same order.
FEATURES = [
    ("Clipboard", [
        ("clip_to_laptop", TO_PC, "Get the phone's copies", "Copy on the phone, paste here.", []),
        ("clip_from_laptop", TO_PHONE, "Send my copies to the phone", "Copy here, paste on the phone. Images too.", []),
        ("clip_secrets", TO_PHONE, "Include password manager copies",
         "Off: copies a password manager marks as secret stay on this computer.", []),
        ("otp_copy", TO_PC, "Copy sign-in codes from the phone",
         "When a text or notification has a one-time code, it lands on this clipboard. "
         "Needs notification access on the phone.", []),
    ]),
    ("Notifications", [
        ("notif_mirror", TO_PC, "Show phone notifications here",
         "Messages, mail, reminders… Media and ongoing notifications stay on the phone. "
         "Pick apps to leave out in the phone app.",
         [("notif_reply", "Reply from here"), ("notif_dismiss_sync", "Dismiss on one, gone on both")]),
    ]),
    ("Calls", [
        ("calls", TO_PC, "Show calls here", "With buttons to silence the ringer or decline.", []),
        ("call_pause_media", TO_PC, "Pause music here during calls", "And play it again when the call ends.", []),
    ]),
    ("Files and links", [
        ("files", BOTH, "Send and receive files",
         "Send with the button at the top, `tandem send`, or Send to phone in the file manager.", []),
        ("open_links", BOTH, "Open shared links on the other device",
         "A link shared to Tandem on the phone opens in the browser here, and `tandem open` sends one over.", []),
        ("screenshots", TO_PC, "Get the phone's new screenshots", "Needs photo access on the phone.",
         [("screenshot_clipboard", "Also put them on the clipboard")]),
    ]),
    ("Audio", [
        ("audio_share", BOTH, "Share audio through one pair of headphones",
         "With Bluetooth headphones on, they stay on one link and you hear both devices: the hub plays "
         "the other's audio. Needs both on the same network, and PipeWire here.", []),
        ("media_controls", TO_PHONE, "Control phone media from here", "Play/pause and volume, and what's playing.", []),
        ("play_opens_app", LOCAL, "Headphone play button opens the phone's music app",
         "If the app is closed, pressing play opens it. Pick the app in the phone app.", []),
    ]),
    ("Phone status", [
        ("battery", TO_PC, "Phone battery here", "The phone's and the headphones' battery, and a warning when it's low.", []),
        ("find_phone", BOTH, "Find my phone",
         "Ring the phone at full volume, even on silent. The phone can ring this computer back.", []),
        ("dnd_sync", BOTH, "Sync Do Not Disturb",
         "Turn it on or off on one and the other follows (GNOME, mako, swaync and dunst).", []),
    ]),
    ("Remote control", [
        ("remote_input", TO_PHONE, "Type on the phone from here",
         "Pick the Tandem keyboard on the phone, then use Type on phone at the top.", []),
        ("screen_mirror", TO_PC, "Mirror the phone's screen",
         "Shows and controls the phone in a window. Needs scrcpy and adb here, and Wireless debugging on "
         "the phone.", []),
    ]),
    ("Security", [
        ("lock_on_leave", TO_PC, "Lock this computer when I walk away",
         "When the phone leaves Bluetooth range, the screen locks.", []),
    ]),
]
NUMBERS = {"battery_low": ("battery", "Warn below (%)", 1, 99), "lock_delay": ("lock_on_leave", "After (seconds)", 5, 3600)}

LIGHT = {"bg": "#edf0f3", "paper": "#ffffff", "ink": "#131a21", "on_ink": "#ffffff", "mute": "#56616c",
         "rule": "#cbd3db", "phone": "#e0512b", "phone_ink": "#b0391a", "phone_wash": "#fbe9e3",
         "pc": "#12776f", "pc_ink": "#0d5f58", "pc_wash": "#dff0ee"}
DARK = {"bg": "#0f1418", "paper": "#1b232b", "ink": "#edf0f3", "on_ink": "#131a21", "mute": "#93a1ae",
        "rule": "#2f3943", "phone": "#e8613b", "phone_ink": "#f5946f", "phone_wash": "#35211b",
        "pc": "#1c9488", "pc_ink": "#5cc9bd", "pc_wash": "#14302d"}

CSS = """
window.tandem, window.tandem .page { background: @bg; color: @ink; font-family: "Atkinson Hyperlegible"; font-size: 11pt; }
window.tandem headerbar { background: @bg; box-shadow: none; border: none; color: @ink; }
.wordmark { font-family: "Familjen Grotesk"; font-weight: 700; font-size: 17pt; letter-spacing: -0.4px; }
.h1 { font-family: "Familjen Grotesk"; font-weight: 600; font-size: 19pt; letter-spacing: -0.4px; }
.h2 { font-family: "Familjen Grotesk"; font-weight: 600; font-size: 15.5pt; letter-spacing: -0.3px; margin: 18px 4px 8px 4px; }
.name { font-family: "Familjen Grotesk"; font-weight: 600; font-size: 13pt; }
.label { font-family: "Martian Mono"; font-weight: 500; font-size: 7.5pt; letter-spacing: 0.6px; color: @mute; }
.label.phone { color: @phone_ink; }
.label.pc { color: @pc_ink; }
.mono { font-family: "Martian Mono"; font-size: 9pt; }
.title { font-weight: 700; }
.text { color: @mute; }
.value { font-weight: 700; }
.card { background: @paper; border-radius: 16px; padding: 18px; box-shadow: none; border: none; }
.card.wash { background: @phone_wash; }
.card.ask { border: 2px solid @pc; }
.group { background: @paper; border-radius: 16px; }
.group > .row { padding: 14px 16px; border-bottom: 1px solid @rule; }
.group > .row:last-child { border-bottom: none; }
.sub { padding-top: 8px; }
.note { background: @bg; border-radius: 10px; padding: 10px 12px; }
.code { font-family: "Martian Mono"; font-size: 9pt; color: @ink; }

button.btn { font-family: "Martian Mono"; font-weight: 600; font-size: 9pt; border-radius: 10px; padding: 8px 16px;
  min-height: 24px; box-shadow: none; background: transparent; color: @ink; border: 2px solid @ink; }
button.btn:hover { background: alpha(@ink, 0.06); }
button.btn:active { background: alpha(@ink, 0.14); }
button.btn.ink { background: @ink; color: @on_ink; }
button.btn.ink:hover { background: @pc_ink; border-color: @pc_ink; }
button.btn.ink:active { background: @pc; border-color: @pc; }
button.btn.phone { background: @phone; border-color: @phone; color: #ffffff; }
button.btn.phone:hover, button.btn.phone:active { background: @phone_ink; border-color: @phone_ink; }
button.btn:disabled { opacity: 0.45; }

.hub { background: @bg; border-radius: 999px; padding: 4px; }
.hub button { font-family: "Martian Mono"; font-weight: 600; font-size: 8.5pt; letter-spacing: 0.5px; border-radius: 999px;
  padding: 6px 12px; background: none; box-shadow: none; color: @mute; border: none; }
.hub button:hover { color: @ink; }
.hub button.on-phone { background: @phone; color: #ffffff; }
.hub button.on-pc { background: @pc; color: #ffffff; }

switch { background: @rule; border-radius: 999px; border: none; box-shadow: none; min-height: 26px; padding: 0; }
switch:checked { background: @ink; }
switch slider { background: @paper; border-radius: 999px; min-width: 26px; min-height: 26px; margin: 0;
  border: 4px solid @rule; box-shadow: none; }
switch:checked slider { background: @on_ink; border-color: @ink; }
switch:hover slider { border-color: @mute; }
switch:checked:hover slider { border-color: @pc_ink; }
switch image { color: transparent; }

entry, spinbutton { background: @bg; border-radius: 10px; border: 2px solid transparent; box-shadow: none; color: @ink;
  font-family: "Martian Mono"; font-size: 9.5pt; }
entry:focus-within, spinbutton:focus-within { border-color: @ink; outline: none; }
spinbutton button { background: none; box-shadow: none; border: none; color: @ink; }
toast { font-family: "Atkinson Hyperlegible"; }
"""


def palette_css(p):
    return "".join(f"@define-color {k} {v};\n" for k, v in p.items()) + CSS


def load_fonts():
    """The brand's fonts ship in ui/fonts; hand them to Pango without installing them system-wide."""
    fm = PangoCairo.FontMap.get_default()
    if not hasattr(fm, "add_font_file"):  # Pango < 1.56: fall back to whatever is installed
        return
    d = os.path.join(UI, "fonts")
    for n in sorted(os.listdir(d)):
        if n.endswith(".ttf"):
            try:
                fm.add_font_file(os.path.join(d, n))
            except GLib.Error:
                pass


def rgb(hexstr):
    h = hexstr.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def tandem_bin():
    return os.environ.get("TANDEM_BIN") or shutil.which("tandem") or os.path.expanduser("~/.local/bin/tandem")


def set_config(key, value):
    """Changes one KEY=value line in ~/.config/tandem/config, keeping the comments."""
    try:
        with open(CONFIG) as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        lines = []
    line = f"{key}={value}"
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].strip().startswith(key + "="):
            lines[i] = line
            break
    else:
        lines.append(line)
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    with open(CONFIG + ".tmp", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(CONFIG + ".tmp", CONFIG)


def ago(t):
    if not t:
        return ""
    s = int(time.time() - t)
    return f", {s} s ago" if s < 60 else f", {s // 60} min ago" if s < 3600 else f", {s // 3600} h ago"


# ---------------------------------------------------------------------------------------------- drawing

class Mark(Gtk.DrawingArea):
    """The wordmark's mark: an orange ring and a teal ring joined by a short ink stroke."""

    def __init__(self, win, w=40, h=20):
        super().__init__(content_width=w, content_height=h)
        self.win = win
        self.set_draw_func(self.draw)

    def draw(self, _a, cr, w, h):
        p = self.win.pal
        s = min(w / 40, h / 20)
        cr.translate((w - 40 * s) / 2, (h - 20 * s) / 2)
        cr.set_line_width(2.6 * s)
        cr.set_line_cap(1)
        for x, c in ((10, "phone"), (30, "pc")):
            cr.set_source_rgb(*rgb(p[c]))
            cr.arc(x * s, 10 * s, 7 * s, 0, 2 * math.pi)
            cr.stroke()
        cr.set_source_rgb(*rgb(p["ink"]))
        cr.move_to(17 * s, 10 * s)
        cr.line_to(23 * s, 10 * s)
        cr.stroke()


class Badge(Gtk.DrawingArea):
    """Which way a feature sends things: orange = phone to computer, teal = to the phone, split = both."""

    def __init__(self, win, direction):
        super().__init__(content_width=28, content_height=28, valign=Gtk.Align.START)
        self.win, self.dir = win, direction
        self.set_draw_func(self.draw)

    def draw(self, _a, cr, w, h):
        p = self.win.pal
        r, cx, cy = min(w, h) / 2, w / 2, h / 2
        if self.dir == LOCAL:
            cr.set_source_rgb(*rgb(p["phone"]))
            cr.set_line_width(r * 0.28)
            cr.arc(cx, cy, r * 0.62, 0, 2 * math.pi)
            cr.stroke()
            return
        if self.dir == BOTH:
            cr.set_source_rgb(*rgb(p["phone"]))
            cr.move_to(cx, cy)
            cr.arc(cx, cy, r, math.pi / 2, 3 * math.pi / 2)
            cr.fill()
            cr.set_source_rgb(*rgb(p["pc"]))
            cr.move_to(cx, cy)
            cr.arc(cx, cy, r, -math.pi / 2, math.pi / 2)
            cr.fill()
        else:
            cr.set_source_rgb(*rgb(p["phone" if self.dir == TO_PC else "pc"]))
            cr.arc(cx, cy, r, 0, 2 * math.pi)
            cr.fill()
        cr.set_source_rgb(1, 1, 1)
        cr.set_line_width(r * 0.17)
        cr.set_line_cap(1)
        cr.set_line_join(1)
        a, hd = r * 0.45, r * 0.26
        cr.move_to(cx - a, cy)
        cr.line_to(cx + a, cy)
        if self.dir != TO_PHONE:  # a head toward the computer, on the right
            cr.move_to(cx + a - hd, cy - hd)
            cr.line_to(cx + a, cy)
            cr.line_to(cx + a - hd, cy + hd)
        if self.dir != TO_PC:
            cr.move_to(cx - a + hd, cy - hd)
            cr.line_to(cx - a, cy)
            cr.line_to(cx - a + hd, cy + hd)
        cr.stroke()


class LinkArea(Gtk.DrawingArea):
    """The link as the site and the phone app draw it: the phone, the computer, and a lane each for
    Bluetooth and the network. Up is solid (half orange, half teal); down is grey and dashed."""

    def __init__(self, win):
        super().__init__(content_height=96, hexpand=True)
        self.win = win
        self.bt = self.net = False
        self.t = {"bt": 0.0, "net": 0.0}
        self.anim = None
        self.set_draw_func(self.draw)

    def set(self, bt, net):
        if (bt, net) == (self.bt, self.net):
            return
        self.bt, self.net = bt, net
        start = dict(self.t)
        t0 = time.monotonic()
        if self.anim:
            self.remove_tick_callback(self.anim)

        def tick(*_):
            f = min(1.0, (time.monotonic() - t0) / 0.32)
            e = 1 - (1 - f) ** 2
            for k, up in (("bt", bt), ("net", net)):
                self.t[k] = start[k] + ((1.0 if up else 0.0) - start[k]) * e
            self.queue_draw()
            if f >= 1:
                self.anim = None
                return False
            return True
        self.anim = self.add_tick_callback(tick)

    def draw(self, _a, cr, w, h):
        p = self.win.pal
        cy = h / 2

        def rrect(x, y, rw, rh, r):
            cr.new_sub_path()
            cr.arc(x + rw - r, y + r, r, -math.pi / 2, 0)
            cr.arc(x + rw - r, y + rh - r, r, 0, math.pi / 2)
            cr.arc(x + r, y + rh - r, r, math.pi / 2, math.pi)
            cr.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
            cr.close_path()

        def shape(fill, stroke):
            cr.set_source_rgb(*rgb(p[fill]))
            cr.fill_preserve()
            cr.set_source_rgb(*rgb(p[stroke]))
            cr.set_line_width(3)
            cr.stroke()

        pw, ph = 36, 62
        rrect(4, cy - ph / 2, pw, ph, 8)
        shape("phone_wash", "phone")
        cr.set_source_rgb(*rgb(p["phone"]))
        rrect(4 + pw / 2 - 5, cy - ph / 2 + 5, 10, 2.5, 1)
        cr.fill()

        lw, lh = 70, 46
        lx = w - lw - 10
        rrect(lx, cy - lh / 2 - 5, lw, lh, 4)
        shape("pc_wash", "pc")
        cr.set_source_rgb(*rgb(p["pc"]))
        cr.set_line_cap(1)
        cr.move_to(lx - 7, cy + lh / 2 + 3)
        cr.line_to(lx + lw + 7, cy + lh / 2 + 3)
        cr.stroke()

        x0, x1 = 4 + pw + 14, lx - 16
        for key, name, y in (("bt", "BLUETOOTH", cy - 13), ("net", "NETWORK", cy + 19)):
            t, mid = self.t[key], (x0 + x1) / 2
            if t < 1:
                cr.set_source_rgba(*rgb(p["rule"]), 1 - t)
                cr.set_line_width(2)
                cr.set_dash([4, 5])
                cr.move_to(x0, y)
                cr.line_to(x1, y)
                cr.stroke()
                cr.set_dash([])
            if t > 0:
                half = (mid - x0) * t
                cr.set_line_width(3.5)
                cr.set_line_cap(1)
                cr.set_source_rgb(*rgb(p["phone"]))
                cr.move_to(mid - half, y)
                cr.line_to(mid, y)
                cr.stroke()
                cr.set_source_rgb(*rgb(p["pc"]))
                cr.move_to(mid, y)
                cr.line_to(mid + half, y)
                cr.stroke()
            layout = self.create_pango_layout(name if t > 0.5 else name + " · OFF")
            layout.set_font_description(_font("Martian Mono 7"))
            tw, th = layout.get_pixel_size()
            cr.set_source_rgb(*rgb(p["ink" if t > 0.5 else "mute"]))
            cr.move_to(mid - tw / 2, y - th - 4)
            PangoCairo.show_layout(cr, layout)


def _rect(w, h):
    from gi.repository import Graphene
    return Graphene.Rect().init(0, 0, w, h)


def _font(desc):
    return Pango.FontDescription.from_string(desc)


# ---------------------------------------------------------------------------------------------- widgets

def label(text, *classes, wrap=False, xalign=0.0, **kw):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap, **kw)
    if wrap:
        lb.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
    for c in classes:
        lb.add_css_class(c)
    return lb


def code_markup(text):
    """`backticked` parts in mono, like code on the site."""
    out, parts = [], text.split("`")
    for i, part in enumerate(parts):
        part = GLib.markup_escape_text(part)
        out.append(f"<span font_family='Martian Mono' size='90%' foreground='{{ink}}'>{part}</span>" if i % 2 else part)
    return "".join(out)


def button(text, kind=None, on_click=None):
    b = Gtk.Button(label=text)
    b.add_css_class("btn")
    if kind:
        b.add_css_class(kind)
    if on_click:
        b.connect("clicked", lambda *_: on_click())
    return b


def box(orient=Gtk.Orientation.VERTICAL, spacing=0, *classes, **kw):
    b = Gtk.Box(orientation=orient, spacing=spacing, **kw)
    for c in classes:
        b.add_css_class(c)
    return b


def hbox(spacing=0, *classes, **kw):
    return box(Gtk.Orientation.HORIZONTAL, spacing, *classes, **kw)


# ---------------------------------------------------------------------------------------------- window

class Window(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Tandem", default_width=760, default_height=900)
        self.add_css_class("tandem")
        self.set_icon_name("tandem")
        self.sm = Adw.StyleManager.get_default()
        self.pal = DARK if self.sm.get_dark() else LIGHT
        self.css = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), self.css,
                                                  Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
        self.apply_palette()
        self.sm.connect("notify::dark", lambda *_: self.apply_palette())

        self.st = None
        self.saved = {}
        self.refreshers = []
        self.pending = {}  # key -> (value, until): a change sent from here that the state file doesn't show yet
        self.ringing_until = 0.0

        self.toasts = Adw.ToastOverlay()
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        title = hbox(10, valign=Gtk.Align.CENTER)
        title.append(Mark(self, 34, 17))
        title.append(label("Tandem", "wordmark"))
        hb.set_title_widget(title)
        tv.add_top_bar(hb)

        self.col = box(Gtk.Orientation.VERTICAL, 12, margin_top=6, margin_bottom=28, margin_start=18, margin_end=18)
        clamp = Adw.Clamp(maximum_size=720, child=self.col)
        scroll = Gtk.ScrolledWindow(child=clamp, hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
        scroll.add_css_class("page")
        tv.set_content(scroll)
        self.toasts.set_child(tv)
        self.set_content(self.toasts)

        self.build()
        self.refresh()
        GLib.timeout_add(1000, self.refresh)
        if os.environ.get("TANDEM_APP_SHOT"):
            whole = os.environ.get("TANDEM_APP_SHOT_PAGE") == "1"
            GLib.timeout_add(1500, self.shoot, clamp if whole else self.toasts, os.environ["TANDEM_APP_SHOT"])

    def shoot(self, widget, path):
        """TANDEM_APP_SHOT=file.png: draw the window to a PNG and quit (TANDEM_APP_SHOT_PAGE=1: the whole
        scrolling page instead). For checking the look and for screenshots."""
        w, h = widget.get_width(), widget.get_height()
        snap = Gtk.Snapshot()
        snap.append_color(Gdk.RGBA(*rgb(self.pal["bg"]), 1), _rect(w, h))
        Gtk.WidgetPaintable(widget=widget).snapshot(snap, w, h)
        tex = self.get_renderer().render_texture(snap.to_node(), _rect(w, h))
        tex.save_to_png(path)
        self.get_application().quit()
        return False

    # -- look
    def apply_palette(self):
        self.pal = DARK if self.sm.get_dark() else LIGHT
        self.css.load_from_string(palette_css(self.pal)) if hasattr(self.css, "load_from_string") \
            else self.css.load_from_data(palette_css(self.pal).encode())
        for w in getattr(self, "drawn", []):
            w.queue_draw()
        for lb, template in getattr(self, "marked", []):
            lb.set_markup(self.fill(template))

    def fill(self, template):
        for k, v in self.pal.items():
            template = template.replace("{" + k + "}", v)
        return template

    def markup(self, lb, template):
        """Pango markup with {colour} names from the palette, redone when light/dark changes."""
        self.marked = getattr(self, "marked", [])
        self.marked.append((lb, template))
        lb.set_markup(self.fill(template))
        return lb

    def text(self, s, *classes):
        """Body text that may hold `code`."""
        return self.markup(label("", *classes, wrap=True), code_markup(s))

    def drawn_widget(self, w):
        self.drawn = getattr(self, "drawn", [])
        self.drawn.append(w)
        return w

    def toast(self, msg):
        self.toasts.add_toast(Adw.Toast(title=msg, timeout=3))

    def send(self, cmd, ok=None):
        if ctl(cmd, quiet=True):
            if ok:
                self.toast(ok)
            return True
        self.toast("Tandem isn't running")
        return False

    # -- building
    def build(self):
        self.build_down()
        self.build_setup()
        self.build_link()
        for title, feats in FEATURES:
            self.section(title)
            group = box(Gtk.Orientation.VERTICAL, 0, "group")
            for key, d, t, s, subs in feats:
                group.append(self.feature(key, d, t, s, subs))
            self.col.append(group)
        self.build_computer()
        self.build_about()

    def section(self, title):
        self.col.append(label(title, "h2"))

    def build_down(self):
        card = box(Gtk.Orientation.VERTICAL, 4, "card", "wash")
        card.append(label("THIS COMPUTER", "label", "phone"))
        card.append(label("Tandem isn't running", "h1"))
        card.append(self.text("Start it, and it starts by itself at every login after that.", "text"))
        row = hbox(8, margin_top=10)
        row.append(button("Start Tandem", "ink", self.start_service))
        card.append(row)
        self.col.append(card)
        self.refreshers.append(lambda st: card.set_visible(st is None))

    def build_setup(self):
        card = box(Gtk.Orientation.VERTICAL, 0, "card")
        card.append(label("Set up", "h1"))
        steps = [
            "Install the Tandem app on your Android phone (tandem.aidenwb.com).",
            "Pair the phone with this computer in Bluetooth settings, like any device.",
            "Open Tandem on the phone. This computer finds it and asks to pair; tap Allow there.",
        ]
        for i, s in enumerate(steps, 1):
            r = hbox(12, margin_top=12)
            n = label(str(i), "mono", xalign=0.5, width_chars=2)
            n.add_css_class("note")
            r.append(n)
            lb = label(s, wrap=True, hexpand=True)
            r.append(lb)
            card.append(r)
        status = label("", "mono", wrap=True, margin_top=14)
        card.append(status)
        row = hbox(8, margin_top=12)
        row.append(button("Look for my phone", "ink", lambda: self.send({"op": "pair"}, "Looking for your phone")))
        bt = button("Bluetooth settings", None, self.bluetooth_settings)
        row.append(bt)
        card.append(row)
        self.col.append(card)

        def refresh(st):
            ln = (st or {}).get("link") or {}
            card.set_visible(st is not None and not ln.get("paired"))
            s = ln.get("bt_state") or ""
            status.set_label({
                "looking": "Looking for a paired phone with Tandem open…",
                "connecting": "Found a phone. Connecting…",
                "pairing": "Waiting for you to tap Allow on the phone.",
                "no phone paired over Bluetooth": "No phone is paired with this computer over Bluetooth yet.",
            }.get(s, s.capitalize()))
            bt.set_visible(bluetooth_settings_cmd() is not None)
        self.refreshers.append(refresh)

    def build_link(self):
        card = box(Gtk.Orientation.VERTICAL, 0, "card")
        names = hbox(0)
        me = box(Gtk.Orientation.VERTICAL, 2, hexpand=True)
        me.append(self.dot_label("PHONE", "phone"))
        phone_name = label("", "name", ellipsize=Pango.EllipsizeMode.END)
        me.append(phone_name)
        them = box(Gtk.Orientation.VERTICAL, 2, hexpand=True)
        them.append(self.dot_label("THIS COMPUTER", "pc", xalign=1.0))
        pc_name = label("", "name", xalign=1.0, ellipsize=Pango.EllipsizeMode.END)
        them.append(pc_name)
        names.append(me)
        names.append(them)
        card.append(names)
        lanes = self.drawn_widget(LinkArea(self))
        lanes.set_margin_top(8)
        lanes.set_margin_bottom(6)
        card.append(lanes)
        line = label("", "h1")
        card.append(line)
        detail = label("", "text", wrap=True, margin_top=4)
        card.append(detail)

        facts = Gtk.Grid(column_spacing=18, row_spacing=6, margin_top=10)
        rows = {}
        for i, (k, name) in enumerate((("battery", "PHONE BATTERY"), ("hp", "HEADPHONES"),
                                       ("clip", "CLIPBOARD"), ("addr", "PHONE ADDRESS"))):
            a, b = label(name, "label"), label("", "value", ellipsize=Pango.EllipsizeMode.END, hexpand=True)
            facts.attach(a, 0, i, 1, 1)
            facts.attach(b, 1, i, 1, 1)
            rows[k] = (a, b)
        card.append(facts)

        hub = hbox(4, "hub", margin_top=14)
        hub.append(label("THE HUB", "label", margin_start=10, margin_end=6))
        hub_phone = Gtk.Button(label="PHONE", hexpand=True)
        hub_pc = Gtk.Button(label="COMPUTER", hexpand=True)
        hub_phone.connect("clicked", lambda *_: self.send({"op": "switch", "to": "phone"}))
        hub_pc.connect("clicked", lambda *_: self.send({"op": "switch", "to": "laptop"}))
        hub.append(hub_phone)
        hub.append(hub_pc)
        card.append(hub)

        if hasattr(Adw, "WrapBox"):  # libadwaita 1.7+: buttons keep their own widths
            actions = Adw.WrapBox(child_spacing=8, line_spacing=8, margin_top=16)
        else:
            actions = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=8, row_spacing=8,
                                  margin_top=16, max_children_per_line=6)
        ring = button("Ring my phone", "ink", self.ring)
        send_files = button("Send files…", None, self.pick_files)
        send_clip = button("Send clipboard", None,
                           lambda: self.send({"op": "send-clipboard"}, "Sent the clipboard to the phone"))
        typing = button("Type on phone", None, lambda: self.spawn("type"))
        mirror = button("Show phone screen", None, lambda: self.spawn("screen"))
        for b in (ring, send_files, send_clip, typing, mirror):
            actions.append(b)

        def show(b, on):
            if isinstance(b.get_parent(), Gtk.FlowBoxChild):  # hide the slot, not just the button in it
                b.get_parent().set_visible(on)
            b.set_visible(on)
        card.append(actions)
        self.col.append(card)

        def refresh(st):
            ln = (st or {}).get("link") or {}
            card.set_visible(bool(ln.get("paired")))
            if not ln.get("paired"):
                return
            s = st["settings"]
            up = bool(ln.get("bt") or ln.get("net"))
            phone_name.set_label(ln.get("phone") or "Your phone")
            pc_name.set_label(st.get("name") or "")
            lanes.set(bool(ln.get("bt")), bool(ln.get("net")))
            if not up:
                line.set_label("Not connected")
                detail.set_label("It connects over Bluetooth when the phone is nearby with Tandem running, or "
                                 "over the network when you're on the same Wi-Fi (or Tailscale).")
            else:
                line.set_label("Connected" if ln.get("net") else "Connected over Bluetooth")
                detail.set_label("" if ln.get("net") else "Big files and audio wait for a shared network.")
            detail.set_visible(bool(detail.get_label()))
            b = st.get("phone_battery")
            hp = st.get("headphones")
            hb = st.get("headphones_battery")
            c = st.get("clipboard") or {}
            values = {
                "battery": f"{b['level']}%" + (" · charging" if b.get("charging") else "") if b and up else None,
                "hp": ((st.get("headphones_name") or "Bluetooth headphones") + (f" · {hb}%" if hb is not None else "")
                       + {"phone": " · on the phone", "laptop": " · on this computer"}.get(st.get("owner"),
                                                                                        " · not connected"))
                if hp else None,
                "clip": f"↑ {c.get('to_phone', 0)} sent{ago(c.get('last_to_phone'))}   "
                        f"↓ {c.get('from_phone', 0)} received{ago(c.get('last_from_phone'))}" if c else None,
                "addr": ln.get("phone_ip") if ln.get("net") else None,
            }
            for k, (a, v) in rows.items():
                a.set_visible(values[k] is not None)
                v.set_visible(values[k] is not None)
                if values[k] is not None:
                    v.set_label(values[k])
            show_hub = up and s.get("audio_share") and st.get("owner") in ("phone", "laptop")
            hub.set_visible(bool(show_hub))
            pc_hub = st.get("prefer") == "laptop"
            for btn, on, cls in ((hub_phone, not pc_hub, "on-phone"), (hub_pc, pc_hub, "on-pc")):
                (btn.add_css_class if on else btn.remove_css_class)(cls)
            ringing = time.monotonic() < self.ringing_until
            ring.set_label("Stop ringing" if ringing else "Ring my phone")
            show(ring, bool(s.get("find_phone")))
            show(send_files, bool(s.get("files")))
            show(send_clip, bool(s.get("clip_from_laptop")))
            show(typing, bool(s.get("remote_input")))
            show(mirror, bool(s.get("screen_mirror")))
            for b in (ring, send_files, send_clip, typing):
                b.set_sensitive(up)
            actions.set_visible(any(b.get_visible() for b in (ring, send_files, send_clip, typing, mirror)))
        self.refreshers.append(refresh)

    def dot_label(self, text, color, xalign=0.0):
        return self.markup(label("", "label", xalign=xalign), f"<span foreground='{{{color}}}'>●</span>  {text}")

    def feature(self, key, direction, title, text, subs):
        row = hbox(14, "row")
        row.append(self.drawn_widget(Badge(self, direction)))
        body = box(Gtk.Orientation.VERTICAL, 3, hexpand=True)
        body.append(label(title, "title", wrap=True))
        body.append(self.text(text, "text"))
        opts = box(Gtk.Orientation.VERTICAL, 4)
        for sk, st in subs:
            r = hbox(10, "sub")
            r.append(label(st, wrap=True, hexpand=True))
            r.append(self.switch(sk, st))
            opts.append(r)
        for nk, (owner, nl, lo, hi) in NUMBERS.items():
            if owner == key:
                r = hbox(10, "sub")
                r.append(label(nl, wrap=True, hexpand=True))
                r.append(self.number(nk, lo, hi))
                opts.append(r)
        if key == "files":
            opts.append(self.text("Files from the phone go to your Downloads folder (change it under This "
                                  "computer).", "text"))
        body.append(opts)
        row.append(body)
        sw = self.switch(key, title)
        row.append(sw)
        click = Gtk.GestureClick()
        click.connect("released", lambda g, n, x, y: sw.set_active(not sw.get_active())
                      if _not_in_control(row.pick(x, y, Gtk.PickFlags.DEFAULT)) else None)
        row.add_controller(click)
        has_opts = opts.get_first_child() is not None
        self.refreshers.append(lambda st: opts.set_visible(has_opts and self.value(st, key) is True))
        return row

    def value(self, st, key):
        p = self.pending.get(key)
        if p and time.monotonic() < p[1]:
            return p[0]
        return ((st or {}).get("settings") or self.saved).get(key, DEFAULTS.get(key))

    def set_setting(self, key, value):
        self.pending[key] = (value, time.monotonic() + 3)
        self.send({"op": "set", "key": key, "value": value})
        self.refresh()

    def switch(self, key, title):
        sw = Gtk.Switch(valign=Gtk.Align.START)
        sw.update_property([Gtk.AccessibleProperty.LABEL], [title])
        hid = sw.connect("notify::active", lambda s, _p: self.set_setting(key, s.get_active())
                         if s.get_active() != self.value(self.st, key) else None)

        def refresh(st):
            v = bool(self.value(st, key))
            sw.set_sensitive(st is not None)
            if sw.get_active() != v:
                sw.handler_block(hid)
                sw.set_active(v)
                sw.handler_unblock(hid)
        self.refreshers.append(refresh)
        return sw

    def number(self, key, lo, hi):
        sp = Gtk.SpinButton.new_with_range(lo, hi, 1)
        sp.set_valign(Gtk.Align.CENTER)
        timer = [0]

        def changed(_s):
            if timer[0]:
                GLib.source_remove(timer[0])

            def go():
                timer[0] = 0
                v = int(sp.get_value())
                if v != self.value(self.st, key):
                    self.set_setting(key, v)
                return False
            timer[0] = GLib.timeout_add(700, go)
        hid = sp.connect("value-changed", changed)

        def refresh(st):
            if sp.has_focus() or timer[0]:
                return
            v = int(self.value(st, key) or DEFAULTS[key])
            if int(sp.get_value()) != v:
                sp.handler_block(hid)
                sp.set_value(v)
                sp.handler_unblock(hid)
        self.refreshers.append(refresh)
        return sp

    def build_computer(self):
        """Settings only this computer has (~/.config/tandem/config). Changing one restarts Tandem."""
        self.section("This computer")
        group = box(Gtk.Orientation.VERTICAL, 0, "group")

        defaults = {}

        def folder_row(key, title, default):
            row = hbox(14, "row")
            body = box(Gtk.Orientation.VERTICAL, 3, hexpand=True)
            body.append(label(title, "title"))
            path = label("", "code", wrap=True)
            body.append(path)
            row.append(body)
            row.append(button("Change…", None, lambda: self.pick_folder(key, title)))
            group.append(row)
            def refresh(st):
                if key not in defaults:
                    defaults[key] = default()  # xdg-user-dir: once, not every second
                path.set_label((load_config().get(key) or defaults[key]).replace(os.path.expanduser("~"), "~", 1))
            self.refreshers.append(refresh)

        folder_row("FILES_DIR", "Files from the phone go to", lambda: user_dir("DOWNLOAD", "Downloads"))
        folder_row("SCREENSHOTS_DIR", "Phone screenshots go to",
                   lambda: os.path.join(user_dir("PICTURES", "Pictures"), "Phone"))

        row = hbox(14, "row")
        body = box(Gtk.Orientation.VERTICAL, 3, hexpand=True)
        body.append(label("Desktop notifications from Tandem", "title"))
        body.append(self.text("Pairing, files arriving, low battery. Phone notifications are a feature above.", "text"))
        row.append(body)
        notify = Gtk.Switch(valign=Gtk.Align.START)
        nid = notify.connect("notify::active", lambda s, _p: self.set_config("NOTIFY", "1" if s.get_active() else "0"))
        row.append(notify)
        group.append(row)

        def refresh_notify(st):
            v = load_config().get("NOTIFY", CONFIG_DEFAULTS["NOTIFY"]) == "1"
            if notify.get_active() != v:
                notify.handler_block(nid)
                notify.set_active(v)
                notify.handler_unblock(nid)
        self.refreshers.append(refresh_notify)

        row = hbox(14, "row")
        body = box(Gtk.Orientation.VERTICAL, 3, hexpand=True)
        body.append(label("The Tandem service", "title"))
        svc = label("", "code")
        body.append(svc)
        row.append(body)
        restart = button("Restart", None, lambda: self.restart_service("Restarted Tandem"))
        row.append(restart)
        group.append(row)
        self.refreshers.append(lambda st: svc.set_label("running" if st else "not running"))
        self.col.append(group)

    def build_about(self):
        self.section("About")
        card = box(Gtk.Orientation.VERTICAL, 4, "card")
        version = label("", "mono")
        card.append(version)
        paired = label("", "text", wrap=True)
        card.append(paired)
        row = hbox(8, margin_top=10)
        unpair = button("Unpair the phone", None, self.confirm_unpair)
        row.append(unpair)
        row.append(button("Website and help", None, lambda: Gtk.UriLauncher(uri=SITE).launch(self, None, None, None)))
        card.append(row)
        self.col.append(card)

        def refresh(st):
            ln = (st or {}).get("link") or {}
            version.set_label(f"Tandem {__version__} · free software, GPL-3.0")
            unpair.set_visible(bool(ln.get("paired")))
            paired.set_label(f"Paired with {ln.get('phone')}. The phone sees this computer as {st.get('name')}."
                             if ln.get("paired") else "No phone paired yet.")
        self.refreshers.append(refresh)

    # -- refresh
    def refresh(self):
        st = state()
        self.st = st
        # With the daemon stopped, show the settings it saved last rather than the defaults.
        self.saved = {} if st else (read_json(SETTINGS) or {}).get("values") or {}
        for k in [k for k, (_, until) in self.pending.items() if time.monotonic() >= until or
                  (st and st["settings"].get(k) == self.pending[k][0])]:
            del self.pending[k]
        for r in self.refreshers:
            try:
                r(st)
            except Exception as e:  # one broken row shouldn't freeze the window
                print("tandem app:", repr(e), file=sys.stderr)
        return True

    # -- actions
    def ring(self):
        on = time.monotonic() >= self.ringing_until
        if self.send({"op": "ring", "on": on}, "Ringing your phone" if on else None):
            self.ringing_until = time.monotonic() + 30 if on else 0
        self.refresh()

    def spawn(self, cmd):
        try:
            subprocess.Popen([tandem_bin(), cmd], start_new_session=True, stdin=subprocess.DEVNULL)
        except OSError as e:
            self.toast(f"Couldn't start tandem {cmd}: {e.strerror}")

    def pick_files(self):
        d = Gtk.FileDialog(title="Send to your phone")

        def done(dlg, res):
            try:
                files = dlg.open_multiple_finish(res)
            except GLib.Error:
                return
            paths = [f.get_path() for f in files if f.get_path()]
            if paths:
                n = len(paths)
                self.send({"op": "send-files", "paths": paths}, f"Sending {n} file{'s' if n > 1 else ''} to your phone")
        d.open_multiple(self, None, done)

    def pick_folder(self, key, title):
        d = Gtk.FileDialog(title=title)

        def done(dlg, res):
            try:
                f = dlg.select_folder_finish(res)
            except GLib.Error:
                return
            if f and f.get_path():
                self.set_config(key, f.get_path())
        d.select_folder(self, None, done)

    def set_config(self, key, value):
        if load_config().get(key) == value:
            return
        set_config(key, value)
        self.restart_service("Saved. Tandem restarted to use it")

    def start_service(self):
        r = subprocess.run(["systemctl", "--user", "start", "tandem.service"], capture_output=True, text=True)
        self.toast("Starting Tandem" if r.returncode == 0 else f"Couldn't start it: {r.stderr.strip()}")

    def restart_service(self, msg):
        r = subprocess.run(["systemctl", "--user", "restart", "tandem.service"], capture_output=True, text=True)
        self.toast(msg if r.returncode == 0 else f"Couldn't restart it: {r.stderr.strip()}")

    def bluetooth_settings(self):
        cmd = bluetooth_settings_cmd()
        if cmd:
            subprocess.Popen(cmd, start_new_session=True)

    def confirm_unpair(self):
        name = ((self.st or {}).get("link") or {}).get("phone") or "the phone"
        d = Adw.AlertDialog(heading=f"Unpair {name}?",
                            body="Tandem stops working with it until you pair again. The Bluetooth pairing stays.")
        d.add_response("cancel", "Cancel")
        d.add_response("unpair", "Unpair")
        d.set_response_appearance("unpair", Adw.ResponseAppearance.DESTRUCTIVE)
        d.connect("response", lambda _d, r: self.send({"op": "unpair"}, f"Unpaired {name}") if r == "unpair" else None)
        d.present(self)


def _not_in_control(w):
    """True unless the click landed on a button, switch or field inside the row."""
    while w is not None:
        if isinstance(w, (Gtk.Button, Gtk.Switch, Gtk.SpinButton, Gtk.Entry, Gtk.Text)):
            return False
        if w.has_css_class("row"):
            return True
        w = w.get_parent()
    return True


def bluetooth_settings_cmd():
    for cmd in (["gnome-control-center", "bluetooth"], ["systemsettings", "kcm_bluetooth"], ["blueman-manager"],
                ["blueberry"], ["overskride"], ["bluedevil-wizard"]):
        if shutil.which(cmd[0]):
            return cmd
    return None


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        GLib.set_application_name("Tandem")

    def do_startup(self):
        Adw.Application.do_startup(self)
        load_fonts()
        theme = Gtk.IconTheme.get_for_display(Gdk.Display.get_default())
        theme.add_search_path(UI)  # tandem.svg, for running from the repo before install.sh put it in hicolor

    def do_activate(self):
        win = self.get_active_window() or Window(self)
        win.present()


def main():
    sys.exit(App().run([sys.argv[0]]))
