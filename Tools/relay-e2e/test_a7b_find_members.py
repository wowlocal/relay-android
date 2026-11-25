#!/usr/bin/env python3
"""A7b e2e: finding things (search, media tabs, pinned, saved posts, recent mentions) and membership
(members, add/remove, create channel, edit, browse/join, leave, archive, DM/GM).

  python3 tools/relay-e2e/test_a7b_find_members.py [--no-install] [--serial emulator-5556] [--user carol]

The app user (carol by default) logs in with the debug broadcast; alice, dave and bob act over REST. The
test channel is created from the app (New Message → New Channel) and named relay-e2e-a7b-<stamp>; a second
public channel relay-e2e-a7bj-<stamp> is created by alice for browse/join/leave. Both are archived at the
end, posts made in DMs/GMs are deleted, saved posts are unsaved.

Bridge-level equality checks send Telegram's own requests through the client's ConnectionsManager with
the A7b debug hooks (RelayFindingDebugHooks, tag RelayA7b) and compare post ids with the REST answer;
the screens are driven with adb (uiautomator) and photographed into docs/relay/screens/a7b-*.png.
"""

import argparse
import datetime
import json
import os
import re
import struct
import sys
import time
import urllib.request
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e as r  # noqa: E402
import test_a6_hygiene as a6  # noqa: E402
import test_a5_write as a5  # noqa: E402

REST_URL = "http://localhost:%d" % r.MM_PORT
TAG = "RelayA7b"


# MARK: - REST

class Rest:
    def __init__(self, user, password):
        req = urllib.request.Request(REST_URL + "/api/v4/users/login", data=json.dumps({"login_id": user, "password": password}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as res:
            self.token = res.headers["Token"]
            self.me = json.loads(res.read())

    def call(self, method, path, body=None, raw=None, content_type="application/json"):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(REST_URL + "/api/v4" + path, data=data, method=method,
                                     headers={"Authorization": "Bearer " + self.token, "Content-Type": content_type})
        with urllib.request.urlopen(req) as res:
            payload = res.read()
            return json.loads(payload) if payload else None

    def try_call(self, method, path, body=None):
        try:
            return self.call(method, path, body)
        except urllib.error.HTTPError as e:
            return {"_error": e.code}

    def post(self, channel_id, message, file_ids=None, root_id=None):
        body = {"channel_id": channel_id, "message": message}
        if file_ids:
            body["file_ids"] = file_ids
        if root_id:
            body["root_id"] = root_id
        return self.call("POST", "/posts", body)

    def upload(self, channel_id, name, data, mime):
        boundary = "----relaye2e%d" % int(time.time() * 1000)
        parts = [("--%s\r\nContent-Disposition: form-data; name=\"channel_id\"\r\n\r\n%s\r\n" % (boundary, channel_id)).encode(),
                 ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, name, mime)).encode(),
                 data, ("\r\n--%s--\r\n" % boundary).encode()]
        res = self.call("POST", "/files", raw=b"".join(parts), content_type="multipart/form-data; boundary=" + boundary)
        return res["file_infos"][0]["id"]

    def search(self, team_id, terms, is_or=False):
        res = self.call("POST", "/teams/%s/posts/search" % team_id, {"terms": terms, "is_or_search": is_or, "page": 0, "per_page": 100,
                                                                     "time_zone_offset": tz_offset(), "include_deleted_channels": False})
        return [p for p in res["order"] if not res["posts"][p].get("type", "").startswith("system_")]

    def channel_by_name(self, team_id, name):
        return self.try_call("GET", "/teams/%s/channels/name/%s?include_deleted=true" % (team_id, name))

    def member_ids(self, channel_id):
        return {m["user_id"]: m for m in self.call("GET", "/channels/%s/members?per_page=200" % channel_id)}


def tz_offset():
    return -time.timezone if not time.localtime().tm_isdst else -time.altzone


def png(width=64, height=48, rgb=(200, 80, 40)):
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


# MARK: - Device helpers

def shot(d, name):
    return d.screenshot(os.path.join(r.SCREENS, "a7b-%s.png" % name))


def hook(d, action, **extras):
    """Runs a debug hook and returns the RelayA7b log line it produced."""
    marker = {"org.relaychat.DEBUG_A7B_SEARCH": "A7B search", "org.relaychat.DEBUG_A7B_MEMBERS": "A7B members",
              "org.relaychat.DEBUG_A7B_COUNTERS": "A7B counters", "org.relaychat.DEBUG_A7B_PEERS": "A7B peers"}[action]
    before = len(d.logcat(TAG).splitlines())
    d.broadcast(action, **extras)
    deadline = time.time() + 20
    while time.time() < deadline:
        lines = d.logcat(TAG).splitlines()[before:]
        for line in lines:
            if marker in line:
                return line
        time.sleep(0.7)
    return ""


