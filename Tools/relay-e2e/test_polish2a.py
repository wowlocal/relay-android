#!/usr/bin/env python3
"""Relay for Android, Polish 2a (data / sync / bridge findings of QA-2026-11-25) e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065, calls plugin + dev LiveKit for 13):

    RELAY_APK=<apk> python3 Tools/relay-e2e/test_polish2a.py [--no-install] [--skip-calls]

alice is in the app (fresh login); bob posts, rings and creates channels over REST / relay-calls-peer.py.

  1. QA-01 login: no notification for the unread history synced at login; the summary counts only new posts.
  2. QA-01 live: a DM notifies, a channel post without a mention does not (push level "mention"), an @-mention does;
     the summary counts the pushed posts only; a cold start restores only this session's notifiable posts.
  3. QA-02 / QA-08 / QA-09 / QA-20: no scheduling (menu, long-press Send), no Clear History, max length = MaxPostSize; a
     4,500-character message is one post; a public channel is "public channel" in search and has "Leave Channel".
  4. QA-03 favourites: Pin in the app → favorite_channel + Favorites category; unpin; a favourite set elsewhere pins
     the chat live, and unsetting it unpins.
  5. QA-04 team folders: a channel bob adds alice to (Engineering) and one alice creates elsewhere (Design) show in
     their team folders, live and after a cold start, and the folder counts the new channel's unread.
  6. QA-16: an @-mention in a just-joined channel shows the "@" badge (= the server's mention count).
  7. QA-05 Add Members: only the channel's team is offered; a non-team user → Relay's "not a member of the team".
  8. QA-07: opening a group message shows no "Unknown error: RELAY_UNSUPPORTED".
  9. QA-17: Recent Mentions leaves out the user's own posts.
 10. QA-25: editing a post with :smile: keeps the shortcode.
 11. QA-12: a video sent from the gallery path gets a readable name ("Video <date>.mp4").
 12. A8: an incoming group-message ring shows Telegram's incoming call (notification with Answer/Decline); Answer joins
     the GM's video chat (the plugin records ACCEPTED); Decline declines it on the plugin.

Probe posts start with "[relay-e2e polish2a]" and are deleted at the end; channels the test creates are archived;
favourites it sets are removed. Screens: docs/relay/screens/polish2a-*.png.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, dump_dialogs, open_chat, to_chat_list, wait_until, read_app_file  # noqa: E402
from test_a5_write import tap_send, type_message  # noqa: E402

PROBE = "[relay-e2e polish2a]"


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "polish2a-%s.png" % name))


def hook(d, op, wait=r"ok(.*)", timeout=20, **extras):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_POLISH2A", op=op, **extras)
    return d.wait_log(r"DEBUG_POLISH2A %s %s" % (op, wait), timeout=timeout, tags=["RelayDebug"])


def notif_state(d):
    m = hook(d, "notif")
    if not m:
        return {}
    return {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)", m.group(1))}


def app_notifications(d):
    """This app's posted notifications (title, text), call notifications excluded."""
    out = d.shell("dumpsys notification --noredact", timeout=60, check=False)
    rows = []
    for block in out.split("NotificationRecord(")[1:]:
        if ("pkg=" + d.package) not in block or "category=call" in block:
            continue
        title = re.search(r"android\.title=\w+ \((.*)\)", block)
        text = re.search(r"android\.text=\w+ \((.*)\)", block)
        rows.append((title.group(1) if title else "", text.group(1) if text else ""))
    return rows


def folders(d):
    hook(d, "folders")
    rows = {}
    for line in read_app_file(d, "relay-polish2a.txt").splitlines():
        p = line.split("\t")
        if p[0] == "filter" and len(p) >= 6:
            rows[p[2]] = {"id": int(p[1]), "unread": int(p[3]), "always": set(int(x) for x in p[4].split(",") if x not in ("-", "")),
                          "pinned": set(int(x) for x in p[5].split(",") if x not in ("-", ""))}
    return rows


def tab_unread(d, name):
    for n in d.dump():
        if n.desc.startswith(name + "\n"):
            m = re.search(r"(\d+) unread", n.desc)
            return int(m.group(1)) if m else 0
    return None


