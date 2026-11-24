"""Relay for Android e2e helpers (Python 3 stdlib only).

Drives the app through adb: install, adb reverse, launch, logcat, screenshots, uiautomator dumps,
taps and typing, plus a socket check from /proc/net/tcp*. See docs/relay/BUILD.md.

Environment:
  RELAY_SERIAL      adb serial (default: emulator-5554)
  RELAY_APK         APK to install (default: the afatDebug output on X5)
  RELAY_MM_URL      server URL as the device sees it (default: http://localhost:8065, via adb reverse)
  RELAY_MM_ENV_FILE dev-server .env holding SEED_USER_PASSWORD
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ANDROID_HOME = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
ADB = os.path.join(ANDROID_HOME, "platform-tools", "adb")
PACKAGE = "org.relaychat.android.debug"
LAUNCHER = PACKAGE + "/org.telegram.messenger.DefaultIcon"
DEFAULT_APK = "/Volumes/X5/relay-android/build/TMessagesProj_App/outputs/apk/afat/debug/app.apk"
MM_PORT = 8065
MM_URL = os.environ.get("RELAY_MM_URL", "http://localhost:%d" % MM_PORT)
ENV_FILE = os.environ.get(
    "RELAY_MM_ENV_FILE",
    os.path.expanduser("~/Developer/relay-calls-integration/ios-device/infra/dev-server/.env"))
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCREENS = os.path.join(REPO, "docs", "relay", "screens")


def mm_password():
    """SEED_USER_PASSWORD from the dev-server .env (never written anywhere)."""
    with open(ENV_FILE) as f:
        for line in f:
            line = line.strip()
            if line.startswith("SEED_USER_PASSWORD="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise RuntimeError("SEED_USER_PASSWORD not found in " + ENV_FILE)


class Node:
    """A uiautomator node: text, content-desc, class, bounds."""

    def __init__(self, el):
        self.text = el.get("text", "")
        self.desc = el.get("content-desc", "")
        self.cls = el.get("class", "")
        self.resource_id = el.get("resource-id", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", "[0,0][0,0]"))
        self.bounds = tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0)

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def __repr__(self):
        return "Node(%r, desc=%r, %s, %s)" % (self.text, self.desc, self.cls.split(".")[-1], self.bounds)


class Device:
    def __init__(self, serial=None, package=PACKAGE):
        self.serial = serial or os.environ.get("RELAY_SERIAL", "emulator-5554")
        self.package = package

    # MARK: adb

    def adb(self, *args, timeout=120, check=True, binary=False):
        cmd = [ADB, "-s", self.serial] + list(args)
        res = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and res.returncode != 0:
            raise RuntimeError("adb %s failed (%d): %s" % (" ".join(args), res.returncode, res.stderr.decode(errors="replace")))
        return res.stdout if binary else res.stdout.decode(errors="replace")

    def shell(self, command, timeout=60, check=True):
        return self.adb("shell", command, timeout=timeout, check=check)

    def install(self, apk=None):
        apk = apk or os.environ.get("RELAY_APK", DEFAULT_APK)
        return self.adb("install", "-r", "-t", apk, timeout=300)

    def clear_data(self):
        self.shell("pm clear " + self.package)

    def force_stop(self):
        self.shell("am force-stop " + self.package)

    def reverse(self, port=MM_PORT):
        self.adb("reverse", "tcp:%d" % port, "tcp:%d" % port)

    def launch(self):
        self.shell("am start -W -n " + LAUNCHER, timeout=60)

    def pid(self):
        out = self.shell("pidof " + self.package, check=False).strip()
        return int(out.split()[0]) if out else None

    def uid(self):
        out = self.shell("pm list packages -U " + self.package)
        m = re.search(r"uid:(\d+)", out)
        return int(m.group(1)) if m else None

    def broadcast(self, action, **extras):
        args = ["am", "broadcast", "-a", action, "-p", self.package]
        for key, value in extras.items():
            args += ["--es", key, "'%s'" % str(value).replace("'", "'\\''")]
        return self.shell(" ".join(args))

    # MARK: logcat

    def logcat_clear(self):
        self.adb("logcat", "-c", check=False)

    def logcat(self, *tags):
        out = self.adb("logcat", "-d", "-v", "time", timeout=60)
        if not tags:
            return out
        return "\n".join(line for line in out.splitlines() if any(t in line for t in tags))

    def wait_log(self, pattern, timeout=30, tags=None):
        deadline = time.time() + timeout
        rx = re.compile(pattern)
        while time.time() < deadline:
            text = self.logcat(*(tags or []))
            m = rx.search(text)
            if m:
                return m
            time.sleep(1)
        return None

    def crashes(self):
        """FATAL EXCEPTION blocks of this app's process in the crash buffer and main log."""
        out = self.adb("logcat", "-d", "-b", "crash", timeout=60, check=False)
        main = self.logcat("AndroidRuntime")
        text = out + "\n" + main
        return [line for line in text.splitlines() if self.package in line or "FATAL EXCEPTION" in line]

    # MARK: screen

    def screenshot(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = self.adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def dump(self):
        """uiautomator dump → list of Node."""
        for _ in range(3):
            self.shell("uiautomator dump /sdcard/relay-ui.xml", timeout=60, check=False)
            xml = self.shell("cat /sdcard/relay-ui.xml", check=False)
            if xml.strip().startswith("<?xml"):
                root = ET.fromstring(xml[xml.index("<?xml"):].encode())
                return [Node(el) for el in root.iter("node")]
            time.sleep(1)
        return []

    def find(self, text=None, desc=None, contains=False, nodes=None):
        nodes = nodes if nodes is not None else self.dump()
        for n in nodes:
            for want, have in ((text, n.text), (desc, n.desc)):
                if want is None:
                    continue
                if (contains and want.lower() in have.lower()) or (not contains and want == have):
                    return n
        return None

    def wait_for(self, text=None, desc=None, contains=False, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            n = self.find(text=text, desc=desc, contains=contains)
            if n:
                return n
            time.sleep(1)
        return None

    def screen_texts(self, nodes=None):
        nodes = nodes if nodes is not None else self.dump()
        return [t for n in nodes for t in (n.text, n.desc) if t]

    def tap(self, x, y):
        self.shell("input tap %d %d" % (x, y))

    def tap_node(self, node):
        self.tap(*node.center)

    def type_text(self, text):
        # `input text` takes %s for spaces; quote everything else for the device shell.
        escaped = text.replace("\\", "\\\\").replace("'", "'\\''").replace(" ", "%s")
        self.shell("input text '%s'" % escaped)

    def key(self, code):
        self.shell("input keyevent %s" % code)

    def clear_field(self, node, max_chars=80):
        self.tap_node(node)
        self.key("KEYCODE_MOVE_END")
        self.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * max_chars))

    def hide_keyboard(self):
        self.key("KEYCODE_ESCAPE")

    # MARK: network

    def tcp_connections(self):
        """(remote_ip, remote_port, state) of this app's TCP sockets from /proc/net/tcp and tcp6."""
        uid = self.uid()
        result = []
        for name in ("tcp", "tcp6"):
            text = self.shell("cat /proc/net/%s" % name, check=False)
            for line in text.splitlines()[1:]:
                parts = line.split()
                if len(parts) < 8 or int(parts[7]) != uid:
                    continue
                rem_ip, rem_port = parts[2].split(":")
                state = parts[3]
                result.append((_decode_ip(rem_ip), int(rem_port, 16), state, name))
        return result


