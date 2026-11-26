#!/usr/bin/env python3
"""Relay for Android, phase A7a (threads, links, forwards, jump to date) e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    python3 Tools/relay-e2e/test_a7a_threads_links.py [--no-install] [--no-login]

alice is in the app (collapsed reply threads on: the server's CollapsedThreads is default_on and alice has no
preference); bob, carol and dave post over REST.

  1. Collapsed threads: a channel's main history shows the root with Telegram's "N Replies" footer, not the
     replies; replies don't raise the channel's or the DM's unread badge.
  2. Thread view: "View N Replies" opens the thread (root + replies); a reply typed there is a Mattermost reply
     (root_id); bob's next reply appears live in the open thread and stays out of the main history.
  3. Follow / Unfollow Thread from the message menu (followed-threads list on the server).
  4. Threads list: the Threads tab with the unread badge, rows of the team, "new reply", open from the list
     marks the thread read on the server.
  5. Copy Link == <server>/<team>/pl/<post>.
  6. Links (DEBUG_OPEN_URL, Browser.openUrl like a tap): post permalink lands on the message in its chat, a reply
     of a 119-reply thread opens its thread at the reply, channel by name, DM by @username, thread link,
     relay:// link, a post that doesn't exist (alert), a post in an unjoined public channel (join prompt with a
     preview, Join → the post).
  7. Incoming forward (a permalink-only post with the server's permalink embed) shows "Forwarded from" + the
     original author and text.
  8. Jump to date (Telegram's ChatActivity.jumpToDate, load type 4) in the 11k-post firehose: 2013-05-01 and a
     date inside the 11-year gap.
  9. Collapsed threads off (alice's display_settings/collapsed_reply_threads = off): replies inline again.

Probe posts start with "[relay-e2e a7a]" and are deleted at the end; the unjoined channel is archived.
Screens: docs/relay/screens/a7a-*.png.
"""

import argparse
import datetime
import os
import sys
import time
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, dump_dialogs, open_chat, to_chat_list, screen_text, wait_until, read_app_file  # noqa: E402
from test_a5_write import composer, tap_send, type_message, keyboard_shown  # noqa: E402

PROBE = "[relay-e2e a7a]"
SERVER = relaye2e.MM_URL


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "a7a-%s.png" % name))


def ui_state(d):
    """The top fragment (DEBUG_A7A ui_state): dict with fragment, dialog, thread, title, visible[(id, reply_to, fwd, replies, date, text)]."""
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_A7A", op="ui_state")
    d.wait_log(r"DEBUG_A7A ui_state ok", timeout=15, tags=["RelayDebug"])
    state = {"fragment": "", "dialog": 0, "thread": 0, "title": "", "visible": [], "highlight": 0}
    for line in read_app_file(d, "relay-ui.txt").splitlines():
        parts = line.split("\t")
        if parts[0] == "fragment":
            state["fragment"] = parts[1]
        elif parts[0] == "chat" and len(parts) >= 4:
            state["dialog"], state["thread"], state["title"] = int(parts[1]), int(parts[2]), parts[3]
        elif parts[0] == "highlight":
            state["highlight"] = int(parts[1])
        elif parts[0] == "visible" and len(parts) >= 7:
            state["visible"].append((int(parts[1]), int(parts[2]), int(parts[3]), int(parts[4]), int(parts[5]), parts[6]))
    return state


def visible_texts(state):
    return [v[5] for v in state["visible"]]


def has_text(state, text):
    return any(text in t for t in visible_texts(state))


