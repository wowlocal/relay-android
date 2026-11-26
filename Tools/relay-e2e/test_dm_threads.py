#!/usr/bin/env python3
"""Relay for Android, DM threads + replier avatars e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    RELAY_APK=<apk> python3 Tools/relay-e2e/test_dm_threads.py [--no-install] [--no-login]

alice is in the app (collapsed reply threads on: the server's CollapsedThreads is default_on and alice has no
preference); bob, carol and dave post over REST (alice's own REST session posts one reply too).

  1. DM thread: a DM root with 3 replies shows root only in the DM's main history, with Telegram's comment footer
     "3 Replies" and the latest repliers' avatars (recent_repliers, users known to the client); the DM badge stays 0.
  2. Megagroup footer: a channel root's footer has the repliers' avatars too.
  3. The footer opens the DM's thread view: root + every reply, as the server's thread.
  4. A reply typed in the DM thread is a Mattermost reply (root_id) and stays out of the DM's main history;
     bob's live reply appears in the open thread; back in the main history the footer counts 5 and bob is the
     newest replier; viewing the thread marks it read on the server; channel live reply puts dave first.
  5. Message menu in the DM: "View 5 Replies" and Reply open the thread; swipe-to-reply opens it.
  6. Follow / Unfollow Thread in the DM (server's followed threads).
  7. Threads list: the DM thread's row opens the DM thread.
  8. Permalink to a DM reply opens the thread at the reply.
  9. The DM's chat-list preview stays the newest root after bob's reply.
 10. CRT off (display_settings/collapsed_reply_threads = off): DM replies inline again (no footer); back on: root
     only with the footer.
 11. Update from a build with inline DM replies (DEBUG_A7A legacy_dm, restart): the cached DM re-renders root only.

Probe posts start with "[relay-e2e dmthreads]" and are deleted at the end. Screens: docs/relay/screens/dmthreads-*.png.
"""

import argparse
import os
import re
import sys
import time
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, dump_dialogs, dump_messages, open_chat, to_chat_list, screen_text, wait_until, read_app_file  # noqa: E402
from test_a5_write import tap_send, type_message, keyboard_shown  # noqa: E402
from test_a7a_threads_links import long_press_menu, open_url, followed_ids  # noqa: E402

PROBE = "[relay-e2e dmthreads]"
SERVER = relaye2e.MM_URL


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "dmthreads-%s.png" % name))


def screen_size(d):
    sizes = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
    return (int(sizes[-1][0]), int(sizes[-1][1])) if sizes else (1080, 2400)


def ui_state(d):
    """DEBUG_A7A ui_state with the DM threads columns: visible rows are dicts id, reply_to, replies, text, repliers
    (TL user ids, newest first), footer (the cell draws "N Replies"), known (repliers the client has as users)."""
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_A7A", op="ui_state")
    d.wait_log(r"DEBUG_A7A ui_state ok", timeout=15, tags=["RelayDebug"])
    state = {"fragment": "", "dialog": 0, "thread": 0, "title": "", "visible": []}
    for line in read_app_file(d, "relay-ui.txt").splitlines():
        p = line.split("\t")
        if p[0] == "fragment":
            state["fragment"] = p[1]
        elif p[0] == "chat" and len(p) >= 4:
            state["dialog"], state["thread"], state["title"] = int(p[1]), int(p[2]), p[3]
        elif p[0] == "visible" and len(p) >= 10:
            state["visible"].append({"id": int(p[1]), "reply_to": int(p[2]), "replies": int(p[4]), "text": p[6],
                                     "repliers": [int(x) for x in p[7].split(",") if x], "footer": p[8] == "1", "known": int(p[9])})
    return state


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


def rows_with(state, text):
    return [v for v in state["visible"] if text in v["text"]]


def has(state, text):
    return bool(rows_with(state, text))


def cell_of(d, text):
    nodes = [n for n in d.dump() if text in n.text]
    return nodes[-1] if nodes else None


