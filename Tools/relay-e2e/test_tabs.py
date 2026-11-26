#!/usr/bin/env python3
"""Relay for Android: the main tab bar Chats · Threads · Mentions · Settings · Profile, e2e against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    python3 Tools/relay-e2e/test_tabs.py [--no-install] [--no-login]

alice is in the app (collapsed reply threads on: the server default); bob and carol post over REST; bob also logs in as
a second account for the account-switch checks.

  1. Tab bar: labels and order (no Contacts, no Calls); no Threads button in the chat list's header; Settings has
     Saved Posts and no Recent Mentions row; New Message (pencil) still lists people (the old Contacts tab's job).
  2. Threads tab: opens on the team of the chat list's selected folder (Design, Engineering; "All" keeps the team); its
     rows are the server's followed threads of that team.
  3. Badge: bob's reply in a followed thread raises it live (+1), an @alice reply puts "@" in front; tapping the row opens
     the thread (read on the server, the badge goes back), back returns to the tab.
  4. Mentions tab: lists alice's mentions, a new @alice post appears live while the tab is shown; tapping it opens the
     chat at the post, back returns to the tab.
  5. Re-tapping a selected tab scrolls its list to the top; back from Threads / Mentions goes to Chats.
  6. Collapsed threads off: the Threads tab stays and says so, no badge; back on: the rows return.
  7. Dark theme (screenshots of every tab).
  8. Profile long press opens the account selector; Add Account → bob: the tabs follow bob (rows, badge); switching
     back to alice brings hers back.

Probe posts start with "[relay-e2e tabs]" and are deleted at the end. Screens: docs/relay/screens/tabs-*.png.
"""

import argparse
import os
import re
import sys
import time
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, wait_until, read_app_file  # noqa: E402
from test_a7a_threads_links import ui_state, has_text  # noqa: E402

PROBE = "[relay-e2e tabs]"
LABELS = ("Chats", "Threads", "Mentions", "Settings", "Profile", "Contacts", "Calls")


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "tabs-%s.png" % name))


def screen_height(d):
    m = re.search(r"(\d+)x(\d+)", d.shell("wm size").splitlines()[-1])
    return int(m.group(2)) if m else 1536


def tabs_state(d):
    """DEBUG_TABS state → dict (top, page, badge, threads_desc, threads_team, threads_teams, threads_rows, empty,
    mentions_rows, first)."""
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_TABS", op="state")
    d.wait_log(r"DEBUG_TABS state ok", timeout=15, tags=["RelayDebug"])
    state = {}
    for line in read_app_file(d, "relay-tabs.txt").splitlines():
        parts = line.split("\t")
        state[parts[0]] = parts[1:] if len(parts) > 2 else (parts[1] if len(parts) > 1 else "")
    return state


def thread_rows(state):
    """threads_rows → {root post id: (unread replies, unread mentions)}."""
    rows = {}
    for item in (state.get("threads_rows") or "").split(","):
        parts = item.split(":")
        if len(parts) == 3:
            rows[parts[0]] = (int(parts[1]), int(parts[2]))
    return rows


def mention_rows(state):
    return [x for x in (state.get("mentions_rows") or "").split(",") if x]


def badge_count(state):
    m = re.search(r"(\d+)", state.get("badge") or "")
    return int(m.group(1)) if m else 0


def first_row(state):
    try:
        return int(state.get("first", "-1"))
    except ValueError:
        return -1


def bar(d, nodes=None):
    """The tab bar's labels, left to right."""
    h = screen_height(d)
    nodes = nodes if nodes is not None else d.dump()
    tabs = [n for n in nodes if n.text in LABELS and n.bounds[1] > h * 0.8]
    return [n.text for n in sorted(tabs, key=lambda n: n.bounds[0])], tabs


def tap_tab(d, label, wait=2.0):
    for _ in range(3):
        _, tabs = bar(d)
        node = next((n for n in tabs if n.text == label), None)
        if node:
            d.tap_node(node)
            time.sleep(wait)
            return True
        d.key("KEYCODE_BACK")
        time.sleep(1)
    return False


def tap_folder(d, name):
    node = next((n for n in d.dump() if n.desc == name or n.desc.startswith(name + "\n")), None)
    if node:
        d.tap_node(node)
        time.sleep(1.5)
    return node is not None


def followed(client, team_id):
    res = client.get("/users/me/teams/%s/threads?per_page=200" % team_id)
    return {t["id"]: t for t in res.get("threads", []) if not t.get("delete_at")}


_ARCHIVED = {}


