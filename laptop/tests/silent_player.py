#!/usr/bin/python3
"""A silent media player for testing hub switches live: an MPRIS player ("Tandem test") whose audio is a
PipeWire stream of silence from a child process, so WirePlumber treats it like any real player. Every
status change is printed with a timestamp.

    laptop/tests/silent_player.py        # starts playing; Ctrl+C to quit
"""
import subprocess
import time

from gi.repository import Gio, GLib

XML = """<node>
  <interface name="org.mpris.MediaPlayer2">
    <property name="Identity" type="s" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Play"/><method name="Pause"/><method name="PlayPause"/><method name="Stop"/>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
  </interface>
</node>"""


class Player:
    def __init__(self):
        self.proc = None
        self.status = "Stopped"
        self.conn = None

    def set(self, status, why):
        if status == "Playing" and not self.proc:
            self.proc = subprocess.Popen(["pw-play", "--raw", "--format", "s16", "--rate", "48000", "--channels", "2",
                                          "--media-role", "Music", "-"], stdin=open("/dev/zero", "rb"))
        elif status != "Playing" and self.proc:
            self.proc.terminate()
            self.proc = None
        if status != self.status:
            self.status = status
            print(f"{time.strftime('%T')} {status} ({why})", flush=True)
            if self.conn:
                self.conn.emit_signal(None, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties",
                                      "PropertiesChanged", GLib.Variant(
                                          "(sa{sv}as)", ("org.mpris.MediaPlayer2.Player",
                                                         {"PlaybackStatus": GLib.Variant("s", status)}, [])))

    def call(self, conn, sender, path, iface, method, params, inv):
        to = {"Play": "Playing", "Pause": "Paused", "Stop": "Stopped",
              "PlayPause": "Paused" if self.status == "Playing" else "Playing"}[method]
        self.set(to, f"{method} from {sender}")
        inv.return_value(None)

    def get(self, conn, sender, path, iface, prop):
        return {"Identity": GLib.Variant("s", "Tandem test"), "PlaybackStatus": GLib.Variant("s", self.status),
                "CanPause": GLib.Variant("b", True), "CanPlay": GLib.Variant("b", True)}[prop]


def main():
    p = Player()
    node = Gio.DBusNodeInfo.new_for_xml(XML)

    def acquired(conn, name):
        p.conn = conn
        for iface in node.interfaces:
            conn.register_object("/org/mpris/MediaPlayer2", iface, p.call, p.get, None)
        p.set("Playing", "start")

    Gio.bus_own_name(Gio.BusType.SESSION, "org.mpris.MediaPlayer2.tandemtest", Gio.BusNameOwnerFlags.NONE,
                     acquired, None, None)
    try:
        GLib.MainLoop().run()
    except KeyboardInterrupt:
        p.set("Stopped", "quit")


if __name__ == "__main__":
    main()
