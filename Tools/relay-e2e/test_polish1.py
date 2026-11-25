#!/usr/bin/env python3
"""Relay for Android, Polish 1 e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065):

    RELAY_APK=<apk> python3 Tools/relay-e2e/test_polish1.py [--no-install] [--no-login]

alice is in the app (collapsed reply threads on: the server default); bob posts over REST.

  1. Own message: the server's markdown rendering replaces Telegram's local parse in place (no reload).
  2. Photo uploaded over REST by bob shows (photo media, bytes downloaded through the bridge).
  3. CRT: the chat list previews the newest root, not a thread reply (live, and after an app restart).
  4. The "N Replies" footer opens the thread at its first unread reply (thread last_viewed_at).
  5. Swipe-to-reply in a CRT channel's main history opens the thread.
  6. Forward of a post with a file shows the file (and the "Forwarded from" header).
  7. Forced id renumber (debug hook) is migrated into the client: ids == bridge map, every post once, in order.
  8. Video uploaded over REST: probed locally (dimensions, duration, thumbnail) and redrawn.
  9. Profile accessibility text says "Custom Status", not "Premium account".
 10. `:emoji:` autocomplete inserts a trailing space.
 11. Full reaction picker lists the custom emoji first.
 12. Attach menu has no Location; the channel edit screen has no "Set Photo or Video".
 13. Leaving a channel from its info screen completes at once (no undo bar delay).
 14. Keep-alive service stops on logout immediately.

Probe posts start with "[relay-e2e polish1]" and are deleted at the end; channels the test creates are archived.
Screens: docs/relay/screens/polish1-*.png.
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
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, REST_URL, dump_dialogs, dump_messages, open_chat, to_chat_list, screen_text, wait_until, read_app_file  # noqa: E402
from test_a5_write import composer, tap_send, type_message, keyboard_shown  # noqa: E402
from test_a7a_threads_links import ui_state, has_text, visible_texts, leave_thread  # noqa: E402

PROBE = "[relay-e2e polish1]"


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "polish1-%s.png" % name))


def screen_size(d):
    sizes = re.findall(r"(\d+)x(\d+)", d.shell("wm size"))
    return (int(sizes[-1][0]), int(sizes[-1][1])) if sizes else (1080, 2400)


def polish(d, op, wait=r"ok", timeout=20, **extras):
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_POLISH1", op=op, **extras)
    return d.wait_log(r"DEBUG_POLISH1 %s %s" % (op, wait), timeout=timeout, tags=["RelayDebug"])


def chat_dump(d):
    """The open chat's loaded messages: list of dicts id, text, entities, media, fwd, shown (MessageObject.messageText)."""
    polish(d, "chat_dump")
    rows = []
    for line in read_app_file(d, "relay-polish1.txt").splitlines():
        p = line.split("\t")
        if p[0] == "msg" and len(p) >= 7:
            rows.append({"id": int(p[1]), "text": p[2], "entities": p[3], "media": p[4], "fwd": p[5], "shown": p[6]})
    return rows


def upload(client, channel_id, path, mime):
    boundary = "relaye2eboundary%d" % int(time.time() * 1000)
    with open(path, "rb") as f:
        data = f.read()
    body = (("--%s\r\nContent-Disposition: form-data; name=\"channel_id\"\r\n\r\n%s\r\n" % (boundary, channel_id)).encode()
            + ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n"
               % (boundary, os.path.basename(path), mime)).encode() + data + ("\r\n--%s--\r\n" % boundary).encode())
    req = urllib.request.Request(REST_URL + "/api/v4/files", data=body, method="POST",
                                 headers={"Authorization": "Bearer " + client.token, "Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read())["file_infos"][0]


def make_files(tmp):
    files = {}
    files["photo"] = os.path.join(tmp, "bob-photo.jpg")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=800x600:rate=1", "-frames:v", "1", "-q:v", "3",
                    files["photo"]], check=True, stdin=subprocess.DEVNULL)
    files["png"] = os.path.join(tmp, "bob-shot.png")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=1", "-frames:v", "1",
                    files["png"]], check=True, stdin=subprocess.DEVNULL)
    files["video"] = os.path.join(tmp, "bob-clip.mp4")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-t", "3",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", files["video"]], check=True, stdin=subprocess.DEVNULL)
    files["text"] = os.path.join(tmp, "forwarded-notes.txt")
    with open(files["text"], "w") as f:
        f.write("polish1 forwarded file\n")
    return files