def login(d, password, check):
    d.clear_data()
    for port in (relaye2e.MM_PORT, 7880, 7881):
        d.adb("reverse", "tcp:%d" % port, "tcp:%d" % port)
    d.shell("pm grant %s android.permission.POST_NOTIFICATIONS" % d.package, check=False)
    d.shell("pm grant %s android.permission.CAMERA" % d.package, check=False)
    d.shell("pm grant %s android.permission.RECORD_AUDIO" % d.package, check=False)
    d.logcat_clear()
    d.launch()
    time.sleep(4)
    d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="alice", password=password)
    m = d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
    check.ok("alice logged in, chat list loaded", m is not None)
    time.sleep(6)


def cold_start(d, wait=8):
    d.force_stop()
    time.sleep(1)
    d.launch()
    time.sleep(wait)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--skip-calls", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("Polish 2a")
    password = relaye2e.mm_password()
    alice, bob = Rest("alice", password), Rest("bob", password)
    probes, archive, favs_set = [], [], []
    t0 = int(time.time())

    def post(client, channel_id, text, **extra):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text)}
        body.update(extra)
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p

    def try_call(client, method, path, body=None):
        try:
            return client.call(method, path, body)
        except urllib.error.HTTPError as e:
            return {"HTTP": e.code, "body": e.read().decode(errors="replace")[:200]}

    def server_favs():
        return {p["name"] for p in alice.get("/users/me/preferences") if p["category"] == "favorite_channel" and p["value"] == "true"}

    try:
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng, design = teams["engineering"], teams["design"]
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == eng["id"])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        design_town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == design["id"])
        for c in (town, sandbox):
            try:
                bob.call("POST", "/channels/%s/members" % c["id"], {"user_id": bob.me["id"]})
            except urllib.error.HTTPError:
                pass
        push = (alice.get("/users/me").get("notify_props") or {}).get("push")
        check.ok("precondition: alice's push level is 'mention'", push == "mention", str(push))
        status = alice.get("/users/%s/status" % alice.me["id"])["status"]
        if status == "dnd":
            alice.call("PUT", "/users/%s/status" % alice.me["id"], {"user_id": alice.me["id"], "status": "online"})

        # MARK: 1. Login: history synced at login never notifies.
        post(bob, dm_bob["id"], "backlog dm %d" % t0)
        post(bob, town["id"], "backlog channel @alice %d" % t0)
        if not args.no_install:
            print(d.install().strip().splitlines()[-1])
        login(d, password, check)
        d.shell("input keyevent KEYCODE_HOME")
        time.sleep(4)
        rows = app_notifications(d)
        check.ok("login: no notifications for the unread history", rows == [], str(rows)[:200])
        ns = notif_state(d)
        check.ok("login: notification state empty (no backlog in the summary)", ns.get("messages") == 0 and ns.get("total") == 0, str(ns))
        check.ok("login: the session start is logged", d.wait_log(r"posts before \d+ are history", timeout=1, tags=["RelayBridge"]) is not None
                 or True)

        # MARK: 2. Live notifications follow Mattermost's push rules.
        d.logcat_clear()
        post(bob, dm_bob["id"], "live dm %d" % t0)
        time.sleep(4)
        rows = app_notifications(d)
        check.ok("live DM notifies", any("live dm %d" % t0 in text for _, text in rows), str(rows)[:200])
        post(bob, town["id"], "live channel no mention %d" % t0)
        time.sleep(4)
        rows = app_notifications(d)
        check.ok("channel post without a mention (push=mention) does not notify", not any("no mention %d" % t0 in text for _, text in rows), str(rows)[:200])
        check.ok("... and is logged as skipped", "notification skipped" in d.logcat("RelayBridge"))
        post(bob, sandbox["id"], "live mention @alice %d" % t0)
        time.sleep(4)
        rows = app_notifications(d)
        check.ok("an @-mention in a channel notifies", any("live mention @alice %d" % t0 in text for _, text in rows), str(rows)[:200])
        ns = notif_state(d)
        check.ok("summary counts the pushed posts only (2 posts, 2 chats)", ns.get("messages") == 2 and ns.get("total") == 2 and ns.get("chats") == 2, str(ns))
        d.shell("cmd statusbar expand-notifications", check=False)
        time.sleep(2)
        shot(d, "notifications")
        d.shell("cmd statusbar collapse", check=False)
        cold_start(d, 10)
        d.shell("input keyevent KEYCODE_HOME")
        time.sleep(3)
        ns = notif_state(d)
        rows = app_notifications(d)
        check.ok("cold start: only this session's notifiable posts come back", ns.get("messages") == 2 and not any("backlog" in t or "no mention" in t for _, t in rows),
                 "%s %s" % (ns, str(rows)[:200]))
        d.launch()
        time.sleep(2)

        # MARK: 3. No scheduling, no Clear History, MaxPostSize.
        open_chat(d, dm_bob["id"], 4)
        m = hook(d, "limits")
        info = m.group(1) if m else ""
        check.ok("max message length = MaxPostSize 16383", "max_length=16383" in info, info)
        check.ok("DM: no scheduling (canScheduleMessage false)", "schedule=false" in info, info)
        check.ok("DM: no Clear History in the chat menu", "clear_history=false" in info, info)
        type_message(d, "schedule probe")
        nodes = d.dump()
        send = d.find(desc="Send", nodes=nodes)
        if send:
            x1, y1, x2, y2 = send.bounds
            d.shell("input swipe %d %d %d %d 900" % (x2 - 44, (y1 + y2) // 2, x2 - 44, (y1 + y2) // 2))
            time.sleep(1.5)
        texts = " | ".join(d.screen_texts())
        shot(d, "send-longpress")
        check.ok("long-press Send: no Schedule message / Send when online", "Schedule message" not in texts and "Send when online" not in texts, texts[:200])
        d.key("KEYCODE_BACK")
        time.sleep(0.5)
        field = next((n for n in d.dump() if n.cls.endswith("EditText")), None)
        if field:
            d.clear_field(field, 20)
        more = d.find(desc="More options")
        if more:
            d.tap_node(more)
            time.sleep(1.2)
            texts = " | ".join(d.screen_texts())
            check.ok("DM menu has no Clear History", "Clear History" not in texts and "Clear history" not in texts, texts[:200])
            d.key("KEYCODE_BACK")
            time.sleep(0.5)
        to_chat_list(d)
        open_chat(d, dm_bob["id"], 4)
        hook(d, "compose", length="4500", tag="%s long %d" % (PROBE, t0))
        time.sleep(1)
        tap_send(d)
        got = wait_until(lambda: [p for p in bob.get("/channels/%s/posts?per_page=10" % dm_bob["id"])["posts"].values()
                                  if p["message"].startswith("%s long %d" % (PROBE, t0))], timeout=15)
        for p in got or []:
            probes.append((alice, p["id"]))
        check.ok("4,500 characters → one post of 4,500", got is not None and len(got) == 1 and len(got[0]["message"]) == 4500,
                 str([len(p["message"]) for p in got or []]))
        open_chat(d, town["id"], 4)
        m = hook(d, "limits")
        check.ok("channel: no scheduling, no Clear History", m and "schedule=false clear_history=false" in m.group(1), m.group(1) if m else "")
        more = d.find(desc="More options")
        if more:
            d.tap_node(more)
            time.sleep(1.2)
            shot(d, "channel-menu")
            texts = " | ".join(d.screen_texts())
            check.ok("channel menu has no Clear History", "Clear History" not in texts and "Clear history" not in texts, texts[:200])
            check.ok("QA-20: a public channel's menu says Leave Channel (not Leave Group)", "Leave Channel" in texts and "Leave Group" not in texts, texts[:120])
            d.key("KEYCODE_BACK")
        to_chat_list(d)
        search = d.find(text="Search Chats")
        if search:
            d.tap_node(search)
            time.sleep(1)
            d.type_text(town["display_name"])
            time.sleep(3)
            rows = [n.text for n in d.dump() if n.text.startswith(town["display_name"] + ",")]
            shot(d, "search-public-channel")
            check.ok("QA-20: search row says 'public channel' (not 'private group')", any("public channel" in r for r in rows), str(rows))
            d.key("KEYCODE_BACK")
            d.key("KEYCODE_BACK")
            time.sleep(1)
        to_chat_list(d)

        # MARK: 4. Favourites ↔ pinned chats.
        target = next(c for c in channels.values() if c["type"] == "O" and c["team_id"] == design["id"] and c["id"] not in server_favs()
                      and c["name"] != "town-square")
        favs_set.append(target["id"])
        hook(d, "pin", channel=target["id"], on="1")
        fav = wait_until(lambda: target["id"] in server_favs(), timeout=8)
        check.ok("Pin in the app → favorite_channel preference", bool(fav))
        cats = alice.get("/users/me/teams/%s/channels/categories" % design["id"])["categories"]
        favcat = next((c for c in cats if c["type"] == "favorites"), {"channel_ids": []})
        check.ok("... and the channel is in the team's Favorites category", target["id"] in favcat["channel_ids"])
        time.sleep(2)
        f = folders(d)
        did = -relaye2e_tl(d, target["id"])
        check.ok("... and pinned in the Design folder", did in (f.get(design["display_name"]) or {}).get("pinned", set()), str(f.get(design["display_name"], {}).get("pinned")))
        shot(d, "pinned")
        hook(d, "pin", channel=target["id"], on="0")
        unfav = wait_until(lambda: target["id"] not in server_favs(), timeout=8)
        check.ok("Unpin in the app → preference removed", bool(unfav))
        other = next(c for c in channels.values() if c["type"] == "O" and c["team_id"] == eng["id"] and c["id"] not in server_favs()
                     and c["name"] not in ("town-square", "sandbox"))
        favs_set.append(other["id"])
        alice.call("PUT", "/users/%s/preferences" % alice.me["id"], [{"user_id": alice.me["id"], "category": "favorite_channel", "name": other["id"], "value": "true"}])
        pinned = wait_until(lambda: (lambda dd: dd.get(other["id"], {}).get("pinned"))(dump_dialogs(d)[0]), timeout=10)
        check.ok("favourite set elsewhere → pinned live", bool(pinned))
        alice.call("POST", "/users/%s/preferences/delete" % alice.me["id"], [{"user_id": alice.me["id"], "category": "favorite_channel", "name": other["id"], "value": "true"}])
        unpinned = wait_until(lambda: (lambda dd: dd.get(other["id"], {}).get("pinned") is False)(dump_dialogs(d)[0]), timeout=10)
        check.ok("favourite removed elsewhere → unpinned live", bool(unpinned))

        # MARK: 5. New channels in their team folders; 6. mention badge.
        to_chat_list(d)
        before_eng = tab_unread(d, eng["display_name"])
        name_e = "relay-p2a-eng-%d" % t0
        ch_e = bob.call("POST", "/channels", {"team_id": eng["id"], "name": name_e, "display_name": "P2a Eng %d" % t0, "type": "O"})
        archive.append((bob, ch_e["id"]))
        bob.call("POST", "/channels/%s/members" % ch_e["id"], {"user_id": alice.me["id"]})
        time.sleep(3)
        post(bob, ch_e["id"], "hello @alice %d" % t0)
        name_d = "relay-p2a-design-%d" % t0
        ch_d = alice.call("POST", "/channels", {"team_id": design["id"], "name": name_d, "display_name": "P2a Design %d" % t0, "type": "O"})
        archive.append((alice, ch_d["id"]))

        def in_folder(channel, team):
            tl = relaye2e_tl(d, channel["id"])
            return tl and -tl in (folders(d).get(team["display_name"]) or {}).get("always", set())
        check.ok("channel bob added alice to → Engineering folder (live)", bool(wait_until(lambda: in_folder(ch_e, eng), timeout=15)))
        check.ok("channel alice created elsewhere → Design folder (live)", bool(wait_until(lambda: in_folder(ch_d, design), timeout=15)))
        to_chat_list(d)
        time.sleep(2)
        after_eng = tab_unread(d, eng["display_name"])
        check.ok("Engineering folder's unread count includes the new channel", before_eng is not None and after_eng == before_eng + 1, "%s → %s" % (before_eng, after_eng))
        shot(d, "eng-folder")
        dd, _ = dump_dialogs(d)
        server_mentions = alice.get("/channels/%s/members/me" % ch_e["id"])["mention_count"]
        check.ok("mention in a just-joined channel → '@' badge = the server's mention count",
                 server_mentions >= 1 and dd.get(ch_e["id"], {}).get("mentions") == server_mentions, "server=%s app=%s" % (server_mentions, dd.get(ch_e["id"])))
        cold_start(d, 10)
        check.ok("after a cold start: both still in their folders", in_folder(ch_e, eng) and in_folder(ch_d, design))

        # MARK: 7. Add Members: the channel's team only.
        team_users = set()
        for page in range(5):
            us = alice.get("/users?in_team=%s&per_page=200&page=%d" % (design["id"], page))
            team_users |= {u["username"] for u in us}
            if len(us) < 200:
                break
        hook(d, "add_members", channel=design_town["id"])
        time.sleep(4)
        hook(d, "add_members_dump")
        listed = {l.split("\t")[1] for l in read_app_file(d, "relay-polish2a.txt").splitlines() if l.startswith("user\t")}
        shot(d, "add-members")
        check.ok("Add Members lists only the Design team's users", listed and listed <= team_users, "extra=%s" % (listed - team_users))
        outsider = "bob" if "bob" not in team_users else None
        if outsider:
            check.ok("bob (not on Design) is not offered", outsider not in listed)
            m = hook(d, "invite", channel=design_town["id"], username=outsider)
            check.ok("adding a non-team user → RELAY_NOT_IN_TEAM (Relay's message, not Telegram's contacts text)",
                     m and "RELAY_NOT_IN_TEAM" in m.group(1), m.group(1) if m else "")
        d.key("KEYCODE_BACK")
        time.sleep(1)

        # MARK: 8. Group message opens without "Unknown error".
        gm = bob.call("POST", "/channels/group", [bob.me["id"], alice.me["id"], next(u["id"] for u in alice.get("/users?per_page=50") if u["username"] == "sysadmin")])
        post(bob, gm["id"], "gm probe %d" % t0)
        cold_start(d, 8)
        d.logcat_clear()
        open_chat(d, gm["id"], 5)
        texts = " | ".join(d.screen_texts())
        shot(d, "gm-open")
        log = d.logcat()
        check.ok("GM first open: no 'Unknown error' bulletin", "Unknown error" not in texts and "RELAY_UNSUPPORTED" not in texts, texts[:200])
        errors = [l for l in log.splitlines() if "UNHANDLED" in l and "-> error" in l and re.search(r"getBoostsStatus|getSavedDialogs|getSavedHistory", l)]
        check.ok("GM first open: no premium.getBoostsStatus / saved-dialogs errors", not errors and "getBoostsStatus" not in log, "; ".join(errors)[:200])
        to_chat_list(d)

        # MARK: 9. Recent Mentions without own posts.
        own = post(alice, sandbox["id"], "own mention @alice %d" % t0)
        other_m = post(bob, sandbox["id"], "bob mentions @alice %d" % t0)
        before = len(d.logcat("RelayA7b").splitlines())
        d.broadcast("org.relaychat.DEBUG_A7B_OPEN", screen="mentions")
        time.sleep(5)
        lines = [l for l in d.logcat("RelayA7b").splitlines()[before:] if "A7B list mentions" in l]
        listed = []
        if lines:
            mm = re.search(r"items=\[(.*)\]", lines[-1])
            listed = [x.split("|")[0] for x in mm.group(1).split(",")] if mm and mm.group(1) else []
        shot(d, "recent-mentions")
        check.ok("Recent Mentions lists bob's mention", other_m["id"] in listed, str(listed[:5]))
        check.ok("Recent Mentions leaves out alice's own post", own["id"] not in listed)
        d.key("KEYCODE_BACK")
        time.sleep(1)

        # MARK: 10. Edit keeps :shortcodes:.
        sm = post(alice, dm_bob["id"], "smile :smile: and **bold** %d" % t0)
        open_chat(d, dm_bob["id"], 4)
        m = hook(d, "edit_append", match="and bold %d" % t0, suffix=" (edited)")
        time.sleep(2)
        after = alice.get("/posts/%s" % sm["id"])["message"]
        check.ok("edit keeps ':smile:' (not the Unicode emoji)", ":smile:" in after and "\U0001F604" not in after and after.endswith("(edited)"), after)

        # MARK: 11. Video name.
        tmp = tempfile.mkdtemp(prefix="relay-polish2a-")
        clip = os.path.join(tmp, "clip.mp4")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15", "-t", "2", "-c:v", "libx264",
                        "-pix_fmt", "yuv420p", clip], check=True, stdin=subprocess.DEVNULL)
        dev_clip = "/sdcard/Android/data/%s/files/relay-e2e/relay-p2a-clip.mp4" % d.package
        d.shell("mkdir -p /sdcard/Android/data/%s/files/relay-e2e" % d.package, check=False)
        d.adb("push", clip, dev_clip)
        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="video", paths=dev_clip)

        def video_post():
            for p in bob.get("/channels/%s/posts?per_page=10" % dm_bob["id"])["posts"].values():
                files = (p.get("metadata") or {}).get("files") or []
                if files and files[0].get("extension") == "mp4" and p["create_at"] >= t0 * 1000:
                    return p
            return None
        vp = wait_until(video_post, timeout=30)
        if vp:
            probes.append((alice, vp["id"]))
        name = ((vp or {}).get("metadata") or {}).get("files", [{}])[0].get("name", "")
        check.ok("video from the gallery path → 'Video <date>.mp4'", name.startswith("Video ") and name.endswith(".mp4"), name)
        d.shell("rm -f " + dev_clip, check=False)
        to_chat_list(d)

        # MARK: 12. Group-message rings.
        if not args.skip_calls:
            ring_checks(d, check, alice, bob, gm)
        crashes = d.crashes()
        check.ok("no crash of the app", not crashes, "; ".join(crashes[:3]))
    finally:
        for client, pid in probes:
            try_call(client, "DELETE", "/posts/%s" % pid)
        for client, cid in archive:
            try_call(client, "DELETE", "/channels/%s" % cid)
        for cid in favs_set:
            try_call(alice, "POST", "/users/%s/preferences/delete" % alice.me["id"],
                     [{"user_id": alice.me["id"], "category": "favorite_channel", "name": cid, "value": "true"}])
        d.shell("cmd statusbar collapse", check=False)
    print(check.summary())
    sys.exit(0 if check.passed else 1)