def items(line):
    m = re.search(r"items=\[(.*)\]", line)
    if not m or not m.group(1):
        return []
    return [x.split("|")[0] for x in m.group(1).split(",")]


def count_of(line):
    m = re.search(r"count=(\d+)", line)
    return int(m.group(1)) if m else -1


def counters(line):
    return {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", line.split("counters", 1)[-1])}


def open_chat(d, channel_id, message=0):
    if message:
        d.shell("am broadcast -a org.relaychat.DEBUG_OPEN_CHAT -p %s --es channel %s --ei message %d" % (d.package, channel_id, message))
    else:
        d.broadcast("org.relaychat.DEBUG_OPEN_CHAT", channel=channel_id)
    time.sleep(4)


def at_chat_list(d):
    texts = d.screen_texts()
    return "Chats" in texts and "Search Chats" in texts and "Settings" in texts and "Messages" not in texts


def go_chat_list(d):
    """Back to the chat list with its tab bar (not the search screen)."""
    for _ in range(7):
        if at_chat_list(d):
            return True
        d.key("KEYCODE_BACK")
        time.sleep(1.2)
    d.launch()
    time.sleep(3)
    return at_chat_list(d)


def tap_title(d, prefix):
    for n in d.dump():
        if n.text and n.text.startswith(prefix) and "\n" not in n.text:
            d.tap_node(n)
            time.sleep(3)
            return True
    return False


def long_press(d, node, ms=900):
    x, y = node.center
    d.shell("input swipe %d %d %d %d %d" % (x, y, x, y, ms))
    time.sleep(2)


def find_contains(d, text, nodes=None):
    nodes = nodes if nodes is not None else d.dump()
    for n in nodes:
        if text in (n.text or "") or text in (n.desc or ""):
            return n
    return None


def message_node(d, text, tries=6):
    """The chat message containing `text` (scrolling up a little when it is not on screen)."""
    for i in range(tries):
        nodes = d.dump()
        for n in nodes:
            if text in (n.text or "") and n.bounds[1] > 200:
                return n
        if i < tries - 1:
            w, h = a6.screen_size(d)
            d.shell("input swipe %d %d %d %d 300" % (w // 2, int(h * 0.35), w // 2, int(h * 0.65)))
            time.sleep(1)
    return None


def open_message_menu(d, node, dx=0.2):
    """Taps a received message's bubble (Telegram opens the message menu on a tap; a long press selects)."""
    x1, y1, x2, y2 = node.bounds
    d.tap(int(x1 + (x2 - x1) * dx), (y1 + y2) // 2)
    time.sleep(2)


def flagged_ids(rest):
    prefs = rest.try_call("GET", "/users/me/preferences/flagged_post")
    if not isinstance(prefs, list):
        return set()
    return {p["name"] for p in prefs if p.get("value") == "true"}


def open_new_message(d):
    """Chat list → New Message (the floating button; retried, an undo bar can cover it for a few seconds)."""
    for _ in range(4):
        go_chat_list(d)
        fab = [n for n in d.dump() if n.desc == "New Message"]
        if fab:
            d.tap_node(fab[-1])
            time.sleep(3)
            if "New Group Message" in d.screen_texts():
                return True
        time.sleep(3)
    return False


def open_edit(d, channel_id, title):
    """Chat → info → Edit (retried: a tap during a screen transition can be lost)."""
    for _ in range(3):
        go_chat_list(d)
        open_chat(d, channel_id)
        if not tap_title(d, title):
            continue
        node = d.wait_for(desc="Edit", timeout=6)
        if node:
            d.tap_node(node)
            time.sleep(3)
            if any(t.startswith("Members") for t in d.screen_texts()):
                return True
    return False


def menu_tap(d, label, timeout=6):
    node = d.wait_for(text=label, timeout=timeout) or d.wait_for(desc=label, timeout=2)
    if node:
        d.tap_node(node)
        time.sleep(2)
    return node


# MARK: - Scenarios

def scenario_create_channel(d, check, ctx):
    go_chat_list(d)
    fab = [n for n in d.dump() if n.desc == "New Message"]
    check.ok("New Message button", bool(fab))
    if not fab:
        return False
    d.tap_node(fab[-1])
    time.sleep(3)
    nodes = d.dump()
    texts = d.screen_texts(nodes)
    shot(d, "new-message")
    check.ok("New Message: New Group Message / New Channel / Browse Channels rows",
             all(t in texts for t in ("New Group Message", "New Channel", "Browse Channels")), str(texts[:8]))
    check.ok("New Message: user directory (contacts.getContacts) lists alice and dave",
             any("Alice Anderson" in t for t in texts) and any("Dave" in t for t in texts), str([t for t in texts if "," in t][:6]))
    ctx["new_message_texts"] = texts
    a6.tap_text(d, "New Channel")
    time.sleep(2)
    if not d.find(text="Channel name"):
        check.ok("New Channel screen", False)
        return False
    a6.tap_text(d, "Engineering")
    d.tap_node(d.find(text="Channel name"))
    d.type_text(ctx["title"])
    d.hide_keyboard()
    time.sleep(1)
    url = d.find(text=ctx["name"])
    check.ok("New Channel: URL from the name", url is not None, ctx["name"])
    d.tap_node(d.find(text="What the channel is for (optional)"))
    d.type_text(ctx["purpose"])
    d.hide_keyboard()
    time.sleep(1)
    d.tap_node(d.find(text="Header (optional)"))
    d.type_text(ctx["header"])
    d.hide_keyboard()
    time.sleep(1)
    shot(d, "new-channel")
    a6.tap_desc(d, "Create")
    time.sleep(5)
    channel = None
    for _ in range(10):
        channel = ctx["carol"].channel_by_name(ctx["team"], ctx["name"])
        if channel and "_error" not in channel:
            break
        time.sleep(1)
    ok = channel and "_error" not in channel
    check.ok("create channel → exists in REST", ok, str(channel)[:120])
    if not ok:
        return False
    ctx["channel"] = channel
    check.ok("create channel → public (O), name, purpose, header",
             channel["type"] == "O" and channel["display_name"] == ctx["title"] and channel["purpose"] == ctx["purpose"] and channel["header"] == ctx["header"],
             "%s %s %r %r" % (channel["type"], channel["display_name"], channel["purpose"], channel["header"]))
    texts = d.screen_texts()
    check.ok("create channel → the new chat opens", any(t.startswith(ctx["title"]) for t in texts), str(texts[:10]))
    shot(d, "new-channel-opened")
    return True


def scenario_members(d, check, ctx):
    ch = ctx["channel"]
    if not tap_title(d, ctx["title"]):
        open_chat(d, ch["id"])
        tap_title(d, ctx["title"])
    if not menu_tap(d, "Add Member"):
        check.ok("info: Add Member", False)
        return
    texts = d.screen_texts()
    check.ok("Add Members: no Telegram invite link row", not any("via Link" in t for t in texts), str(texts[:6]))
    a6.tap_text(d, "Alice Anderson")
    a6.tap_text(d, "Dave Okafor-Lindqvist")
    a6.tap_desc(d, "Next")
    shot(d, "add-members")
    menu_tap(d, "Add")
    time.sleep(4)
    members = ctx["carol"].member_ids(ch["id"])
    check.ok("add members (app) → alice and dave in REST", ctx["alice"].me["id"] in members and ctx["dave"].me["id"] in members, str(list(members)))
    texts = d.screen_texts()
    check.ok("info: 3 members shown", any(t.startswith("3 members") for t in texts), str([t for t in texts if "member" in t][:3]))
    shot(d, "channel-info")

    # Member list (channels.getParticipants) = REST, admin badge = channel admin.
    line = hook(d, "org.relaychat.DEBUG_A7B_MEMBERS", channel=ch["id"])
    app = dict(x.split("|") for x in re.search(r"items=\[(.*)\]", line).group(1).split(",")) if "items=[" in line and "items=[]" not in line else {}
    rest = ctx["carol"].member_ids(ch["id"])
    check.ok("member list = REST", set(app) == set(rest), "app=%s rest=%s" % (sorted(app), sorted(rest)))
    check.ok("member roles: channel admins are admins", all((app.get(u) == "admin") == bool(m.get("scheme_admin")) for u, m in rest.items()),
             str({u: (app.get(u), m.get("scheme_admin")) for u, m in rest.items()}))
    line = hook(d, "org.relaychat.DEBUG_A7B_MEMBERS", channel=ch["id"], filter="search", q="dave")
    check.ok("member search (participants search) finds dave", items(line) == [ctx["dave"].me["id"]], line[-120:])
    check.ok("info: admin badge for the creator", "Admin" in texts)

    # Live: alice removes dave over REST → the open info screen drops him.
    ctx["alice"].call("DELETE", "/channels/%s/members/%s" % (ch["id"], ctx["dave"].me["id"]))
    deadline = time.time() + 15
    while time.time() < deadline:
        texts = d.screen_texts()
        if any(t.startswith("2 members") for t in texts):
            break
        time.sleep(1)
    check.ok("live: member removed elsewhere → info shows 2 members", any(t.startswith("2 members") for t in texts), str([t for t in texts if "member" in t][:3]))
    ctx["alice"].call("POST", "/channels/%s/members" % ch["id"], {"user_id": ctx["dave"].me["id"]})
    deadline = time.time() + 15
    while time.time() < deadline:
        texts = d.screen_texts()
        if any(t.startswith("3 members") for t in texts) and any("Dave" in t for t in texts):
            break
        time.sleep(1)
    check.ok("live: member added elsewhere → info shows dave again", any(t.startswith("3 members") for t in texts), str([t for t in texts if "member" in t][:3]))

    # Remove dave from the app.
    node = find_contains(d, "Dave Okafor-Lindqvist")
    if node:
        long_press(d, node)
        texts = d.screen_texts()
        shot(d, "member-menu")
        check.ok("member menu: no Telegram permissions/tags", "Change permissions" not in texts and not any("Tag" in t for t in texts), str(texts[:6]))
        menu_tap(d, "Remove from group")
        time.sleep(3)
    members = ctx["carol"].member_ids(ch["id"])
    check.ok("remove member (app) → dave gone in REST", ctx["dave"].me["id"] not in members, str(list(members)))

    # Edit header and purpose.
    if menu_tap(d, "Edit") or a6.tap_desc(d, "Edit"):
        node = d.find(text=ctx["header"])
        check.ok("edit screen: header field", node is not None)
        if node:
            d.clear_field(node, 60)
            d.type_text(ctx["header2"])
            d.hide_keyboard()
            time.sleep(1)
        node = d.find(text=ctx["purpose"])
        if node:
            d.clear_field(node, 60)
            d.type_text(ctx["purpose2"])
            d.hide_keyboard()
            time.sleep(1)
        texts = d.screen_texts()
        check.ok("edit screen: Archive Channel (member may archive)", "Archive Channel" in texts, str(texts[:12]))
        shot(d, "channel-edit")
        a6.tap_desc(d, "Save")
        time.sleep(4)
    channel = ctx["carol"].call("GET", "/channels/%s" % ch["id"])
    check.ok("edit header (app) → REST header", channel["header"] == ctx["header2"], repr(channel["header"]))
    check.ok("edit description (app) → REST purpose", channel["purpose"] == ctx["purpose2"], repr(channel["purpose"]))
    a6.back(d)


def seed_posts(ctx):
    ch, alice, dave, carol = ctx["channel"], ctx["alice"], ctx["dave"], ctx["carol"]
    n = ctx["needle"]
    ctx["posts"] = posts = {}
    posts["a1"] = alice.post(ch["id"], "%s alpha first from alice" % n)["id"]
    posts["d1"] = dave.post(ch["id"], "%s from dave" % n)["id"] if dave.me["id"] in carol.member_ids(ch["id"]) else None
    posts["a2"] = alice.post(ch["id"], "%s alpha second, see https://example.com/a7b-%s" % (n, ctx["stamp"]))["id"]
    posts["link"] = alice.post(ch["id"], "release notes at https://example.org/notes-%s" % ctx["stamp"])["id"]
    f1 = alice.upload(ch["id"], "a7b-photo-%s.png" % ctx["stamp"], png(), "image/png")
    f2 = alice.upload(ch["id"], "a7b-notes-%s.txt" % ctx["stamp"], ("notes %s\n" % ctx["stamp"]).encode(), "text/plain")
    posts["photo"] = alice.post(ch["id"], "photo %s" % ctx["stamp"], [f1])["id"]
    posts["file"] = alice.post(ch["id"], "file %s" % ctx["stamp"], [f2])["id"]
    posts["mention"] = alice.post(ch["id"], "@%s please look, mention %s" % (carol.me["username"], ctx["stamp"]))["id"]
    posts["a3"] = alice.post(ch["id"], "%s alpha third" % n)["id"]
    # One pinned elsewhere (REST) before the app looks.
    alice.call("POST", "/posts/%s/pin" % posts["a1"])
    # A DM hit for the global search.
    dm = alice.call("POST", "/channels/direct", [alice.me["id"], carol.me["id"]])
    posts["dm"] = alice.post(dm["id"], "%s in a DM" % n)["id"]
    ctx["cleanup_posts"].append((alice, posts["dm"]))
    time.sleep(3)


def scenario_search(d, check, ctx):
    ch, carol, team, n = ctx["channel"], ctx["carol"], ctx["team"], ctx["needle"]
    # In-chat search = REST (in:channel).
    rest = carol.search(team, "%s in:%s" % (n, ch["id"]))
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q=n)
    check.ok("in-chat search = REST search in:<channel>", items(line) == rest and len(rest) >= 3, "app=%s rest=%s" % (items(line), rest))
    # from: (Telegram's "search by member").
    rest_from = carol.search(team, "%s in:%s from:alice" % (n, ch["id"]))
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q=n, **{"from": "alice"})
    check.ok("in-chat search from alice = REST from:alice", items(line) == rest_from, "app=%s rest=%s" % (items(line), rest_from))
    # Global search = REST across teams (DM hit included).
    teams = [t["id"] for t in carol.call("GET", "/users/me/teams")]
    rest_global = []
    for t in teams:
        for p in carol.search(t, n):
            if p not in rest_global:
                rest_global.append(p)
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel="", q=n, **{"global": "1"})
    check.ok("global search = REST search (all teams)", set(items(line)) == set(rest_global) and ctx["posts"]["dm"] in items(line),
             "app=%s rest=%s" % (items(line), rest_global))
    # Modifiers pass through: from:, in:, on:/after:/before:.
    today = datetime.date.today()
    for terms in ("%s from:alice" % n, "%s in:%s" % (n, ctx["name"]), "%s on:%s" % (n, today.isoformat()),
                  "%s after:%s" % (n, (today - datetime.timedelta(days=1)).isoformat()), "%s before:%s" % (n, (today - datetime.timedelta(days=1)).isoformat())):
        rest_m = []
        for t in teams:
            for p in carol.search(t, terms):
                if p not in rest_m:
                    rest_m.append(p)
        line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel="", q=terms, **{"global": "1"})
        check.ok("search modifier %r = REST" % terms.split(" ", 1)[1], set(items(line)) == set(rest_m), "app=%s rest=%s" % (items(line), rest_m))
    # People and channels (contacts.search).
    line = hook(d, "org.relaychat.DEBUG_A7B_PEERS", q="dave")
    check.ok("people search finds dave", ("user:" + ctx["dave"].me["id"]) in line, line[-160:])
    line = hook(d, "org.relaychat.DEBUG_A7B_PEERS", q=ctx["join_name"])
    check.ok("channel search offers the unjoined public channel", "join:" in line, line[-160:])

    # Screens: global search in the chat list, in-chat search.
    go_chat_list(d)
    a6.tap_text(d, "Search Chats")
    d.type_text(n)
    time.sleep(5)
    d.hide_keyboard()
    texts = d.screen_texts()
    shot(d, "global-search")
    check.ok("global search screen: no Telegram Channels/Apps/Posts tabs", not any(t in ("Channels", "Apps", "Posts") for t in texts), str(texts[:10]))
    go_chat_list(d)
    open_chat(d, ch["id"])
    a6.tap_desc(d, "More options")
    if menu_tap(d, "Search"):
        d.type_text(n)
        d.key("KEYCODE_ENTER")
        time.sleep(4)
        texts = d.screen_texts()
        shot(d, "chat-search")
        check.ok("in-chat search screen: result counter", any(re.search(r"\d+ of %d" % len(rest), t) for t in texts), str([t for t in texts if " of " in t][:4]))
    else:
        check.ok("chat menu: Search", False)
    go_chat_list(d)


