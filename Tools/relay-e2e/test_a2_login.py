#!/usr/bin/env python3
"""A2 e2e: boot without Telegram, Mattermost login, chat list, no Telegram traffic.

  python3 tools/relay-e2e/test_a2_login.py [--no-install] [--serial emulator-5554]

Scenarios (each starts from cleared app data):
  1. unreachable server  -> "Can't Connect" alert, still on the server step
  2. wrong password      -> "Incorrect Login" alert, still on the credentials step
  3. login as alice      -> chat list, no "Connecting…", no crash, only localhost sockets,
                            logout from Settings returns to the Relay login screen
  4. debug login hook    -> org.relaychat.DEBUG_LOGIN broadcast lands on the chat list
Screenshots: docs/relay/screens/a2-*.png. Unhandled TL methods: docs/relay/screens/a2-unhandled.txt.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e as r  # noqa: E402

# Telegram DC address ranges (149.154.160.0/20, 91.108.4.0/22 … 91.108.56.0/22, 95.161.64.0/20).
TELEGRAM_PREFIXES = ("149.154.", "91.108.", "95.161.", "91.105.", "185.76.151.")


def shot(d, name):
    path = os.path.join(r.SCREENS, "a2-%s.png" % name)
    d.screenshot(path)
    return path


def fresh_start(d, check):
    d.force_stop()
    d.clear_data()
    for permission in ("POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED"):
        d.shell("pm grant %s android.permission.%s" % (d.package, permission), check=False)
    d.reverse()
    d.logcat_clear()
    d.launch()
    field = d.wait_for(rid="relay_server_field", timeout=30)
    check.ok("fresh install shows the Relay server screen", field is not None)
    return field


def enter_server(d, url):
    field = d.find(rid="relay_server_field")
    d.clear_field(field)
    d.type_text(url)
    d.tap_node(d.find(rid="relay_connect_button"))


def enter_credentials(d, user, password):
    login = d.wait_for(rid="relay_login_field", timeout=20)
    if login is None:
        return False
    d.tap_node(login)
    d.type_text(user)
    d.tap_node(d.find(rid="relay_password_field"))
    d.type_text(password)
    d.tap_node(d.find(rid="relay_login_button"))
    return True


def dismiss_alert(d):
    ok = d.find(text="OK")
    if ok:
        d.tap_node(ok)
        time.sleep(1)


def wait_chat_list(d, timeout=30):
    deadline = time.time() + timeout
    nodes = []
    while time.time() < deadline:
        nodes = d.dump()
        texts = d.screen_texts(nodes)
        if "Chats" in texts and any(t == "Search Chats" for t in texts):
            return nodes
        # System permission prompts (notifications, media) that may still pop up.
        for label in ("Allow all", "Allow", "Don\u2019t allow", "Don't allow"):
            button = d.find(text=label, nodes=nodes)
            if button:
                d.tap_node(button)
                break
        time.sleep(1)
    return None


def scenario_unreachable(d, check):
    fresh_start(d, check)
    enter_server(d, "http://localhost:9")
    alert = d.wait_for(text="Can't Connect", timeout=30)
    shot(d, "error-unreachable")
    check.ok("unreachable server shows \"Can't Connect\"", alert is not None)
    dismiss_alert(d)
    check.ok("still on the server step", d.find(rid="relay_server_field") is not None)


def scenario_not_mattermost(d, check):
    # The device's adb reverse only forwards 8065, so a non-Mattermost HTTP answer comes from the
    # same server under a path that is not a Mattermost site (404 on /api/v4/system/ping).
    enter_server(d, "http://localhost:8065/not-mattermost")
    alert = d.wait_for(text="Not a Mattermost Server", timeout=30)
    shot(d, "error-not-mattermost")
    check.ok("non-Mattermost URL shows \"Not a Mattermost Server\"", alert is not None)
    dismiss_alert(d)


def scenario_wrong_password(d, check, user):
    fresh_start(d, check)
    enter_server(d, r.MM_URL)
    check.ok("server step accepts " + r.MM_URL, enter_credentials(d, user, "definitely-wrong-password"))
    alert = d.wait_for(text="Incorrect Login", timeout=30)
    shot(d, "error-password")
    check.ok("wrong password shows \"Incorrect Login\"", alert is not None)
    dismiss_alert(d)
    check.ok("still on the credentials step", d.find(rid="relay_password_field") is not None)


def check_connected(d, check, label):
    # The title shows "Connecting…"/"Updating…"/"Waiting for network…" while not connected.
    stuck = None
    for _ in range(5):
        texts = d.screen_texts()
        stuck = [t for t in texts if t.startswith("Connecting") or t.startswith("Updating") or t.startswith("Waiting for network")]
        if not stuck:
            break
        time.sleep(2)
    check.ok(label + ": no Connecting/Updating in the title", not stuck, ", ".join(stuck or []))


def check_sockets(d, check):
    conns = d.tcp_connections()
    remote = r.non_local_connections(conns)
    telegram = [c for c in conns if c[0].startswith(TELEGRAM_PREFIXES)]
    check.ok("no socket to a Telegram DC", not telegram, str(telegram))
    check.ok("no socket besides localhost", not remote, str(remote))
    local = [c for c in conns if c[0] in r.LOCAL_ADDRESSES and c[1] == r.MM_PORT]
    print("   sockets: %s" % conns)
    return local


def dump_unhandled(d):
    """The bridge's unhandled-method table of this process (debug builds)."""
    d.broadcast("org.relaychat.DEBUG_DUMP")
    time.sleep(2)
    return d.shell("run-as %s cat files/relay-unhandled-0.txt" % d.package, check=False)