def tiny_png(width=64, height=48, rgb=(200, 80, 40)):
    """A7b's fixture: a small solid PNG (no larger than Mattermost's 120x100 thumbnail box)."""
    import struct
    import zlib

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def cell_of(d, text):
    nodes = [n for n in d.dump() if text in n.text]
    return nodes[-1] if nodes else None


def service_running(d, name="RelayKeepAlive"):
    out = d.shell("dumpsys activity services %s" % d.package, timeout=60, check=False)
    return name in out and "ServiceRecord" in out


def login(d, password, check):
    d.clear_data()
    d.reverse()
    d.shell("pm grant %s android.permission.POST_NOTIFICATIONS" % d.package, check=False)
    d.shell("pm grant %s android.permission.CAMERA" % d.package, check=False)  # the attach menu's camera preview
    d.logcat_clear()
    d.launch()
    time.sleep(4)
    d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="alice", password=password)
    m = d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
    check.ok("alice logged in, chat list loaded", m is not None)
    time.sleep(5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("Polish 1")
    password = relaye2e.mm_password()
    alice, bob = Rest("alice", password), Rest("bob", password)
    probes, cleanup = [], []
    t0 = int(time.time())
    tmp = tempfile.mkdtemp(prefix="relay-polish1-")
    files = make_files(tmp)
    W, H = screen_size(d)

    def post(client, channel_id, text, root_id="", file_ids=None):
        body = {"channel_id": channel_id, "message": "%s %s" % (PROBE, text) if text else ""}
        if root_id:
            body["root_id"] = root_id
        if file_ids:
            body["file_ids"] = file_ids
        p = client.call("POST", "/posts", body)
        probes.append((client, p["id"]))
        return p

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        teams = {t["name"]: t for t in alice.get("/users/me/teams")}
        eng = teams["engineering"]
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        town = next(c for c in channels.values() if c["name"] == "town-square" and c["team_id"] == eng["id"])
        try:
            bob.call("POST", "/channels/%s/members" % sandbox["id"], {"user_id": bob.me["id"]})
        except urllib.error.HTTPError:
            pass
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
            login(d, password, check)
        to_chat_list(d)

        # MARK: 1. Own message: server rendering in place.
        open_chat(d, dm_bob["id"], wait=4)
        typed = "%s _italic_ and **bold** and `code` %d" % (PROBE, t0)
        rendered = "%s italic and bold and code %d" % (PROBE, t0)
        type_message(d, typed)
        tap_send(d)
        got = wait_until(lambda: [p for p in bob.get("/channels/%s/posts?per_page=10" % dm_bob["id"])["posts"].values()
                                  if p["message"] == typed], timeout=12)
        if got:
            probes.append((alice, got[0]["id"]))
        check.ok("own markdown message reached the server as typed", bool(got))
        time.sleep(2)
        rows = [r for r in chat_dump(d) if str(t0) in r["text"] and "code" in r["text"]]
        row = rows[-1] if rows else {}
        check.ok("own message: text is the server's rendering (no _ ** ` left), without a reload",
                 row.get("text") == rendered and row.get("shown") == rendered, str(row))
        check.ok("own message: italic, bold and code entities", all(k in row.get("entities", "") for k in ("Italic", "Bold", "Code")), str(row))
        shot(d, "own-markdown")

        # MARK: 2. Photos uploaded over REST show (A7b: a 64x48 PNG stayed blank).
        small = os.path.join(tmp, "bob-small.png")
        with open(small, "wb") as f:
            f.write(tiny_png())
        fi_jpg = upload(bob, dm_bob["id"], files["photo"], "image/jpeg")
        fi_png = upload(bob, dm_bob["id"], small, "image/png")
        post(bob, dm_bob["id"], "photo from REST %d" % t0, file_ids=[fi_jpg["id"]])
        post(bob, dm_bob["id"], "png from REST %d" % t0, file_ids=[fi_png["id"]])

        def rest_photos():
            return [r for r in chat_dump(d) if r["media"].startswith("photo") and ("photo from REST %d" % t0 in r["text"] or "png from REST %d" % t0 in r["text"])]
        rows = wait_until(lambda: (lambda rs: rs if len(rs) == 2 and all(r["media"].endswith("loaded=1") for r in rs) else None)(rest_photos()), timeout=15)
        rows = rows or rest_photos()
        check.ok("REST photos (800x600 jpg, 64x48 png) arrive as photos", len(rows) == 2, str([(r["text"][-24:], r["media"]) for r in rows]))
        check.ok("REST photos: the bubble's image (original 'y') is downloaded, not left blank",
                 len(rows) == 2 and all(r["media"] == "photo y loaded=1" for r in rows), str([r["media"] for r in rows]))
        shot(d, "rest-photo")

        # MARK: 3. CRT preview.
        to_chat_list(d)
        root = post(bob, sandbox["id"], "preview root %d" % t0)
        time.sleep(1.5)
        post(bob, sandbox["id"], "preview reply %d" % t0, root["id"])
        time.sleep(3)
        dialogs, _ = dump_dialogs(d)
        preview = dialogs.get(sandbox["id"], {}).get("preview", "")
        check.ok("CRT: chat list previews the newest root, not the reply (live)", "preview root %d" % t0 in preview, preview)
        shot(d, "crt-preview")
        d.force_stop()
        d.launch()
        time.sleep(8)
        dialogs, _ = dump_dialogs(d)
        preview = dialogs.get(sandbox["id"], {}).get("preview", "")
        check.ok("CRT: preview stays the root after an app restart (client cache)", "preview root %d" % t0 in preview, preview)

        # MARK: 4. Footer opens the thread at the first unread reply.
        troot = post(bob, sandbox["id"], "footer root %d" % t0)
        replies = []
        for i in range(1, 26):
            replies.append(post(bob, sandbox["id"], "footer reply %02d %d" % (i, t0), troot["id"]))
        alice.call("PUT", "/users/%s/teams/%s/threads/%s/following" % (alice.me["id"], eng["id"], troot["id"]))
        alice.call("PUT", "/users/%s/teams/%s/threads/%s/read/%d" % (alice.me["id"], eng["id"], troot["id"], replies[14]["create_at"]))
        alice.call("POST", "/channels/members/me/view", {"channel_id": sandbox["id"]})
        time.sleep(2)
        open_chat(d, sandbox["id"], wait=4)
        st = ui_state(d)
        root_v = [v for v in st["visible"] if "footer root %d" % t0 in v[5]]
        check.ok("footer root in the main history with 25 replies", root_v and root_v[0][3] == 25, str(root_v))
        cell = cell_of(d, "footer root %d" % t0)
        d.logcat_clear()
        if cell:
            x1, y1, x2, y2 = cell.bounds
            d.tap(x1 + min(200, (x2 - x1) // 3), y2 - 22)  # the "25 Replies" footer at the bottom of the bubble
        time.sleep(4)
        st = ui_state(d)
        texts = visible_texts(st)
        check.ok("footer opens the thread", st["thread"] == (root_v[0][0] if root_v else -1) and d.wait_log(r"footer: thread", timeout=2, tags=["RelayThreads"]) is not None
                 or st["thread"] == (root_v[0][0] if root_v else -1), "thread=%s" % st["thread"])
        check.ok("thread opens at the first unread reply (16), not at the top",
                 any("footer reply 16 %d" % t0 in t for t in texts) and not any("footer reply 01 %d" % t0 in t for t in texts),
                 str([t[-24:] for t in texts]))
        shot(d, "footer-first-unread")
        leave_thread(d)

        # MARK: 5. Swipe-to-reply opens the thread.
        st = ui_state(d)
        cell = cell_of(d, "preview root %d" % t0) or cell_of(d, "footer root %d" % t0)
        target = "preview root %d" % t0 if cell_of(d, "preview root %d" % t0) else "footer root %d" % t0
        want = [v[0] for v in st["visible"] if target in v[5]]
        if cell:
            x1, y1, x2, y2 = cell.bounds
            y = y1 + (y2 - y1) // 3
            d.shell("input swipe %d %d %d %d 250" % (int(W * 0.85), y, int(W * 0.25), y))
        time.sleep(4)
        st = ui_state(d)
        check.ok("swipe-to-reply (CRT main history) opens the thread", want and st["thread"] == want[0], "thread=%s want=%s" % (st["thread"], want))
        shot(d, "swipe-thread")
        leave_thread(d)
        to_chat_list(d)

        # MARK: 6. Forward with a file.
        fi_txt = upload(bob, town["id"], files["text"], "text/plain")
        orig = post(bob, town["id"], "forwarded original %d" % t0, file_ids=[fi_txt["id"]])
        fi_img = upload(bob, town["id"], files["photo"], "image/jpeg")
        orig_img = post(bob, town["id"], "forwarded image %d" % t0, file_ids=[fi_img["id"]])
        time.sleep(1)
        fwd = bob.call("POST", "/posts", {"channel_id": dm_bob["id"], "message": "%s/%s/pl/%s" % (relaye2e.MM_URL, eng["name"], orig["id"])})
        probes.append((bob, fwd["id"]))
        fwd2 = bob.call("POST", "/posts", {"channel_id": dm_bob["id"], "message": "%s/%s/pl/%s" % (relaye2e.MM_URL, eng["name"], orig_img["id"])})
        probes.append((bob, fwd2["id"]))
        time.sleep(3)
        open_chat(d, dm_bob["id"], wait=4)
        rows = chat_dump(d)
        f_rows = [r for r in rows if r["fwd"] != "0" and "forwarded original %d" % t0 in r["text"]]
        check.ok("forward: the original's file shows, with the forward header", f_rows and f_rows[0]["media"].startswith("doc text/plain"), str(f_rows))
        fi_rows = [r for r in rows if r["fwd"] != "0" and "forwarded image %d" % t0 in r["text"]]
        check.ok("forward: the original's photo shows", fi_rows and fi_rows[0]["media"].startswith("photo"), str(fi_rows))
        check.ok("'Forwarded from' visible", "Forwarded from" in screen_text(d))
        shot(d, "forward-files")
        to_chat_list(d)

        # MARK: 7. Forced renumber.
        name = "relay-e2e-p1-%d" % t0
        ch = bob.call("POST", "/channels", {"team_id": eng["id"], "name": name, "display_name": "P1 Renumber %d" % t0, "type": "O"})
        cleanup.append(lambda: bob.call("DELETE", "/channels/%s" % ch["id"]))
        bob.call("POST", "/channels/%s/members" % ch["id"], {"user_id": alice.me["id"]})
        time.sleep(3)
        for i in range(10):
            post(bob, ch["id"], "renumber %02d %d" % (i, t0))
        time.sleep(3)
        alice.call("POST", "/channels/members/me/view", {"channel_id": ch["id"]})
        open_chat(d, ch["id"], wait=5)
        before = {r["text"]: r["id"] for r in chat_dump(d) if "renumber" in r["text"]}
        res = polish(d, "renumber", wait=r"ok (.*)", timeout=30, channel=ch["id"])
        summary = res.group(1) if res else ""
        check.ok("debug hook forced a renumber that moved real posts", res is not None and re.search(r"real=([1-9]\d*)", summary) is not None, summary)
        time.sleep(3)
        after_rows = [r for r in chat_dump(d) if "renumber" in r["text"]]
        after = {r["text"]: r["id"] for r in after_rows}
        _, bridge_map = dump_messages(d, ch["id"])
        moved = [t for t in before if before[t] != after.get(t)]
        server = sorted((p for p in bob.get("/channels/%s/posts?per_page=50" % ch["id"])["posts"].values() if "renumber" in p["message"]),
                        key=lambda p: p["create_at"])
        by_id = sorted(after_rows, key=lambda r: r["id"])
        check.ok("renumber: some open-chat messages got new ids", bool(moved), "before=%s after=%s" % (sorted(before.values()), sorted(after.values())))
        check.ok("renumber: every post once in the open chat (no duplicate, none lost)", len(after_rows) == len(after) == len(server), "%d rows %d posts" % (len(after_rows), len(server)))
        check.ok("renumber: client ids == bridge map", all(bridge_map.get(r["id"], ("",))[0] == next((p["id"] for p in server if p["message"] == r["text"]), None)
                                                            for r in after_rows), str([(r["id"], bridge_map.get(r["id"])) for r in after_rows][:4]))
        check.ok("renumber: id order == post order", [r["text"] for r in by_id] == [p["message"] for p in server])
        msgs, _ = dump_messages(d, ch["id"])
        stored = sorted((mid for mid, m in msgs.items() if "renumber" in m["text"]))
        check.ok("renumber: client storage holds the new ids only", sorted(after.values()) == stored, "%s vs %s" % (stored, sorted(after.values())))
        notif = d.shell("dumpsys notification --noredact", timeout=60, check=False)
        check.ok("renumber: re-inserted messages don't notify", ("renumber 0" not in notif))
        shot(d, "renumber")
        to_chat_list(d)

        # MARK: 8. Video from another client.
        d.logcat_clear()
        fi_vid = upload(bob, dm_bob["id"], files["video"], "video/mp4")
        post(bob, dm_bob["id"], "video from REST %d" % t0, file_ids=[fi_vid["id"]])
        open_chat(d, dm_bob["id"], wait=3)
        probe = d.wait_log(r"video probe %s .*: (ok .*) in \d+ ms" % fi_vid["id"], timeout=30, tags=["RelayBridge"])
        check.ok("video: probed locally (size, duration, frame)", probe is not None and "640x360" in probe.group(1), probe.group(0) if probe else "")
        time.sleep(3)
        v_rows = [r for r in chat_dump(d) if "video from REST %d" % t0 in r["text"]]
        check.ok("video: bubble redrawn with 640x360, 3 s and a thumbnail", v_rows and v_rows[0]["media"] == "video 640x360 3s thumbs=1", str(v_rows))
        shot(d, "video")

        # MARK: 10. :emoji: autocomplete trailing space.
        field = composer(d)
        if field:
            d.tap_node(field)
            time.sleep(0.5)
            d.type_text(":smile")
        # Suggestion rows read ":  smile" (the emoji glyph, then the name).
        sug = wait_until(lambda: next((n for n in d.dump() if n.text.replace(" ", "") == ":smile" and not n.cls.endswith("EditText")), None), timeout=8)
        if sug:
            d.tap_node(sug)
            time.sleep(1)
        field = composer(d)
        text = field.text if field else ""
        check.ok(":emoji: autocomplete inserts the emoji and a trailing space", bool(sug) and text.endswith(" ") and ":smile" not in text, repr(text))
        if field:
            d.clear_field(field, 10)
        if keyboard_shown(d):
            d.key("KEYCODE_BACK")
            time.sleep(0.6)

        # MARK: 11. Full reaction picker: custom emoji first.
        post(bob, dm_bob["id"], "react target %d" % t0)
        time.sleep(3)
        target = wait_until(lambda: cell_of(d, "react target %d" % t0), timeout=8)
        if target:
            x1, y1, x2, y2 = target.bounds
            d.tap(int(W * 0.33), min(y1 + 60, (y1 + y2) // 2))  # the text of an incoming bubble (as A7c)
            time.sleep(2.5)
        expand = wait_until(lambda: next((n for n in d.dump() if n.desc == "Expand panel"), None), timeout=6)
        d.logcat_clear()
        if expand:
            d.tap_node(expand)
        m = d.wait_log(r"full picker: (\d+) custom pack\(s\) first, emoji from row (\d+)", timeout=8, tags=["RelayEmoji"])
        custom = len(alice.get("/emoji?per_page=5"))
        check.ok("full reaction picker: custom emoji section first, then Unicode",
                 m is not None and (int(m.group(1)) >= 1 or custom == 0) and int(m.group(2)) > 1, m.group(0) if m else "no picker log")
        time.sleep(1.5)
        shot(d, "picker-custom-first")
        d.key("KEYCODE_BACK")
        time.sleep(1)
        if not composer(d):
            d.key("KEYCODE_BACK")
            time.sleep(1)

        # MARK: 12a. Attach menu: no Location.
        if not composer(d):
            open_chat(d, dm_bob["id"], wait=3)
        att = wait_until(lambda: next((n for n in d.dump() if n.desc == "Attach media"), None), timeout=6)
        if att:
            d.tap_node(att)
            time.sleep(2)
        texts = screen_text(d)
        check.ok("attach menu open (Gallery, File) without Location", "Gallery" in texts and "File" in texts and "Location" not in texts, texts[:200])
        shot(d, "attach-no-location")
        d.key("KEYCODE_BACK")
        time.sleep(1)
        to_chat_list(d)

        # MARK: 9. Profile accessibility.
        bob.call("PUT", "/users/me/status/custom", {"emoji": "palm_tree", "text": "On a beach"})
        cleanup.append(lambda: bob.call("DELETE", "/users/me/status/custom"))
        time.sleep(4)
        open_chat(d, dm_bob["id"], wait=3)
        polish(d, "open_profile", username="bob")
        time.sleep(3)
        nodes = d.dump()
        all_text = " | ".join(n.text + " " + n.desc for n in nodes)
        check.ok("profile: no 'Premium account' accessibility text", "Premium account" not in all_text, all_text[:200])
        check.ok("profile: emoji status described as the custom status", "Custom Status: On a beach" in all_text or "Custom Status" in all_text, all_text[:300])
        shot(d, "profile-a11y")
        to_chat_list(d)

        # MARK: 12b + 13. Channel edit (no photo), leave from info at once.
        name2 = "relay-e2e-p1l-%d" % t0
        ch2 = alice.call("POST", "/channels", {"team_id": eng["id"], "name": name2, "display_name": "P1 Leave %d" % t0, "type": "O"})
        cleanup.append(lambda: bob.call("DELETE", "/channels/%s" % ch2["id"]))
        bob.call("POST", "/channels/%s/members" % ch2["id"], {"user_id": bob.me["id"]})
        post(bob, ch2["id"], "leave probe %d" % t0)
        time.sleep(4)
        open_chat(d, ch2["id"], wait=4)
        title = next((n for n in d.dump() if n.desc.startswith("P1 Leave %d" % t0)), None)
        if title:
            d.tap_node(title)
            time.sleep(2.5)
        edit = wait_until(lambda: next((n for n in d.dump() if n.desc == "Edit"), None), timeout=5)
        if edit:
            d.tap_node(edit)
            time.sleep(2.5)
            texts = screen_text(d)
            check.ok("channel edit screen without 'Set Photo or Video'", ("P1 Leave %d" % t0) in texts and "Set Photo" not in texts and "Set New Photo" not in texts, texts[:200])
            shot(d, "channel-edit-no-photo")
            d.key("KEYCODE_BACK")
            time.sleep(1.5)
            if keyboard_shown(d):
                d.key("KEYCODE_BACK")
                time.sleep(1)
        else:
            check.ok("channel edit screen opens", False)
        to_chat_list(d)
        name3 = "relay-e2e-p1m-%d" % t0
        ch3 = bob.call("POST", "/channels", {"team_id": eng["id"], "name": name3, "display_name": "P1 Member %d" % t0, "type": "O"})
        cleanup.append(lambda: bob.call("DELETE", "/channels/%s" % ch3["id"]))
        bob.call("POST", "/channels/%s/members" % ch3["id"], {"user_id": alice.me["id"]})
        post(bob, ch3["id"], "member probe %d" % t0)
        time.sleep(4)
        open_chat(d, ch3["id"], wait=4)
        title = next((n for n in d.dump() if n.desc.startswith("P1 Member %d" % t0)), None)
        if title:
            d.tap_node(title)
            time.sleep(2.5)
        more = [n for n in d.dump() if n.desc == "More options"]
        if more:
            d.tap_node(max(more, key=lambda n: n.bounds[0]))
            time.sleep(1.5)
        leave = wait_until(lambda: next((n for n in d.dump() if n.text in ("Leave channel", "Leave group", "Delete and leave group", "Leave Group", "Leave Channel")), None), timeout=5)
        if leave:
            d.tap_node(leave)
            time.sleep(1.5)
            confirm = wait_until(lambda: next((n for n in d.dump() if n.text.upper() in ("LEAVE GROUP", "LEAVE CHANNEL", "LEAVE", "DELETE AND LEAVE", "DELETE CHAT")), None), timeout=5)
            if confirm:
                t_leave = time.time()
                d.tap_node(confirm)
                left = wait_until(lambda: alice.me["id"] not in [m["user_id"] for m in bob.get("/channels/%s/members?per_page=100" % ch3["id"])], timeout=4, step=0.3)
                check.ok("leave from channel info completes at once (no undo delay): %.1f s" % (time.time() - t_leave), bool(left))
                shot(d, "left-channel")
            else:
                check.ok("leave confirmation", False, screen_text(d)[:200])
        else:
            check.ok("channel info menu: Leave", False, screen_text(d)[:300])
        to_chat_list(d)

        # MARK: 14. Keep-alive stops on logout (last: logs out).
        polish(d, "keepalive", on="1")
        running = wait_until(lambda: service_running(d), timeout=10)
        check.ok("keep-alive service running (setting on)", bool(running))
        polish(d, "logout")
        stopped = wait_until(lambda: not service_running(d), timeout=3, step=0.3)
        check.ok("keep-alive service stops right after logout", bool(stopped))
        d.shell("cmd notification post -h", check=False)
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