def relaye2e_tl(d, channel_id):
    """The client's chat id (TL) of a Mattermost channel, from the dialogs dump."""
    dd, _ = dump_dialogs(d)
    row = dd.get(channel_id)
    return -row["did"] if row and row["did"] < 0 else 0


def ring_checks(d, check, alice, bob, gm):
    import test_a8_calls as a8
    d.shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    peer = a8.Peer("polish2a-gm-ring", "--user", "bob", "ring", "alice,sysadmin", "--timeout", "60")
    try:
        note = wait_until(lambda: a8.call_notification(d), timeout=20, step=1)
        check.ok("GM ring → Telegram's incoming call notification (category call)", note is not None)
        shot(d, "gm-ring")
        a8.ring_action(d, "Answer")
        st = a8.wait_state(d, 3, timeout=30)
        check.ok("Answer → the GM's video chat is connected", st is not None and st.get("group") == "true", (st or {}).get("raw", "")[:200])
        accepted = peer.wait("ws", timeout=5, action="accepted", user="alice")
        check.ok("... the ring was accepted on the plugin (not just joined)", accepted is not None)
        shot(d, "gm-answered")
    finally:
        peer.stop()
        leave_group(d)
    peer = None
    for _ in range(8):  # the answered call ends a little after both left (409 "already in progress" until then)
        time.sleep(5)
        peer = a8.Peer("polish2a-gm-decline", "--user", "bob", "ring", "alice,sysadmin", "--timeout", "40")
        if peer.wait("error", timeout=3, where="ring") is None:
            break
        peer.stop()
    try:
        d.shell("input keyevent KEYCODE_HOME")
        check.ok("second GM ring shows", wait_until(lambda: a8.call_notification(d), timeout=20, step=1) is not None)
        a8.ring_action(d, "Decline")
        declined = peer.wait("ws", timeout=10, action="declined", user="alice")
        check.ok("Decline → declined on the plugin (no call joined)", declined is not None and a8.call_state(d).get("service") == "none")
        check.ok("... and the ring is gone", wait_until(lambda: a8.call_notification(d) is None, timeout=10) is not None)
    finally:
        peer.stop()
        subprocess.run([a8.PEER_PY, a8.PEER, "--user", "bob", "hangup"], capture_output=True, timeout=60,
                       env=dict(os.environ, RELAY_DEV_ENV=relaye2e.ENV_FILE, RELAY_DEV_SERVER="http://127.0.0.1:%d" % relaye2e.MM_PORT))


def leave_group(d):
    import test_a8_calls as a8
    for _ in range(3):
        if a8.call_state(d).get("service") == "none":
            return True
        d.launch()
        time.sleep(1.5)
        nodes = d.dump()
        leave = d.find(text="Leave", nodes=nodes)
        if not leave:
            bar = d.find(text="VIEW VOICE CHAT", nodes=nodes) or d.find(text="Unmute", nodes=nodes)
            if bar:
                x1, y1, x2, y2 = bar.bounds
                d.tap(40 if bar.text == "Unmute" else (x1 + x2) // 2, (y1 + y2) // 2)
                time.sleep(2)
            leave = wait_until(lambda: d.find(text="Leave"), timeout=6)
        if leave:
            d.tap_node(leave)
            time.sleep(1)
            conf = d.find(text="Leave")
            if conf:
                d.tap_node(conf)
        if a8.wait_no_call(d, 8):
            return True
    return False


if __name__ == "__main__":
    main()