def scenario_login(d, check, user, password):
    fresh_start(d, check)
    shot(d, "server")
    enter_server(d, r.MM_URL)
    if not d.wait_for(rid="relay_login_field", timeout=20):
        check.ok("credentials step appears", False)
        return
    shot(d, "credentials")
    enter_credentials(d, user, password)
    nodes = wait_chat_list(d, timeout=40)
    check.ok("login as %s lands on the chat list" % user, nodes is not None)
    time.sleep(5)
    check_connected(d, check, "chat list")
    shot(d, "chatlist-" + user)
    check.ok("no crash", not d.crashes(), "\n".join(d.crashes()[:5]))
    check.ok("tgnet never initialised", "native_init" not in d.logcat("tgnet"))
    check_sockets(d, check)
    log = d.logcat("RelayBridge")
    check.ok("bridge started", "start account=0" in log)
    first_summary = dump_unhandled(d)

    # Relaunch: the session survives a cold start and the list comes back connected.
    d.force_stop()
    d.logcat_clear()
    d.launch()
    nodes = wait_chat_list(d, timeout=30)
    check.ok("cold start with a session opens the chat list", nodes is not None)
    time.sleep(5)
    check_connected(d, check, "after relaunch")
    check.ok("no crash after relaunch", not d.crashes())

    # Unhandled-method summary (written by the bridge in debug builds).
    summary = dump_unhandled(d)
    out = os.path.join(r.SCREENS, "a2-unhandled.txt")
    with open(out, "w") as f:
        f.write("# Session 1: login → idle chat list\n" + first_summary + "\n# Session 2: cold start with a session → idle chat list\n" + summary)
    check.ok("unhandled-method summary collected", summary.startswith("account=0") and first_summary.startswith("account=0"), out)

    # Logout from Telegram's settings must not crash and returns to the Relay login.
    logout(d, check)


def logout(d, check):
    """Settings tab → ⋮ → Log out → "Log Out" row → confirm "Log out"."""
    settings = d.find(text="Settings")
    if settings is None:
        check.ok("Settings tab found", False)
        return
    d.tap_node(settings)
    more = d.wait_for(desc="More options", timeout=10)
    if more is None:
        check.ok("settings overflow menu found", False)
        return
    d.tap_node(more)
    item = d.wait_for(text="Log out", timeout=10)
    if item is None:
        shot(d, "logout-missing")
        check.ok("Log out menu item found", False)
        return
    d.tap_node(item)
    row = d.wait_for(text="Log Out", timeout=10)
    shot(d, "logout-screen")
    if row is None:
        check.ok("Log Out screen shown", False)
        return
    # The screen title is also "Log Out": the row is the lower one.
    rows = [n for n in d.dump() if n.text == "Log Out"]
    d.tap_node(max(rows, key=lambda n: n.bounds[1]))
    confirm = d.wait_for(text="Log out", timeout=10)
    if confirm is None:
        check.ok("logout confirmation shown", False)
        return
    nodes = [n for n in d.dump() if n.text == "Log out"]
    d.tap_node(max(nodes, key=lambda n: n.bounds[0]))
    field = d.wait_for(rid="relay_server_field", timeout=20)
    shot(d, "after-logout")
    check.ok("logout returns to the Relay login screen", field is not None)
    check.ok("no crash on logout", not d.crashes())
    revoked = d.wait_log(r"RelayAuth.*mattermost session revoked account=0", timeout=15, tags=["RelayAuth"])
    check.ok("Mattermost session revoked on logout", revoked is not None)


def scenario_debug_hook(d, check, user, password):
    fresh_start(d, check)
    d.broadcast("org.relaychat.DEBUG_LOGIN", server=r.MM_URL, user=user, password=password)
    ok = d.wait_log(r"DEBUG_LOGIN (ok|failed)[^\n]*", timeout=30, tags=["RelayDebug"])
    check.ok("DEBUG_LOGIN broadcast logs in", ok is not None and ok.group(1) == "ok", ok.group(0) if ok else "no result")
    nodes = wait_chat_list(d, timeout=30)
    check.ok("debug login lands on the chat list", nodes is not None)
    time.sleep(3)
    check_connected(d, check, "debug login")
    shot(d, "debug-login-" + user)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--user", default="alice")
    ap.add_argument("--only", choices=["unreachable", "password", "login", "hook"])
    args = ap.parse_args()

    d = r.Device(args.serial)
    check = r.Check("A2 login")
    password = r.mm_password()
    if not args.no_install:
        print(d.install().strip().splitlines()[-1])

    if args.only in (None, "unreachable"):
        scenario_unreachable(d, check)
        scenario_not_mattermost(d, check)
    if args.only in (None, "password"):
        # dave: failed logins count towards Mattermost's lockout, keep them off the main test user.
        scenario_wrong_password(d, check, "dave")
    if args.only in (None, "login"):
        scenario_login(d, check, args.user, password)
    if args.only in (None, "hook"):
        scenario_debug_hook(d, check, "bob", password)

    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