def long_press_menu(d, text, item, timeout=4):
    """Opens the message menu of the message showing `text` and taps `item` (exact or prefix).

    The tap goes to the text of the bubble: a root's bottom is its "N Replies" footer, which
    opens the thread, and a bulletin from the previous action may cover the bottom of the screen.
    """
    if keyboard_shown(d):
        d.key("KEYCODE_BACK")
        time.sleep(0.6)
    n = d.find(text=text, contains=True)
    if n is None:
        return False
    x1, y1, x2, y2 = n.bounds
    # Incoming bubble: inside the text, below the sender's name (a tap on the name opens the profile).
    d.tap(min(x2 - 40, x1 + 300), y1 + int((y2 - y1) * 0.42))
    time.sleep(1.2)
    entry = wait_until(lambda: next((x for x in d.dump() if x.text == item or (x.text and x.text.startswith(item))), None), timeout=timeout)
    if entry is None:
        d.key("KEYCODE_BACK")
        return False
    d.tap_node(entry)
    time.sleep(1.2)
    return True


def leave_thread(d):
    """Back from a thread view to the main history (the first BACK may only close the keyboard)."""
    for _ in range(3):
        if keyboard_shown(d):
            d.key("KEYCODE_BACK")
            time.sleep(0.8)
        st = ui_state(d)
        if st["thread"] == 0:
            return st
        d.key("KEYCODE_BACK")
        time.sleep(1.5)
    return ui_state(d)


def open_url(d, url, wait=3.5):
    d.broadcast("org.relaychat.DEBUG_OPEN_URL", url=url)
    time.sleep(wait)


def followed_ids(client, team_id):
    res = client.get("/users/me/teams/%s/threads?per_page=200" % team_id)
    return {t["id"]: t for t in res.get("threads", [])}


