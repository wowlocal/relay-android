#!/usr/bin/env python3
"""Relay for Android, phase A8 e2e test: calls (plugin org.relaychat.calls on LiveKit) with Telegram's call UI.

Run from the repo root (debug APK built, emulator up, local Mattermost on :8065 with the calls plugin and the
dev LiveKit on 127.0.0.1:7880/7881, see ~/Developer/relay-calls-integration/dev-livekit):

    RELAY_SERIAL=emulator-5560 RELAY_APK=… python3 Tools/relay-e2e/test_a8_calls.py [--no-install] [--no-login] [--skip-missed]

dave uses the app; bob is the scripted LiveKit peer (relay-calls-peer.py from the iOS repo, with the
media-probe-venv Python: it publishes a 440 Hz tone and a moving test pattern, and reports what it receives).

  1. Outgoing 1:1 call from the DM header's Call button: Telegram's call screen, the peer answers, the screen shows
     the call established (timer), the peer receives dave's microphone frames; Mute → the peer sees the microphone
     muted, Unmute → unmuted; Start Video → Share Video → the peer receives dave's camera; End Call → the peer's call
     ends (hangup); the call record post is on the server (custom_relay_call, ended, duration) and the chat shows
     Telegram's "Outgoing Call" service message.
  2. Incoming call (bob rings): Telegram's incoming call notification (dumpsys: category call, full-screen intent),
     Answer → established, the peer receives dave's audio, the peer hangs up → the app's call ends; "Incoming Call".
  3. Incoming call declined from the notification → the peer's call ends declined.
  4. Missed: bob rings and nobody answers → after the plugin's 45 s timeout the call ends missed, the notification
     goes away, "Missed Call" (--skip-missed skips it).
  5. Channel call: bob starts a call (with camera) in a channel of dave and bob; the chat's join panel shows it;
     Join → Telegram's video chat with both participants, bob's video tile; the peer sees dave join; Unmute → the
     peer receives dave's microphone; dave's camera (Share Video) and screen share (system consent dialog) reach
     the peer; Leave → the peer sees dave leave.

Every call is ended; screens: docs/relay/screens/a8-*.png.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e  # noqa: E402
from test_a4_live import Rest, open_chat, wait_until  # noqa: E402

CALLS_REPO = os.path.expanduser(os.environ.get("RELAY_CALLS_REPO", "~/Developer/relay-calls-integration/ios-device"))
PEER = os.path.join(CALLS_REPO, "tools", "relay-calls-peer.py")
PEER_PY = os.path.expanduser(os.environ.get("RELAY_PEER_PYTHON", "~/Developer/relay-calls-integration/media-probe-venv/bin/python"))
# The app's user (default dave; RELAY_A8_USER=alice when dave is busy on another device).
APP_USER = os.environ.get("RELAY_A8_USER", "dave")
CHANNEL_NAME = "relay-a8-calls" if APP_USER == "dave" else "relay-a8-calls-" + APP_USER
OUT_DIR = os.environ.get("RELAY_A8_OUT", "/tmp/relay-a8-calls")
# Usernames → Mattermost ids: the peer names remote users by id until it has looked them up.
USER_IDS = {}


def shot(d, name):
    return d.screenshot(os.path.join(relaye2e.SCREENS, "a8-%s.png" % name))


class Peer:
    """relay-calls-peer.py as a subprocess; its JSON lines are collected as they arrive."""

    def __init__(self, name, *args):
        os.makedirs(OUT_DIR, exist_ok=True)
        self.log = os.path.join(OUT_DIR, name + ".jsonl")
        env = dict(os.environ)
        env["RELAY_DEV_ENV"] = relaye2e.ENV_FILE
        env["RELAY_DEV_SERVER"] = "http://127.0.0.1:%d" % relaye2e.MM_PORT
        self.events = []
        self.proc = subprocess.Popen([PEER_PY, PEER] + list(args), stdout=subprocess.PIPE, stderr=open(self.log + ".err", "w"), env=env, text=True)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        with open(self.log, "w") as out:
            for line in self.proc.stdout:
                out.write(line)
                out.flush()
                try:
                    self.events.append(json.loads(line))
                except ValueError:
                    pass

    def find(self, event, **fields):
        def match(e, k, v):
            if k == "user":
                return e.get("user") in (v, USER_IDS.get(v)) or (e.get("identity") or "").split(":")[0] == USER_IDS.get(v)
            return e.get(k) == v
        for e in list(self.events):
            if e.get("event") == event and all(match(e, k, v) for k, v in fields.items()):
                return e
        return None

    def wait(self, event, timeout=30, **fields):
        return wait_until(lambda: self.find(event, **fields), timeout=timeout)

    def remote_stats(self, user=APP_USER):
        """The newest stats entry of a remote user (dict) or None."""
        for e in reversed(list(self.events)):
            if e.get("event") == "stats":
                for r in e.get("remote") or []:
                    if r.get("user") in (user, USER_IDS.get(user)) or (r.get("name") or "").lower().startswith(user):
                        return r
        return None

    def wait_stats(self, pred, timeout=30, user=APP_USER):
        def ok():
            s = self.remote_stats(user)
            return s if s is not None and pred(s) else None
        return wait_until(ok, timeout=timeout, step=1)

    def done(self, timeout=60):
        try:
            self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.terminate()
        self.reader.join(timeout=5)
        return self.find("summary")

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def call_state(d):
    """VoIPService / Relay call state from the DEBUG_CALL_STATE hook (dict)."""
    d.logcat_clear()
    d.shell("am broadcast -a org.relaychat.DEBUG_CALL_STATE -p %s" % d.package, check=False)
    m = d.wait_log(r"DEBUG_CALL_STATE (.*)", timeout=6, tags=["RelayDebug"])
    if not m:
        return {}
    text = m.group(1)
    state = {"raw": text}
    for key in ("state", "mic_muted", "video", "screen", "group", "service", "group_videos"):
        mm = re.search(r"\b%s=(\S+)" % key, text)
        if mm:
            state[key] = mm.group(1)
    return state


def wait_state(d, value, timeout=30):
    return wait_until(lambda: (lambda s: s if s.get("state") == str(value) else None)(call_state(d)), timeout=timeout, step=1)


def wait_no_call(d, timeout=20):
    return wait_until(lambda: (lambda s: s if s.get("service") == "none" else None)(call_state(d)), timeout=timeout, step=1)


def tap_text(d, text, timeout=10, desc=False):
    """Taps a button: Telegram's call buttons carry the label as content description (the text under them is a
    plain label), so the description is preferred."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = d.dump()
        n = d.find(desc=text, nodes=nodes)
        if n is None and not desc:
            n = d.find(text=text, nodes=nodes)
        if n:
            d.tap_node(n)
            return n
        time.sleep(1)
    return None