def tap_footer(d, text):
    """Taps the "N Replies" footer at the bottom of the bubble showing `text`."""
    cell = cell_of(d, text)
    if not cell:
        return False
    x1, y1, x2, y2 = cell.bounds
    d.tap(x1 + min(200, (x2 - x1) // 3), y2 - 22)
    return True


def login(d, password, check, install):
    if install:
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


def set_crt(alice, on):
    pref = [{"user_id": alice.me["id"], "category": "display_settings", "name": "collapsed_reply_threads", "value": "off"}]
    if on:
        try:
            alice.call("POST", "/users/%s/preferences/delete" % alice.me["id"], pref)
        except urllib.error.HTTPError:
            pass
    else:
        alice.call("PUT", "/users/%s/preferences" % alice.me["id"], pref)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("DM threads + replier avatars")
    password = relaye2e.mm_password()
    alice, bob, carol, dave = Rest("alice", password), Rest("bob", password), Rest("carol", password), Rest("dave", password)
    probes = []
    t0 = int(time.time())
    W, _H = screen_size(d)

    def post(client, channel_id, text, root_id=""):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text)}
        if root_id:
            body["root_id"] = root_id
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p

    def thread_replies(root_id):
        posts = alice.get("/posts/%s/thread" % root_id)["posts"].values()
        return sorted((p for p in posts if p.get("root_id") == root_id and not p.get("delete_at")), key=lambda p: p["create_at"])

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng = teams["engineering"]
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        for client in (bob, carol, dave):
            try:
                client.call("POST", "/channels/%s/members" % sandbox["id"], {"user_id": client.me["id"]})
            except urllib.error.HTTPError:
                pass
        set_crt(alice, True)
        for c in (dm_bob, sandbox):
            alice.call("POST", "/channels/members/me/view", {"channel_id": c["id"]})

        if not args.no_login:
            login(d, password, check, not args.no_install)
        to_chat_list(d)
        dialogs, _ = dump_dialogs(d)
        bob_tl = dialogs.get(dm_bob["id"], {}).get("did", 0)
        check.ok("fixture: the bob DM is in the chat list", bob_tl > 0, str(bob_tl))

        # MARK: 1. DM root with 3 replies (live, the app on the chat list).
        root = post(bob, dm_bob["id"], "dm root %d" % t0)
        time.sleep(1)
        alice.call("POST", "/channels/members/me/view", {"channel_id": dm_bob["id"]})
        time.sleep(1.5)
        post(bob, dm_bob["id"], "dm reply one %d" % t0, root["id"])
        post(alice, dm_bob["id"], "dm reply two %d" % t0, root["id"])
        post(bob, dm_bob["id"], "dm reply three %d" % t0, root["id"])
        time.sleep(3)
        dialogs, _ = dump_dialogs(d)
        row = dialogs.get(dm_bob["id"], {})
        check.ok("DM: thread replies don't raise the DM badge", row.get("unread") == 0, str(row.get("unread")))
        check.ok("DM: chat-list preview is the root, not the newest reply", "dm root %d" % t0 in row.get("preview", ""), row.get("preview", ""))

        open_chat(d, dm_bob["id"], wait=4)
        st = ui_state(d)
        dm_did = st["dialog"]
        roots = rows_with(st, "dm root %d" % t0)
        check.ok("DM main history shows the root", bool(roots), str([v["text"][-30:] for v in st["visible"]]))
        check.ok("DM main history hides the replies", not any(has(st, "dm reply %s %d" % (w, t0)) for w in ("one", "two", "three")),
                 str([v["text"][-30:] for v in st["visible"]]))
        r = roots[0] if roots else {}
        check.ok("DM root: footer \"3 Replies\" (reply count 3)", r.get("replies") == 3 and r.get("footer"), str(r))
        check.ok("DM root: replier avatars = bob and alice, both known users", len(r.get("repliers", [])) == 2 and bob_tl in r.get("repliers", [])
                 and r.get("known") == 2, str(r))
        check.ok("DM root: bob (the newest replier) first", r.get("repliers", [0])[0] == bob_tl if r.get("repliers") else False, str(r.get("repliers")))
        msgs, _ = dump_messages(d, dm_bob["id"])
        stored = [m for m in msgs.values() if "dm root %d" % t0 in m["text"]]
        check.ok("DM root in the client database: replies 3", stored and stored[0]["replies"] == 3, str(stored)[:200])
        shot(d, "dm-footer")

        # MARK: 2. Megagroup footer avatars.
        croot = post(bob, sandbox["id"], "channel root %d" % t0)
        post(carol, sandbox["id"], "channel reply carol %d" % t0, croot["id"])
        post(bob, sandbox["id"], "channel reply bob %d" % t0, croot["id"])
        time.sleep(2)
        to_chat_list(d)
        open_chat(d, sandbox["id"], wait=4)
        st = ui_state(d)
        c = (rows_with(st, "channel root %d" % t0) or [{}])[0]
        check.ok("channel root: footer \"2 Replies\" with 2 replier avatars (bob, carol), known users",
                 c.get("replies") == 2 and c.get("footer") and len(c.get("repliers", [])) == 2 and c.get("known") == 2, str(c))
        post(dave, sandbox["id"], "channel reply dave %d" % t0, croot["id"])
        time.sleep(3)
        st = ui_state(d)
        c2 = (rows_with(st, "channel root %d" % t0) or [{}])[0]
        check.ok("channel live reply: count 3, dave first, 3 avatars", c2.get("replies") == 3 and len(c2.get("repliers", [])) == 3
                 and c2.get("repliers", [0])[0] not in c.get("repliers", []) and c2.get("known") == 3, "%s -> %s" % (c.get("repliers"), c2))
        shot(d, "channel-footer")
        to_chat_list(d)

        # MARK: 3. Footer opens the DM thread.
        open_chat(d, dm_bob["id"], wait=4)
        d.logcat_clear()
        tap_footer(d, "dm root %d" % t0)
        time.sleep(4)
        st = ui_state(d)
        root_local = roots[0]["id"] if roots else -1
        check.ok("footer opens the DM's thread view", st["thread"] == root_local and st["dialog"] == dm_did, "thread=%s dialog=%s" % (st["thread"], st["dialog"]))
        server = [p["message"] for p in thread_replies(root["id"])]
        shown = [v["text"] for v in st["visible"] if v["id"] != root_local]
        check.ok("DM thread shows the root and every reply, as the server's thread",
                 has(st, "dm root %d" % t0) and len(server) == 3 and all(any(s in t for t in shown) for s in server),
                 "server=%s shown=%s" % ([s[-20:] for s in server], [t[-20:] for t in shown]))
        check.ok("DM thread header counts the replies", "3" in st["title"] or "Repl" in st["title"], st["title"])
        shot(d, "dm-thread")

        # MARK: 4. Reply from the DM thread; bob's live reply.
        reply_text = "%s alice in dm thread %d" % (PROBE, t0)
        type_message(d, reply_text)
        tap_send(d)
        got = wait_until(lambda: [p for p in thread_replies(root["id"]) if p["message"] == reply_text], timeout=12)
        check.ok("reply typed in the DM thread is a Mattermost reply (root_id)", got and got[0]["root_id"] == root["id"], str(got)[:200])
        if got:
            probes.append((alice, got[0]["id"]))
        if keyboard_shown(d):
            d.key("KEYCODE_BACK")
            time.sleep(0.6)
        post(bob, dm_bob["id"], "dm live reply %d" % t0, root["id"])
        check.ok("bob's live reply appears in the open DM thread", wait_until(lambda: has(ui_state(d), "dm live reply %d" % t0), timeout=12))
        shot(d, "dm-thread-live")
        thread_state = lambda: alice.get("/users/me/teams/%s/threads/%s" % (eng["id"], root["id"]))  # noqa: E731
        check.ok("viewing the DM thread marks it read on the server", wait_until(lambda: thread_state().get("unread_replies") == 0, timeout=12),
                 str({k: thread_state().get(k) for k in ("unread_replies", "last_viewed_at", "last_reply_at")}))
        st = leave_thread(d)
        r = (rows_with(st, "dm root %d" % t0) or [{}])[0]
        check.ok("back in the DM main history: own and live replies stay hidden", st["thread"] == 0 and r
                 and not has(st, "alice in dm thread %d" % t0) and not has(st, "dm live reply %d" % t0), str([v["text"][-30:] for v in st["visible"]]))
        check.ok("DM footer follows: 5 Replies, bob newest, 2 known avatars", r.get("replies") == 5 and r.get("footer")
                 and (r.get("repliers") or [0])[0] == bob_tl and r.get("known") == 2, str(r))
        shot(d, "dm-footer-after")

        # MARK: 5. Message menu and swipe.
        ok = long_press_menu(d, "dm root %d" % t0, "View 5 Replies")
        time.sleep(2.5)
        st = ui_state(d)
        check.ok("DM message menu: View 5 Replies opens the thread", ok and st["thread"] == root_local, "ok=%s thread=%s" % (ok, st["thread"]))
        leave_thread(d)
        time.sleep(1)
        ok = long_press_menu(d, "dm root %d" % t0, "Reply")
        time.sleep(2.5)
        st = ui_state(d)
        check.ok("DM message menu: Reply opens the thread (CRT)", ok and st["thread"] == root_local, "ok=%s thread=%s" % (ok, st["thread"]))
        leave_thread(d)
        time.sleep(1)
        cell = cell_of(d, "dm root %d" % t0)
        if cell:
            x1, y1, x2, y2 = cell.bounds
            y = y1 + (y2 - y1) // 3
            d.shell("input swipe %d %d %d %d 250" % (int(W * 0.85), y, int(W * 0.25), y))
        time.sleep(3)
        st = ui_state(d)
        check.ok("DM swipe-to-reply opens the thread", st["thread"] == root_local, "thread=%s" % st["thread"])
        leave_thread(d)

        # MARK: 6. Follow / Unfollow (alice replied → following).
        time.sleep(1)
        check.ok("alice follows the DM thread after replying (server)", root["id"] in followed_ids(alice, eng["id"]))
        ok = long_press_menu(d, "dm root %d" % t0, "Unfollow Thread")
        check.ok("DM message menu: Unfollow Thread", ok)
        check.ok("DM thread unfollowed on the server", wait_until(lambda: root["id"] not in followed_ids(alice, eng["id"]), timeout=8))
        time.sleep(3)
        ok = long_press_menu(d, "dm root %d" % t0, "Follow Thread")
        check.ok("DM message menu: Follow Thread", ok)
        check.ok("DM thread followed again on the server", wait_until(lambda: root["id"] in followed_ids(alice, eng["id"]), timeout=8))
        time.sleep(3)
        to_chat_list(d)

        # MARK: 9. Preview after bob's reply (the chat list is open).
        post(bob, dm_bob["id"], "dm reply after %d" % t0, root["id"])
        time.sleep(3)
        dialogs, _ = dump_dialogs(d)
        row = dialogs.get(dm_bob["id"], {})
        check.ok("DM preview stays the root after bob's reply", "dm root %d" % t0 in row.get("preview", "") and row.get("unread") == 0,
                 "%s unread=%s" % (row.get("preview"), row.get("unread")))

        # MARK: 7. Threads list → DM thread.
        btn = d.wait_for(text="Threads", timeout=10)  # the Threads tab
        if btn:
            d.tap_node(btn)
        time.sleep(3)
        tab = d.find(desc="Engineering")  # its team tabs
        if tab:
            d.tap_node(tab)
        row_node = wait_until(lambda: d.find(text="dm root %d" % t0, contains=True), timeout=10)
        check.ok("Threads list has the DM thread", row_node is not None, screen_text(d)[:300])
        shot(d, "threads-list")
        if row_node:
            d.tap_node(row_node)
            time.sleep(3.5)
            st = ui_state(d)
            check.ok("Threads list row opens the DM thread", st["thread"] == root_local and st["dialog"] == dm_did and has(st, "dm reply after %d" % t0),
                     "thread=%s dialog=%s" % (st["thread"], st["dialog"]))
            d.key("KEYCODE_BACK")
            time.sleep(1.5)
        to_chat_list(d)

        # MARK: 8. Permalink to a DM reply.
        target = thread_replies(root["id"])[1]
        open_url(d, "%s/%s/pl/%s" % (SERVER, eng["name"], target["id"]), wait=5)
        st = ui_state(d)
        check.ok("permalink to a DM reply opens the DM thread at the reply", st["thread"] == root_local and st["dialog"] == dm_did
                 and has(st, target["message"][-24:]), "thread=%s want=%r got=%s" % (st["thread"], target["message"][-24:], [v["text"][-24:] for v in st["visible"]]))
        shot(d, "permalink-dm-reply")
        to_chat_list(d)

        # MARK: 10. CRT off → inline; back on → root only.
        d.logcat_clear()
        set_crt(alice, False)
        check.ok("CRT off: the bridge follows the preference", d.wait_log(r"collapsed reply threads off", timeout=15, tags=["RelayBridge"]) is not None)
        check.ok("CRT off: DM histories reloaded", d.wait_log(r"DM histories reloaded", timeout=20, tags=["RelayBridge"]) is not None)
        time.sleep(3)
        dialogs, _ = dump_dialogs(d)
        row = dialogs.get(dm_bob["id"], {})
        check.ok("CRT off: DM preview is the newest post (a reply)", "dm reply after %d" % t0 in row.get("preview", ""), row.get("preview", ""))
        open_chat(d, dm_bob["id"], wait=5)
        st = ui_state(d)
        r = (rows_with(st, "dm root %d" % t0) or [{}])[0]
        check.ok("CRT off: DM replies inline in the main history", st["thread"] == 0 and has(st, "dm reply after %d" % t0) and has(st, "dm live reply %d" % t0),
                 str([v["text"][-30:] for v in st["visible"]]))
        check.ok("CRT off: no footer on the DM root", not r.get("footer") if r else True, str(r))
        shot(d, "crt-off-inline")
        to_chat_list(d)
        d.logcat_clear()
        set_crt(alice, True)
        check.ok("CRT back on: the bridge follows", d.wait_log(r"collapsed reply threads on", timeout=15, tags=["RelayBridge"]) is not None)
        check.ok("CRT back on: DM histories reloaded", d.wait_log(r"DM histories reloaded", timeout=20, tags=["RelayBridge"]) is not None)
        time.sleep(3)
        dialogs, _ = dump_dialogs(d)
        row = dialogs.get(dm_bob["id"], {})
        check.ok("CRT back on: DM preview is the root again", "dm root %d" % t0 in row.get("preview", ""), row.get("preview", ""))
        open_chat(d, dm_bob["id"], wait=5)
        st = ui_state(d)
        r = (rows_with(st, "dm root %d" % t0) or [{}])[0]
        check.ok("CRT back on: DM root only, footer 6 Replies with avatars", r and r.get("footer") and r.get("replies") == 6 and r.get("known") == 2
                 and not has(st, "dm reply after %d" % t0), "%s %s" % (r, [v["text"][-30:] for v in st["visible"]]))
        shot(d, "crt-on-again")
        to_chat_list(d)

        # MARK: 11. Update from a build with inline DM replies.
        set_crt(alice, False)
        d.wait_log(r"DM histories reloaded", timeout=20, tags=["RelayBridge"])
        time.sleep(2)
        open_chat(d, dm_bob["id"], wait=5)
        st = ui_state(d)
        inline = has(st, "dm reply after %d" % t0)
        to_chat_list(d)
        d.broadcast("org.relaychat.DEBUG_A7A", op="legacy_dm")
        time.sleep(1)
        d.force_stop()
        set_crt(alice, True)
        time.sleep(1)
        d.logcat_clear()
        d.launch()
        m = d.wait_log(r"DM threads: first start: (\d+) DM histories reloaded", timeout=40, tags=["RelayBridge"])
        check.ok("update from inline DM replies: cached DMs reloaded once at start", inline and m is not None, "inline cache=%s" % inline)
        time.sleep(4)
        to_chat_list(d)
        open_chat(d, dm_bob["id"], wait=5)
        st = ui_state(d)
        r = (rows_with(st, "dm root %d" % t0) or [{}])[0]
        check.ok("after the update: the cached DM shows root only with its footer", r and r.get("footer") and r.get("replies") == 6
                 and not has(st, "dm reply after %d" % t0), "%s %s" % (r, [v["text"][-30:] for v in st["visible"]]))
        shot(d, "after-update")
        to_chat_list(d)
    finally:
        set_crt(alice, True)
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