def midnight(y, m, dd):
    """Local midnight of the device (UTC on the emulator image) as Telegram's calendar sends it."""
    return int(datetime.datetime(y, m, dd, tzinfo=datetime.timezone.utc).timestamp())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("A7a threads + links")
    password = relaye2e.mm_password()
    alice, bob, carol = Rest("alice", password), Rest("bob", password), Rest("carol", password)
    probes = []
    cleanup = []
    t0 = int(time.time())

    def post(client, channel_id, text, root_id=""):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text) if text else ""}
        if root_id:
            body["root_id"] = root_id
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p

    def badge(name):
        dialogs, _ = dump_dialogs(d)
        return dialogs[name]["unread"] if name in dialogs else None

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng = teams["engineering"]
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == eng["id"])
        architecture = next(c for c in channels.values() if c["name"] == "architecture" and c["team_id"] == eng["id"])
        firehose = next(c for c in channels.values() if c["name"] == "firehose" and c["team_id"] == eng["id"])
        try:
            bob.call("POST", "/channels/%s/members" % sandbox["id"], {"user_id": bob.me["id"]})
        except urllib.error.HTTPError:
            pass
        # Collapsed threads on for alice (the server default): no preference of her own.
        try:
            alice.call("POST", "/users/%s/preferences/delete" % alice.me["id"],
                       [{"user_id": alice.me["id"], "category": "display_settings", "name": "collapsed_reply_threads", "value": "off"}])
        except urllib.error.HTTPError:
            pass
        for c in (dm_bob, sandbox):
            alice.call("POST", "/channels/members/me/view", {"channel_id": c["id"]})

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

        # MARK: 1. Collapsed threads in the main history, unread badges.
        root = post(bob, sandbox["id"], "thread root %d" % t0)
        time.sleep(1.5)
        alice.call("POST", "/channels/members/me/view", {"channel_id": sandbox["id"]})
        time.sleep(1.5)
        before = badge(sandbox["id"])
        r1 = post(bob, sandbox["id"], "first reply %d" % t0, root["id"])
        r2 = post(bob, sandbox["id"], "second reply %d" % t0, root["id"])
        time.sleep(3)
        after = badge(sandbox["id"])
        check.ok("CRT: bob's replies don't raise the channel badge", before == after == 0, "%s -> %s" % (before, after))

        dm_root = post(bob, dm_bob["id"], "dm root %d" % t0)
        time.sleep(1.5)
        alice.call("POST", "/channels/members/me/view", {"channel_id": dm_bob["id"]})
        time.sleep(2)
        dm_before = badge(dm_bob["id"])
        post(bob, dm_bob["id"], "dm reply %d" % t0, dm_root["id"])
        time.sleep(3)
        dm_after = badge(dm_bob["id"])
        check.ok("CRT: a DM thread reply doesn't raise the DM badge (A5 known issue)", dm_before == dm_after == 0, "%s -> %s" % (dm_before, dm_after))

        open_chat(d, sandbox["id"], wait=4)
        st = ui_state(d)
        root_ids = [v for v in st["visible"] if "thread root %d" % t0 in v[5]]
        check.ok("main history shows the root", bool(root_ids), str(visible_texts(st))[-300:])
        check.ok("main history hides the replies", not has_text(st, "first reply %d" % t0) and not has_text(st, "second reply %d" % t0))
        check.ok("root carries the reply count (2)", root_ids and root_ids[0][3] == 2, str(root_ids))
        sandbox_did = st["dialog"]
        shot(d, "channel-crt")

        # MARK: 2. Thread view.
        opened = long_press_menu(d, "thread root %d" % t0, "View 2 Replies")
        check.ok("message menu: View 2 Replies", opened)
        time.sleep(2.5)
        st = ui_state(d)
        check.ok("thread view opened on the root", st["thread"] == (root_ids[0][0] if root_ids else -1) and st["dialog"] == sandbox_did,
                 "thread=%s dialog=%s" % (st["thread"], st["dialog"]))
        check.ok("thread view shows root + both replies", has_text(st, "thread root %d" % t0) and has_text(st, "first reply %d" % t0)
                 and has_text(st, "second reply %d" % t0), str(visible_texts(st))[-300:])
        shot(d, "thread")
        reply_text = "%s alice in thread %d" % (PROBE, t0)
        type_message(d, reply_text)
        tap_send(d)
        got = wait_until(lambda: [p for p in bob.get("/posts/%s/thread" % root["id"])["posts"].values() if p["message"] == reply_text], timeout=12)
        check.ok("reply typed in the thread is a Mattermost reply (root_id)", got and got[0]["root_id"] == root["id"], str(got)[:200])
        if got:
            probes.append((alice, got[0]["id"]))
        live = post(bob, sandbox["id"], "live reply %d" % t0, root["id"])
        check.ok("bob's new reply appears live in the open thread", wait_until(lambda: has_text(ui_state(d), "live reply %d" % t0), timeout=12))
        shot(d, "thread-live")
        st = leave_thread(d)
        check.ok("back in the main history: replies (incl. live + own) stay hidden", st["thread"] == 0 and has_text(st, "thread root %d" % t0)
                 and not has_text(st, "live reply %d" % t0) and not has_text(st, "alice in thread %d" % t0), str(visible_texts(st))[-200:])
        root_v = [v for v in st["visible"] if "thread root %d" % t0 in v[5]]
        check.ok("root's count follows live replies (4)", root_v and root_v[0][3] == 4, str(root_v))

        # MARK: 3. Follow / unfollow (alice replied → following).
        time.sleep(1)
        check.ok("alice follows the thread after replying (server)", root["id"] in followed_ids(alice, eng["id"]))
        ok = long_press_menu(d, "thread root %d" % t0, "Unfollow Thread")
        check.ok("message menu: Unfollow Thread", ok)
        check.ok("unfollowed on the server", wait_until(lambda: root["id"] not in followed_ids(alice, eng["id"]), timeout=8))
        shot(d, "unfollowed")
        time.sleep(3)  # the bulletin goes away
        ok = long_press_menu(d, "thread root %d" % t0, "Follow Thread")
        check.ok("message menu: Follow Thread", ok)
        check.ok("followed again on the server", wait_until(lambda: root["id"] in followed_ids(alice, eng["id"]), timeout=8))

        # MARK: 5. Copy Link.
        time.sleep(3)
        ok = long_press_menu(d, "thread root %d" % t0, "Copy Link")
        check.ok("message menu: Copy Link", ok)
        d.logcat_clear()
        d.broadcast("org.relaychat.DEBUG_A7A", op="clipboard")
        m = d.wait_log(r"DEBUG_A7A clipboard ok (\S+)", timeout=10, tags=["RelayDebug"])
        expected = "%s/%s/pl/%s" % (SERVER, eng["name"], root["id"])
        copied = m.group(1) if m else None
        check.ok("Copy Link text == REST permalink", copied == expected, "%s vs %s" % (copied, expected))

        # Reply in the main history opens the thread (the reply would leave the main history at once).
        time.sleep(2)
        ok = long_press_menu(d, "thread root %d" % t0, "Reply")
        time.sleep(2.5)
        st = ui_state(d)
        check.ok("message menu Reply (main history, CRT) opens the thread", ok and st["thread"] == (root_ids[0][0] if root_ids else -1),
                 "thread=%s" % st["thread"])
        leave_thread(d)

        # MARK: 4. Threads list + unread badge.
        to_chat_list(d)
        time.sleep(1)
        post(bob, sandbox["id"], "unread reply %d" % t0, root["id"])
        # The Threads tab (it replaced the chat list's threads button) reads its badge: "Threads, N unread".
        btn = wait_until(lambda: next((n for n in d.dump() if n.desc.startswith("Threads,")), None), timeout=15)
        check.ok("Threads tab shows the unread badge", btn is not None, btn.desc if btn else str([n.desc for n in d.dump() if n.desc][:10]))
        shot(d, "threads-tab")
        btn = btn or d.find(text="Threads")
        if btn:
            d.tap_node(btn)
        time.sleep(3)
        tab = d.find(desc="Engineering")  # the team tabs; the list opens on the selected folder's team ("All" keeps it)
        if tab:
            d.tap_node(tab)
        rows_ok = wait_until(lambda: d.find(text="thread root %d" % t0, contains=True), timeout=10)
        check.ok("Threads list shows the followed thread", rows_ok is not None)
        check.ok("row shows the new reply", d.find(text="1 new reply", contains=True) is not None, screen_text(d)[:300])
        shot(d, "threads-list")
        if rows_ok:
            d.tap_node(rows_ok)
            time.sleep(3.5)
            st = ui_state(d)
            check.ok("tapping the row opens the thread", st["thread"] != 0 and has_text(st, "unread reply %d" % t0), "thread=%s" % st["thread"])
            read = wait_until(lambda: followed_ids(alice, eng["id"]).get(root["id"], {}).get("unread_replies") == 0, timeout=12)
            check.ok("viewing the thread marks it read on the server", read)
            d.key("KEYCODE_BACK")
            time.sleep(1.5)
        to_chat_list(d)

        # MARK: 6. Links.
        target = post(carol, town["id"], "permalink target %d" % t0)
        time.sleep(1.5)
        open_url(d, "%s/%s/pl/%s" % (SERVER, eng["name"], target["id"]))
        st = ui_state(d)
        town_did = st["dialog"]
        check.ok("post permalink opens its chat at the post", st["fragment"] == "ChatActivity" and st["thread"] == 0 and has_text(st, "permalink target %d" % t0),
                 "%s %s" % (st["title"], visible_texts(st)[-3:]))
        shot(d, "permalink-post")
        to_chat_list(d)

        t1 = bob.get("/channels/%s/pinned" % architecture["id"])
        t1_root = next((p for p in t1["posts"].values() if p.get("reply_count", 0) >= 100 and not p.get("root_id")), None)
        deep = None
        deep_thread = 0
        if t1_root:
            thread = alice.get("/posts/%s/thread" % t1_root["id"])
            replies = sorted((p for p in thread["posts"].values() if p.get("root_id") == t1_root["id"]), key=lambda p: p["create_at"])
            deep = replies[100] if len(replies) > 100 else (replies[-1] if replies else None)
        check.ok("fixture: T1 thread with 100+ replies", deep is not None)
        if deep:
            open_url(d, "%s/%s/pl/%s" % (SERVER, eng["name"], deep["id"]), wait=5)
            st = ui_state(d)
            snippet = deep["message"][:30]
            check.ok("reply permalink (CRT) opens its thread at the reply", st["thread"] != 0 and has_text(st, snippet),
                     "thread=%s want=%r got=%s" % (st["thread"], snippet, [t[:30] for t in visible_texts(st)]))
            deep_thread = st["thread"]
            shot(d, "permalink-reply-thread")
            to_chat_list(d)
            open_url(d, "%s/%s/threads/%s" % (SERVER, eng["name"], t1_root["id"]), wait=5)
            st = ui_state(d)
            check.ok("thread link opens the thread", st["fragment"] == "ChatActivity" and st["thread"] == deep_thread != 0, "thread=%s want %s" % (st["thread"], deep_thread))
            shot(d, "thread-link")
            to_chat_list(d)

        open_url(d, "%s/%s/channels/town-square" % (SERVER, eng["name"]))
        st = ui_state(d)
        check.ok("channel link opens the channel", st["fragment"] == "ChatActivity" and st["dialog"] == town_did and st["thread"] == 0, st["title"])
        to_chat_list(d)
        open_url(d, "%s/%s/messages/@carol" % (SERVER, eng["name"]))
        st = ui_state(d)
        check.ok("DM link (@carol) opens the DM", st["fragment"] == "ChatActivity" and st["dialog"] > 0 and "Carol" in st["title"], st["title"])
        to_chat_list(d)
        open_url(d, "relay://localhost:%d/%s/pl/%s" % (relaye2e.MM_PORT, eng["name"], target["id"]))
        st = ui_state(d)
        check.ok("relay:// permalink opens the post", st["dialog"] == town_did and has_text(st, "permalink target %d" % t0), st["title"])
        to_chat_list(d)
        open_url(d, "%s/%s/pl/%s" % (SERVER, eng["name"], "a" * 26))
        alert = wait_until(lambda: d.find(text="doesn't exist", contains=True), timeout=6)
        check.ok("missing post: alert", alert is not None)
        shot(d, "link-not-found")
        d.key("KEYCODE_BACK")
        time.sleep(1)

        # Unjoined public channel → join prompt.
        name = "relay-e2e-a7a-%d" % t0
        ch = bob.call("POST", "/channels", {"team_id": eng["id"], "name": name, "display_name": "A7a Unjoined %d" % t0,
                                            "type": "O", "purpose": "A public channel alice is not in (A7a permalink check)"})
        cleanup.append(lambda: bob.call("DELETE", "/channels/%s" % ch["id"]))
        for i in range(3):
            post(bob, ch["id"], "unjoined filler %d" % i)
        unjoined = post(bob, ch["id"], "unjoined target %d" % t0)
        time.sleep(1)
        open_url(d, "%s/%s/pl/%s" % (SERVER, eng["name"], unjoined["id"]))
        prompt = wait_until(lambda: d.find(text="Join A7a Unjoined", contains=True), timeout=8)
        check.ok("unjoined public channel: join prompt", prompt is not None)
        check.ok("join prompt previews the channel (name, members, recent posts)",
                 wait_until(lambda: (lambda s: "~" + name in s and "unjoined target" in s)(screen_text(d)), timeout=3), screen_text(d)[:300])
        shot(d, "join-prompt")
        join = d.find(text="Join")
        if join:
            d.tap_node(join)
        time.sleep(5)
        st = ui_state(d)
        check.ok("Join → the post in the joined channel", st["fragment"] == "ChatActivity" and has_text(st, "unjoined target %d" % t0),
                 "%s %s" % (st["title"], visible_texts(st)[-3:]))
        members = [m["user_id"] for m in bob.get("/channels/%s/members?per_page=100" % ch["id"])]
        check.ok("alice is a member on the server", alice.me["id"] in members)
        shot(d, "joined")
        to_chat_list(d)

        # MARK: 7. Incoming forward.
        fwd = bob.call("POST", "/posts", {"channel_id": dm_bob["id"], "message": "%s/%s/pl/%s" % (SERVER, eng["name"], target["id"])})
        probes.append((bob, fwd["id"]))
        time.sleep(1)
        fwd_post = bob.get("/posts/%s" % fwd["id"])
        has_embed = any(e.get("type") == "permalink" for e in (fwd_post.get("metadata") or {}).get("embeds", []) or [])
        check.ok("fixture: forward has the server's permalink embed", has_embed)
        open_chat(d, dm_bob["id"], wait=4)
        st = ui_state(d)
        fwd_rows = [v for v in st["visible"] if v[2] != 0 and "permalink target %d" % t0 in v[5]]
        check.ok("forward shows the original text with a forward header (original author)", bool(fwd_rows), str(st["visible"][-3:]))
        check.ok("'Forwarded from' visible", "Forwarded from" in screen_text(d), screen_text(d)[-300:])
        shot(d, "forward")
        to_chat_list(d)

        # MARK: 8. Jump to date (firehose).
        open_chat(d, firehose["id"], wait=4)
        day = midnight(2013, 5, 1)
        d.logcat_clear()
        d.broadcast("org.relaychat.DEBUG_A7A", op="jump_date", date=str(day))
        m = d.wait_log(r"jump to date \d+ in firehose: .* (\d+) requests", timeout=30, tags=["RelayBridge"])
        time.sleep(3)
        st = ui_state(d)
        dates = [v[4] for v in st["visible"]]
        check.ok("jump to 2013-05-01: bridge found the anchor by binary search", m is not None, m.group(0)[:160] if m else "")
        check.ok("jump to 2013-05-01: visible posts are around that day",
                 dates and all(abs(x - day) < 10 * 86400 for x in dates) and any(x >= day for x in dates),
                 str([datetime.datetime.utcfromtimestamp(x).strftime("%Y-%m-%d") for x in dates]))
        shot(d, "jump-date-2013")
        day2 = midnight(2020, 1, 1)
        d.broadcast("org.relaychat.DEBUG_A7A", op="jump_date", date=str(day2))
        time.sleep(5)
        st = ui_state(d)
        dates = [v[4] for v in st["visible"]]
        check.ok("jump into the 2013–2024 gap: the last 2013 posts / first 2024 posts",
                 dates and all(x < midnight(2013, 7, 1) or midnight(2024, 11, 1) <= x < midnight(2025, 1, 1) for x in dates),
                 str([datetime.datetime.utcfromtimestamp(x).strftime("%Y-%m-%d") for x in dates]))
        to_chat_list(d)

        # MARK: 9. Collapsed threads off → replies inline.
        alice.call("PUT", "/users/%s/preferences" % alice.me["id"],
                   [{"user_id": alice.me["id"], "category": "display_settings", "name": "collapsed_reply_threads", "value": "off"}])
        cleanup.append(lambda: alice.call("POST", "/users/%s/preferences/delete" % alice.me["id"],
                                          [{"user_id": alice.me["id"], "category": "display_settings", "name": "collapsed_reply_threads", "value": "off"}]))
        m = d.wait_log(r"collapsed reply threads off", timeout=15, tags=["RelayBridge"])
        check.ok("CRT off: the bridge follows the preference", m is not None)
        time.sleep(4)
        open_chat(d, sandbox["id"], wait=5)
        st = ui_state(d)
        check.ok("CRT off: replies inline in the main history", has_text(st, "live reply %d" % t0) or has_text(st, "unread reply %d" % t0),
                 str(visible_texts(st))[-300:])
        shot(d, "crt-off-inline")
        to_chat_list(d)
    finally:
        for fn in cleanup:
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                print("cleanup:", e)
        for client, pid in reversed(probes):
            try:
                client.call("DELETE", "/posts/%s" % pid)
            except Exception:  # noqa: BLE001
                pass
        print("crashes:", d.crashes() or "none")
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
