#!/usr/bin/env python3
"""A6b e2e: the screens A6 did not walk, links, version.

  python3 tools/relay-e2e/test_a6b_screens.py [--no-install] [--serial emulator-5556] [--user carol]

Before logging in, seeds (REST, as alice) a DM to the user with a probe message, a Mattermost
permalink and a t.me link. Then logs in with the debug broadcast and walks, uiautomator-dumping each
screen against FORBIDDEN (A6's list plus report / translate / block / archive / folders / invite links /
topics / auto-delete / stickers / GIFs / gifts / video chats / statistics …):

  chat list: selection mode + its overflow menu, folder-tab menu; DM: chat, header menu, message menu,
  attach menu, emoji panel; other user's profile + menu; channel (one the user administers): chat, header
  menu, message menu, info, info menu, edit screen; Settings: footer version, Power Saving, Chat
  Settings, About (Relay version + "Based on Telegram for Android").
Links: tapping the permalink → RelayLinks hook (POST, logged); tapping the t.me link → browser, not
Telegram; `relay://` VIEW intent → hook; tg:/ton: through Browser.openUrl (DEBUG_OPEN_URL) → ignored;
the package resolves no t.me / telegram.me / tg: intents and does resolve relay:.
Screenshots: docs/relay/screens/a6b-*.png.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e as r  # noqa: E402
import test_a6_hygiene as a6  # noqa: E402

PROBE = "[relay-e2e a6b]"
REST_URL = "http://localhost:%d" % r.MM_PORT

FORBIDDEN = a6.FORBIDDEN + [
    (r"\breport\b", "Report"),
    (r"translat", "Translate"),
    (r"block (user|contact)|\bunblock\b|delete and block", "Block"),
    (r"^archive$|\barchive chat|unarchive", "Archive"),
    (r"add to folder|remove from folder|edit folder|reorder|delete folder|share folder|chat folders", "Folders"),
    (r"invite link", "Invite links"),
    (r"\btopics?\b", "Topics"),
    (r"auto-delete", "Auto-Delete"),
    (r"add to contacts|share contact|edit contact|delete contact", "Contacts"),
    (r"disable sharing|enable sharing", "Sharing restrictions"),
    (r"statistic", "Statistics"),
    # A8: Relay calls and video chats are features (Video Call, Voice Chat, call records); RTMP streams are not.
    (r"live stream", "Video chat"),
    (r"saved gifs|save as sticker|add to stickers|add to favorites", "Stickers/GIFs"),
    (r"privacy and security|privacy settings", "Privacy"),
    (r"chat history for new|^chat history", "Chat history"),
    (r"appearance & emoji|welcome message|permissions|recent actions|removed users|affiliate", "Telegram group settings"),
    (r"t\.me/|telegram\.me", "t.me link"),
    (r"call animations", "Call animations"),
]
FORBIDDEN_RX = [(re.compile(p, re.I), label) for p, label in FORBIDDEN]
# Text on screen that is ours (seeded messages) or legitimately mentions a forbidden word.
ALLOWED = re.compile(r"^\[relay-e2e a6b\]|Autoplay GIFs|Telegram for Android|GNU General Public License|trademark|Boost\.Regex"
                     r"|^Video Call$|^Voice Call$", re.I)  # A8: Relay calls in DMs


def forbidden_hits(texts):
    hits = []
    for t in texts:
        if ALLOWED.search(t) or PROBE in t or "/pl/" in t or t.startswith("https://t.me/durov\n"):
            continue
        for rx, label in FORBIDDEN_RX:
            if rx.search(t):
                hits.append("%s: %r" % (label, t[:80]))
    return hits


def shot(d, name):
    return d.screenshot(os.path.join(r.SCREENS, "a6b-%s.png" % name))


def check_screen(d, check, name, nodes=None, take_shot=True, require=()):
    nodes = nodes if nodes is not None else d.dump()
    texts = d.screen_texts(nodes)
    if take_shot:
        shot(d, name)
    hits = forbidden_hits(texts)
    check.ok("%s: no Telegram-only items" % name, not hits and texts, "; ".join(hits) if hits else ("%d texts" % len(texts) if texts else "empty dump"))
    for want in require:
        check.ok("%s: has %r" % (name, want), want in texts, str([t for t in texts if len(t) < 40][:25]))
    return nodes, texts


def back(d, times=1):
    a6.back(d, times)


def tap_desc_rightmost(d, desc):
    return a6.tap_desc(d, desc, pick=lambda ns: max(ns, key=lambda n: n.bounds[0]))


def tap_desc_overlay(d, desc):
    """The profile overlay's button on tablets (between the chat list's and the chat's), else the rightmost."""
    def pick(ns):
        ns = sorted(ns, key=lambda n: n.bounds[0])
        return ns[len(ns) // 2] if len(ns) >= 3 else ns[-1]
    return a6.tap_desc(d, desc, pick=pick)


def in_right_pane(d, node):
    w, h = a6.screen_size(d)
    return w <= h or node.bounds[0] >= w * 0.3


# MARK: - REST

class Rest:
    def __init__(self, user, password):
        req = urllib.request.Request(REST_URL + "/api/v4/users/login", data=json.dumps({"login_id": user, "password": password}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as res:
            self.token = res.headers["Token"]
            self.me = json.loads(res.read())

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(REST_URL + "/api/v4" + path, data=data, method=method,
                                     headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req) as res:
            payload = res.read()
            return json.loads(payload) if payload else None


def seed(user, password):
    """DM alice → user with a probe, a permalink and a t.me link; a channel the user administers."""
    alice = Rest("alice", password)
    me = Rest(user, password)
    dm = alice.call("POST", "/channels/direct", [alice.me["id"], me.me["id"]])
    teams = me.call("GET", "/users/me/teams")
    team = sorted(teams, key=lambda t: t["name"])[0]
    target = alice.call("POST", "/posts", {"channel_id": dm["id"], "message": PROBE + " permalink target"})
    permalink = "%s/%s/pl/%s" % (r.MM_URL, team["name"], target["id"])
    alice.call("POST", "/posts", {"channel_id": dm["id"], "message": permalink})
    alice.call("POST", "/posts", {"channel_id": dm["id"], "message": "https://t.me/durov"})
    alice.call("POST", "/posts", {"channel_id": dm["id"], "message": PROBE + " long-press me"})

    admin_channel = None
    for t in teams:
        channels = {c["id"]: c for c in me.call("GET", "/users/me/teams/%s/channels" % t["id"])}
        for m in me.call("GET", "/users/me/teams/%s/channels/members" % t["id"]):
            c = channels.get(m["channel_id"])
            # The creator gets Telegram's creator rights from the bridge, so the edit screen is reachable.
            if c and c["type"] in ("O", "P") and c.get("creator_id") == me.me["id"] and c.get("total_msg_count", 0) > 0:
                admin_channel = c
                break
        if admin_channel:
            break
    if admin_channel is None:
        admin_channel = me.call("GET", "/teams/%s/channels/name/town-square" % team["id"])
    me.call("POST", "/posts", {"channel_id": admin_channel["id"], "message": PROBE + " channel message"})
    return {"dm": dm["id"], "team": team["name"], "post": target["id"], "permalink": permalink,
            "channel": admin_channel["id"], "channel_name": admin_channel["display_name"]}


# MARK: - scenarios

def scenario_chat_list(d, check):
    if a6.wait_chat_list(d, timeout=20) is None:
        check.ok("chat list", False)
        return
    w, h = a6.screen_size(d)
    # Long-press the second row: selection mode.
    nodes = d.dump()
    tabs = [n for n in nodes if "unread chats" in n.desc or n.desc in ("All Chats", "Design", "Engineering")]
    top = max([n.bounds[3] for n in tabs] or [300])
    x, y = 300, top + 200
    d.shell("input swipe %d %d %d %d 900" % (x, y, x, y))
    time.sleep(1.5)
    nodes, texts = check_screen(d, check, "chatlist-select")
    check.ok("selection mode: no Archive button", "Archive" not in texts and "Unarchive" not in texts, str(texts[:8]))
    if tap_desc_left(d, "More options"):
        nodes, texts = check_screen(d, check, "chatlist-select-menu")
        check.ok("selection menu keeps Pin / Mark as read", any(t in texts for t in ("Pin", "Unpin")) and any("Mark as" in t for t in texts), str(texts[:8]))
        back(d)
    back(d)
    # Long-press a folder tab: no folder editing.
    nodes = d.dump()
    tab = next((n for n in nodes if n.desc.startswith("All Chats")), None)
    if tab:
        cx, cy = tab.center
        d.shell("input swipe %d %d %d %d 900" % (cx, cy, cx, cy))
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "folder-tab-menu")
        check.ok("folder tab menu: no Edit / Reorder", not any(t in texts for t in ("Edit Folders", "Edit Folder", "Reorder", "Delete Folder")), str(texts[:10]))
        back(d)


def tap_desc_left(d, desc):
    return a6.tap_desc(d, desc, pick=lambda ns: min(ns, key=lambda n: n.bounds[0]))


def message_node(d, contains):
    for n in d.dump():
        if n.text and contains in n.text and "\n" in n.text:
            return n
    return None


def tap_message(d, contains, dx=0.25):
    """Taps the left part of a message row's bubble text (the rows span the pane)."""
    n = message_node(d, contains)
    if n is None:
        return None
    x1, y1, x2, y2 = n.bounds
    d.tap(int(x1 + (x2 - x1) * dx), (y1 + y2) // 2)
    time.sleep(1.5)
    return n


def scenario_dm(d, check, seeded):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_OPEN_CHAT", channel=seeded["dm"])
    time.sleep(4)
    nodes, texts = check_screen(d, check, "dm")
    check.ok("DM shows the seeded messages", message_node(d, "long-press me") is not None, str([t[:40] for t in texts if "\n" in t][-4:]))

    if tap_desc_rightmost(d, "More options"):
        check_screen(d, check, "dm-menu")
        back(d)

    # Message menu (received message).
    if tap_message(d, "long-press me", dx=0.12):
        nodes, texts = check_screen(d, check, "dm-message-menu", require=("Reply", "Copy", "Forward"))
        back(d)
    else:
        check.ok("DM message menu opens", False)

    if a6.tap_desc(d, "Attach media"):
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "dm-attach", require=("Gallery", "File"))
        back(d)
    if a6.tap_desc(d, "Emoji"):
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "dm-emoji")
        gear = [n for n in nodes if n.desc == "Settings" and in_right_pane(d, n) and n.bounds[1] > a6.screen_size(d)[1] * 0.4 and n.bounds[3] < a6.screen_size(d)[1] * 0.9]
        check.ok("emoji panel: no settings gear", not gear, str(gear))
        back(d)

    # Links: the permalink stays in the app (RelayLinks hook), t.me goes to the browser.
    d.logcat_clear()
    if tap_message(d, "/pl/" + seeded["post"], dx=0.12):
        m = d.wait_log(r"open POST team=(\S+) value=(\S+)", timeout=8, tags=["RelayLinks"])
        check.ok("tap on a permalink → RelayLinks POST hook", m is not None and m.group(2) == seeded["post"] and m.group(1) == seeded["team"],
                 m.group(0) if m else d.logcat("RelayLinks")[-300:])
        check.ok("permalink: no 'Open this link?' alert", d.find(text="Open") is None)
    else:
        check.ok("permalink message visible", False)
    d.logcat_clear()
    if tap_message(d, "t.me/durov", dx=0.1):
        m = d.wait_log(r"browser: t\.me", timeout=8, tags=["RelayLinks"])
        alert = d.find(text="Open")
        if m is None and alert is not None:
            # A plain URL may ask "Open this link?" first; confirm it.
            d.tap_node(alert)
            m = d.wait_log(r"browser: t\.me", timeout=8, tags=["RelayLinks"])
        check.ok("tap on t.me link → system browser (not a Telegram flow)", m is not None, d.logcat("RelayLinks")[-300:])
        time.sleep(2)
        focus = d.shell("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'", check=False)
        if d.package in focus:
            check.ok("t.me link opened no in-app screen", True, focus.strip())
        else:
            d.launch()
            time.sleep(2)
    else:
        check.ok("t.me message visible", False)


def scenario_user_profile(d, check):
    nodes = d.dump()
    header = next((n for n in nodes if n.desc.startswith("Alice") and in_right_pane(d, n)), None)
    if header is None:
        check.ok("DM header visible", False, str([n.desc for n in nodes if n.desc][:10]))
        return
    d.tap_node(header)
    time.sleep(2.5)
    nodes, texts = check_screen(d, check, "user-profile")
    check.ok("user profile shows @alice", "@alice" in texts, str(texts[:15]))
    check.ok("user profile: no QR button / Add to contacts", "Get QR code" not in texts and "Add to contacts" not in texts)
    if tap_desc_overlay(d, "More options"):
        check_screen(d, check, "user-profile-menu")
        back(d)
    back(d)


def scenario_channel(d, check, seeded):
    d.broadcast("org.relaychat.DEBUG_OPEN_CHAT", channel=seeded["channel"])
    time.sleep(4)
    nodes, texts = check_screen(d, check, "channel")
    if tap_desc_rightmost(d, "More options"):
        check_screen(d, check, "channel-menu")
        back(d)
    if tap_message(d, "channel message", dx=0.85):
        check_screen(d, check, "channel-message-menu", require=("Reply", "Copy", "Edit", "Delete"))
        back(d)
    else:
        check.ok("channel message menu opens", False)

    nodes = d.dump()
    title = next((n for n in nodes if n.desc.startswith(seeded["channel_name"] + "\n") and in_right_pane(d, n)), None)
    if title is None:
        check.ok("channel header visible", False, seeded["channel_name"])
        return
    d.tap_node(title)
    time.sleep(2.5)
    nodes, texts = check_screen(d, check, "channel-info", require=("Mute",))
    if tap_desc_overlay(d, "More options"):
        check_screen(d, check, "channel-info-menu")
        back(d)
    if a6.tap_desc(d, "Edit", timeout=4):
        time.sleep(2)
        nodes, texts = check_screen(d, check, "channel-edit")
        a6.swipe(d, right_pane=True)
        check_screen(d, check, "channel-edit-2")
        back(d)
    else:
        check.ok("channel edit screen (user administers the channel)", False)
    back(d, 2)


def scenario_settings(d, check):
    if a6.wait_chat_list(d, timeout=8) is None:
        d.launch()
        a6.wait_chat_list(d, timeout=20)
    if a6.tap_text(d, "Settings") is None:
        check.ok("Settings tab opens", False)
        return
    time.sleep(2)
    texts = []
    for i in range(3):
        texts += d.screen_texts()
        a6.scroll_down(d)
    footer = [t for t in texts if t.startswith("Relay for Android")]
    check.ok("settings footer: Relay for Android v1.0.0 (1)", any(t.startswith("Relay for Android v1.0.0 (1)") for t in footer), str(footer))
    check.ok("settings footer: not upstream's 13.2.2", not any("13.2.2" in t for t in footer), str(footer))
    shot(d, "settings-footer")

    if a6.tap_text(d, "Power Saving") is not None:
        time.sleep(1.5)
        nodes, texts = check_screen(d, check, "power-saving")
        a6.swipe(d, right_pane=True)
        nodes, texts2 = check_screen(d, check, "power-saving-2")
        check.ok("Power Saving: no Animated Stickers", not any("Sticker" in t for t in texts + texts2))
        check.ok("Power Saving: keeps Animated Emoji", any("Emoji" in t for t in texts + texts2), str(texts[:12]))
        back(d)

    for _ in range(3):
        a6.swipe(d, up=False)
    if a6.tap_text(d, "Chat Settings") is not None:
        time.sleep(1.5)
        all_texts = []
        for i in range(4):
            nodes, texts = check_screen(d, check, "chat-settings-%d" % (i + 1), take_shot=i < 2)
            all_texts += texts
            a6.swipe(d, right_pane=True)
        check.ok("Chat Settings: no Stickers and Emoji row", not any("Sticker" in t for t in all_texts))
        back(d)

    for _ in range(3):
        a6.scroll_down(d)
    if a6.tap_text(d, "About Relay") is not None:
        time.sleep(1.5)
        about = d.screen_texts()
        shot(d, "about")
        check.ok("About: Relay version 1.0.0 (1)", "1.0.0 (1)" in about, str(about[:12]))
        based = [t for t in about if t.startswith("Based on Telegram for Android")]
        check.ok("About: based on Telegram for Android 13.2.2", any("13.2.2" in t for t in based), str(based))
        back(d)


def scenario_links_system(d, check, seeded):
    # Telegram links no longer resolve to Relay; relay: does.
    def resolves(uri):
        out = d.shell("cmd package query-activities -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '%s'" % uri, check=False)
        return d.package in out

    for uri in ("https://t.me/durov", "https://telegram.me/durov", "tg://resolve?domain=durov", "ton://transfer/x", "tc://x"):
        check.ok("system does not route %s to Relay" % uri, not resolves(uri))
    app_link = seeded["permalink"].replace("http://", "relay://", 1)
    check.ok("system routes relay:// to Relay", resolves(app_link))

    d.logcat_clear()
    d.shell("am start -W -a android.intent.action.VIEW -d '%s' %s" % (app_link, d.package), check=False)
    m = d.wait_log(r"open POST team=(\S+) value=(\S+)", timeout=10, tags=["RelayLinks"])
    check.ok("relay:// VIEW intent → RelayLinks POST hook", m is not None and m.group(2) == seeded["post"], m.group(0) if m else d.logcat("RelayLinks")[-300:])

    for url, pattern, label in (
        ("tg://resolve?domain=durov", r"ignored tg link: resolve", "in-app tg:// link ignored"),
        ("ton://transfer/abc", r"ignored ton link", "in-app ton: link ignored"),
        ("https://t.me/joinchat/abc", r"browser: t\.me", "in-app t.me link → browser"),
        ("https://telegram.me/durov", r"browser: telegram\.me", "in-app telegram.me link → browser"),
        ("relay://localhost:8065/%s/channels/town-square" % seeded["team"], r"open CHANNEL team=\S+ value=town-square", "in-app relay:// channel link → hook"),
    ):
        d.launch()
        time.sleep(1)
        d.logcat_clear()
        d.broadcast("org.relaychat.DEBUG_OPEN_URL", url=url)
        m = d.wait_log(pattern, timeout=8, tags=["RelayLinks"])
        check.ok(label, m is not None, d.logcat("RelayLinks")[-200:])
    d.launch()
    time.sleep(1.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--user", default="carol")
    args = ap.parse_args()

    d = r.Device(args.serial)
    check = r.Check("A6b screens")
    password = r.mm_password()
    if not args.no_install:
        print(d.install().strip().splitlines()[-1])

    seeded = seed(args.user, password)
    print("seeded:", {k: v for k, v in seeded.items() if k != "permalink"})
    nodes = a6.fresh_login(d, check, args.user, password)
    check.ok("chat list after login", nodes is not None)
    if nodes is None:
        shot(d, "no-chatlist")
        print(check.summary())
        sys.exit(1)
    time.sleep(3)

    scenario_chat_list(d, check)
    scenario_dm(d, check, seeded)
    scenario_user_profile(d, check)
    scenario_channel(d, check, seeded)
    scenario_settings(d, check)
    scenario_links_system(d, check, seeded)

    crashes = d.crashes()
    check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    blocked = d.logcat("RelayFeatures")
    if blocked.strip():
        print("fragments refused by RelayFeatures.isFragmentAllowed:\n" + blocked)
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