def share_video(d, timeout=20):
    """Telegram's video preview sheet: waits for it and taps Share Video. A playing preview can keep uiautomator from
    dumping (no idle state), so after the timeout the button's place on this layout is tapped."""
    n = d.wait_for(text="Share Video", timeout=timeout)
    if n:
        d.tap_node(n)
        return True
    w, h = screen_size(d)
    d.tap(w // 2, int(h * 0.857))
    return False


def screen_size(d):
    m = re.findall(r"(\d+)x(\d+)", d.shell("wm size", check=False))
    return (int(m[-1][0]), int(m[-1][1])) if m else (1080, 2400)


def end_call(d, attempts=5):
    """End Call on Telegram's call screen. With video the controls hide after a few seconds and a tap on the video
    brings them back, quicker than a uiautomator dump: the button's place is remembered and tapped right after."""
    place = None
    for _ in range(attempts):
        nodes = d.dump()
        n = d.find(desc="End Call", nodes=nodes) or d.find(text="End Call", nodes=nodes)
        if n:
            place = n.center
            d.tap_node(n)
        elif place:
            d.tap(540, 1200)
            time.sleep(0.6)
            d.tap(*place)
        else:
            d.tap(540, 1200)
            time.sleep(0.5)
            continue
        if wait_no_call(d, 8):
            return True
    return False


def ring_action(d, label, timeout=20):
    """Answer / Decline on Telegram's incoming call notification. The heads-up is a System UI window that
    uiautomator does not dump, so the notification shade is opened and its action button tapped."""
    deadline = time.time() + timeout
    # A dump while the shade still animates sees the home screen (and can leave it there): settle first.
    d.shell("cmd statusbar expand-notifications", check=False)
    last_expand = time.time()
    time.sleep(4)
    while time.time() < deadline:
        nodes = d.dump()
        # The shade's action text carries an icon placeholder and bidi marks around the label.
        n = d.find(text=label, nodes=nodes) or d.find(text=label, contains=True, nodes=nodes)
        if n:
            d.tap_node(n)
            return n
        if time.time() - last_expand > 9:
            d.shell("cmd statusbar expand-notifications", check=False)
            last_expand = time.time()
            time.sleep(4)
        time.sleep(1)
    print("ring_action: no %r in %s" % (label, " | ".join(d.screen_texts())[:300]))
    d.shell("cmd statusbar collapse", check=False)
    return None


def call_notification(d):
    out = d.shell("dumpsys notification --noredact", timeout=60, check=False)
    blocks = out.split("NotificationRecord(")
    for b in blocks:
        if ("pkg=" + d.package) in b and "category=call" in b:
            return b
    return None


def record_posts(client, channel_id, since_ms):
    posts = client.get("/channels/%s/posts?since=%d" % (channel_id, since_ms))
    found = []
    for pid in posts.get("order", []) or list(posts.get("posts", {}).keys()):
        p = posts["posts"][pid]
        if p.get("type") == "custom_relay_call":
            rc = (p.get("props") or {}).get("relay_call") or {}
            if isinstance(rc, str):
                try:
                    rc = json.loads(rc)
                except ValueError:
                    rc = {}
            found.append((p, rc))
    return found


def record_for(client, channel_id, call_id, since_ms):
    for p, rc in record_posts(client, channel_id, since_ms):
        if rc.get("call_id") == call_id:
            return p, rc
    return None, None


def ensure_channel(dave, bob, team_id):
    """A private channel of dave and bob only (keeps calls out of channels other tests use)."""
    try:
        c = bob.get("/teams/%s/channels/name/%s" % (team_id, CHANNEL_NAME))
    except Exception:  # noqa: BLE001
        c = bob.call("POST", "/channels", {"team_id": team_id, "name": CHANNEL_NAME, "display_name": "Relay A8 Calls", "type": "P"})
    try:
        bob.call("POST", "/channels/%s/members" % c["id"], {"user_id": dave.me["id"]})
    except Exception:  # noqa: BLE001
        pass
    return c


def peer_cleanup():
    env = dict(os.environ)
    env["RELAY_DEV_ENV"] = relaye2e.ENV_FILE
    for user in ("bob",):
        subprocess.run([PEER_PY, PEER, "--user", user, "cleanup"], capture_output=True, env=env, timeout=60)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-login", action="store_true")
    ap.add_argument("--skip-missed", action="store_true")
    args = ap.parse_args()

    check = relaye2e.Check("A8 calls")
    d = relaye2e.Device()
    password = relaye2e.mm_password()
    dave = Rest(APP_USER, password)
    bob = Rest("bob", password)
    USER_IDS.update({APP_USER: dave.me["id"], "bob": bob.me["id"]})
    dm = dave.call("POST", "/channels/direct", [dave.me["id"], bob.me["id"]])
    team = dave.get("/users/me/teams")[0]
    channel = ensure_channel(dave, bob, team["id"])
    peers = []
    peer_cleanup()

    try:
        if not args.no_install:
            d.install(os.environ.get("RELAY_APK"))
        for perm in ("RECORD_AUDIO", "CAMERA", "POST_NOTIFICATIONS"):
            d.shell("pm grant %s android.permission.%s" % (d.package, perm), check=False)
        for port in (relaye2e.MM_PORT, 7880, 7881):
            d.adb("reverse", "tcp:%d" % port, "tcp:%d" % port)
        if not args.no_login:
            d.clear_data()
            for perm in ("RECORD_AUDIO", "CAMERA", "POST_NOTIFICATIONS"):
                d.shell("pm grant %s android.permission.%s" % (d.package, perm), check=False)
            d.launch()
            time.sleep(6)
            d.logcat_clear()
            d.broadcast("org.relaychat.DEBUG_LOGIN", server=relaye2e.MM_URL, user=APP_USER, password=password)
            check.ok("login as dave", d.wait_log(r"DEBUG_LOGIN ok", timeout=40, tags=["RelayDebug"]))
            time.sleep(10)
        else:
            d.launch()
            time.sleep(4)

        # MARK: 1. Outgoing 1:1 call
        t1 = int(time.time() * 1000)
        open_chat(d, dm["id"], 5)
        check.ok("DM header has Telegram's Call button (Bob is callable)", d.wait_for(desc="Call", timeout=10) is not None)
        p1 = Peer("outgoing", "--user", "bob", "answer", "--from", APP_USER, "--timeout", "60", "--duration", "120", "--report", "2")
        peers.append(p1)
        time.sleep(3)
        tap_text(d, "Call", desc=True)
        st = wait_state(d, 3, timeout=30)
        check.ok("outgoing: the peer answered (plugin ring → accept)", p1.wait("accepted", timeout=20, status=200) is not None)
        check.ok("outgoing: Telegram's call screen is established (state 3)", st is not None, (call_state(d) or {}).get("raw", "")[:200])
        names = " ".join(d.screen_texts())
        check.ok("outgoing: the call screen shows Bob, the timer and End Call", "Bob Brennan" in names and "End Call" in names and re.search(r"Relay Call, \d+ seconds?", names) is not None, names[:200])
        shot(d, "outgoing-established")
        sub = p1.wait("track_subscribed", timeout=20, source="microphone", user=APP_USER)
        check.ok("outgoing: the peer subscribed to dave's microphone", sub is not None)
        frames = p1.wait_stats(lambda s: (s.get("audio") or {}).get("frames", 0) > 50, timeout=20)
        check.ok("outgoing: dave's audio frames reach the peer", frames is not None, str((frames or {}).get("audio")))
        tap_text(d, "Mute")
        check.ok("outgoing: Mute → the peer sees dave's microphone muted", p1.wait("track_muted", timeout=15, source="microphone", user=APP_USER) is not None)
        check.ok("outgoing: VoIPService reports the microphone muted", call_state(d).get("mic_muted") == "true")
        tap_text(d, "Unmute")
        check.ok("outgoing: Unmute → unmuted again", p1.wait("track_unmuted", timeout=15, source="microphone", user=APP_USER) is not None)
        tap_text(d, "Start Video")
        time.sleep(2)
        shot(d, "video-preview")
        share_video(d)
        cam = p1.wait("track_subscribed", timeout=25, source="camera", user=APP_USER)
        check.ok("outgoing: Share Video → the peer receives dave's camera", cam is not None)
        moving = p1.wait_stats(lambda s: (s.get("camera") or {}).get("frames", 0) > 5, timeout=20)
        check.ok("outgoing: camera frames flow to the peer", moving is not None, str((moving or {}).get("camera")))
        time.sleep(2)
        shot(d, "outgoing-video")
        check.ok("outgoing: End Call", end_call(d))
        summary = p1.done(timeout=30)
        reason = ((summary or {}).get("call") or {}).get("end_reason") or ((summary or {}).get("end") or {}).get("end_reason")
        check.ok("outgoing: the peer's call ended (hangup)", reason == "hangup", str(reason))
        check.ok("outgoing: the app's call is over", wait_no_call(d, 20) is not None)
        call_id = (summary or {}).get("call_id")
        post, rc = wait_until(lambda: (lambda r: r if r[1] and rc_ended(r[1]) else None)(record_for(dave, dm["id"], call_id, t1)), timeout=20) or (None, None)
        check.ok("outgoing: call record post on the server (custom_relay_call, ended, duration)", rc is not None and rc.get("duration_ms", 0) > 0, str(rc))
        msg = d.wait_for(text="Outgoing Call", desc="Outgoing Call", contains=True, timeout=10)
        check.ok("outgoing: the chat shows Telegram's call service message", msg is not None)
        shot(d, "call-messages")

        # MARK: 2. Incoming call, answered
        t2 = int(time.time() * 1000)
        d.key("KEYCODE_HOME")
        time.sleep(1)
        p2 = Peer("incoming", "--user", "bob", "ring", APP_USER, "--timeout", "40", "--duration", "35", "--report", "2")
        peers.append(p2)
        note = wait_until(lambda: call_notification(d), timeout=20, step=1)
        check.ok("incoming: Telegram's incoming call notification (category call)", note is not None)
        check.ok("incoming: the notification has a full-screen intent", note is not None and ("fullScreenIntent" in note or "full_screen" in note.lower() or "fullscreen" in note.lower()), "")
        check.ok("incoming: the caller's device sees it ringing (received)", p2.wait("ws", timeout=10, action="received") is not None or p2.find("call", source="ws:call_updated") is not None)
        time.sleep(1)
        shot(d, "incoming-ring")
        answer = ring_action(d, "Answer")
        check.ok("incoming: Answer on the call notification", answer is not None, " ".join(d.screen_texts())[:200])
        st = wait_state(d, 3, timeout=30)
        check.ok("incoming: answered → established (state 3)", st is not None)
        frames = p2.wait_stats(lambda s: (s.get("audio") or {}).get("frames", 0) > 50, timeout=25)
        check.ok("incoming: dave's audio reaches the caller", frames is not None)
        shot(d, "incoming-established")
        summary = p2.done(timeout=60)
        reason = ((summary or {}).get("end") or {}).get("end_reason") or ((summary or {}).get("call") or {}).get("end_reason")
        check.ok("incoming: the caller hung up (hangup)", reason == "hangup", str(reason))
        check.ok("incoming: the app's call ended with it", wait_no_call(d, 20) is not None)
        _, rc = wait_until(lambda: (lambda r: r if r[1] and rc_ended(r[1]) else None)(record_for(dave, dm["id"], (summary or {}).get("call_id"), t2)), timeout=20) or (None, None)
        check.ok("incoming: call record ended with a duration", rc is not None and rc.get("duration_ms", 0) > 0, str(rc))

        d.shell("cmd statusbar collapse", check=False)

        # MARK: 3. Incoming call, declined
        p3 = Peer("declined", "--user", "bob", "ring", APP_USER, "--timeout", "40", "--report", "5")
        peers.append(p3)
        check.ok("decline: ringing", wait_until(lambda: call_notification(d), timeout=20, step=1) is not None)
        decline = ring_action(d, "Decline")
        check.ok("decline: Decline on the call notification", decline is not None)
        summary = p3.done(timeout=30)
        reason = ((summary or {}).get("end") or {}).get("end_reason") or ((summary or {}).get("call") or {}).get("end_reason")
        check.ok("decline: the caller's call ended declined", reason == "declined", str(reason))
        d.shell("cmd statusbar collapse", check=False)
        check.ok("decline: no call notification left", wait_until(lambda: call_notification(d) is None, timeout=10) is not None)

        # MARK: 4. Missed
        if not args.skip_missed:
            t4 = int(time.time() * 1000)
            p4 = Peer("missed", "--user", "bob", "ring", APP_USER, "--timeout", "75", "--report", "10")
            peers.append(p4)
            check.ok("missed: ringing", wait_until(lambda: call_notification(d), timeout=20, step=1) is not None)
            summary = p4.done(timeout=90)
            reason = ((summary or {}).get("end") or {}).get("end_reason") or ((summary or {}).get("call") or {}).get("end_reason")
            check.ok("missed: the plugin's ring timeout ends the call missed", reason == "missed", str(reason))
            check.ok("missed: the notification went away", wait_until(lambda: call_notification(d) is None, timeout=15) is not None)
            _, rc = wait_until(lambda: (lambda r: r if r[1] and rc_ended(r[1]) else None)(record_for(dave, dm["id"], (summary or {}).get("call_id"), t4)), timeout=20) or (None, None)
            check.ok("missed: call record missed", rc is not None and rc.get("end_reason") == "missed", str(rc))
            d.shell("cmd statusbar collapse", check=False)
            d.launch()
            time.sleep(3)
            open_chat(d, dm["id"], 5)
            shot(d, "missed")
            check.ok("missed: the chat shows Missed Call", d.wait_for(text="Missed Call\nReceived", desc="Missed Call\nReceived", contains=True, timeout=10) is not None,
                     " | ".join(d.screen_texts())[:200])

        # MARK: 5. Channel call (video chat)
        p5 = Peer("channel", "--user", "bob", "channel", CHANNEL_NAME, "--camera", "--duration", "150", "--report", "2")
        peers.append(p5)
        check.ok("channel: bob's call is up", p5.wait("connected", timeout=20) is not None)
        time.sleep(3)
        d.shell("cmd statusbar collapse", check=False)
        d.launch()
        time.sleep(2)
        open_chat(d, channel["id"], 5)
        join = d.wait_for(text="Join", timeout=15)
        check.ok("channel: the chat's join panel shows the video chat", join is not None and d.find(text="Voice Chat") is not None, " ".join(d.screen_texts())[:150])
        shot(d, "join-panel")
        if join:
            d.tap_node(join)
        joined = p5.wait("participant_joined", timeout=25, user=APP_USER)
        check.ok("channel: the peer sees dave join", joined is not None)
        st = wait_state(d, 3, timeout=25)
        check.ok("channel: Telegram's video chat is connected (state 3)", st is not None and st.get("group") == "true", (st or {}).get("raw", "")[:200])
        texts = " ".join(d.screen_texts())
        check.ok("channel: participants list shows bob and dave", "Bob Brennan" in texts and APP_USER.capitalize() in texts, texts[:200])
        videos = wait_until(lambda: (lambda st: st if int(st.get("group_videos", "0") or 0) > 0 else None)(call_state(d)), timeout=15, step=1)
        check.ok("channel: bob's camera tile renders (video output attached)", videos is not None)
        check.ok("channel: bob shows as speaking", d.wait_for(text="speaking", timeout=10) is not None or "speaking" in texts)
        shot(d, "group-joined")
        unmute = d.wait_for(text="Unmute", timeout=5)
        if unmute:
            d.tap_node(unmute)
        mic = p5.wait("track_subscribed", timeout=20, source="microphone", user=APP_USER)
        check.ok("channel: Unmute → the peer receives dave's microphone", mic is not None)
        frames = p5.wait_stats(lambda s: (s.get("audio") or {}).get("frames", 0) > 50, timeout=20)
        check.ok("channel: dave's audio frames flow", frames is not None)
        tap_text(d, "Camera")
        time.sleep(3)
        check.ok("channel: the video preview does not publish the camera yet", p5.find("track_subscribed", source="camera", user=APP_USER) is None)
        share_video(d)
        check.ok("channel: Share Video → the peer receives dave's camera", p5.wait("track_subscribed", timeout=25, source="camera", user=APP_USER) is not None)
        time.sleep(3)
        shot(d, "group-video")
        # Camera off (Telegram's Camera button stops a running camera), then the preview's PHONE SCREEN tab and the
        # system's consent dialog for the screen.
        tap_text(d, "Camera")
        check.ok("channel: Camera again → dave's camera stops for the peer", p5.wait("track_unsubscribed", timeout=15, source="camera", user=APP_USER) is not None
                 or p5.wait("track_muted", timeout=5, source="camera", user=APP_USER) is not None)
        time.sleep(2)
        tap_text(d, "Camera")
        time.sleep(2)
        tabs = d.wait_for(text="PHONE SCREEN", timeout=8)
        screen_ok = False
        if tabs:
            d.tap_node(tabs)
            time.sleep(2)
            share_video(d, timeout=8)
            spinner = wait_until(lambda: next((n for n in d.dump() if n.resource_id.endswith("screen_share_mode_spinner")), None), timeout=10)
            if spinner:
                d.tap_node(spinner)
                time.sleep(1)
                tap_text(d, "Entire screen", timeout=5)
                time.sleep(1)
            tap_text(d, "Start", timeout=8)
            screen_ok = p5.wait("track_subscribed", timeout=25, source="screen", user=APP_USER) is not None
        check.ok("channel: screen share → the peer receives dave's screen", screen_ok)
        if screen_ok:
            check.ok("channel: screen frames flow", p5.wait_stats(lambda s: (s.get("screen") or {}).get("frames", 0) > 3, timeout=20) is not None)
        time.sleep(2)
        leave = wait_until(lambda: d.find(text="Leave"), timeout=10)
        if leave:
            d.tap_node(leave)
        check.ok("channel: Leave → the peer sees dave leave", p5.wait("participant_left", timeout=20, user=APP_USER) is not None)
        check.ok("channel: the app left the video chat", wait_no_call(d, 20) is not None)
        p5.stop()

        crashes = d.crashes()
        check.ok("no crash of the app", not crashes, "; ".join(crashes[:3]))
    finally:
        for p in peers:
            p.stop()
        peer_cleanup()
        try:
            call_state(d)
        except Exception:  # noqa: BLE001
            pass
    print(check.summary())
    sys.exit(0 if check.passed else 1)


def rc_ended(rc):
    return isinstance(rc, dict) and rc.get("state") == "ended"


if __name__ == "__main__":
    main()