def followed_listed(client, team_id):
    """The followed threads the Threads tab lists: those in archived channels are left out (as since A7a)."""
    result = {}
    for tid, t in followed(client, team_id).items():
        channel_id = (t.get("post") or {}).get("channel_id")
        if channel_id not in _ARCHIVED:
            try:
                _ARCHIVED[channel_id] = bool(client.get("/channels/%s" % channel_id).get("delete_at"))
            except urllib.error.HTTPError:
                _ARCHIVED[channel_id] = True
        if not _ARCHIVED[channel_id]:
            result[tid] = t
    return result


def expected_badge(client, team_ids):
    """The Threads badge from the server: unread followed threads of all teams, "@" when one has mentions."""
    unread, mentions = 0, 0
    for team_id in team_ids:
        for t in followed(client, team_id).values():
            if t.get("unread_replies", 0) > 0 or t.get("unread_mentions", 0) > 0:
                unread += 1
                mentions += t.get("unread_mentions", 0)
    if unread == 0:
        return "-"
    return ("@" if mentions > 0 else "") + str(unread)


def set_crt(client, on):
    pref = [{"user_id": client.me["id"], "category": "display_settings", "name": "collapsed_reply_threads", "value": "off"}]
    if on:
        try:
            client.call("POST", "/users/%s/preferences/delete" % client.me["id"], pref)
        except urllib.error.HTTPError:
            pass
    else:
        client.call("PUT", "/users/%s/preferences" % client.me["id"], pref)


def mark_thread_read(client, team_id, root_id):
    client.call("PUT", "/users/%s/teams/%s/threads/%s/read/%d" % (client.me["id"], team_id, root_id, int(time.time() * 1000) + 1000))