def scenario_media(d, check, ctx):
    ch, carol, team = ctx["channel"], ctx["carol"], ctx["team"]
    files = carol.call("POST", "/teams/%s/files/search" % team, {"terms": "in:%s" % ch["id"], "page": 0, "per_page": 100})
    infos = [files["file_infos"][i] for i in files["order"]]
    images = {f["post_id"] for f in infos if f["mime_type"].startswith("image/")}
    docs = {f["post_id"] for f in infos if not f["mime_type"].startswith(("image/", "video/", "audio/"))}
    line = hook(d, "org.relaychat.DEBUG_A7B_COUNTERS", channel=ch["id"])
    c = counters(line)
    check.ok("media counters = channel files (photos, files)", c.get("photovideo") == len(images) and c.get("document") == len(docs), "%s images=%d docs=%d" % (c, len(images), len(docs)))
    check.ok("links counter = posts with URLs", c.get("url") == 2, str(c))
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q="", filter="photovideo")
    check.ok("media tab lists the channel's photos", set(items(line)) == images, "app=%s rest=%s" % (items(line), images))
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q="", filter="document")
    check.ok("files tab lists the channel's files", set(items(line)) == docs, "app=%s rest=%s" % (items(line), docs))
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q="", filter="url")
    check.ok("links tab lists posts with links", set(items(line)) == {ctx["posts"]["a2"], ctx["posts"]["link"]}, str(items(line)))
    open_chat(d, ch["id"])
    tap_title(d, ctx["title"])
    for _ in range(3):
        a6.swipe(d)
    texts = d.screen_texts()
    shot(d, "media-tabs")
    check.ok("info: shared media tabs (Media, Files, Links)", all(t in texts for t in ("Media", "Files", "Links")), str([t for t in texts if len(t) < 12][:20]))
    go_chat_list(d)