def _decode_ip(hex_ip):
    if len(hex_ip) == 8:
        b = bytes.fromhex(hex_ip)[::-1]
        return ".".join(str(x) for x in b)
    # IPv6: four little-endian 32-bit words.
    words = [bytes.fromhex(hex_ip[i:i + 8])[::-1] for i in range(0, 32, 8)]
    raw = b"".join(words)
    if raw[:12] == b"\x00" * 10 + b"\xff\xff":
        return ".".join(str(x) for x in raw[12:])
    groups = ["%x" % int.from_bytes(raw[i:i + 2], "big") for i in range(0, 16, 2)]
    return ":".join(groups)


LOCAL_ADDRESSES = ("127.0.0.1", "0.0.0.0", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0")
# TCP states: 0A = LISTEN.
LISTEN = "0A"


def non_local_connections(conns):
    """Sockets to anything but localhost (listening sockets ignored)."""
    return [c for c in conns if c[2] != LISTEN and c[0] not in LOCAL_ADDRESSES]


class Check:
    """Collects assertion results for a readable report."""

    def __init__(self, name):
        self.name = name
        self.results = []

    def ok(self, label, condition, detail=""):
        self.results.append((bool(condition), label, detail))
        print(("PASS " if condition else "FAIL ") + label + ((" — " + detail) if detail else ""), flush=True)
        return bool(condition)

    @property
    def passed(self):
        return all(r[0] for r in self.results)

    def summary(self):
        failed = [r for r in self.results if not r[0]]
        return "%s: %d checks, %d failed" % (self.name, len(self.results), len(failed))
