#!/usr/bin/env python3
"""Relay for Android, phase A5 (write path) e2e test against the local Mattermost.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065, ffmpeg on the host):

    python3 Tools/relay-e2e/test_a5_write.py [--no-install] [--no-login]

alice types in the app (adb input) and the result is checked over REST as bob:

  1. Text in the DM and a channel (markdown passes through), typing reaches bob's WebSocket.
  2. Reply = thread reply with root_id; a reply to a reply stays in the same thread (CRT).
  3. Edit and delete of own posts; deleting bob's post is refused by Mattermost and the message comes back.
  4. Reactions add / replace / clear (Telegram's sendReaction via the debug hook: the picker needs
     Telegram's animated reaction set).
  5. Forward = a post with the permalink of the forwarded post.
  6. Media through Telegram's own uploader (debug hook DEBUG_SEND_MEDIA instead of the picker UI): photo
     (md5 of what the bridge uploaded == md5 on the server), album of 3 → one post with 3 files, document
     (md5 == the local file), video, voice (props.relay_voice).
  7. Mark as unread from the chat list menu → the channel is unread on the server.
  8. Offline send: airplane mode, send, the post appears exactly once after the network returns.

Probe posts start with "[relay-e2e a5]" and are deleted at the end. Screens: docs/relay/screens/a5-*.png.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, dump_dialogs, dump_messages, find_msg, open_chat, to_chat_list, screen_text, wait_until, epoch_lines  # noqa: E402

PROBE = "[relay-e2e a5]"
# The app reads its own external files dir without a storage permission (a .bin in Download is not media).
DEVICE_DIR = "/sdcard/Android/data/%s/files/relay-e2e" % relaye2e.PACKAGE


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "a5-%s.png" % name))


# MARK: - A minimal WebSocket client (bob listens for alice's typing)

class WebSocket:
    def __init__(self, token):
        self.sock = socket.create_connection(("localhost", relaye2e.MM_PORT), timeout=5)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET /api/v4/websocket HTTP/1.1\r\nHost: localhost:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer %s\r\n\r\n") % (relaye2e.MM_PORT, key, token)
        self.sock.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise RuntimeError("websocket upgrade failed: %r" % head[:80])
        self.buf = b""

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError()
            self.buf += chunk
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def recv(self):
        b0, b1 = self._read(2)
        length = b1 & 0x7f
        if length == 126:
            length = int.from_bytes(self._read(2), "big")
        elif length == 127:
            length = int.from_bytes(self._read(8), "big")
        payload = self._read(length)
        return b0 & 0x0f, payload

    def events(self, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.sock.settimeout(max(0.1, deadline - time.time()))
            try:
                op, payload = self.recv()
            except (socket.timeout, EOFError):
                return
            if op == 1:
                try:
                    yield json.loads(payload)
                except ValueError:
                    pass

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


# MARK: - App UI helpers

def composer(d):
    for n in d.dump():
        if n.cls.endswith("EditText"):
            return n
    return None


def tap_send(d):
    for _ in range(3):
        nodes = d.dump()
        n = d.find(desc="Send", nodes=nodes) or d.find(desc="Edit Message", nodes=nodes) or d.find(desc="Done", nodes=nodes)
        if n:
            x1, y1, x2, y2 = n.bounds
            t = time.time()
            d.tap(x2 - 44, (y1 + y2) // 2)
            return t
        time.sleep(0.5)
    return False


def type_message(d, text):
    field = composer(d)
    if field is None:
        return False
    d.tap_node(field)
    time.sleep(0.5)
    d.type_text(text)
    time.sleep(0.5)
    return True


def send_text(d, text):
    if not type_message(d, text):
        return None
    return tap_send(d)


def keyboard_shown(d):
    out = d.shell("dumpsys input_method", check=False)
    return "mInputShown=true" in out


def message_menu(d, text, item):
    """Taps a message (context menu) and an item of it."""
    if keyboard_shown(d):
        d.key("KEYCODE_BACK")  # closes the keyboard only (ESC / BACK without it would leave the chat)
        time.sleep(0.6)
    n = d.find(text=text, contains=True)
    if n is None:
        return False
    x1, y1, x2, y2 = n.bounds
    # Low in the bubble (the text / time line): a reply's quote at the top would jump to the original.
    d.tap(max(x1 + 40, x2 - 150), max(y1 + 10, y2 - 28))
    time.sleep(1.2)
    entry = wait_until(lambda: d.find(text=item), timeout=4)
    if entry is None:
        d.key("KEYCODE_BACK")
        return False
    d.tap_node(entry)
    time.sleep(1)
    return True


def posts(client, channel_id, n=10):
    res = client.get("/channels/%s/posts?per_page=%d" % (channel_id, n))
    return [res["posts"][i] for i in res["order"]]


def find_post(client, channel_id, text, n=30):
    return [p for p in posts(client, channel_id, n) if text in p["message"]]


def sent_latency(d, since):
    for t, line in epoch_lines(d, "RelayDebug"):
        if "UI sent old=" in line and t >= since - 1:
            return t - since
    return None


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def download_md5(client, file_id):
    req = urllib.request.Request(relaye2e.MM_URL.replace("localhost", "localhost") + "/api/v4/files/" + file_id,
                                 headers={"Authorization": "Bearer " + client.token})
    with urllib.request.urlopen(req) as res:
        return hashlib.md5(res.read()).hexdigest()


def make_media(tmp):
    """Test files made with ffmpeg: 3 photos, a video, a voice note (Ogg/Opus) and a binary document."""
    files = {}
    for i, color in enumerate(("red", "green", "blue")):
        path = os.path.join(tmp, "photo%d.jpg" % (i + 1))
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1024x768:rate=1", "-vf",
                        "drawbox=x=0:y=0:w=300:h=300:color=%s:t=fill" % color, "-frames:v", "1", "-q:v", "3", path], check=True, stdin=subprocess.DEVNULL)
        files["photo%d" % (i + 1)] = path
    path = os.path.join(tmp, "clip.mp4")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-t", "3",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", path], check=True, stdin=subprocess.DEVNULL)
    files["video"] = path
    path = os.path.join(tmp, "voice.ogg")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-ac", "1", "-ar", "48000",
                    "-c:a", "libopus", "-b:a", "32k", path], check=True, stdin=subprocess.DEVNULL)
    files["voice"] = path
    path = os.path.join(tmp, "relay-e2e-a5-report.bin")
    with open(path, "wb") as f:
        f.write(os.urandom(700 * 1024 + 123))
    files["document"] = path
    return files


def bridge_upload_md5(d, name_part, since):
    for t, line in epoch_lines(d, "RelayBridge"):
        m = re.search(r"uploaded (.+?) \((\d+) bytes, md5 ([0-9a-f]+)\) as (\w+)", line)
        if m and name_part in m.group(1) and t >= since - 1:
            return m.group(3), m.group(4)
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    args = ap.parse_args()

    d = relaye2e.Device()
    check = relaye2e.Check("A5 write path")
    password = relaye2e.mm_password()
    alice, bob = Rest("alice", password), Rest("bob", password)
    latencies = []
    bob_posts = []
    ws = None
    dm_post = None
    t0 = int(time.time())

    try:
        channels = {c["id"]: c for c in alice.get("/users/me/channels?per_page=200") if not c.get("delete_at")}
        eng = next(t for t in alice.get("/users/me/teams") if t["name"] == "engineering")
        dm_bob = bob.call("POST", "/channels/direct", [bob.me["id"], alice.me["id"]])
        sandbox = next(c for c in channels.values() if c["name"] == "sandbox" and c["team_id"] == eng["id"])
        try:
            bob.call("POST", "/channels/%s/members" % sandbox["id"], {"user_id": bob.me["id"]})
        except urllib.error.HTTPError:
            pass

        if not args.no_login:
            if not args.no_install:
                print(d.install().strip().splitlines()[-1])
            d.clear_data()
            d.reverse()
            d.shell("pm grant %s android.permission.POST_NOTIFICATIONS" % d.package, check=False)
            for perm in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE"):
                d.shell("pm grant %s android.permission.%s" % (d.package, perm), check=False)
            d.logcat_clear()
            d.launch()
            time.sleep(4)
            d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user="alice", password=password)
            m = d.wait_log(r"dialog rows: (\d+) chats in (\d+) ms", timeout=60, tags=["RelayBridge"])
            check.ok("alice logged in, chat list loaded", m is not None)
            time.sleep(5)

        # 1. Text in the DM (markdown pass-through) + typing to bob's WebSocket.
        ws = WebSocket(bob.token)
        open_chat(d, dm_bob["id"])
        text = "%s dm **bold** and _italic_ %d" % (PROBE, t0)
        type_message(d, "%s typing" % PROBE)
        typing = any(e.get("event") == "typing" and e.get("broadcast", {}).get("channel_id") == dm_bob["id"]
                     for e in ws.events(6))
        check.ok("typing in the composer reaches bob's WebSocket (user_typing)", typing)
        field = composer(d)
        d.clear_field(field, 60)
        d.logcat_clear()
        t = send_text(d, text)
        got = wait_until(lambda: find_post(bob, dm_bob["id"], "%s dm" % PROBE), timeout=10)
        check.ok("DM text reaches Mattermost (markdown passes through)", got and got[0]["message"] == text and got[0]["user_id"] == alice.me["id"],
                 got[0]["message"] if got else "")
        lat = sent_latency(d, t) if t else None
        if lat is not None:
            latencies.append(("DM send → sent (server id)", lat))
        dm_post = got[0] if got else None
        time.sleep(1)
        shown = [x for x in d.screen_texts() if ("%d" % t0) in x and "dm bold and" in x]
        check.ok("DM message shows once, sent (server id)", len(shown) == 1 and "Sent at" in shown[0], str(shown))
        shot(d, "dm-sent")

        # 2. Channel text, reply, reply to a reply.
        root = bob.call("POST", "/posts", {"channel_id": sandbox["id"], "message": "%s root by bob %d" % (PROBE, t0)})
        bob_posts.append(root["id"])
        open_chat(d, sandbox["id"])
        wait_until(lambda: "root by bob %d" % t0 in screen_text(d), timeout=8)
        t = send_text(d, "%s channel text %d" % (PROBE, t0))
        got = wait_until(lambda: find_post(bob, sandbox["id"], "channel text %d" % t0), timeout=10)
        check.ok("channel text reaches Mattermost", got)
        lat = sent_latency(d, t) if t else None
        if lat is not None:
            latencies.append(("channel send → sent", lat))
        ok = message_menu(d, "root by bob %d" % t0, "Reply")
        check.ok("context menu: Reply", ok)
        time.sleep(1.5)  # with collapsed threads (A7a) Reply opens the thread view
        send_text(d, "%s reply one %d" % (PROBE, t0))
        r1 = wait_until(lambda: find_post(bob, sandbox["id"], "reply one %d" % t0), timeout=10)
        check.ok("reply is a thread reply (root_id = bob's post)", r1 and r1[0]["root_id"] == root["id"], str(r1[0]["root_id"] if r1 else None))
        time.sleep(1)
        message_menu(d, "reply one %d" % t0, "Reply")
        send_text(d, "%s reply two %d" % (PROBE, t0))
        r2 = wait_until(lambda: find_post(bob, sandbox["id"], "reply two %d" % t0), timeout=10)
        check.ok("reply to a reply stays in the thread (CRT)", r2 and r2[0]["root_id"] == root["id"])
        shot(d, "thread-reply")
        # With collapsed threads (A7a) Reply opened the thread view: back to the channel's main history.
        open_chat(d, sandbox["id"])

        # 3. Edit and delete.
        ok = message_menu(d, "channel text %d" % t0, "Edit")
        check.ok("context menu: Edit", ok)
        if ok:
            field = composer(d)
            d.clear_field(field, 70)
            d.type_text("%s channel edited %d" % (PROBE, t0))
            tap_send(d)
        got = wait_until(lambda: (lambda p: p if p and p[0]["edit_at"] > 0 else None)(find_post(bob, sandbox["id"], "channel edited %d" % t0)), timeout=10)
        check.ok("edit reaches Mattermost (message + edit_at)", got)
        shot(d, "edited")
        edited_id = got[0]["id"] if got else None
        ok = message_menu(d, "channel edited %d" % t0, "Delete")
        if ok:
            confirm = wait_until(lambda: d.find(text="Delete") or d.find(text="DELETE"), timeout=4)
            if confirm:
                d.tap_node(confirm)
        texts_dialog = screen_text(d)
        check.ok("delete dialog has no 'delete for me' option", "for me" not in texts_dialog.lower() and "also delete" not in texts_dialog.lower())
        gone = wait_until(lambda: not find_post(bob, sandbox["id"], "channel edited %d" % t0), timeout=10)
        check.ok("delete reaches Mattermost", gone and edited_id is not None)
        # A7b maps Mattermost roles to rights: without delete_others the menu has no Delete for bob's post (the
        # menu opens with Reply/Copy). With the right, the delete is confirmed and the server's 403 brings it back.
        menu_texts = []
        ok = message_menu(d, "root by bob %d" % t0, "Delete")
        if not ok:
            n = d.find(text="root by bob %d" % t0, contains=True)
            if n:
                x1, y1, x2, y2 = n.bounds
                d.tap(max(x1 + 40, x2 - 150), max(y1 + 10, y2 - 28))
                time.sleep(1.5)
                menu_texts = [x.text for x in d.dump()]
                d.key("KEYCODE_BACK")
                time.sleep(1)
        confirmed = not ok and "Reply" in menu_texts and "Delete" not in menu_texts
        if ok:
            # A group creator gets Telegram's admin sheet ("Proceed"), others the plain alert ("Delete").
            confirm = wait_until(lambda: d.find(desc="Proceed") or d.find(text="Proceed") or d.find(text="Delete"), timeout=4)
            if confirm:
                d.tap_node(confirm)
                confirmed = True
        time.sleep(3)
        check.ok("delete of bob's post confirmed in the app (or not offered without the right, A7b)", confirmed, str(menu_texts[:12]))
        still = find_post(bob, sandbox["id"], "root by bob %d" % t0)
        msgs, _ = dump_messages(d, sandbox["id"])
        check.ok("deleting bob's post is refused and the message comes back", still and find_msg(msgs, "root by bob %d" % t0))

        # 4. Reactions (own message in the DM).
        open_chat(d, dm_bob["id"])
        target = "dm bold and italic %d" % t0
        for emoji, want in (("thumbsup", {"+1", "thumbsup"}), ("tada", {"tada"}), ("", set())):
            d.broadcast("org.relaychat.DEBUG_REACT", text=target, emoji=emoji)
            time.sleep(2.5)
            mine = {r["emoji_name"] for r in (bob.get("/posts/%s/reactions" % dm_post["id"]) or []) if r["user_id"] == alice.me["id"]} if dm_post else None
            label = ("reaction set to %s" % emoji) if emoji else "reactions cleared"
            check.ok(label + " on Mattermost", mine is not None and (mine & want if want else not mine) and len(mine) <= 1, str(mine))
            if emoji == "tada":
                shot(d, "reaction")
        msgs, _ = dump_messages(d, dm_bob["id"])
        hit = find_msg(msgs, target)
        check.ok("client shows no reaction after clearing", hit and hit[0][1]["reactions"] == "", str(hit[:1]))

        # 5. Forward the DM message to the sandbox channel.
        ok = message_menu(d, "%d" % t0, "Forward")  # the bubble may still show the locally parsed text
        check.ok("context menu: Forward", ok)
        search = wait_until(lambda: d.find(desc="Search Chats"), timeout=6)
        if search:
            d.tap_node(search)
            time.sleep(0.8)
            d.type_text("Sandbox")
            picked = wait_until(lambda: d.find(text="Sandbox,", contains=True), timeout=6)
            if picked:
                d.tap_node(picked)
                time.sleep(2)
                tap_send(d)
        fwd = wait_until(lambda: [p for p in posts(bob, sandbox["id"], 5) if dm_post and ("/pl/" + dm_post["id"]) in p["message"]], timeout=10)
        check.ok("forward posts the permalink of the original", fwd, fwd[0]["message"] if fwd else "")
        shot(d, "forward")

        # 6. Media through Telegram's uploader.
        tmp = tempfile.mkdtemp(prefix="relay-a5-")
        media = make_media(tmp)
        d.shell("mkdir -p %s" % DEVICE_DIR)
        for path in media.values():
            d.adb("push", path, DEVICE_DIR + "/", timeout=120)
        dev = {k: DEVICE_DIR + "/" + os.path.basename(v) for k, v in media.items()}
        open_chat(d, dm_bob["id"])
        before = {p["id"] for p in posts(bob, dm_bob["id"], 20)}

        def new_post(pred, timeout=40):
            return wait_until(lambda: [p for p in posts(bob, dm_bob["id"], 10) if p["id"] not in before and pred(p)], timeout=timeout, step=1)

        t = time.time()
        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="photo", paths=dev["photo1"], caption="%s photo %d" % (PROBE, t0))
        got = new_post(lambda p: "photo %d" % t0 in p["message"])
        files = (got[0].get("metadata") or {}).get("files", []) if got else []
        check.ok("photo: one post with one image file and the caption", got and len(files) == 1 and files[0]["mime_type"].startswith("image/"))
        bridge_md5, bridge_id = bridge_upload_md5(d, "Photo", t)
        check.ok("photo: server bytes == bytes Telegram uploaded (md5)", files and bridge_md5 and download_md5(bob, files[0]["id"]) == bridge_md5,
                 "bridge %s" % bridge_md5)
        if got:
            before.add(got[0]["id"])
        time.sleep(2)
        shot(d, "photo")

        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="photo",
                    paths=",".join([dev["photo1"], dev["photo2"], dev["photo3"]]), caption="%s album %d" % (PROBE, t0))
        got = new_post(lambda p: len(p.get("file_ids") or []) >= 2)
        files = (got[0].get("metadata") or {}).get("files", []) if got else []
        check.ok("album of 3 photos → one post with 3 files", got and len(files) == 3, "%d files" % len(files))
        if got:
            before.add(got[0]["id"])
        time.sleep(3)
        msgs, _ = dump_messages(d, dm_bob["id"])
        sending = [m for mid, m in msgs.items() if mid < 0]
        check.ok("album: no message left sending/failed in the client", not sending, str(sending[:2]))
        shot(d, "album")

        t = time.time()
        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="document", paths=dev["document"], caption="")
        got = new_post(lambda p: any(f["name"] == os.path.basename(media["document"]) for f in (p.get("metadata") or {}).get("files", [])))
        files = (got[0].get("metadata") or {}).get("files", []) if got else []
        check.ok("document: file with its name and size", files and files[0]["size"] == os.path.getsize(media["document"]))
        check.ok("document: md5 on the server == local file", files and download_md5(bob, files[0]["id"]) == md5_file(media["document"]))
        if got:
            before.add(got[0]["id"])

        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="video", paths=dev["video"], caption="%s video %d" % (PROBE, t0))
        got = new_post(lambda p: "video %d" % t0 in p["message"], timeout=90)
        files = (got[0].get("metadata") or {}).get("files", []) if got else []
        check.ok("video: post with a video/mp4 file", files and files[0]["mime_type"].startswith("video/"), str([f["mime_type"] for f in files]))
        if got:
            before.add(got[0]["id"])

        d.broadcast("org.relaychat.DEBUG_SEND_MEDIA", channel=dm_bob["id"], kind="voice", paths=dev["voice"])
        got = new_post(lambda p: "relay_voice" in (p.get("props") or {}))
        voice = (got[0]["props"]["relay_voice"] if got else {})
        files = (got[0].get("metadata") or {}).get("files", []) if got else []
        check.ok("voice: one audio file + props.relay_voice {v, duration, waveform}",
                 got and len(files) == 1 and voice.get("v") == 1 and voice.get("duration", 0) > 0 and voice.get("waveform"),
                 json.dumps(voice)[:120])
        check.ok("voice: Ogg/Opus file named 'Voice message …'", files and files[0]["name"].startswith("Voice message") and files[0]["extension"] == "ogg",
                 files[0]["name"] if files else "")
        time.sleep(2)
        msgs, _ = dump_messages(d, dm_bob["id"])
        shot(d, "voice")

        # 7. Mark as unread from the chat list.
        to_chat_list(d)
        alice.call("POST", "/channels/members/me/view", {"channel_id": sandbox["id"]})
        time.sleep(1)
        # Chat list rows have no accessibility nodes: the row is found by its position (debug dump order).
        dialogs, order = dump_dialogs(d)
        index = order.index(sandbox["id"]) if sandbox["id"] in order else 99
        marked = False
        if index < 7:
            y = 345 + 70 + 141 * index
            d.shell("input swipe 300 %d 300 %d 800" % (y, y))
            time.sleep(1.2)
            more = d.find(desc="More options")
            if more:
                d.tap_node(more)
                item = wait_until(lambda: d.find(text="Mark as unread"), timeout=4)
                if item:
                    d.tap_node(item)
                    marked = True
            if not marked:
                d.key("KEYCODE_BACK")
        mem = wait_until(lambda: (lambda m, c: m if m["msg_count"] < c["total_msg_count"] else None)(
            alice.get("/channels/%s/members/me" % sandbox["id"]), alice.get("/channels/%s" % sandbox["id"])), timeout=8)
        check.ok("mark as unread → unread on the server (set_unread)", marked and mem)
        shot(d, "mark-unread")

        # 8. Offline send.
        open_chat(d, dm_bob["id"])
        d.shell("cmd connectivity airplane-mode enable", check=False)
        wait_until(lambda: "Waiting for network" in screen_text(d) or True, timeout=3)
        time.sleep(3)
        send_text(d, "%s offline send %d" % (PROBE, t0))
        time.sleep(4)
        offline_now = find_post(bob, dm_bob["id"], "offline send %d" % t0)
        check.ok("offline: nothing reaches the server while the network is down", not offline_now)
        shot(d, "offline-pending")
        d.shell("cmd connectivity airplane-mode disable", check=False)
        got = wait_until(lambda: find_post(bob, dm_bob["id"], "offline send %d" % t0), timeout=40, step=1)
        time.sleep(3)
        got = find_post(bob, dm_bob["id"], "offline send %d" % t0)
        check.ok("offline: sent once when the network returns", got and len(got) == 1, "%d copies" % len(got or []))
        msgs, _ = dump_messages(d, dm_bob["id"])
        check.ok("offline: one message in the client, not pending", len(find_msg(msgs, "offline send %d" % t0)) == 1
                 and all(mid > 0 for mid, _ in find_msg(msgs, "offline send %d" % t0)))

        crashes = d.crashes()
        check.ok("no crash", not crashes, "\n".join(crashes[:5]))
    finally:
        d.shell("cmd connectivity airplane-mode disable", check=False)
        if ws:
            ws.close()
        # Clean up: every probe post of alice and bob in the chats used.
        for client in (alice, bob):
            for channel_id in [c for c in (locals().get("dm_bob"), locals().get("sandbox")) if c]:
                try:
                    for p in posts(client, channel_id["id"], 60):
                        names = [f.get("name", "") for f in (p.get("metadata") or {}).get("files", [])]
                        ours = (PROBE in p["message"] or "relay_voice" in (p.get("props") or {})
                                or (dm_post is not None and ("/pl/" + dm_post["id"]) in p["message"])
                                or any(n.startswith(("Photo ", "Voice message ", "relay-e2e-a5-", "clip")) for n in names))
                        if p["user_id"] == client.me["id"] and ours and p["create_at"] >= (t0 - 5) * 1000:
                            client.call("DELETE", "/posts/" + p["id"])
                except Exception as e:  # noqa: BLE001
                    print("cleanup:", e)
        for pid in bob_posts:
            try:
                bob.call("DELETE", "/posts/" + pid)
            except Exception:  # noqa: BLE001
                pass
        d.shell("rm -rf %s" % DEVICE_DIR, check=False)
    for label, value in latencies:
        print("LATENCY %s: %.0f ms" % (label, value * 1000))
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