def scenario_pin_save(d, check, ctx):
    ch, carol, alice = ctx["channel"], ctx["carol"], ctx["alice"]
    posts = ctx["posts"]
    open_chat(d, ch["id"])
    # Pinned list includes alice's REST pin.
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q="", filter="pinned")
    check.ok("pinned list (filter pinned) = /channels/{id}/pinned", items(line) == [posts["a1"]], line[-120:])
    # Pin from the app: long-press "alpha third".
    node = message_node(d, "alpha third")
    check.ok("message on screen", node is not None)
    if node:
        open_message_menu(d, node)
        texts = d.screen_texts()
        shot(d, "message-menu")
        check.ok("message menu: Pin and Save Post", "Pin" in texts and "Save Post" in texts, str(texts[:14]))
        menu_tap(d, "Pin")
        menu_tap(d, "Pin", timeout=4)  # confirmation
        time.sleep(3)
    post = carol.call("GET", "/posts/%s" % posts["a3"])
    check.ok("pin from app → REST is_pinned", post.get("is_pinned") is True)
    pinned = carol.call("GET", "/channels/%s/pinned" % ch["id"])["order"]
    line = hook(d, "org.relaychat.DEBUG_A7B_SEARCH", channel=ch["id"], q="", filter="pinned")
    check.ok("pinned list after pin = REST", set(items(line)) == set(pinned) and len(pinned) == 2, "app=%s rest=%s" % (items(line), pinned))
    texts = d.screen_texts()
    shot(d, "pinned-bar")
    check.ok("pinned bar shows the pinned message", any("alpha third" in t or "Pinned" in t for t in texts), str([t for t in texts if "Pinned" in t or "alpha" in t][:4]))

    # Save (flag) from the app.
    node = message_node(d, "alpha first")
    if node:
        open_message_menu(d, node)
        menu_tap(d, "Save Post")
        time.sleep(3)
    flagged = flagged_ids(carol)
    check.ok("save post (app) → REST flagged_post", posts["a1"] in flagged, str(flagged))
    ctx["flagged"] = True
    go_chat_list(d)

    # Saved Posts screen (Settings row).
    a6.tap_text(d, "Settings")
    texts = d.screen_texts()
    check.ok("Settings: Saved Posts and Recent Mentions rows", "Saved Posts" in texts and "Recent Mentions" in texts, str(texts[:16]))
    before = len(d.logcat(TAG).splitlines())
    a6.tap_text(d, "Saved Posts")
    time.sleep(4)
    shot(d, "saved-posts")
    lines = [l for l in d.logcat(TAG).splitlines()[before:] if "A7B list saved" in l]
    rest_flagged = (carol.try_call("GET", "/users/me/posts/flagged?per_page=200") or {}).get("order", [])
    check.ok("Saved Posts list = REST flagged posts", lines and items(lines[-1]) == rest_flagged, "app=%s rest=%s" % (items(lines[-1]) if lines else None, rest_flagged))
    a6.back(d)
    before = len(d.logcat(TAG).splitlines())
    a6.tap_text(d, "Recent Mentions")
    time.sleep(5)
    shot(d, "recent-mentions")
    lines = [l for l in d.logcat(TAG).splitlines()[before:] if "A7B list mentions" in l]
    # Collapsed reply threads: replies outside DMs are listed as their thread roots (as the main history shows them).
    res = carol.call("POST", "/teams/%s/posts/search" % ctx["team"], {"terms": "@" + carol.me["username"], "is_or_search": True, "page": 0, "per_page": 100})
    dm_channels = {c["id"] for c in carol.call("GET", "/users/me/channels") if c["type"] == "D"}
    crt = carol.try_call("GET", "/users/me/preferences/display_settings/name/collapsed_reply_threads")
    crt_on = not (isinstance(crt, dict) and crt.get("value") == "off")
    rest_mentions = set()
    for pid in res["order"]:
        p = res["posts"][pid]
        if p.get("type", "").startswith("system_"):
            continue
        if p.get("user_id") == carol.me["id"]:
            continue  # polish 2a: own posts are not mentions (Mattermost's Recent Mentions)
        rest_mentions.add(p["root_id"] if crt_on and p.get("root_id") and p["channel_id"] not in dm_channels else pid)
    app_mentions = items(lines[-1]) if lines else []
    check.ok("Recent Mentions includes the new mention", posts["mention"] in app_mentions, str(app_mentions[:5]))
    check.ok("Recent Mentions ⊇ REST mention search (team)", set(rest_mentions) <= set(app_mentions), "missing=%s" % (set(rest_mentions) - set(app_mentions)))
    a6.back(d)
    go_chat_list(d)

    # Unsave from the app.
    open_chat(d, ch["id"])
    node = message_node(d, "alpha first")
    if node:
        open_message_menu(d, node)
        texts = d.screen_texts()
        check.ok("message menu: Remove from Saved for a saved post", "Remove from Saved" in texts, str(texts[:12]))
        menu_tap(d, "Remove from Saved")
        time.sleep(3)
    flagged = flagged_ids(carol)
    check.ok("unsave (app) → REST flag removed", posts["a1"] not in flagged)
    go_chat_list(d)


