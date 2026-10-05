"""BlueZ over busctl (no Python dependencies)."""
import json
import subprocess

from .config import log

A2DP_SOURCE = "0000110a-0000-1000-8000-00805f9b34fb"  # what a phone exposes
A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb"  # what headphones expose
HFP_AG = "0000111f-0000-1000-8000-00805f9b34fb"
HSP_AG = "00001112-0000-1000-8000-00805f9b34fb"
CLASS_PHONE = 0x02  # major device class
CLASS_AUDIO = 0x04


def devices():
    """Address -> {path, name, connected, blocked, paired, uuids, transport, cls, icon, battery}, or None
    if BlueZ can't be read."""
    try:
        out = subprocess.run(
            ["busctl", "--system", "--json=short", "call", "org.bluez", "/",
             "org.freedesktop.DBus.ObjectManager", "GetManagedObjects"],
            capture_output=True, text=True, timeout=5)
        objs = json.loads(out.stdout)["data"][0]
    except Exception as e:
        log("bluez: can't read devices:", e)
        return None
    devs = {}
    for path, ifaces in objs.items():
        d = ifaces.get("org.bluez.Device1")
        if not d:
            continue
        g = lambda k, dflt=None: d.get(k, {}).get("data", dflt)
        bat = (ifaces.get("org.bluez.Battery1") or {}).get("Percentage", {}).get("data")
        devs[g("Address")] = {
            "path": path, "name": g("Alias") or g("Name") or "", "connected": g("Connected", False),
            "blocked": g("Blocked", False), "paired": g("Paired", False), "uuids": g("UUIDs", []),
            "transport": False, "cls": g("Class", 0), "icon": g("Icon", ""), "battery": bat,
        }
    # An A2DP link has a MediaTransport1 under the device. It exists while linked, even while idle; the
    # PipeWire node for it only shows up once audio flows.
    # The transport sits under the device, at dev_X/sepN/fdM or dev_X/fdM depending on how it was set up.
    for path, ifaces in objs.items():
        if "org.bluez.MediaTransport1" in ifaces:
            for dev in devs.values():
                if path.startswith(dev["path"] + "/"):
                    dev["transport"] = True
    return devs


def is_phone(d):
    return ((d["cls"] >> 8) & 0x1F) == CLASS_PHONE or d["icon"] == "phone" or (
        HFP_AG in d["uuids"] and A2DP_SOURCE in d["uuids"])


def is_headphones(d):
    return A2DP_SINK in d["uuids"] and (((d["cls"] >> 8) & 0x1F) == CLASS_AUDIO or d["icon"].startswith("audio"))


def bonded_phones():
    """[(address, name)] of paired phones, connected ones first."""
    devs = devices() or {}
    phones = [(a, d) for a, d in devs.items() if d["paired"] and is_phone(d)]
    phones.sort(key=lambda x: not x[1]["connected"])
    return [(a, d["name"]) for a, d in phones]


def busctl(*args, timeout=40):
    r = subprocess.run(["busctl", "--system", *args], capture_output=True, text=True, timeout=timeout)
    return r.returncode == 0, (r.stderr or r.stdout).strip()


def dev_call(path, method, *args):
    return busctl("call", "org.bluez", path, "org.bluez.Device1", method, *args)


def set_blocked(path, value):
    return busctl("set-property", "org.bluez", path, "org.bluez.Device1", "Blocked", "b",
                  "true" if value else "false", timeout=10)


def owner():
    ok, out = busctl("call", "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                     "GetNameOwner", "s", "org.bluez", timeout=5)
    return out if ok else None
