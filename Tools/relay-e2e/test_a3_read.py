#!/usr/bin/env python3
"""Relay for Android, phase A3 (read path) e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    python3 Tools/relay-e2e/test_a3_read.py [--no-install] [--swipes N]

What it does (alice is logged in through the debug hook, bob writes probe posts over REST):

  1. Chat list: every Mattermost channel/DM/GM of alice is a dialog with the right name, unread
     count (CRT `_root` counters), mention badge (channels only), muted and pinned (favourites) state;
     one folder per team. Compared through the DEBUG_DUMP_DIALOGS dump with REST.
  2. DM with bob and the iOS channel: visible messages (uiautomator) match REST posts; replies show a
     reply header.
  3. First unread: a chat with unread posts opens at the "Unread Messages" separator; read marking
     reaches the server (channels/members/me/view), the badge clears.
  4. Firehose (11k+ posts): fling back N times; the bridge's id map and the client's cached message ids
     (DEBUG_DUMP_DIALOG) must equal the newest posts from REST, in order, with no gap and no duplicate.
  5. Reply → original: tapping a reply header whose thread root is 120 posts back loads it as an island.
  6. Media: photos show (screenshot), the 6 MB zip downloads and matches the server's md5.
  7. Profile: bob's bio (position), username and presence.
  8. Cold start: a post made while the app is stopped shows up as unread with its preview.

Probe posts are prefixed "[relay-e2e a3]" and deleted at the end. Screens: docs/relay/screens/a3-*.png.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402


def screen_size(d):
    """The size apps get: "Override size" (when set) comes after "Physical size" in `wm size`."""
    sizes = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
    return (int(sizes[-1][0]), int(sizes[-1][1])) if sizes else (1080, 2400)

PROBE = "[relay-e2e a3]"
EXTERNAL_FILES = "/sdcard/Android/data/%s/files/Telegram/Telegram Files" % relaye2e.PACKAGE
REST_URL = "http://localhost:%d" % relaye2e.MM_PORT


# MARK: - REST

class Rest:
    def __init__(self, user, password):
        req = urllib.request.Request(REST_URL + "/api/v4/users/login", data=json.dumps({"login_id": user, "password": password}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as res:
            self.token = res.headers["Token"]
            self.me = json.loads(res.read())

    def call(self, method, path, body=None, raw=False):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(REST_URL + "/api/v4" + path, data=data, method=method,
                                     headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        with urllib.request.urlopen(req) as res:
            payload = res.read()
            return payload if raw else (json.loads(payload) if payload else None)

    def get(self, path):
        return self.call("GET", path)


def display_name(user):
    first, last = (user.get("first_name") or "").strip(), (user.get("last_name") or "").strip()
    if first or last:
        return (first + " " + last).strip()
    return (user.get("nickname") or "").strip() or user["username"]


def strip_md(text):
    text = re.sub(r"[*_`~>#]", "", text)
    text = re.sub(r":[a-z0-9_+\-]+:", "", text)
    return " ".join(text.split())


# MARK: - Device helpers

def shot(d, name):
    path = os.path.join(relaye2e.SCREENS, "a3-%s.png" % name)
    d.screenshot(path)
    return path


def read_app_file(d, name):
    return d.adb("exec-out", "run-as", d.package, "cat", "files/" + name, check=False)


def dump_dialogs(d):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_DUMP_DIALOGS")
    d.wait_log(r"DEBUG_DUMP_DIALOGS ok", timeout=20, tags=["RelayDebug"])
    dialogs, filters = {}, []
    for line in read_app_file(d, "relay-dialogs.txt").splitlines():
        parts = line.split("\t")
        if parts[0] == "dialog" and len(parts) >= 11:
            dialogs[parts[2]] = {"did": int(parts[1]), "title": parts[3], "unread": int(parts[4]), "mentions": int(parts[5]),
                                 "muted": parts[6] == "1", "pinned": parts[7] == "1", "top": int(parts[8]), "date": int(parts[9]),
                                 "preview": parts[10]}
        elif parts[0] == "filter":
            filters.append((int(parts[1]), parts[2]))
    return dialogs, filters


def dump_dialog_map(d, channel_id):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_DUMP_DIALOG", channel=channel_id)
    d.wait_log(r"DEBUG_DUMP_DIALOG ok", timeout=30, tags=["RelayDebug"])
    entries, client = {}, []
    for line in read_app_file(d, "relay-dialog.txt").splitlines():
        parts = line.split("\t")
        if parts[0] == "map":
            entries[int(parts[1])] = (parts[2], int(parts[3]), parts[4] == "1")
        elif parts[0] == "client":
            client.append(int(parts[1]))
    return entries, client


def open_chat(d, channel_id, wait=4):
    """Pushes the chat on top of the current screen (DEBUG_OPEN_CHAT, like a notification tap)."""
    d.broadcast("org.relaychat.DEBUG_OPEN_CHAT", channel=channel_id)
    time.sleep(wait)


def message_texts(nodes):
    return [n.text for n in nodes if n.text and "\n" in n.text]


def summary_counts(d):
    d.broadcast("org.relaychat.DEBUG_DUMP")
    time.sleep(2)
    text = read_app_file(d, "relay-unhandled-0.txt")
    counts = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[2].isdigit():
            counts[parts[0]] = (parts[1], int(parts[2]))
    return text, counts


# MARK: - Test

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-install", action="store_true")
    parser.add_argument("--swipes", type=int, default=150, help="flings back through the firehose")
    args = parser.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("A3 read path")
    password = relaye2e.mm_password()
    alice = Rest("alice", password)
    bob = Rest("bob", password)
    probes = []

    def probe(channel_id, text):
        post = bob.call("POST", "/posts", {"channel_id": channel_id, "message": "%s %s %d" % (PROBE, text, int(time.time()))})
        probes.append(post["id"])
        return post

    try:
        # Seed: unread posts in Town Square (engineering) and in the bob DM, made before login.
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        by_name = {}
        for c in channels.values():
            by_name.setdefault(c["name"], c)
        team_ids = {t["name"]: t["id"] for t in alice.get("/users/me/teams")}
        town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == team_ids["engineering"])
        dm_bob = next(c for c in channels.values() if c["type"] == "D" and bob.me["id"] in c["name"])
        firehose = by_name["firehose"]
        architecture = by_name["architecture"]
        media = by_name["media"]
        ios = by_name["ios"]
        # Several tall unread posts: upstream only draws "Unread Messages" when the unread part is taller
        # than half the screen.
        for i in range(6):
            probe(town["id"], "unread in town square #%d\nsecond line\nthird line\nfourth line" % i)
        probe(dm_bob["id"], "unread in the DM")
        # The firehose must open at its newest post, not at a first unread 11k posts back.
        alice.call("POST", "/channels/members/me/view", {"channel_id": firehose["id"]})

        # Fresh app + login.
        if not args.no_install:
            d.install()
        d.clear_data()
        d.reverse()
        for perm in ("POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"):
            d.shell("pm grant %s android.permission.%s" % (d.package, perm), check=False)
        d.logcat_clear()
        d.launch()
        time.sleep(4)
        started = time.time()
        d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="alice", password=password)
        m = d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
        check.ok("login loads the chat list", m is not None, m.group(0) if m else "")
        time.sleep(4)
        list_seconds = time.time() - started

        # 1. Chat list vs REST.
        members = {m["channel_id"]: m for m in alice.get("/users/me/channel_members?per_page=200")}
        # Chats without any post (e.g. a GM another test created and cleaned) have no dialog, by design since A3.
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")
                    and alice.get("/channels/%s/posts?per_page=1" % c["id"])["order"]}
        config = alice.get("/config/client?format=old")
        prefs = alice.get("/users/me/preferences")
        crt_pref = next((p["value"] for p in prefs if p["category"] == "display_settings" and p["name"] == "collapsed_reply_threads"), None)
        crt = config["CollapsedThreads"] == "always_on" or (config["CollapsedThreads"] == "default_on" and crt_pref != "off") \
            or (config["CollapsedThreads"] == "default_off" and crt_pref == "on")
        favorites = {p["name"] for p in prefs if p["category"] == "favorite_channel" and p["value"] == "true"}
        users = {u["id"]: u for u in alice.call("POST", "/users/ids", [c["name"].split("__")[1 if c["name"].startswith(alice.me["id"]) else 0]
                                                                      for c in channels.values() if c["type"] == "D"])}
        dialogs, filters = dump_dialogs(d)
        check.ok("every channel, DM and GM is a dialog", set(channels) == set(dialogs),
                 "missing=%s extra=%s" % (set(channels) - set(dialogs), set(dialogs) - set(channels)))
        bad_names, bad_unread, bad_mentions, bad_muted, bad_pinned, empty_preview = [], [], [], [], [], []
        for cid, c in channels.items():
            dlg = dialogs.get(cid)
            if not dlg:
                continue
            if c["type"] == "D":
                other = c["name"].split("__")
                other = other[1] if other[0] == alice.me["id"] else other[0]
                want = "Saved Messages" if other == alice.me["id"] else display_name(users[other])
            else:
                want = c["display_name"]
            if dlg["title"] != want:
                bad_names.append((dlg["title"], want))
            mem = members[cid]
            unread = (c["total_msg_count_root"] - mem["msg_count_root"]) if crt else (c["total_msg_count"] - mem["msg_count"])
            if dlg["unread"] != max(0, unread):
                bad_unread.append((want, dlg["unread"], unread))
            mentions = 0 if c["type"] in ("D", "G") else (mem["mention_count_root"] if crt else mem["mention_count"])
            if dlg["mentions"] != mentions:
                bad_mentions.append((want, dlg["mentions"], mentions))
            if dlg["muted"] != (mem["notify_props"].get("mark_unread") == "mention"):
                bad_muted.append(want)
            if dlg["pinned"] != (cid in favorites):
                bad_pinned.append(want)
            if not dlg["preview"].strip():
                empty_preview.append(want)
        check.ok("dialog names match REST (%d dialogs)" % len(dialogs), not bad_names, str(bad_names))
        check.ok("unread counts match REST (CRT %s)" % ("root counters" if crt else "all posts"), not bad_unread, str(bad_unread))
        check.ok("mention badges match REST (channels only)", not bad_mentions, str(bad_mentions))
        check.ok("muted state matches mark_unread=mention", not bad_muted, str(bad_muted))
        check.ok("pinned = Mattermost favourites", not bad_pinned, str(bad_pinned))
        check.ok("every dialog has a last-message preview", not empty_preview, str(empty_preview))
        check.ok("probe post is the Town Square preview", PROBE in dialogs[town["id"]]["preview"], dialogs[town["id"]]["preview"])
        teams = sorted(t["display_name"] for t in alice.get("/users/me/teams"))
        folder_titles = sorted(t for i, t in filters if i != 0)
        check.ok("one folder per team plus All", folder_titles == teams and any(i == 0 for i, _ in filters), str(filters))
        shot(d, "chatlist")
        tab = d.find(desc="Engineering", contains=True)
        if tab:
            d.tap_node(tab)
            time.sleep(2)
            shot(d, "folder-engineering")
            all_tab = d.find(desc="All Chats", contains=True)
            if all_tab:
                d.tap_node(all_tab)

        # 2. DM with bob: first unread + messages vs REST.
        d.logcat_clear()
        open_chat(d, dm_bob["id"])
        nodes = d.dump()
        texts = " ".join(t.replace("\n", " ") for t in d.screen_texts(nodes))
        dm_posts = alice.get("/channels/%s/posts?per_page=30" % dm_bob["id"])
        # Plain posts: call records (A8, e.g. A8 run as alice) show as Telegram call bubbles, not their text.
        newest = [dm_posts["posts"][i] for i in dm_posts["order"] if not dm_posts["posts"][i].get("type")][:6]
        visible = [p for p in newest if strip_md(p["message"])[:20] and strip_md(p["message"])[:20] in texts]
        check.ok("DM shows the newest posts from REST", len(visible) >= 3 and newest[0]["id"] in [p["id"] for p in visible],
                 "%d of %d newest visible" % (len(visible), len(newest)))
        # No separator check here: upstream ChatActivity only inserts "Unread Messages" when the first page
        # is taller than half the screen; a fresh cache with one unread DM message at the bottom is not.
        check.ok("DM shows the unread probe", PROBE in texts)
        shot(d, "dm-bob")

        # 3. First unread in a channel, then read marking.
        open_chat(d, town["id"])
        texts = " ".join(d.screen_texts())
        check.ok("Town Square opens at 'Unread Messages'", "Unread Messages" in texts)
        check.ok("probe post visible", PROBE in texts)
        shot(d, "first-unread")
        time.sleep(3)
        mem = alice.get("/channels/%s/members/me" % town["id"])
        ch = alice.get("/channels/%s" % town["id"])
        check.ok("opening marks the channel read on the server", mem["msg_count"] == ch["total_msg_count"],
                 "msg_count %d / total %d" % (mem["msg_count"], ch["total_msg_count"]))
        mem = alice.get("/channels/%s/members/me" % dm_bob["id"])
        ch = alice.get("/channels/%s" % dm_bob["id"])
        check.ok("opening the DM marked it read", mem["msg_count"] == ch["total_msg_count"])

        # iOS channel: replies, edited.
        open_chat(d, ios["id"])
        nodes = d.dump()
        if crt:
            # A7a: collapsed reply threads keep replies out of a channel's main history (they live in the thread view).
            check.ok("iOS channel hides thread replies (collapsed threads)", not any(n.desc.startswith("Reply,") for n in nodes))
        else:
            check.ok("iOS channel shows reply headers", any(n.desc.startswith("Reply,") for n in nodes))
        ios_posts = alice.get("/channels/%s/posts?per_page=10%s" % (ios["id"], "&collapsedThreads=true" if crt else ""))
        ios_texts = " ".join(t.replace("\n", " ") for t in d.screen_texts(nodes))
        ios_newest = [ios_posts["posts"][i] for i in ios_posts["order"]][:4]
        hits = [p for p in ios_newest if strip_md(p["message"])[:20] in ios_texts]
        check.ok("iOS channel shows the newest posts", len(hits) >= 2, "%d of %d" % (len(hits), len(ios_newest)))
        shot(d, "channel-ios")

        # 4. Firehose paging (read before login, so the chat opens at its newest post).
        d.logcat_clear()
        open_chat(d, firehose["id"])
        t0 = time.time()
        w, h = screen_size(d)  # the swipes scale with the screen (750x1536 override, 1080x2400 phones)
        lines = []
        for _round in range(3):  # denser screens scroll less per fling: fling on until 1,000+ messages are loaded
            for _ in range(args.swipes):
                d.shell("input swipe %d %d %d %d 60" % (w // 2, int(h * 0.26), w // 2, int(h * 0.88)), check=False)
            time.sleep(4)
            lines += [l for l in d.logcat("RelayBridge").splitlines() if "getHistory firehose" in l]  # the dump clears logcat
            entries, client = dump_dialog_map(d, firehose["id"])
            if len(set(client)) > 1050:
                break
        scroll_seconds = time.time() - t0
        times = [int(re.search(r"in (\d+) ms", l).group(1)) for l in lines]
        pages = len(lines)
        shot(d, "firehose")
        client_desc = sorted(set(client), reverse=True)
        check.ok("no duplicate message ids in the client", len(client) == len(set(client)))
        mapped = [entries.get(mid) for mid in client_desc]
        check.ok("every cached message id is in the bridge map", all(m is not None for m in mapped))
        posts_desc = [m[0] for m in mapped if m]
        want, page = [], None
        while len(want) < len(posts_desc):
            q = "/channels/%s/posts?per_page=200" % firehose["id"] + ("&before=" + page if page else "") + ("&collapsedThreads=true" if crt else "")
            res = alice.get(q)
            if not res["order"]:
                break
            want.extend(res["order"])
            page = res["order"][-1]
        want = want[:len(posts_desc)]
        check.ok("firehose: %d pages, %d messages, newest-first equal to REST (no gap, no duplicate)" % (pages, len(posts_desc)),
                 posts_desc == want and len(set(posts_desc)) == len(posts_desc) and len(posts_desc) > 1000,
                 "first mismatch at %s" % next((i for i, (a, b) in enumerate(zip(posts_desc, want)) if a != b), None))
        avg = sum(times) / len(times) if times else 0
        check.ok("firehose pages answer fast (avg %.0f ms, max %d ms)" % (avg, max(times) if times else 0), avg < 300)

        # 5. Reply → original (root 120 posts back: a permalink island). With collapsed threads (A7a) the replies
        # are in the thread view and the root is in the main history with its "119 Replies" footer.
        d.logcat_clear()
        open_chat(d, architecture["id"])
        if crt:
            texts = d.screen_texts()
            check.ok("collapsed threads: the RFC root is in the main history, its replies are not",
                     any("RFC: Message ID allocation" in t for t in texts) and not any(t.startswith("Reply,") for t in texts))
        reply = None if crt else next((n for n in d.dump() if n.desc.startswith("Reply,") and 250 < n.bounds[1] < 1200), None)
        if not crt and reply is None:
            check.ok("tapping a reply loads its root around the root's id", False, "no reply header")
        if reply:
            d.tap_node(reply)
            time.sleep(4)
            root_visible = any("RFC: Message ID allocation" in t for t in d.screen_texts())
            around = d.wait_log(r"getHistory architecture offset=(\d+) add=-10 limit=20", timeout=5, tags=["RelayBridge"])
            check.ok("tapping a reply loads its root around the root's id", around is not None and root_visible,
                     around.group(0) if around else "")
            shot(d, "reply-jump")

        # 6. Media: photos and a ranged document download.
        open_chat(d, media["id"], wait=6)
        shot(d, "media")
        zip_node = None
        for _ in range(12):
            found = d.find(text="simulator-diagnostics.zip", contains=True)
            if found and int(h * 0.13) < found.bounds[1] < int(h * 0.75):
                zip_node = found
                break
            d.shell("input swipe %d %d %d %d 300" % (w // 2, int(h * 0.39), w // 2, int(h * 0.59)), check=False)
            time.sleep(1)
        zip_info = next(f for p in alice.get("/channels/%s/posts?per_page=60" % media["id"])["posts"].values()
                        for f in ((p.get("metadata") or {}).get("files") or []) if f["name"] == "simulator-diagnostics.zip")
        md5_ok = False
        if zip_node:
            x1, y1, _, _ = zip_node.bounds
            d.tap(x1 + 180, y1 + 70)
            remote = hashlib.md5(alice.call("GET", "/files/%s" % zip_info["id"], raw=True)).hexdigest()
            deadline = time.time() + 60
            while time.time() < deadline and not md5_ok:
                out = d.shell("md5sum '%s/simulator-diagnostics.zip'" % EXTERNAL_FILES, check=False)
                md5_ok = out.split()[0] == remote if out.strip() and "No such" not in out else False
                time.sleep(2)
        check.ok("6 MB document downloads with ranged upload.getFile, md5 equal", md5_ok)
        _, counts = summary_counts(d)
        check.ok("photos and files went through upload.getFile", counts.get("upload.getFile", ("", 0))[1] > 10,
                 str(counts.get("upload.getFile")))

        # 7. Profile.
        open_chat(d, dm_bob["id"], wait=3)
        d.tap(300, 100)
        time.sleep(3)
        texts = d.screen_texts()
        status = alice.call("POST", "/users/status/ids", [bob.me["id"]])[0]["status"]
        want_status = {"online": "online", "dnd": "online", "away": "last seen recently"}.get(status, "last seen")
        check.ok("profile: bio from position", any(bob.me.get("position", "") in t for t in texts if bob.me.get("position")))
        check.ok("profile: username", "@bob" in texts)
        check.ok("profile: presence (%s)" % status, any(want_status in t for t in texts), str(texts[:6]))
        shot(d, "profile")

        session1, _ = summary_counts(d)

        # 8. Cold start with server-side changes.
        random_links = by_name["random-links"]
        d.force_stop()
        probe(random_links["id"], "posted while the app was stopped")
        d.logcat_clear()
        d.launch()
        # A4: the first getDifference of a process runs a REST catch-up (was: a chat-list fingerprint).
        reset = d.wait_log(r"catch-up since \d+ s: \d+ channels, (\d+) new", timeout=30, tags=["RelayBridge"])
        time.sleep(5)
        dialogs, _ = dump_dialogs(d)
        mem = alice.get("/channels/%s/members/me" % random_links["id"])
        ch = alice.get("/channels/%s" % random_links["id"])
        unread = (ch["total_msg_count_root"] - mem["msg_count_root"]) if crt else (ch["total_msg_count"] - mem["msg_count"])
        rl = dialogs.get(random_links["id"], {})
        check.ok("cold start: server change caught up", reset is not None and int(reset.group(1)) >= 1, reset.group(0) if reset else "")
        check.ok("cold start: unread from server (%d) and new preview" % unread,
                 rl.get("unread") == unread and PROBE in rl.get("preview", ""), str(rl))
        shot(d, "cold-start")

        summary, _ = summary_counts(d)
        with open(os.path.join(relaye2e.SCREENS, "a3-unhandled.txt"), "w") as f:
            f.write("# Session 1: login, chat list, DM, channels, firehose, reply jump, media, profile\n")
            f.write(session1)
            f.write("\n# Session 2: cold start after a server-side change\n")
            f.write(summary)
        check.ok("no crash", not d.crashes(), "\n".join(d.crashes()[:5]))
        print("NUMBERS dialogs=%d firehose_pages=%d firehose_messages=%d avg_page_ms=%.0f login_to_list_s=%.1f scroll_s=%.1f"
              % (len(dialogs), pages, len(client), avg, list_seconds, scroll_seconds))
    finally:
        for post_id in probes:
            try:
                bob.call("DELETE", "/posts/" + post_id)
            except Exception as e:  # noqa: BLE001
                print("cleanup failed for", post_id, e)
    print(check.summary())
    return 0 if check.passed else 1


if __name__ == "__main__":
    sys.exit(main())