def scenario_browse_join_leave(d, check, ctx):
    join, carol = ctx["join_channel"], ctx["carol"]
    open_new_message(d)
    a6.tap_text(d, "Browse Channels")
    time.sleep(4)
    texts = d.screen_texts()
    shot(d, "browse")
    check.ok("Browse Channels: team sections and joined labels", ("Design" in texts or "Engineering" in texts) and any("Joined" in t for t in texts), str(texts[:10]))
    a6.tap_desc(d, "Search channels")
    d.type_text(ctx["join_name"])
    time.sleep(4)
    d.hide_keyboard()
    node = find_contains(d, ctx["join_title"])
    check.ok("Browse search finds the unjoined channel (purpose shown, not joined)", node is not None and "Joined" not in (node.text or ""), str(node))
    if not node:
        return
    d.tap_node(node)
    time.sleep(4)
    shot(d, "channel-preview")
    join_btn = d.find(desc="Join") or d.find(text="Join")
    check.ok("preview of an unjoined channel: Join button", join_btn is not None)
    if join_btn:
        d.tap_node(join_btn)
        time.sleep(5)
    members = carol.member_ids(join["id"])
    check.ok("join (app) → member in REST", carol.me["id"] in members)
    texts = d.screen_texts()
    check.ok("joined: composer instead of Join", "Message" in texts and "Join" not in texts, str(texts[:12]))
    shot(d, "joined")
    # Leave from the info screen.
    tap_title(d, ctx["join_title"])
    if menu_tap(d, "Leave"):
        buttons = [n for n in d.dump() if n.text == "Leave Group"]
        if buttons:
            d.tap_node(buttons[-1])
    # Telegram leaves from the chat list, after its undo bar (a few seconds).
    go_chat_list(d)
    deadline = time.time() + 20
    while time.time() < deadline:
        members = ctx["alice"].member_ids(join["id"])
        if carol.me["id"] not in members:
            break
        time.sleep(1)
    check.ok("leave (app) → not a member in REST", carol.me["id"] not in members, str(list(members)))
    go_chat_list(d)


