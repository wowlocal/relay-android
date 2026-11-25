#!/usr/bin/env python3
"""Relay for Android, phase A4 (live path) e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    python3 Tools/relay-e2e/test_a4_live.py [--no-install] [--no-login]

alice is logged in through the debug hook; bob (and carol) produce events over REST:

  1. Chat list: a GM post reorders the list, bumps the unread badge, shows its preview and posts an
     Android notification while the app is in the foreground.
  2. DM with bob (open): new message, edit, reaction add/remove, delete (peer-aware: carol's DM keeps
     its messages with the same ids), typing and presence (online / away / dnd / offline) in the header.
  3. GM and channel: new / edit / reaction / delete, typing in the header, a thread reply bumps the
     root's "N replies" and points at the root.
  4. Read on another device: channel_viewed from REST clears the badge.
  5. Profile change (user_updated) renames the chat; channel created → added (dialog appears), renamed
     (title follows), removed (dialog disappears).
  6. Airplane mode: "Waiting for network…" in the title, posts made meanwhile appear once afterwards
     (resumed socket); a non-resumed reconnect runs the REST catch-up (new, edited, deleted posts).
  7. Cold start: posts and edits made while the app was stopped are caught up through getDifference.

Latency (WS event → UI) comes from the debug hook's "UI newMessages" log line (logcat epoch) against the
REST response time. Probe posts are prefixed "[relay-e2e a4]" and deleted at the end.
Screens: docs/relay/screens/a4-*.png.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402

PROBE = "[relay-e2e a4]"
REST_URL = "http://localhost:%d" % relaye2e.MM_PORT


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

    def get(self, path):
        return self.call("GET", path)


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "a4-%s.png" % name))


def read_app_file(d, name):
    return d.adb("exec-out", "run-as", d.package, "cat", "files/" + name, check=False)


def dump_dialogs(d):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_DUMP_DIALOGS")
    d.wait_log(r"DEBUG_DUMP_DIALOGS ok", timeout=20, tags=["RelayDebug"])
    dialogs, order = {}, []
    for line in read_app_file(d, "relay-dialogs.txt").splitlines():
        parts = line.split("\t")
        if parts[0] == "dialog" and len(parts) >= 11:
            dialogs[parts[2]] = {"did": int(parts[1]), "title": parts[3], "unread": int(parts[4]), "mentions": int(parts[5]),
                                 "muted": parts[6] == "1", "pinned": parts[7] == "1", "top": int(parts[8]), "date": int(parts[9]),
                                 "preview": parts[10]}
            order.append(parts[2])
    return dialogs, order


def dump_messages(d, channel_id):
    """Client messages of a chat (from its database): mid → dict; plus the bridge map mid → post id."""
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_DUMP_DIALOG", channel=channel_id)
    d.wait_log(r"DEBUG_DUMP_DIALOG ok", timeout=30, tags=["RelayDebug"])
    msgs, bridge = {}, {}
    for line in read_app_file(d, "relay-dialog.txt").splitlines():
        parts = line.split("\t")
        if parts[0] == "msg" and len(parts) >= 9:
            msgs[int(parts[1])] = {"text": parts[2], "edit": int(parts[3]), "reactions": parts[4], "replies": int(parts[5]),
                                   "reply_to": int(parts[6]), "media": parts[7], "out": parts[8] == "1"}
        elif parts[0] == "map" and len(parts) >= 5:
            bridge[int(parts[1])] = (parts[2], int(parts[3]), parts[4] == "1")
    return msgs, bridge


def find_msg(msgs, text):
    return [(mid, m) for mid, m in msgs.items() if text in m["text"]]


def open_chat(d, channel_id, wait=3):
    d.broadcast("org.relaychat.DEBUG_OPEN_CHAT", channel=channel_id)
    time.sleep(wait)


def to_chat_list(d):
    for _ in range(4):
        if d.find(text="Relay") or d.find(text="Waiting for network...") or d.find(text="Connecting..."):
            return
        d.key("KEYCODE_BACK")
        time.sleep(0.8)


def screen_text(d):
    return " | ".join(t.replace("\n", " ") for t in d.screen_texts())


def wait_until(fn, timeout=15, step=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(step)
    return None


def epoch_lines(d, tag):
    out = d.adb("logcat", "-d", "-v", "epoch", "-s", tag, timeout=60, check=False)
    lines = []
    for line in out.splitlines():
        m = re.match(r"\s*(\d+\.\d+)\s", line)
        if m:
            lines.append((float(m.group(1)), line))
    return lines


def ui_latency(d, text, posted_at, timeout=10):
    """Seconds from the REST post to the "UI newMessages" line that carries `text` (debug hook)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, line in epoch_lines(d, "RelayDebug"):
            if "UI newMessages" in line and text in line:
                return t - posted_at
        time.sleep(0.3)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true", help="reuse the running app (alice logged in)")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("A4 live path")
    password = relaye2e.mm_password()
    alice, bob, carol = Rest("alice", password), Rest("bob", password), Rest("carol", password)
    probes = []          # (rest client, post id) to delete at the end
    latencies = []
    cleanup = []         # callables

    def post(client, channel_id, text, root_id=""):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text)}
        if root_id:
            body["root_id"] = root_id
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p, time.time()

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng = teams["engineering"]
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        dm_carol = carol.call("POST", "/channels/direct", [carol.me["id"], alice.me["id"]])
        # A GM with posts (one a test created and cleaned up has none, and no dialog).
        gm = next(c for c in channels.values() if c["type"] == "G" and c["display_name"].count(",") == 2
                  and alice.get("/channels/%s/posts?per_page=1" % c["id"])["order"])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        try:
            bob.call("POST", "/channels/%s/members" % sandbox["id"], {"user_id": bob.me["id"]})
        except urllib.error.HTTPError:
            pass
        # Nothing unread in the chats under test (clean badges).
        for c in (dm_bob, gm, sandbox):
            alice.call("POST", "/channels/members/me/view", {"channel_id": c["id"]})
        bob.call("PUT", "/users/%s/status" % bob.me["id"], {"user_id": bob.me["id"], "status": "online"})

        if not args.no_login:
            if not args.no_install:
                print(d.install().strip().splitlines()[-1])
            d.clear_data()
            d.reverse()
            d.shell("pm grant %s android.permission.POST_NOTIFICATIONS" % d.package, check=False)
            d.logcat_clear()
            d.launch()
            time.sleep(4)
            d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="alice", password=password)
            m = d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
            check.ok("alice logged in, chat list loaded", m is not None)
            time.sleep(5)
        to_chat_list(d)
        m = wait_until(lambda: d.wait_log(r"live: connected", timeout=1, tags=["RelayBridge"]) or d.find(text="Relay"), timeout=20)
        check.ok("WebSocket connected (title Relay, no Connecting…)", d.find(text="Relay") is not None and not d.find(text="Connecting..."),
                 screen_text(d)[:200])

        # 1. Chat list: GM post → reorder, badge, preview, notification.
        dialogs, order = dump_dialogs(d)
        before_unread = dialogs[gm["id"]]["unread"]
        t0 = int(time.time())
        d.logcat_clear()
        p_gm, t = post(bob, gm["id"], "gm hello %d" % t0)
        lat = ui_latency(d, "gm hello %d" % t0, t)
        if lat is not None:
            latencies.append(("GM post → UI", lat))
        dialogs, order = wait_until(lambda: (lambda r: r if "gm hello %d" % t0 in r[0][gm["id"]]["preview"] else None)(dump_dialogs(d)), timeout=10) or dump_dialogs(d)
        dlg = dialogs[gm["id"]]
        check.ok("GM post: preview updated", "gm hello %d" % t0 in dlg["preview"], dlg["preview"])
        check.ok("GM post: unread badge +1", dlg["unread"] == before_unread + 1, "%d -> %d" % (before_unread, dlg["unread"]))
        unpinned = [cid for cid in order if not dialogs[cid]["pinned"]]
        newest_date = max(x["date"] for x in dialogs.values())
        check.ok("GM post: chat moved to the top (newest date, first unpinned)", dlg["date"] == newest_date and unpinned and unpinned[0] == gm["id"],
                 "first unpinned=%s" % (dialogs[unpinned[0]]["title"] if unpinned else None))
        notif = wait_until(lambda: "gm hello %d" % t0 in d.shell("dumpsys notification --noredact", check=False), timeout=10)
        check.ok("GM post: Android notification shown (app in foreground)", notif)
        shot(d, "chatlist-live")

        # 2. DM with bob (open chat).
        carol_before, _ = dump_messages(d, dm_carol["id"])
        open_chat(d, dm_bob["id"])
        d.logcat_clear()
        p_dm, t = post(bob, dm_bob["id"], "dm hello %d" % t0)
        lat = ui_latency(d, "dm hello %d" % t0, t)
        if lat is not None:
            latencies.append(("DM post → UI (open chat)", lat))
        check.ok("DM post shows in the open chat", wait_until(lambda: "dm hello %d" % t0 in screen_text(d), timeout=8))
        bob.call("PUT", "/posts/%s/patch" % p_dm["id"], {"message": "%s dm edited %d" % (PROBE, t0)})
        check.ok("DM edit shows (text + 'edited')", wait_until(lambda: (lambda s: "dm edited %d" % t0 in s and "edited" in s)(screen_text(d)), timeout=8))
        bob.call("POST", "/reactions", {"user_id": bob.me["id"], "post_id": p_dm["id"], "emoji_name": "thumbsup"})
        time.sleep(1.5)
        msgs, bridge = dump_messages(d, dm_bob["id"])
        hit = find_msg(msgs, "dm edited %d" % t0)
        check.ok("DM reaction added (👍 1)", hit and "👍=1" in hit[0][1]["reactions"], str(hit))
        shot(d, "dm-live")
        bob.call("DELETE", "/users/%s/posts/%s/reactions/thumbsup" % (bob.me["id"], p_dm["id"]))
        time.sleep(1.5)
        msgs, _ = dump_messages(d, dm_bob["id"])
        hit = find_msg(msgs, "dm edited %d" % t0)
        check.ok("DM reaction removed", hit and hit[0][1]["reactions"] == "", str(hit))
        dm_mid = hit[0][0] if hit else 0
        bob.call("POST", "/users/%s/typing" % bob.me["id"], {"channel_id": dm_bob["id"]})
        check.ok("DM typing shows in the header", wait_until(lambda: "typing" in screen_text(d), timeout=4))
        shot(d, "dm-typing")
        # Presence: Mattermost sends status_change only to the user concerned; the bridge polls (15 s).
        def header():
            return " ".join(t.split("\n", 1)[1] for t in d.screen_texts() if t.startswith("Bob Brennan\n"))
        for status, want in (("away", "last seen recently"), ("offline", "last seen"), ("dnd", "online"), ("online", "online")):
            t_status = time.time()
            bob.call("PUT", "/users/%s/status" % bob.me["id"], {"user_id": bob.me["id"], "status": status})
            got = wait_until(lambda: header().startswith(want) and (want != "last seen" or "recently" not in header()), timeout=35, step=1)
            check.ok("status %s → header '%s' (%.0f s)" % (status, want, time.time() - t_status), got, header())
        bob.call("DELETE", "/posts/" + p_dm["id"])
        check.ok("DM delete removes the message", wait_until(lambda: "dm edited %d" % t0 not in screen_text(d), timeout=8))
        msgs, _ = dump_messages(d, dm_bob["id"])
        check.ok("DM delete: gone from the client database", dm_mid and dm_mid not in msgs)
        carol_after, _ = dump_messages(d, dm_carol["id"])
        shared = dm_mid in carol_before
        check.ok("DM delete is peer-aware (carol's DM keeps its %d messages%s)" % (len(carol_before), ", same id included" if shared else ""),
                 set(carol_before) <= set(carol_after))

        # 3. GM and channel: new / edit / reaction / delete, typing, thread replies.
        for name, chat in (("GM", gm), ("channel", sandbox)):
            open_chat(d, chat["id"])
            d.logcat_clear()
            p, t = post(bob, chat["id"], "%s new %d" % (name, t0))
            lat = ui_latency(d, "%s new %d" % (name, t0), t)
            if lat is not None:
                latencies.append(("%s post → UI (open chat)" % name, lat))
            check.ok("%s post shows" % name, wait_until(lambda: "%s new %d" % (name, t0) in screen_text(d), timeout=8))
            bob.call("PUT", "/posts/%s/patch" % p["id"], {"message": "%s %s edited %d" % (PROBE, name, t0)})
            bob.call("POST", "/reactions", {"user_id": bob.me["id"], "post_id": p["id"], "emoji_name": "tada"})
            carol_react = name == "GM"
            if carol_react:
                carol.call("POST", "/reactions", {"user_id": carol.me["id"], "post_id": p["id"], "emoji_name": "tada"})
            bob.call("POST", "/users/%s/typing" % bob.me["id"], {"channel_id": chat["id"]})
            typing = wait_until(lambda: "typing" in screen_text(d), timeout=4)
            check.ok("%s typing shows in the header" % name, typing, screen_text(d)[:120])
            time.sleep(1.5)
            msgs, _ = dump_messages(d, chat["id"])
            hit = find_msg(msgs, "%s edited %d" % (name, t0))
            check.ok("%s edit applied" % name, hit and hit[0][1]["edit"] > 0, str(hit))
            want = "🎉=%d" % (2 if carol_react else 1)
            check.ok("%s reactions (%s)" % (name, want), hit and want in hit[0][1]["reactions"], str(hit))
            root, _ = post(bob, chat["id"], "%s thread root %d" % (name, t0))
            reply, _ = post(carol if name == "GM" else bob, chat["id"], "%s thread reply %d" % (name, t0), root_id=root["id"])
            time.sleep(2)
            msgs, _ = dump_messages(d, chat["id"])
            r = find_msg(msgs, "%s thread root %d" % (name, t0))
            rp = find_msg(msgs, "%s thread reply %d" % (name, t0))
            check.ok("%s thread: root shows 1 reply" % name, r and r[0][1]["replies"] == 1, str(r))
            check.ok("%s thread: reply points at the root" % name, r and rp and rp[0][1]["reply_to"] == r[0][0], str(rp))
            shot(d, "%s-live" % name.lower())
            bob.call("DELETE", "/posts/" + p["id"])
            check.ok("%s delete removes the message" % name, wait_until(lambda: "%s edited %d" % (name, t0) not in screen_text(d), timeout=8))
            # A reply deleted: the root's counter goes back to 0.
            (carol if name == "GM" else bob).call("DELETE", "/posts/" + reply["id"])
            time.sleep(2)
            msgs, _ = dump_messages(d, chat["id"])
            r = find_msg(msgs, "%s thread root %d" % (name, t0))
            check.ok("%s thread: deleting the reply resets the root's counter" % name, r and r[0][1]["replies"] == 0, str(r))

        # 4. Read on another device clears the badge.
        to_chat_list(d)
        post(bob, sandbox["id"], "unread for viewed %d" % t0)
        post(bob, sandbox["id"], "unread for viewed 2 %d" % t0)
        time.sleep(2)
        dialogs, _ = dump_dialogs(d)
        unread_before = dialogs[sandbox["id"]]["unread"]
        alice.call("POST", "/channels/members/me/view", {"channel_id": sandbox["id"]})
        time.sleep(2)
        dialogs, _ = dump_dialogs(d)
        check.ok("channel_viewed from REST clears the badge", unread_before >= 2 and dialogs[sandbox["id"]]["unread"] == 0,
                 "%d -> %d" % (unread_before, dialogs[sandbox["id"]]["unread"]))
        p_dmu, _ = post(bob, dm_bob["id"], "dm unread for viewed %d" % t0)
        time.sleep(1.5)
        dialogs, _ = dump_dialogs(d)
        dm_unread = dialogs[dm_bob["id"]]["unread"]
        alice.call("POST", "/channels/members/me/view", {"channel_id": dm_bob["id"]})
        time.sleep(2)
        dialogs, _ = dump_dialogs(d)
        check.ok("DM read on another device clears the badge", dm_unread >= 1 and dialogs[dm_bob["id"]]["unread"] == 0,
                 "%d -> %d" % (dm_unread, dialogs[dm_bob["id"]]["unread"]))

        # 5. Profile and channel lifecycle.
        old_last = bob.me.get("last_name", "")
        bob.call("PUT", "/users/%s/patch" % bob.me["id"], {"last_name": "Brennan-E2E"})
        cleanup.append(lambda: bob.call("PUT", "/users/%s/patch" % bob.me["id"], {"last_name": old_last}))
        check.ok("user_updated renames the DM", wait_until(lambda: "Brennan-E2E" in dump_dialogs(d)[0][dm_bob["id"]]["title"], timeout=10))
        bob.call("PUT", "/users/%s/patch" % bob.me["id"], {"last_name": old_last})
        cleanup.pop()

        name = "relay-e2e-a4-%d" % t0
        ch = bob.call("POST", "/channels", {"team_id": eng["id"], "name": name, "display_name": "Relay E2E A4", "type": "P"})
        cleanup.append(lambda: bob.call("DELETE", "/channels/" + ch["id"]))
        post(bob, ch["id"], "welcome %d" % t0)
        bob.call("POST", "/channels/%s/members" % ch["id"], {"user_id": alice.me["id"]})
        got = wait_until(lambda: ch["id"] in dump_dialogs(d)[0], timeout=12)
        check.ok("added to a new channel → dialog appears", got)
        shot(d, "channel-added")
        bob.call("PUT", "/channels/%s/patch" % ch["id"], {"display_name": "Relay E2E A4 renamed"})
        got = wait_until(lambda: (dump_dialogs(d)[0].get(ch["id"]) or {}).get("title") == "Relay E2E A4 renamed", timeout=10)
        check.ok("channel_updated renames the dialog", got)
        p_new, _ = post(bob, ch["id"], "in the new channel %d" % t0)
        got = wait_until(lambda: "in the new channel" in (dump_dialogs(d)[0].get(ch["id"]) or {}).get("preview", ""), timeout=10)
        check.ok("posts in the new channel arrive", got)
        bob.call("DELETE", "/channels/%s/members/%s" % (ch["id"], alice.me["id"]))
        got = wait_until(lambda: ch["id"] not in dump_dialogs(d)[0], timeout=12)
        check.ok("removed from the channel → dialog disappears", got)

        # 6. Airplane mode: resumed socket replays what was missed.
        to_chat_list(d)
        d.logcat_clear()
        d.shell("cmd connectivity airplane-mode enable", check=False)
        title = wait_until(lambda: d.find(text="Waiting for network...") or d.find(text="Connecting..."), timeout=15)
        check.ok("airplane mode: title shows Waiting for network…", title is not None, screen_text(d)[:120])
        shot(d, "offline")
        off1, _ = post(bob, dm_bob["id"], "offline 1 %d" % t0)
        off2, _ = post(bob, gm["id"], "offline 2 %d" % t0)
        time.sleep(2)
        d.shell("cmd connectivity airplane-mode disable", check=False)
        back = wait_until(lambda: d.find(text="Relay") and not d.find(text="Connecting...") and not d.find(text="Waiting for network..."), timeout=40)
        check.ok("network back: title returns to Relay", back, screen_text(d)[:120])
        time.sleep(2)
        msgs_dm, _ = dump_messages(d, dm_bob["id"])
        msgs_gm, _ = dump_messages(d, gm["id"])
        n1, n2 = len(find_msg(msgs_dm, "offline 1 %d" % t0)), len(find_msg(msgs_gm, "offline 2 %d" % t0))
        resumed = "resumed" in d.logcat("RelayBridge")
        check.ok("posts made offline arrive once (DM %d, GM %d; %s)" % (n1, n2, "resumed" if resumed else "caught up"), n1 == 1 and n2 == 1)

        # Non-resumed reconnect: REST catch-up of new, edited and deleted posts.
        d.shell("cmd connectivity airplane-mode enable", check=False)
        wait_until(lambda: d.find(text="Waiting for network...") or d.find(text="Connecting..."), timeout=15)
        off3, _ = post(bob, dm_bob["id"], "offline 3 %d" % t0)
        off4, _ = post(bob, sandbox["id"], "offline 4 %d" % t0)
        bob.call("PUT", "/posts/%s/patch" % off2["id"], {"message": "%s offline 2 edited %d" % (PROBE, t0)})
        bob.call("DELETE", "/posts/" + off1["id"])
        time.sleep(1)
        d.logcat_clear()
        d.broadcast("org.relaychat.DEBUG_WS", op="reset")   # forget the resume state, reconnect now
        m = d.wait_log(r"catch-up since \d+ s: .*", timeout=20, tags=["RelayBridge"])
        check.ok("non-resumed reconnect runs the REST catch-up", m is not None, m.group(0) if m else "")
        d.shell("cmd connectivity airplane-mode disable", check=False)
        wait_until(lambda: d.find(text="Relay") and not d.find(text="Connecting..."), timeout=40)
        time.sleep(3)
        msgs_dm, _ = dump_messages(d, dm_bob["id"])
        msgs_gm, _ = dump_messages(d, gm["id"])
        msgs_sb, _ = dump_messages(d, sandbox["id"])
        check.ok("catch-up: new DM post once", len(find_msg(msgs_dm, "offline 3 %d" % t0)) == 1)
        check.ok("catch-up: new channel post once", len(find_msg(msgs_sb, "offline 4 %d" % t0)) == 1)
        check.ok("catch-up: edit applied", len(find_msg(msgs_gm, "offline 2 edited %d" % t0)) == 1)
        check.ok("catch-up: delete applied", not find_msg(msgs_dm, "offline 1 %d" % t0))

        # 7. Cold start.
        d.force_stop()
        cold1, _ = post(bob, dm_bob["id"], "cold 1 %d" % t0)
        cold2, _ = post(bob, gm["id"], "cold 2 %d" % t0)
        bob.call("PUT", "/posts/%s/patch" % off3["id"], {"message": "%s offline 3 edited cold %d" % (PROBE, t0)})
        d.logcat_clear()
        d.launch()
        m = d.wait_log(r"catch-up since \d+ s: .*", timeout=30, tags=["RelayBridge"])
        check.ok("cold start runs the catch-up", m is not None, m.group(0) if m else "")
        time.sleep(4)
        dialogs, _ = dump_dialogs(d)
        check.ok("cold start: DM preview shows the new post", "cold 1 %d" % t0 in dialogs[dm_bob["id"]]["preview"], dialogs[dm_bob["id"]]["preview"])
        msgs_dm, _ = dump_messages(d, dm_bob["id"])
        msgs_gm, _ = dump_messages(d, gm["id"])
        check.ok("cold start: posts arrive once", len(find_msg(msgs_dm, "cold 1 %d" % t0)) == 1 and len(find_msg(msgs_gm, "cold 2 %d" % t0)) == 1)
        check.ok("cold start: edit applied", len(find_msg(msgs_dm, "offline 3 edited cold %d" % t0)) == 1)
        shot(d, "cold-start")

        crashes = d.crashes()
        check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    finally:
        d.shell("cmd connectivity airplane-mode disable", check=False)
        for fn in reversed(cleanup):
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                print("cleanup failed:", e)
        try:
            bob.call("PUT", "/users/%s/status" % bob.me["id"], {"user_id": bob.me["id"], "status": "online"})
        except Exception:  # noqa: BLE001
            pass
        for client, pid in probes:
            try:
                client.call("DELETE", "/posts/" + pid)
            except Exception:  # noqa: BLE001
                pass
    for label, value in latencies:
        print("LATENCY %s: %.0f ms" % (label, value * 1000))
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
