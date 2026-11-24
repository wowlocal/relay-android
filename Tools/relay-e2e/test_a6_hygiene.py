#!/usr/bin/env python3
"""A6 e2e: fork hygiene. No Telegram-only entry points are reachable, branding says Relay.

  python3 tools/relay-e2e/test_a6_hygiene.py [--no-install] [--serial emulator-5556] [--user carol]

Logs in with the debug broadcast (org.relaychat.DEBUG_LOGIN), then walks the reachable screens and
uiautomator-dumps each one:
  chat list, chat-list overflow menu, Saved Messages chat, its header menu, attach menu, emoji panel,
  Contacts tab, Settings (whole list, search, About), own profile and its menu, the APK label.
Every dump is checked against FORBIDDEN (Premium, Stars, Stories, Stickers, Telegram FAQ, phone
numbers, secret chats, gifts, wallet, boosts, "Telegram" anywhere …).
Screenshots: docs/relay/screens/a6-*.png.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e as r  # noqa: E402

# Text (or content description) that must not appear on any reachable screen.
FORBIDDEN = [
    (r"premium", "Premium"),
    (r"\bstars?\b", "Stars"),
    (r"\bstor(y|ies)\b", "Stories"),
    (r"\bstickers?\b", "Stickers"),
    (r"\bgifs?\b", "GIFs"),
    (r"telegram", "Telegram"),
    (r"\bfaq\b", "FAQ"),
    (r"ask a question", "Ask a Question"),
    (r"phone number|\+\d[\d ]{6,}", "phone number"),
    (r"secret chat|encrypted chat", "secret chat"),
    (r"\bgifts?\b", "Gift"),
    (r"wallet|\bton\b|\bgrams?\b", "Wallet"),
    (r"\bboost", "Boost"),
    (r"giveaway", "Giveaway"),
    (r"business", "Business"),
    (r"invite friends", "Invite Friends"),
    (r"recent calls|voice call|video call", "Calls"),
    (r"new channel", "New Channel"),
    (r"passport", "Passport"),
    (r"\bproxy\b", "Proxy"),
    (r"qr code|link desktop", "QR"),
    (r"fragment", "Fragment"),
]
FORBIDDEN_RX = [(re.compile(p, re.I), label) for p, label in FORBIDDEN]


def shot(d, name):
    return d.screenshot(os.path.join(r.SCREENS, "a6-%s.png" % name))


def forbidden_hits(texts):
    hits = []
    for t in texts:
        for rx, label in FORBIDDEN_RX:
            if rx.search(t):
                hits.append("%s: %r" % (label, t[:80]))
    return hits


def check_screen(d, check, name, nodes=None, take_shot=True):
    nodes = nodes if nodes is not None else d.dump()
    texts = d.screen_texts(nodes)
    if take_shot:
        shot(d, name)
    hits = forbidden_hits(texts)
    check.ok("%s: no Telegram-only items" % name, not hits and texts, "; ".join(hits) if hits else ("%d texts" % len(texts) if texts else "empty dump"))
    return nodes, texts


def back(d, times=1):
    for _ in range(times):
        d.key("KEYCODE_BACK")
        time.sleep(1.2)


def fresh_login(d, check, user, password):
    d.force_stop()
    d.clear_data()
    for permission in ("POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "CAMERA", "RECORD_AUDIO"):
        d.shell("pm grant %s android.permission.%s" % (d.package, permission), check=False)
    d.reverse()
    d.logcat_clear()
    d.launch()
    d.wait_for(desc="relay_server_field", timeout=40)
    d.broadcast("org.relaychat.DEBUG_LOGIN", server=r.MM_URL, user=user, password=password)
    ok = d.wait_log(r"DEBUG_LOGIN (ok|failed)[^\n]*", timeout=40, tags=["RelayDebug"])
    check.ok("debug login as %s" % user, ok is not None and ok.group(1) == "ok", ok.group(0) if ok else "no result")
    return wait_chat_list(d)


def wait_chat_list(d, timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = d.dump()
        texts = d.screen_texts(nodes)
        if "Chats" in texts and "Search Chats" in texts:
            return nodes
        for label in ("Allow all", "Allow", "Don’t allow", "Don't allow"):
            button = d.find(text=label, nodes=nodes)
            if button:
                d.tap_node(button)
                break
        time.sleep(1)
    return None


def tap_text(d, text, timeout=10, contains=False):
    node = d.wait_for(text=text, timeout=timeout, contains=contains)
    if node:
        d.tap_node(node)
        time.sleep(1.5)
    return node


def tap_desc(d, desc, timeout=10, pick=None):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = [n for n in d.dump() if n.desc == desc]
        if nodes:
            node = pick(nodes) if pick else nodes[0]
            d.tap_node(node)
            time.sleep(1.5)
            return node
        time.sleep(1)
    return None


_SIZE = {}


def screen_size(d):
    if d.serial not in _SIZE:
        # "Override size" (when set) comes after "Physical size" and is what apps get.
        sizes = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
        _SIZE[d.serial] = (int(sizes[-1][0]), int(sizes[-1][1])) if sizes else (1080, 2400)
    return _SIZE[d.serial]


def swipe(d, up=True, right_pane=False):
    """Scrolls the left column (the whole screen on phones; tablets show two panes)."""
    w, h = screen_size(d)
    x = int(w * (0.7 if right_pane and w > h else 0.15))
    y1, y2 = int(h * 0.75), int(h * 0.25)
    if not up:
        y1, y2 = y2, y1
    d.shell("input swipe %d %d %d %d 400" % (x, y1, x, y2))
    time.sleep(1.2)


def scroll_down(d):
    swipe(d, up=True)


# MARK: - screens

def scenario_chat_list(d, check, nodes):
    nodes, texts = check_screen(d, check, "chatlist", nodes)
    check.ok("chat-list title is Relay", "Relay" in texts, "texts: %s" % texts[:12])
    check.ok("no stories bar / story camera button", not any(re.search(r"story|stories", t, re.I) for t in texts))

    if tap_desc(d, "More options") is None:
        check.ok("chat-list overflow menu opens", False)
        return
    nodes, texts = check_screen(d, check, "chatlist-menu")
    check.ok("overflow menu keeps New Group and Saved Messages", "New Group" in texts and "Saved Messages" in texts, str(texts))


def scenario_saved_chat(d, check):
    # Opened from the overflow menu, which is still showing.
    if tap_text(d, "Saved Messages") is None:
        check.ok("Saved Messages opens", False)
        return
    time.sleep(2)
    nodes, texts = check_screen(d, check, "chat")
    if tap_desc(d, "More options", pick=lambda ns: max(ns, key=lambda n: n.bounds[0])):
        check_screen(d, check, "chat-menu")
        back(d)
    if tap_desc(d, "Attach media"):
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "attach")
        check.ok("attach menu has Gallery and File", any(t in texts for t in ("Gallery", "Photo")) and "File" in texts, str(texts[:20]))
        check.ok("attach menu has no Poll / Contact / Checklist", not any(t in texts for t in ("Poll", "Contact", "Checklist", "To Do", "Wallet")), str(texts[:20]))
        back(d)
    if tap_desc(d, "Emoji"):
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "emoji")
        back(d)
    back(d)


def scenario_contacts(d, check):
    if tap_text(d, "Contacts") is None:
        check.ok("Contacts tab opens", False)
        return
    time.sleep(1.5)
    check_screen(d, check, "contacts")


def scenario_settings(d, check, user):
    if tap_text(d, "Settings") is None:
        check.ok("Settings tab opens", False)
        return
    time.sleep(2)
    nodes, texts1 = check_screen(d, check, "settings")
    scroll_down(d)
    nodes, texts2 = check_screen(d, check, "settings-2")
    scroll_down(d)
    nodes, texts3 = check_screen(d, check, "settings-3")
    texts = texts1 + texts2 + texts3
    joined = "\n".join(texts)
    check.ok("settings shows @%s" % user, ("@" + user) in joined, str(texts1[:10]))
    check.ok("settings shows the email", re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", joined) is not None)
    check.ok("settings shows the server row", "Server" in texts and any("localhost:8065" in t for t in texts))
    for row in ("Chat Settings", "Notifications", "Data and Storage", "Passcode Lock", "Language", "About Relay", "Log Out"):
        check.ok("settings has '%s'" % row, row in texts)
    check.ok("settings footer says Relay for Android", any(t.startswith("Relay for Android") for t in texts), str([t for t in texts if "Android" in t]))
    check.ok("no Privacy / Folders rows", "Privacy and Security" not in texts and "Chat Folders" not in texts)

    if tap_text(d, "About Relay") is not None:
        time.sleep(1.5)
        about1 = d.screen_texts()
        shot(d, "about")
        # Phone layouts need more scrolling than the tablet's right pane to reach the GPL notice.
        about2 = []
        for _ in range(14):
            swipe(d, right_pane=True)
            about2 += d.screen_texts()
            if any("GNU General Public License" in t for t in about2):
                break
        shot(d, "about-2")
        # The license list and the GPL notice name Telegram for Android on purpose (attribution).
        attribution = re.compile(r"Telegram for Android|GNU General Public License|trademark|Boost\.Regex", re.I)
        hits = forbidden_hits([t for t in about1 + about2 if not attribution.search(t)])
        check.ok("about: no Telegram-only items (attribution excepted)", not hits, "; ".join(hits))
        about = "\n".join(about1 + about2)
        check.ok("About shows the version", "Version" in about1)
        check.ok("About lists open-source licenses", "Open-Source Licenses" in about and "Apache-2.0" in about)
        # The GPL notice names Telegram for Android on purpose (license attribution).
        check.ok("About has the GPL notice", "GNU General Public License" in about)
        back(d)

    # Settings search must not find Telegram-only settings.
    swipe(d, up=False)
    swipe(d, up=False)
    if tap_desc(d, "Search"):
        for query in ("premium", "privacy", "stickers", "proxy"):
            field = [n for n in d.dump() if n.cls.endswith("EditText")]
            if field:
                d.clear_field(field[0], 20)
            d.type_text(query)
            time.sleep(1.5)
            nodes = d.dump()
            results = [t for t in d.screen_texts(nodes) if t.lower() != query and "search" not in t.lower()]
            hits = forbidden_hits(results)
            check.ok("settings search '%s' finds nothing Telegram-only" % query, not hits, "; ".join(hits))
        shot(d, "settings-search")
        back(d, 2)


def scenario_profile(d, check):
    if tap_text(d, "Profile") is None:
        check.ok("Profile tab opens", False)
        return
    time.sleep(2)
    check_screen(d, check, "profile")
    if tap_desc(d, "More options", pick=lambda ns: max(ns, key=lambda n: n.bounds[0])):
        check_screen(d, check, "profile-menu")
        back(d)


def check_app_label(check):
    apk = os.environ.get("RELAY_APK", r.DEFAULT_APK)
    tools = sorted(glob.glob(os.path.join(r.ANDROID_HOME, "build-tools", "*", "aapt2")))
    if not tools or not os.path.exists(apk):
        check.ok("APK label check (aapt2, APK found)", False, apk)
        return
    out = subprocess.run([tools[-1], "dump", "badging", apk], capture_output=True, text=True).stdout
    m = re.search(r"application-label:'([^']*)'", out)
    label = m.group(1) if m else ""
    check.ok("launcher label is Relay", label in ("Relay", "Relay Debug"), label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--user", default="carol")
    args = ap.parse_args()

    d = r.Device(args.serial)
    check = r.Check("A6 hygiene")
    password = r.mm_password()
    if not args.no_install:
        print(d.install().strip().splitlines()[-1])
    check_app_label(check)

    nodes = fresh_login(d, check, args.user, password)
    check.ok("chat list after login", nodes is not None)
    if nodes is None:
        shot(d, "no-chatlist")
        print(check.summary())
        sys.exit(1)
    time.sleep(3)
    nodes = d.dump()

    scenario_chat_list(d, check, nodes)
    scenario_saved_chat(d, check)
    if wait_chat_list(d, timeout=8) is None:
        d.launch()
        wait_chat_list(d, timeout=20)
    scenario_contacts(d, check)
    scenario_settings(d, check, args.user)
    scenario_profile(d, check)

    crashes = d.crashes()
    check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    blocked = d.logcat("RelayFeatures")
    if blocked.strip():
        print("fragments refused by RelayFeatures.isFragmentAllowed:\n" + blocked)
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