def scenario_dm_gm(d, check, ctx):
    carol, alice, bob = ctx["carol"], ctx["alice"], ctx["bob"]
    time.sleep(5)  # the leave undo bar from the previous scenario
    open_new_message(d)
    a6.tap_text(d, "New Group Message")
    time.sleep(2)
    texts = d.screen_texts()
    check.ok("New Group Message: people picker", "Alice Anderson" in texts, str(texts[:8]))
    a6.tap_text(d, "Alice Anderson")
    third = ("%s %s" % (bob.me.get("first_name", ""), bob.me.get("last_name", ""))).strip()  # bob, or dave with --user bob
    a6.tap_text(d, third)
    a6.tap_desc(d, "Next")
    time.sleep(5)
    gm = carol.call("POST", "/channels/group", [carol.me["id"], alice.me["id"], bob.me["id"]])
    texts = d.screen_texts()
    shot(d, "group-message")
    title = ", ".join(sorted([carol.me["username"], alice.me["username"], bob.me["username"]]))
    # The chat itself (its composer), not the chat list behind it (which has the GM's row too).
    check.ok("New Group Message → the GM chat opens", any(title in t for t in texts) and "Message" in texts, str(texts[:8]))
    text = "[relay-e2e a7b] gm %s" % ctx["stamp"]
    a5.send_text(d, text)
    time.sleep(4)
    # A post-less GM has no dialog: its first load must still finish, or the chat holds back new messages
    # (its own bubble included) until it closes, and a stray "loading" spinner stays over the chat list.
    check.ok("GM: the sent message shows in the open chat", message_node(d, text, tries=2) is not None)
    posts = carol.call("GET", "/channels/%s/posts?per_page=10" % gm["id"])
    hit = [p for p in posts["order"] if posts["posts"][p]["message"] == text]
    check.ok("GM (app) = Mattermost GM with alice and bob, message posted there", bool(hit), str([posts["posts"][p]["message"] for p in posts["order"][:3]]))
    for p in hit:
        ctx["cleanup_posts"].append((carol, p))
    go_chat_list(d)

    # DM with someone carol has no DM with: New Message → tap the person → send.
    target = ctx["dm_target"]
    open_new_message(d)
    node = d.find(text=target["name"])
    for _ in range(6):  # the directory is sorted by presence: on a phone the person can be a few screens down
        if node is not None:
            break
        a6.swipe(d)
        node = d.find(text=target["name"])
    check.ok("New Message lists %s" % target["name"], node is not None)
    if node:
        d.tap_node(node)
        time.sleep(4)
    text = "[relay-e2e a7b] dm %s" % ctx["stamp"]
    a5.send_text(d, text)
    time.sleep(5)
    shot(d, "direct-message")
    dm = carol.call("POST", "/channels/direct", [carol.me["id"], target["id"]])
    posts = carol.call("GET", "/channels/%s/posts?per_page=10" % dm["id"])
    hit = [p for p in posts["order"] if posts["posts"][p]["message"] == text]
    check.ok("DM (app) → Mattermost DM created, message posted", bool(hit))
    for p in hit:
        ctx["cleanup_posts"].append((carol, p))
    go_chat_list(d)


