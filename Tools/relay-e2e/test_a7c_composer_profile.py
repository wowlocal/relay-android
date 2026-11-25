#!/usr/bin/env python3
"""Relay for Android, phase A7c e2e test: reactions, composer, profile and status, notifications.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065, ffmpeg on the host):

    RELAY_SERIAL=emulator-5558 RELAY_APK=… python3 Tools/relay-e2e/test_a7c_composer_profile.py [--no-install] [--quick]

dave uses the app; bob acts over REST.

  1. Reactions: the message menu's reaction bar (plain emoji + the server's custom emoji) → REST; a custom emoji
     reaction from bob renders as an emoji document; a custom emoji picked in the bar → REST; the full picker opens.
  2. Composer (a channel with more than 5 members): @user, ~channel, :emoji: (custom) and @channel autocomplete rows
     appear and insert; the @channel confirmation; the post on the server; /echo in the slash suggestions; /away runs
     (status away) and its ephemeral answer shows; /online; an unknown command asks to post it as text.
  3. Profile: Edit Info (nickname, position) → PUT /users/me/patch; avatar upload through Telegram's uploader →
     the server's picture changes; Settings status menu (Do Not Disturb for 1 hour, then Online) → REST; custom status
     screen → REST and shown in the title; bob's custom status reaches dave's client as an emoji status.
  4. Notifications: a DM from bob while the app is in the background → Android notification; reply from the
     notification → a post on the server; a muted DM (muted on the server) does not notify; "Keep connected in
     background" runs the foreground service and a post 2 minutes after backgrounding still notifies (--quick: 20 s).

Everything changed is restored (profile fields, picture, status, custom statuses, notify props, recent emoji); probe
posts start with "[relay-e2e a7c]" and are deleted. Screens: docs/relay/screens/a7c-*.png.
"""

import argparse
import hashlib
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, REST_URL, dump_dialogs, dump_messages, find_msg, open_chat, to_chat_list, wait_until  # noqa: E402
from test_a5_write import composer, tap_send  # noqa: E402

PROBE = "[relay-e2e a7c]"
DEVICE_DIR = "/sdcard/Android/data/%s/files/relay-e2e" % relaye2e.PACKAGE


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "a7c-%s.png" % name))


def posts(client, channel_id, n=30):
    res = client.get("/channels/%s/posts?per_page=%d" % (channel_id, n))
    return [res["posts"][pid] for pid in res["order"]]


def image_md5(client, user_id):
    req = urllib.request.Request(relaye2e.MM_URL + "/api/v4/users/%s/image?_=%d" % (user_id, int(time.time() * 1000)),
                                 headers={"Authorization": "Bearer " + client.token})
    with urllib.request.urlopen(req) as res:
        return hashlib.md5(res.read()).hexdigest()


def nodes_with(d, text=None, desc=None, contains=True, nodes=None):
    nodes = nodes if nodes is not None else d.dump()
    out = []
    for n in nodes:
        if text is not None and ((contains and text in n.text) or n.text == text):
            out.append(n)
        elif desc is not None and ((contains and desc in n.desc) or n.desc == desc):
            out.append(n)
    return out


def type_and_wait(d, text, want, timeout=8):
    d.type_text(text)
    return wait_until(lambda: nodes_with(d, text=want), timeout=timeout)