def scroll_list(d, times=2):
    for _ in range(times):
        d.shell("input swipe 375 1100 375 350 250")
        time.sleep(0.8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("Tab bar")
    password = relaye2e.mm_password()
    alice, bob, carol = Rest("alice", password), Rest("bob", password), Rest("carol", password)
    probes = []
    t0 = int(time.time())

    def post(client, channel_id, text, root_id=""):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text)}
        if root_id:
            body["root_id"] = root_id
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng, design = teams["engineering"], teams["design"]
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == eng["id"])
        for client in (bob, carol):
            for c in (sandbox, town):
                try:
                    client.call("POST", "/channels/%s/members" % c["id"], {"user_id": client.me["id"]})
                except urllib.error.HTTPError:
                    pass
        set_crt(alice, True)

        # A thread alice follows (she replied), read.
        root = post(bob, sandbox["id"], "tabs thread root %d" % t0)
        post(alice, sandbox["id"], "alice joins the thread %d" % t0, root["id"])
        time.sleep(1)
        mark_thread_read(alice, eng["id"], root["id"])
        alice.call("POST", "/channels/members/me/view", {"channel_id": sandbox["id"]})

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
            time.sleep(6)

        # MARK: 1. Tab bar.
        tap_tab(d, "Chats")
        h = screen_height(d)
        nodes = d.dump()
        labels, _ = bar(d, nodes)
        check.ok("tab bar: Chats · Threads · Mentions · Settings · Profile", labels == ["Chats", "Threads", "Mentions", "Settings", "Profile"], str(labels))
        header_threads = [n for n in nodes if (n.desc.startswith("Threads") or n.text == "Threads") and n.bounds[3] < h * 0.8]
        check.ok("chat list header has no Threads button", not header_threads, str(header_threads))
        threads_tab = next((n for n in nodes if n.desc.startswith("Threads") and n.bounds[1] > h * 0.8), None)
        check.ok("Threads tab reads its badge (\"Threads, N unread\" or \"Threads\")", threads_tab is not None, repr(threads_tab))
        shot(d, "chats")

        tap_tab(d, "Settings")
        texts = d.screen_texts()
        check.ok("Settings: Saved Posts row, no Recent Mentions row", "Saved Posts" in texts and "Recent Mentions" not in texts, str(texts[:14]))
        shot(d, "settings")

        # The old Contacts tab listed the user directory: New Message still does (and opens a DM from it).
        tap_tab(d, "Chats")
        fab = next((n for n in d.dump() if n.desc == "New Message"), None)
        check.ok("New Message button on the chat list", fab is not None)
        if fab:
            d.tap_node(fab)
            time.sleep(3)
            texts = d.screen_texts()
            check.ok("New Message lists people (the old Contacts tab's directory)",
                     any("Bob Brennan" in t for t in texts) and any("Carol" in t for t in texts), str(texts[:12]))
            d.key("KEYCODE_BACK")
            time.sleep(1.5)

        # MARK: 2. Threads tab follows the folder's team; rows = the server's followed threads.
        tap_tab(d, "Chats")
        tap_folder(d, "Design")
        tap_tab(d, "Threads", wait=3)
        st = tabs_state(d)
        check.ok("Threads tab is a main tab page", st.get("top") == "MainTabsActivity" and st.get("page") == "RelayThreadsActivity", str(st)[:200])
        check.ok("Design folder → Threads on Design", (st.get("threads_team") or ["", ""])[1] == "Design", str(st.get("threads_team")))
        tap_tab(d, "Chats")
        tap_folder(d, "Engineering")
        tap_tab(d, "Threads", wait=3)
        st = tabs_state(d)
        check.ok("Engineering folder → Threads on Engineering", (st.get("threads_team") or ["", ""])[1] == "Engineering", str(st.get("threads_team")))
        tap_tab(d, "Chats")
        tap_folder(d, "All Chats")
        tap_tab(d, "Threads", wait=3)
        st = wait_until(lambda: (lambda s: s if root["id"] in thread_rows(s) else None)(tabs_state(d)), timeout=15) or tabs_state(d)
        check.ok("\"All\" keeps the team (Engineering)", (st.get("threads_team") or ["", ""])[1] == "Engineering", str(st.get("threads_team")))
        rest = set(followed_listed(alice, eng["id"]))
        app = set(thread_rows(st))
        check.ok("Threads rows = the server's followed threads of the team", app == rest and root["id"] in app,
                 "missing=%s extra=%s" % (sorted(rest - app)[:5], sorted(app - rest)[:5]))
        team_tab = d.find(desc="Design")
        check.ok("team tabs in the Threads header", team_tab is not None and d.find(desc="Engineering") is not None)
        shot(d, "threads")

        # MARK: 3. Live badge, row → thread, back.
        st = tabs_state(d)
        b0 = badge_count(st)
        post(bob, sandbox["id"], "bob replies %d" % t0, root["id"])
        st = wait_until(lambda: (lambda s: s if badge_count(s) == b0 + 1 else None)(tabs_state(d)), timeout=20)
        check.ok("bob's reply raises the Threads badge live (+1)", st is not None, "before %d after %s" % (b0, tabs_state(d).get("badge")))
        st = st or tabs_state(d)
        check.ok("the tab's description carries the badge", st.get("badge", "-") in (st.get("threads_desc") or ""), str(st.get("threads_desc")))
        st = wait_until(lambda: (lambda s: s if thread_rows(s).get(root["id"], (0, 0))[0] >= 1 else None)(tabs_state(d)), timeout=10) or st
        check.ok("the row shows the new reply live", thread_rows(st).get(root["id"], (0, 0))[0] >= 1, str(thread_rows(st).get(root["id"])))
        post(bob, sandbox["id"], "@%s please look %d" % (alice.me["username"], t0), root["id"])
        st = wait_until(lambda: (lambda s: s if (s.get("badge") or "").startswith("@") else None)(tabs_state(d)), timeout=20)
        check.ok("an @alice reply puts \"@\" in front of the badge", st is not None, str(tabs_state(d).get("badge")))
        desc = next((n.desc for n in d.dump() if n.desc.startswith("Threads,")), "")
        check.ok("Threads tab reads \"Threads, @N unread\"", re.match(r"Threads, @\d+ unread", desc) is not None, desc)
        shot(d, "threads-badge")

        row = wait_until(lambda: d.find(text="tabs thread root %d" % t0, contains=True), timeout=10)
        check.ok("thread row on screen", row is not None)
        if row:
            d.tap_node(row)
            time.sleep(3.5)
            ui = ui_state(d)
            check.ok("tapping the row opens the thread", ui["fragment"] == "ChatActivity" and ui["thread"] != 0 and has_text(ui, "bob replies %d" % t0),
                     "%s thread=%s" % (ui["fragment"], ui["thread"]))
            read = wait_until(lambda: followed(alice, eng["id"]).get(root["id"], {}).get("unread_replies") == 0, timeout=12)
            check.ok("the opened thread is read on the server", read)
            d.key("KEYCODE_BACK")
            time.sleep(2)
            st = tabs_state(d)
            check.ok("back returns to the Threads tab", st.get("top") == "MainTabsActivity" and st.get("page") == "RelayThreadsActivity", str(st)[:120])
            st = wait_until(lambda: (lambda s: s if badge_count(s) == b0 else None)(tabs_state(d)), timeout=15)
            check.ok("the badge goes back once the thread is read", st is not None, "want %d got %s" % (b0, tabs_state(d).get("badge")))

        # MARK: 4. Mentions tab, live.
        tap_tab(d, "Mentions", wait=4)
        st = wait_until(lambda: (lambda s: s if mention_rows(s) else None)(tabs_state(d)), timeout=15) or tabs_state(d)
        check.ok("Mentions tab is a main tab page with alice's mentions", st.get("page") == "RelayPostListActivity" and mention_rows(st), str(st)[:200])
        mention = post(carol, town["id"], "@%s tabs mention %d" % (alice.me["username"], t0))
        st = wait_until(lambda: (lambda s: s if mention["id"] in mention_rows(s) else None)(tabs_state(d)), timeout=20)
        check.ok("a new @alice post shows in the Mentions tab live", st is not None, str(mention_rows(tabs_state(d))[:4]))
        check.ok("the new mention is first", st is not None and mention_rows(st)[0] == mention["id"], str(mention_rows(st)[:3]) if st else "")
        time.sleep(8)  # the mention's heads-up notification goes first
        shot(d, "mentions")
        # The rows are drawn cells (no text nodes): tap the first one, under the header (the new mention is first).
        title = d.find(text="Recent Mentions")
        node = title if st is not None and title is not None else None
        if node:
            m = re.findall(r"(\d+)", d.shell("wm density"))
            density = int(m[-1]) / 160.0 if m else 2.0
            for _ in range(2):  # a live reload of the list can swallow a tap
                d.tap(title.center[0], title.bounds[3] + int(52 * density))
                time.sleep(3.5)
                ui = ui_state(d)
                if ui["fragment"] == "ChatActivity":
                    break
            check.ok("tapping a mention opens the chat at the post", ui["fragment"] == "ChatActivity" and has_text(ui, "tabs mention %d" % t0),
                     "%s %s" % (ui["fragment"], ui["title"]))
            d.key("KEYCODE_BACK")
            time.sleep(2)
            st = tabs_state(d)
            check.ok("back returns to the Mentions tab", st.get("page") == "RelayPostListActivity", str(st)[:120])
        else:
            check.ok("mention row on screen", False)

        # MARK: 5. Re-tap scrolls to the top; back → Chats.
        scroll_list(d, 3)
        before = first_row(tabs_state(d))
        tap_tab(d, "Mentions", wait=2.5)
        after = first_row(tabs_state(d))
        check.ok("re-tapping Mentions scrolls to the top", before > 0 and after == 0, "first row %d → %d" % (before, after))
        tap_tab(d, "Threads", wait=3)
        scroll_list(d, 2)
        before = first_row(tabs_state(d))
        tap_tab(d, "Threads", wait=2.5)
        after = first_row(tabs_state(d))
        check.ok("re-tapping Threads keeps / brings the list at the top", after == 0, "first row %d → %d" % (before, after))
        d.key("KEYCODE_BACK")
        time.sleep(2)
        st = tabs_state(d)
        check.ok("back from the Threads tab goes to Chats", st.get("top") == "MainTabsActivity" and st.get("page") == "DialogsActivity", str(st)[:120])
        tap_tab(d, "Mentions", wait=2)
        d.key("KEYCODE_BACK")
        time.sleep(2)
        check.ok("back from the Mentions tab goes to Chats", tabs_state(d).get("page") == "DialogsActivity")

        # MARK: 6. Collapsed threads off.
        tap_tab(d, "Threads", wait=2)
        d.logcat_clear()
        set_crt(alice, False)
        d.wait_log(r"collapsed reply threads off", timeout=15, tags=["RelayBridge"])
        st = wait_until(lambda: (lambda s: s if "collapsed reply threads are off" in str(s.get("empty")) else None)(tabs_state(d)), timeout=15)
        check.ok("CRT off: the Threads tab says so", st is not None, str(tabs_state(d).get("empty")))
        labels, _ = bar(d)
        check.ok("CRT off: the Threads tab stays", labels == ["Chats", "Threads", "Mentions", "Settings", "Profile"], str(labels))
        check.ok("CRT off: no Threads badge", (st or {}).get("badge") == "-", str((st or {}).get("badge")))
        shot(d, "threads-crt-off")
        set_crt(alice, True)
        st = wait_until(lambda: (lambda s: s if root["id"] in thread_rows(s) else None)(tabs_state(d)), timeout=25)
        check.ok("CRT back on: the rows return", st is not None, str(tabs_state(d).get("empty")))

        # MARK: 7. Dark theme.
        d.broadcast("org.relaychat.DEBUG_TABS", op="theme", dark="1")
        time.sleep(4)
        tap_tab(d, "Chats", wait=2)
        shot(d, "chats-dark")
        tap_tab(d, "Threads", wait=3)
        shot(d, "threads-dark")
        tap_tab(d, "Mentions", wait=3)
        shot(d, "mentions-dark")
        labels, _ = bar(d)
        check.ok("dark theme: tab bar intact", labels == ["Chats", "Threads", "Mentions", "Settings", "Profile"], str(labels))
        d.broadcast("org.relaychat.DEBUG_TABS", op="theme", dark="0")
        time.sleep(4)
        tap_tab(d, "Chats", wait=2)

        # MARK: 8. Profile long press, second account.
        _, tabs = bar(d)
        profile = next((n for n in tabs if n.text == "Profile"), None)
        if profile:
            x, y = profile.center
            d.shell("input swipe %d %d %d %d 900" % (x, y, x, y))
            time.sleep(2)
        texts = d.screen_texts()
        check.ok("Profile long press opens the account selector", "Add Account" in texts and any("Alice Anderson" in t for t in texts), str(texts[:8]))
        shot(d, "account-selector")
        if "Add Account" in texts:
            d.tap_node(d.find(text="Add Account"))
            field = d.wait_for(rid="relay_server_field", timeout=20)
            check.ok("Add Account opens the login screen", field is not None)
            d.logcat_clear()
            d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="bob", password=password)
            ok = d.wait_log(r"DEBUG_LOGIN (ok|failed)[^\n]*", timeout=40, tags=["RelayDebug"])
            check.ok("bob logged in as a second account", ok is not None and ok.group(1) == "ok", ok.group(0) if ok else "no result")
            d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
            time.sleep(6)
            bob_teams = [t["id"] for t in bob.get("/users/me/teams")]
            want = expected_badge(bob, bob_teams)
            st = wait_until(lambda: (lambda s: s if s.get("badge") == want else None)(tabs_state(d)), timeout=20) or tabs_state(d)
            check.ok("bob's account: the Threads badge is bob's", st.get("badge") == want and want in (st.get("threads_desc") or "") + "-",
                     "app %s / %s, server %s" % (st.get("badge"), st.get("threads_desc"), want))
            tap_tab(d, "Chats")
            tap_folder(d, "Engineering")
            tap_tab(d, "Threads", wait=4)
            st = wait_until(lambda: (lambda s: s if thread_rows(s) else None)(tabs_state(d)), timeout=15) or tabs_state(d)
            rest = set(followed_listed(bob, eng["id"]))
            check.ok("bob's account: Threads rows = bob's followed threads", set(thread_rows(st)) == rest,
                     "missing=%s extra=%s" % (sorted(rest - set(thread_rows(st)))[:4], sorted(set(thread_rows(st)) - rest)[:4]))
            # Back to alice.
            tap_tab(d, "Chats")
            _, tabs = bar(d)
            profile = next((n for n in tabs if n.text == "Profile"), None)
            if profile:
                x, y = profile.center
                d.shell("input swipe %d %d %d %d 900" % (x, y, x, y))
                time.sleep(2)
            node = d.find(text="Alice Anderson")
            if node:
                d.tap_node(node)
                time.sleep(6)
            want = expected_badge(alice, [eng["id"], design["id"]])
            st = wait_until(lambda: (lambda s: s if s.get("badge") == want else None)(tabs_state(d)), timeout=20) or tabs_state(d)
            check.ok("switched back to alice: her Threads badge", st.get("badge") == want, "app %s, server %s" % (st.get("badge"), want))
            tap_tab(d, "Chats")
            tap_folder(d, "Engineering")  # a new tab page starts on the first team (Design)
            tap_tab(d, "Threads", wait=4)
            st = wait_until(lambda: (lambda s: s if root["id"] in thread_rows(s) else None)(tabs_state(d)), timeout=15)
            check.ok("switched back to alice: her Threads rows", st is not None, str(tabs_state(d).get("threads_team")))
            tap_tab(d, "Chats")

        crashes = d.crashes()
        check.ok("no app crash", not crashes, "\n".join(crashes[-5:]))
    finally:
        set_crt(alice, True)
        try:
            d.broadcast("org.relaychat.DEBUG_TABS", op="theme", dark="0")
        except Exception:
            pass
        for client, pid in reversed(probes):
            try:
                client.call("DELETE", "/posts/" + pid)
            except urllib.error.HTTPError:
                pass
        print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