def scenario_archive(d, check, ctx):
    ch, carol = ctx["channel"], ctx["carol"]
    if not open_edit(d, ch["id"], ctx["title"]):
        check.ok("archive: edit screen", False)
        return
    if menu_tap(d, "Archive Channel"):
        shot(d, "archive-confirm")
        buttons = [n for n in d.dump() if n.text == "Archive Channel"]
        if buttons:
            d.tap_node(buttons[-1])  # the dialog's button (the first one is its title)
        time.sleep(4)
    channel = carol.call("GET", "/channels/%s" % ch["id"])
    ctx["archived"] = channel.get("delete_at", 0) > 0
    check.ok("archive (app) → REST delete_at set", ctx["archived"])
    open_chat(d, ch["id"])
    time.sleep(2)
    texts = d.screen_texts()
    shot(d, "archived-readonly")
    check.ok("archived channel is read-only (notice, no composer)", any("archived" in t for t in texts) and "Message" not in texts, str(texts[-8:]))
    go_chat_list(d)


# MARK: - Main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("RELAY_SERIAL", "emulator-5556"))
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--user", default="carol")
    args = ap.parse_args()

    d = r.Device(args.serial)
    check = r.Check("A7b find + members")
    password = r.mm_password()
    stamp = str(int(time.time()))[-7:]
    carol = Rest(args.user, password)
    alice = Rest("alice", password)
    dave = Rest("dave", password)
    # The GM's third member: bob, or dave when bob is the app's user (--user bob).
    bob = Rest("bob" if args.user != "bob" else "dave", password)
    team = next(t for t in carol.call("GET", "/users/me/teams") if t["name"] == "engineering")["id"]
    ctx = {"carol": carol, "alice": alice, "dave": dave, "bob": bob, "team": team, "stamp": stamp, "cleanup_posts": [],
           "title": "Relay E2E A7b %s" % stamp, "name": "relay-e2e-a7b-%s" % stamp,
           "purpose": "A7b purpose %s" % stamp, "purpose2": "A7b purpose edited %s" % stamp,
           "header": "A7b header %s" % stamp, "header2": "A7b header edited %s" % stamp,
           "needle": "needle%s" % stamp,
           "join_title": "Relay E2E A7b join %s" % stamp, "join_name": "relay-e2e-a7bj-%s" % stamp}

    # Someone the user has no DM with yet (for "start a DM").
    dms = {c["name"] for c in carol.call("GET", "/users/me/channels") if c["type"] == "D"}
    target = None
    for u in carol.call("GET", "/users?in_team=%s&per_page=200&active=true" % team):
        pair = "__".join(sorted([carol.me["id"], u["id"]]))
        if u["id"] != carol.me["id"] and not u.get("is_bot") and pair not in dms and u.get("first_name"):
            target = {"id": u["id"], "name": ("%s %s" % (u["first_name"], u.get("last_name", ""))).strip()}
            break
    ctx["dm_target"] = target or {"id": alice.me["id"], "name": "Alice Anderson"}
    print("DM target:", ctx["dm_target"]["name"])

    # A public channel the user is not in (browse/join/leave), created by alice.
    ctx["join_channel"] = alice.call("POST", "/channels", {"team_id": team, "name": ctx["join_name"], "display_name": ctx["join_title"],
                                                           "type": "O", "purpose": "A7b browse and join %s" % stamp})

    if not args.no_install:
        print(d.install().strip().splitlines()[-1])
    nodes = a6.fresh_login(d, check, args.user, password)
    check.ok("chat list after login", nodes is not None)
    time.sleep(4)
    try:
        if scenario_create_channel(d, check, ctx):
            scenario_members(d, check, ctx)
            seed_posts(ctx)
            scenario_search(d, check, ctx)
            scenario_media(d, check, ctx)
            scenario_pin_save(d, check, ctx)
        scenario_browse_join_leave(d, check, ctx)
        scenario_dm_gm(d, check, ctx)
        if "channel" in ctx:
            scenario_archive(d, check, ctx)
        crashes = d.crashes()
        check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    finally:
        # Clean up only what this run made.
        if "posts" in ctx:
            carol.call("POST", "/users/%s/preferences/delete" % carol.me["id"],
                       [{"user_id": carol.me["id"], "category": "flagged_post", "name": ctx["posts"]["a1"], "value": "true"}])
        for user, post in ctx["cleanup_posts"]:
            user.try_call("DELETE", "/posts/%s" % post)
        if "channel" in ctx and not ctx.get("archived"):
            carol.try_call("DELETE", "/channels/%s" % ctx["channel"]["id"])
        alice.try_call("DELETE", "/channels/%s" % ctx["join_channel"]["id"])
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