def tap_message(d, text):
    n = wait_until(lambda: nodes_with(d, text=text), timeout=10)
    if not n:
        return False
    # The first text line of the bubble: the middle may be a reaction button.
    x1, y1, x2, y2 = n[-1].bounds
    d.tap(250, min(y1 + 60, (y1 + y2) // 2))
    time.sleep(2.5)
    return True


def notification_text(d):
    return d.shell("dumpsys notification --noredact", timeout=60, check=False)


def set_status(client, status, dnd_end=0):
    body = {"user_id": client.me["id"], "status": status}
    if status == "dnd" and dnd_end:
        body["dnd_end_time"] = dnd_end
    client.call("PUT", "/users/me/status", body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    ap.add_argument("--quick", action="store_true", help="background wait of 20 s instead of 2 min")
    args = ap.parse_args()

    check = relaye2e.Check("A7c composer/profile")
    d = relaye2e.Device()
    password = relaye2e.mm_password()
    # The app's user (default dave; RELAY_A7C_USER=alice when dave is busy on another device).
    app_user = os.environ.get("RELAY_A7C_USER", "dave")
    dave = Rest(app_user, password)
    bob = Rest("bob", password)
    t0 = int(time.time())

    # Originals, restored at the end.
    me0 = dave.get("/users/me")
    avatar0 = None
    if me0.get("last_picture_update", 0) > 0:
        # A custom profile picture: kept and uploaded again at the end (the test sets and deletes one).
        req = urllib.request.Request(REST_URL + "/api/v4/users/%s/image" % dave.me["id"], headers={"Authorization": "Bearer " + dave.token})
        with urllib.request.urlopen(req) as res:
            avatar0 = (res.read(), res.headers.get("Content-Type", "image/png"))
    status0 = dave.get("/users/me/status")
    prefs0 = [p for p in dave.get("/users/me/preferences") if p["category"] == "recent_emojis"]
    bob0 = bob.get("/users/me")
    dm = dave.call("POST", "/channels/direct", [dave.me["id"], bob.me["id"]])
    dm_props0 = dave.get("/channels/%s/members/me" % dm["id"])["notify_props"]
    teams = dave.get("/users/me/teams")
    channel = None
    for team in teams:
        for c in dave.get("/users/me/teams/%s/channels" % team["id"]):
            if c["type"] == "O" and c["name"] == "off-topic" and dave.get("/channels/%s/stats" % c["id"])["member_count"] > 5:
                channel = c
                break
        if channel:
            break
    if channel is None:
        print("no channel with more than 5 members for the composer test")
        sys.exit(2)
    channel_props0 = dave.get("/channels/%s/members/me" % channel["id"])["notify_props"]
    bob_posts = []

    try:
        if not args.no_install:
            d.install(os.environ.get("RELAY_APK"))
        if not args.no_login:
            d.clear_data()
            d.shell("pm grant %s android.permission.POST_NOTIFICATIONS" % d.package, check=False)
            d.reverse()
            d.launch()
            time.sleep(6)
            d.logcat_clear()
            d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user=app_user, password=password)
            check.ok("login as dave", d.wait_log(r"DEBUG_LOGIN ok", timeout=40, tags=["RelayDebug"]))
            time.sleep(8)
        set_status(dave, "online")

        # MARK: 1. Reactions
        p = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "%s react %d" % (PROBE, t0)})
        bob_posts.append(p["id"])
        time.sleep(2)
        open_chat(d, dm["id"], 4)
        check.ok("reactions: menu opens on the message", tap_message(d, "react %d" % t0))
        nodes = d.dump()
        bar = [n for n in nodes if n.cls.endswith("FrameLayout") and n.text in ("👍", "❤", "😂", "😮", "Custom emoji")]
        check.ok("reactions: the menu has a reaction bar with plain emoji", any(n.text == "👍" for n in bar), str([n.text for n in bar][:8]))
        check.ok("reactions: the bar offers the server's custom emoji", any(n.text == "Custom emoji" for n in bar))
        check.ok("reactions: expand button for the full picker", bool(nodes_with(d, desc="Expand panel", nodes=nodes)))
        shot(d, "reaction-bar")
        n = nodes_with(d, desc="Expand panel", nodes=nodes)
        if n:
            d.tap(*n[0].center)
            time.sleep(2.5)
        # The picker is a window of its own (not in uiautomator's dump of the chat).
        panels = [l for l in d.shell("dumpsys window windows", timeout=60, check=False).splitlines()
                  if "Window #" in l and ("Panel:" + d.package) in l]
        check.ok("reactions: the full picker opens (Unicode + custom emoji grid with search)", panels)
        shot(d, "reaction-picker")
        d.key("KEYCODE_BACK")
        time.sleep(1)
        if not composer(d):
            d.key("KEYCODE_BACK")
            time.sleep(1)
        if not composer(d):
            open_chat(d, dm["id"], 4)
        check.ok("reactions: menu opens again", tap_message(d, "react %d" % t0))
        thumbs = [n for n in d.dump() if n.text == "👍" and n.cls.endswith("FrameLayout")]
        if thumbs:
            d.tap(*thumbs[0].center)
        got = wait_until(lambda: [r for r in (dave.get("/posts/%s" % p["id"]).get("metadata") or {}).get("reactions", [])
                                  if r["user_id"] == dave.me["id"] and r["emoji_name"] in ("+1", "thumbsup")], timeout=10)
        check.ok("reactions: 👍 from the bar is +1 on the server", got)
        msgs, _ = dump_messages(d, dm["id"])
        mine = find_msg(msgs, "react %d" % t0)
        check.ok("reactions: the client shows it as the user's own", mine and "👍=1*" in mine[0][1]["reactions"], mine[0][1]["reactions"] if mine else "")

        custom = [e["name"] for e in dave.get("/emoji?per_page=50")]
        check.ok("reactions: the server has custom emoji", custom, str(custom))
        if custom:
            bob.call("POST", "/reactions", {"user_id": bob.me["id"], "post_id": p["id"], "emoji_name": custom[-1]})
            ok = wait_until(lambda: "doc:" in (find_msg(dump_messages(d, dm["id"])[0], "react %d" % t0) or [(0, {"reactions": ""})])[0][1]["reactions"], timeout=12)
            check.ok("reactions: bob's custom emoji reaction is a custom-emoji document in the client", ok)
            time.sleep(2)
            shot(d, "custom-reaction")
            # Pick a custom emoji in the bar.
            if tap_message(d, "react %d" % t0):
                customs = [n for n in d.dump() if n.cls.endswith("FrameLayout") and n.text == "Custom emoji"]
                if customs:
                    d.tap(*customs[0].center)
                got = wait_until(lambda: [r for r in (dave.get("/posts/%s" % p["id"]).get("metadata") or {}).get("reactions", [])
                                          if r["user_id"] == dave.me["id"] and r["emoji_name"] in custom], timeout=10)
                check.ok("reactions: a custom emoji from the bar is a reaction with its name on the server", got, str(got and got[0]["emoji_name"]))

        # MARK: 2. Composer
        to_chat_list(d)
        open_chat(d, channel["id"], 4)
        field = wait_until(lambda: composer(d), timeout=15)
        d.tap_node(field)
        d.type_text("%s composer %d " % (PROBE, t0))
        row = type_and_wait(d, "@bo", "@bob")
        check.ok("composer: @ autocomplete lists channel members", row)
        shot(d, "mention")
        if row:
            d.tap(*row[0].center)
            time.sleep(1)
        check.ok("composer: the member is inserted", "@bob " in (composer(d).text or ""))
        row = type_and_wait(d, "~off", "~off-topic")
        check.ok("composer: ~ autocomplete lists channels", row)
        if row:
            d.tap(*row[0].center)
            time.sleep(1)
        check.ok("composer: the channel is inserted", "~off-topic " in (composer(d).text or ""))
        name = custom[0] if custom else "partyblob"
        row = type_and_wait(d, ":" + name[:5], ":  " + name)
        check.ok("composer: : autocomplete lists custom emoji", row)
        shot(d, "emoji-autocomplete")
        if row:
            d.tap(*row[0].center)
            time.sleep(1)
        row = type_and_wait(d, " @chan", "@channel")
        check.ok("composer: @channel is suggested", row)
        if row:
            d.tap(*row[0].center)
            time.sleep(1)
        tap_send(d)
        dialog = wait_until(lambda: nodes_with(d, text="Confirm sending notifications to entire channel"), timeout=10)
        check.ok("composer: @channel in a channel of more than 5 asks first", dialog)
        body = " | ".join(n.text for n in d.dump() if n.text)
        check.ok("composer: the confirmation names the people", re.search(r"notifications to \d+ people", body), body[:200])
        shot(d, "channel-confirm")
        n = [x for x in d.dump() if x.text == "Send"]
        if n:
            d.tap(*n[-1].center)
        post = wait_until(lambda: [x for x in posts(dave, channel["id"], 10) if "composer %d" % t0 in x["message"]], timeout=10)
        text = post[0]["message"] if post else ""
        check.ok("composer: posted with @mention, ~channel, :custom: and @channel", all(s in text for s in ("@bob", "~off-topic", ":%s:" % name, "@channel")), text)

        field = composer(d)
        d.tap_node(field)
        row = type_and_wait(d, "/ec", "/echo")
        check.ok("composer: slash command suggestions (/echo)", row)
        shot(d, "slash")
        d.clear_field(composer(d), 10)
        d.type_text("/away")
        time.sleep(0.5)
        tap_send(d)
        st = wait_until(lambda: dave.get("/users/me/status")["status"] == "away", timeout=10)
        check.ok("composer: /away runs on the server (status away)", st)
        eph = wait_until(lambda: nodes_with(d, text="You are now away"), timeout=10)
        check.ok("composer: the ephemeral answer shows in the chat", eph)
        check.ok("composer: … marked as only visible to you", nodes_with(d, text="(Only visible to you)"))
        shot(d, "ephemeral")
        check.ok("composer: the command was not posted", not [x for x in posts(dave, channel["id"], 5) if x["message"].startswith("/away")])
        field = composer(d)
        d.tap_node(field)
        d.type_text("/online")
        time.sleep(0.5)
        tap_send(d)
        check.ok("composer: /online", wait_until(lambda: dave.get("/users/me/status")["status"] == "online", timeout=10))
        d.tap_node(composer(d))
        d.type_text("/nosuchcmd%d" % t0)
        time.sleep(0.5)
        tap_send(d)
        unknown = wait_until(lambda: nodes_with(d, text="Unknown command"), timeout=10)
        check.ok("composer: an unknown command asks to send it as a message", unknown)
        n = [x for x in d.dump() if x.text == "Cancel"]
        if n:
            d.tap(*n[-1].center)
            time.sleep(1)
        check.ok("composer: cancelled, the text is back in the field", "/nosuchcmd" in (composer(d).text or ""))
        d.clear_field(composer(d), 40)
        d.hide_keyboard()

        # MARK: 3. Profile and status
        to_chat_list(d)
        d.tap_node(d.wait_for(text="Profile", timeout=10))
        time.sleep(2)
        d.tap_node(d.wait_for(text="Edit Info", timeout=10))
        time.sleep(3)
        nick = "Dee%d" % (t0 % 1000)
        n = wait_until(lambda: d.find(text="Nickname") or d.find(text=me0.get("nickname") or "Nickname"), timeout=10)
        if me0.get("nickname"):
            d.clear_field(n, 40)
        else:
            d.tap_node(n)
        d.type_text(nick)
        n = d.find(text=me0.get("position") or "Position")
        if me0.get("position"):
            d.clear_field(n, 60)
        else:
            d.tap_node(n)
        d.type_text("A7c position %d" % t0)
        d.hide_keyboard()
        time.sleep(1)
        shot(d, "edit-profile")
        save = d.find(desc="Save")
        if save:
            d.tap_node(save)
        me = wait_until(lambda: (lambda u: u if u.get("nickname") == nick else None)(dave.get("/users/me")), timeout=10)
        check.ok("profile: nickname saved (PUT /users/me/patch)", me)
        check.ok("profile: the bio field is the position", me and me.get("position") == "A7c position %d" % t0)
        check.ok("profile: the profile shows position · nickname", wait_until(lambda: nodes_with(d, text="A7c position %d · %s" % (t0, nick)), timeout=8))

        md5_before = image_md5(dave, dave.me["id"])
        os.system("ffmpeg -loglevel error -y -f lavfi -i testsrc=size=320x320 -frames:v 1 /tmp/relay-e2e-a7c-avatar.jpg")
        d.shell("mkdir -p " + DEVICE_DIR)
        d.adb("push", "/tmp/relay-e2e-a7c-avatar.jpg", DEVICE_DIR + "/avatar.jpg")
        d.logcat_clear()
        d.broadcast("org.relaychat.DEBUG_SET_AVATAR", path=DEVICE_DIR + "/avatar.jpg")
        check.ok("avatar: uploaded through Telegram's uploader", d.wait_log(r"DEBUG_SET_AVATAR ok", timeout=30, tags=["RelayDebug"]))
        lpu = dave.get("/users/me").get("last_picture_update", 0)
        check.ok("avatar: the server's picture changed", lpu > me0.get("last_picture_update", 0) and image_md5(dave, dave.me["id"]) != md5_before)
        time.sleep(2)
        shot(d, "avatar")

        d.tap_node(d.wait_for(text="Settings", timeout=10))
        time.sleep(2)
        st = wait_until(lambda: d.find(text="Status"), timeout=10)
        d.tap_node(st)
        time.sleep(2)
        menu = d.dump()
        check.ok("status: quick menu lists the four statuses", all(nodes_with(d, text=t, contains=False, nodes=menu) for t in ("Online", "Away", "Do Not Disturb", "Offline")))
        shot(d, "status-menu")
        d.tap_node(d.find(text="Do Not Disturb", nodes=menu))
        time.sleep(2)
        shot(d, "status-dnd")
        d.tap_node(d.wait_for(text="For 1 Hour", timeout=5))
        s = wait_until(lambda: (lambda x: x if x["status"] == "dnd" else None)(dave.get("/users/me/status")), timeout=10)
        check.ok("status: Do Not Disturb for 1 hour (REST)", s and abs(s["dnd_end_time"] - (time.time() + 3600)) < 600, str(s))
        check.ok("status: the row shows Do Not Disturb", wait_until(lambda: d.find(text="Do Not Disturb"), timeout=5))
        d.tap_node(d.find(text="Status"))
        time.sleep(2)
        d.tap_node(d.wait_for(text="Online", timeout=5))
        check.ok("status: back to online (REST)", wait_until(lambda: dave.get("/users/me/status")["status"] == "online", timeout=10))

        d.tap_node(d.wait_for(text="Custom Status", timeout=5))
        time.sleep(2.5)
        d.tap_node(d.wait_for(text="Emoji", timeout=5))
        time.sleep(1.5)
        d.tap_node(d.wait_for(desc="rocket", timeout=5))
        time.sleep(1)
        field = [n for n in d.dump() if n.cls.endswith("EditText")]
        if field:
            d.clear_field(field[0], 40)
        d.type_text("A7c launch %d" % t0)
        d.hide_keyboard()
        d.tap_node(d.wait_for(text="30 Minutes", timeout=5))
        time.sleep(1)
        shot(d, "custom-status")
        d.tap_node(d.wait_for(text="Set a Status", timeout=5))
        cs = wait_until(lambda: (lambda u: u if "A7c launch %d" % t0 in (u.get("props") or {}).get("customStatus", "") else None)(dave.get("/users/me")), timeout=10)
        check.ok("custom status: saved (REST)", cs)
        check.ok("custom status: emoji, duration", cs and '"emoji":"rocket"' in cs["props"]["customStatus"] and "thirty_minutes" in cs["props"]["customStatus"])
        check.ok("custom status: the Settings row shows it", wait_until(lambda: nodes_with(d, text="A7c launch %d" % t0), timeout=8))

        bob.call("PUT", "/users/me/status/custom", {"emoji": "palm_tree", "text": "A7c away %d" % t0})
        def bob_status():
            d.logcat_clear()
            d.broadcast("org.relaychat.DEBUG_USER_STATUS", username="bob")
            m = d.wait_log(r"DEBUG_USER_STATUS bob .*", timeout=10, tags=["RelayDebug"])
            return m and "emoji=palm_tree" in m.group(0)
        check.ok("custom status: bob's status reaches dave's client as an emoji status (live)", wait_until(bob_status, timeout=20, step=2))
        to_chat_list(d)
        d.tap_node(d.wait_for(text="Chats", timeout=10))
        time.sleep(3)
        shot(d, "chat-list-status")
        open_chat(d, dm["id"], 4)
        shot(d, "dm-header-status")

        # MARK: 4. Notifications
        # Telegram's per-chat mute ↔ the channel's mark_unread.
        d.tap_node(d.wait_for(desc="More options", timeout=10))
        time.sleep(1.5)
        d.tap_node(d.wait_for(text="Mute", timeout=5))
        time.sleep(1.5)
        d.tap_node(d.wait_for(text="Mute forever", timeout=5))
        check.ok("notification settings: Mute forever → mark_unread=mention",
                 wait_until(lambda: dave.get("/channels/%s/members/me" % dm["id"])["notify_props"].get("mark_unread") == "mention", timeout=10))
        d.tap_node(d.wait_for(desc="More options", timeout=10))
        time.sleep(1.5)
        d.tap_node(d.wait_for(text="Unmute", timeout=5))
        check.ok("notification settings: Unmute → mark_unread=all",
                 wait_until(lambda: dave.get("/channels/%s/members/me" % dm["id"])["notify_props"].get("mark_unread") == "all", timeout=10))
        to_chat_list(d)
        d.key("KEYCODE_HOME")
        time.sleep(3)
        p = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "%s notify %d" % (PROBE, t0)})
        bob_posts.append(p["id"])
        check.ok("notifications: a DM while in the background shows a notification", wait_until(lambda: "notify %d" % t0 in notification_text(d), timeout=15))
        d.shell("cmd statusbar expand-notifications")
        time.sleep(2)
        shot(d, "notification")
        reply = None
        for _ in range(3):
            nodes = d.dump()
            reply = [n for n in nodes if n.desc.startswith("Reply to Bob")]
            if reply:
                break
            exp = [n for n in nodes if n.desc == "Expand"]
            if exp:
                d.tap(*exp[0].center)
                time.sleep(1.5)
        check.ok("notifications: Reply action", reply)
        if reply:
            d.tap(*reply[0].center)
            time.sleep(1.5)
            d.type_text("%s reply %d" % (PROBE, t0))
            send = [n for n in d.dump() if n.desc == "Send" and n.cls.endswith("ImageButton")]
            if send:
                d.tap(*send[0].center)
            got = wait_until(lambda: [x for x in posts(bob, dm["id"], 10) if x["message"] == "%s reply %d" % (PROBE, t0) and x["user_id"] == dave.me["id"]], timeout=15)
            check.ok("notifications: reply from the notification is posted", got)
        d.shell("cmd statusbar collapse")
        time.sleep(1)

        dave.call("PUT", "/channels/%s/members/%s/notify_props" % (dm["id"], dave.me["id"]), {"mark_unread": "mention"})
        time.sleep(4)
        d.logcat_clear()
        p = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "%s muted %d" % (PROBE, t0)})
        bob_posts.append(p["id"])
        time.sleep(6)
        check.ok("notifications: a muted DM does not notify", "muted %d" % t0 not in notification_text(d))
        dave.call("PUT", "/channels/%s/members/%s/notify_props" % (dm["id"], dave.me["id"]), {"mark_unread": dm_props0.get("mark_unread", "all")})
        time.sleep(3)

        # Keep connected in background.
        d.launch()
        time.sleep(3)
        to_chat_list(d)
        d.tap_node(d.wait_for(text="Settings", timeout=10))
        time.sleep(2)
        d.tap_node(d.wait_for(text="Notifications", timeout=10))
        time.sleep(2)
        row = None
        for _ in range(8):
            row = d.find(text="Keep connected in background")
            if row:
                break
            sizes = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
            sw, sh = (int(sizes[-1][0]), int(sizes[-1][1])) if sizes else (1080, 2400)
            d.shell("input swipe %d %d %d %d 300" % (sw // 2, int(sh * 0.75), sw // 2, int(sh * 0.3)))  # any screen size
            time.sleep(1)
        check.ok("keep connected: setting in Notifications", row)
        if row:
            d.tap_node(row)
            time.sleep(3)
        shot(d, "keep-connected")
        services = d.shell("dumpsys activity services %s" % d.package, timeout=60, check=False)
        check.ok("keep connected: foreground service running", "RelayKeepAlive" in services and "isForeground=true" in services)
        d.key("KEYCODE_HOME")
        wait = 20 if args.quick else 125
        print("waiting %d s in the background…" % wait, flush=True)
        time.sleep(wait)
        p = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "%s late %d" % (PROBE, t0)})
        bob_posts.append(p["id"])
        check.ok("keep connected: a post %d s after backgrounding notifies" % wait, wait_until(lambda: "late %d" % t0 in notification_text(d), timeout=20))

        crashes = d.crashes()
        check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    finally:
        # Restore everything this test changed.
        try:
            d.launch()
            time.sleep(2)
            d.broadcast("org.relaychat.DEBUG_DELETE_AVATAR")
            time.sleep(3)
            if me0.get("last_picture_update", 0) == 0:
                try:
                    dave.call("DELETE", "/users/%s/image" % dave.me["id"])
                except Exception:  # noqa: BLE001
                    pass
            elif avatar0:
                boundary = "relaya7cavatar%d" % t0
                body = (("--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"avatar\"\r\nContent-Type: %s\r\n\r\n"
                         % (boundary, avatar0[1])).encode() + avatar0[0] + ("\r\n--%s--\r\n" % boundary).encode())
                req = urllib.request.Request(REST_URL + "/api/v4/users/%s/image" % dave.me["id"], data=body, method="POST",
                                             headers={"Authorization": "Bearer " + dave.token, "Content-Type": "multipart/form-data; boundary=" + boundary})
                urllib.request.urlopen(req).read()
        except Exception as e:  # noqa: BLE001
            print("cleanup avatar:", e)
        try:
            dave.call("PUT", "/users/me/patch", {"nickname": me0.get("nickname", ""), "position": me0.get("position", ""),
                                                 "first_name": me0.get("first_name", ""), "last_name": me0.get("last_name", ""),
                                                 "notify_props": me0.get("notify_props", {})})
            cs0 = (me0.get("props") or {}).get("customStatus")
            if cs0:
                import json
                c = json.loads(cs0)
                body = {"emoji": c.get("emoji", ""), "text": c.get("text", "")}
                if c.get("duration"):
                    body["duration"] = c["duration"]
                    body["expires_at"] = c.get("expires_at")
                dave.call("PUT", "/users/me/status/custom", body)
            else:
                dave.call("DELETE", "/users/me/status/custom")
            if not (bob0.get("props") or {}).get("customStatus"):
                bob.call("DELETE", "/users/me/status/custom")
            set_status(dave, status0["status"], status0.get("dnd_end_time", 0))
            dave.call("PUT", "/channels/%s/members/%s/notify_props" % (dm["id"], dave.me["id"]), dm_props0)
            dave.call("PUT", "/channels/%s/members/%s/notify_props" % (channel["id"], dave.me["id"]), channel_props0)
            if prefs0:
                dave.call("PUT", "/users/me/preferences", prefs0)
            else:
                dave.call("POST", "/users/%s/preferences/delete" % dave.me["id"],
                          [{"user_id": dave.me["id"], "category": "recent_emojis", "name": dave.me["id"], "value": ""}])
        except Exception as e:  # noqa: BLE001
            print("cleanup profile:", e)
        for client, channel_id in ((dave, channel["id"]), (dave, dm["id"]), (bob, dm["id"])):
            try:
                for x in posts(client, channel_id, 60):
                    if x["user_id"] == client.me["id"] and PROBE in x["message"] and x["create_at"] >= (t0 - 5) * 1000:
                        client.call("DELETE", "/posts/" + x["id"])
            except Exception as e:  # noqa: BLE001
                print("cleanup posts:", e)
        for pid in bob_posts:
            try:
                bob.call("DELETE", "/posts/" + pid)
            except Exception:  # noqa: BLE001
                pass
        # Keep connected off again (the app's preference; the service stops with it).
        d.shell("am force-stop %s" % d.package, check=False)
        d.shell("rm -rf %s" % DEVICE_DIR, check=False)
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
