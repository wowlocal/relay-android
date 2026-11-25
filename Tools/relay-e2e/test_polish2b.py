#!/usr/bin/env python3
"""Relay for Android, polish 2b e2e test (UI, languages, branding) against the local Mattermost.

    RELAY_SERIAL=emulator-5556 RELAY_APK=<apk> python3 Tools/relay-e2e/test_polish2b.py [--no-install] [--user carol]

carol (default) uses the app; bob acts over REST.

  1. Login screen: the controls have readable labels ("Server address", "Connect") and stable resource ids.
  2. Languages (docs/relay/LOCALIZATION.md): English is the only language. Settings has no Language row and Settings
     search finds none. The app is switched to each language Android could ask for (per-app locale de, ar, ru; a
     `language=de` left in the app's settings by an older build) and back; every time the chat list, a chat,
     Settings and the attach menu are fully English (no German/Arabic/Russian words, Latin digits, left-to-right),
     the All Chats tab keeps its unread counter, and a photo preview says "Photo".
  3. Telegram leftovers: no "Telegram" on the screens walked (chat list, search, chat, menus, attach menu, file
     picker, photo viewer menu, pin dialog, Chat Settings, Notifications, Data and Storage, Storage Usage, Devices,
     Power Saving, Log Out screen, passcode intro and lock screen); "Unlock to use Relay"; the photo
     viewer has no Create Sticker; the composer has no AI button with long text; the pin dialog has no
     "Notify all members"; the gallery permission card quotes the server's MaxFileSize, not "2 GB".
  4. Saved photo path: Save to gallery writes to Pictures/Relay (not Pictures/Telegram).
  5. DM message menu: Pin (→ REST is_pinned), Copy Link (Mattermost permalink on the clipboard), Select (→ selection).
  6. Reactions are all read out (accessibility text names 👍 and ❤️).
  7. Chat-list search hides the Threads button and its badge.

Probe posts start with "[relay-e2e polish2b]" and are deleted; the pin, passcode, per-app locale, permissions and
the saved file are undone. Screens: docs/relay/screens/polish2b-*.png.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relaye2e as r  # noqa: E402
import test_a6_hygiene as a6  # noqa: E402
from test_a4_live import Rest, REST_URL, dump_dialogs, open_chat, wait_until  # noqa: E402

PROBE = "[relay-e2e polish2b]"
PKG = r.PACKAGE

# Words that must not show once the app is (supposedly) in another language: Telegram's bundled translations of
# the chat list, Settings, composer and media previews.
FOREIGN = {
    "de": ["Alle Chats", "Chats durchsuchen", "Einstellungen", "Nachricht", "Bild", "Du:", "Kontakte", "Profil",
           "Galerie", "Datei", "Abbrechen", "Benachrichtigungen"],
    "ar": ["الدردشات", "الإعدادات", "رسالة", "صورة", "جهات الاتصال", "بحث", "إلغاء"],
    "ru": ["Все чаты", "Настройки", "Сообщение", "Фото", "Контакты", "Поиск", "Отмена", "Вы:"],
}
ARABIC_DIGITS = re.compile(r"[٠-٩]")
TELEGRAM = re.compile(r"telegram", re.I)


def shot(d, name):
    return d.screenshot(os.path.join(r.SCREENS, "polish2b-%s.png" % name))


def back(d, times=1):
    a6.back(d, times)


def texts_of(d, nodes=None):
    return d.screen_texts(nodes if nodes is not None else d.dump())


def telegram_hits(texts):
    return [t[:80] for t in texts if TELEGRAM.search(t)]


def check_no_telegram(d, check, name, nodes=None, take_shot=True):
    nodes = nodes if nodes is not None else d.dump()
    texts = d.screen_texts(nodes)
    if take_shot:
        shot(d, name)
    hits = telegram_hits(texts)
    check.ok("%s: no \"Telegram\"" % name, texts and not hits, "; ".join(hits) if hits else "%d texts" % len(texts))
    return nodes, texts


def to_tab(d, label):
    """Taps a bottom tab (Chats / Contacts / Settings / Profile)."""
    w, h = a6.screen_size(d)
    for _ in range(4):
        nodes = d.dump()
        tabs = [n for n in nodes if (n.text == label or n.desc == label or n.desc.startswith(label + "\n")) and n.bounds[1] > h * 0.85]
        if tabs:
            d.tap_node(tabs[0])
            time.sleep(1.5)
            return True
        back(d)
    return False


def all_chats_tab(d, nodes=None):
    nodes = nodes if nodes is not None else d.dump()
    for n in nodes:
        label = n.desc or n.text
        if label.startswith("All Chats"):
            return label
    return None


def unread_counter(label):
    m = re.search(r"(\d+) unread", label or "")
    return int(m.group(1)) if m else 0


def ime_off(d):
    d.hide_keyboard()
    time.sleep(0.5)


def message_cell(d, contains):
    nodes = [n for n in d.dump() if contains in n.text or contains in n.desc]
    return nodes[-1] if nodes else None


def tap_message(d, contains):
    node = message_cell(d, contains)
    if node is None:
        return None
    x1, y1, x2, y2 = node.bounds
    d.tap(x1 + (x2 - x1) // 3, (y1 + y2) // 2)
    time.sleep(1.5)
    return node


def menu_items(d):
    return [t for t in texts_of(d)]


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


def make_photo(tmp):
    path = os.path.join(tmp, "polish2b-photo.jpg")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=800x600:rate=1", "-frames:v", "1", "-q:v", "3", path],
                   check=True, stdin=subprocess.DEVNULL)
    return path


# MARK: - 1. login screen

def scenario_login_labels(d, check):
    d.force_stop()
    d.clear_data()
    d.reverse()
    d.launch()
    field = d.wait_for(rid="relay_server_field", timeout=40)
    nodes = d.dump()
    button = d.find(rid="relay_connect_button", nodes=nodes)
    shot(d, "login")
    check.ok("login: server field has resource id and the label \"Server address\"", field is not None and field.desc == "Server address",
             repr(field.desc if field else None))
    check.ok("login: Connect button reads \"Connect\"", button is not None and button.desc == "Connect", repr(button.desc if button else None))
    check.ok("login: no resource names as labels", not any(t.startswith("relay_") for t in d.screen_texts(nodes)), str(d.screen_texts(nodes)[:10]))


def login(d, check, user, password):
    for permission in ("POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "CAMERA", "RECORD_AUDIO"):
        d.shell("pm grant %s android.permission.%s" % (PKG, permission), check=False)
    d.logcat_clear()
    d.broadcast("org.relaychat.DEBUG_LOGIN", server=r.MM_URL, user=user, password=password)
    ok = d.wait_log(r"DEBUG_LOGIN (ok|failed)[^\n]*", timeout=40, tags=["RelayDebug"])
    check.ok("debug login as %s" % user, ok is not None and ok.group(1) == "ok", ok.group(0) if ok else "no result")
    nodes = a6.wait_chat_list(d)
    time.sleep(4)
    return nodes


def relaunch(d):
    d.force_stop()
    d.launch()
    nodes = a6.wait_chat_list(d)
    time.sleep(3)
    return nodes


# MARK: - 2. languages

def settings_texts(d):
    if not to_tab(d, "Settings"):
        return []
    time.sleep(1.5)
    texts = texts_of(d)
    a6.scroll_down(d)
    texts += texts_of(d)
    a6.scroll_down(d)
    texts += texts_of(d)
    a6.swipe(d, up=False)
    a6.swipe(d, up=False)
    a6.swipe(d, up=False)
    return texts


def scenario_language_settings(d, check):
    texts = settings_texts(d)
    shot(d, "settings")
    check.ok("Settings has no Language row", texts and "Language" not in texts and not any(t.startswith("Language\n") for t in texts),
             str([t for t in texts if "anguage" in t]))
    if a6.tap_desc(d, "Search"):
        field = [n for n in d.dump() if n.cls.endswith("EditText")]
        if field:
            d.tap_node(field[0])
        d.type_text("language")
        time.sleep(1.5)
        nodes = d.dump()
        results = [t for t in d.screen_texts(nodes) if t.lower() != "language"]
        shot(d, "settings-search-language")
        check.ok("Settings search 'language' finds no Language screen", not any("Language" in t or "Translat" in t for t in results), str(results[:10]))
        ime_off(d)
        back(d, 2)
    to_tab(d, "Chats")


def walk_english(d, check, tag, channel_id, photo_dm):
    """Chat list, Settings, a chat with the composer and its attach menu: English only, LTR, the All Chats counter."""
    foreign = [w for words in FOREIGN.values() for w in words]
    collected = []
    keep = not tag.endswith("-back")  # screenshots: each language once
    keep_all = tag in ("de-DE", "ar")
    to_tab(d, "Chats")
    time.sleep(2)
    nodes = d.dump()
    chatlist = d.screen_texts(nodes)
    collected += chatlist
    if keep:
        shot(d, "lang-%s-chatlist" % tag)
    tab = all_chats_tab(d, nodes)
    check.ok("[%s] chat list in English (All Chats, Search Chats, Settings tab)" % tag,
             tab is not None and "Search Chats" in chatlist and any(t == "Settings" or t.startswith("Settings") for t in chatlist), str(chatlist[:12]))
    check.ok("[%s] All Chats tab keeps its unread counter" % tag, unread_counter(tab) > 0, repr(tab))
    search = d.find(text="Search Chats", nodes=nodes)
    w, _ = a6.screen_size(d)
    check.ok("[%s] left-to-right layout (search hint starts on the left)" % tag, search is not None and search.bounds[0] < w * 0.4,
             str(search.bounds if search else None))
    # The DM's preview (its newest post is bob's photo) is worded in English.
    dialogs, _ = dump_dialogs(d)
    preview = dialogs.get(channel_id, {}).get("preview", "")
    check.ok("[%s] photo preview says Photo" % tag, preview.strip() == "Photo", repr(preview))

    settings = settings_texts(d)
    collected += settings
    if keep_all:
        shot(d, "lang-%s-settings" % tag)
    check.ok("[%s] Settings in English" % tag, "Chat Settings" in settings and "Data and Storage" in settings and "Log Out" in settings,
             str(settings[:12]))
    to_tab(d, "Chats")

    open_chat(d, channel_id, wait=3)
    chat = texts_of(d)
    collected += chat
    if keep_all:
        shot(d, "lang-%s-chat" % tag)
    check.ok("[%s] composer hint is \"Message\"" % tag, "Message" in chat, str(chat[-8:]))
    if a6.tap_desc(d, "Attach media"):
        time.sleep(1.5)
        attach = texts_of(d)
        collected += attach
        if keep_all:
            shot(d, "lang-%s-attach" % tag)
        check.ok("[%s] attach menu in English (Gallery, File)" % tag, "File" in attach and any(t in attach for t in ("Gallery", "Photo")), str(attach[:12]))
        back(d)
    back(d)
    hits = sorted({w for w in foreign for t in collected if re.search(r"(?<!\w)%s(?!\w)" % re.escape(w), t)})
    check.ok("[%s] no foreign words on the walked screens" % tag, not hits, str(hits))
    digits = [t for t in collected if ARABIC_DIGITS.search(t)]
    check.ok("[%s] Latin digits only" % tag, not digits, str(digits[:5]))
    return collected


def set_app_locale(d, locale):
    d.shell("cmd locale set-app-locales %s --locales %s" % (PKG, locale if locale else "''"), check=False)
    time.sleep(2)


def app_locale(d):
    """The app's per-app locales, e.g. "de-DE" ("" when it follows the device)."""
    out = d.shell("cmd locale get-app-locales %s" % PKG, check=False)
    m = re.search(r"are \[(.*)\]", out)
    return m.group(1).strip() if m else out.strip()


def write_language_pref(d, lang):
    """An older build's Settings → Language choice: mainconfig.xml language=<lang> (debuggable app, run-as)."""
    d.force_stop()
    path = "shared_prefs/mainconfig.xml"
    xml = d.adb("exec-out", "run-as", PKG, "cat", path, check=False)
    if "<map" not in xml:
        return False
    xml = re.sub(r'\s*<string name="language">[^<]*</string>', "", xml)
    xml = xml.replace("<map>", '<map>\n    <string name="language">%s</string>' % lang, 1)
    tmp = "/data/local/tmp/relay-p2b-mainconfig.xml"
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
        local = f.name
    d.adb("push", local, tmp)
    os.unlink(local)
    d.shell("chmod 644 %s" % tmp, check=False)
    d.shell("run-as %s cp %s %s" % (PKG, tmp, path), check=False)
    d.shell("rm %s" % tmp, check=False)
    return lang in d.adb("exec-out", "run-as", PKG, "cat", path, check=False)


def scenario_languages(d, check, channel_id, photo_dm):
    scenario_language_settings(d, check)
    walk_english(d, check, "en", channel_id, photo_dm)
    for locale in ("de-DE", "ar", "ru-RU"):
        to_tab(d, "Chats")
        set_app_locale(d, locale)  # while the app is open: Android recreates it, Telegram rebuilds every screen
        check.ok("per-app locale %s set" % locale, locale.split("-")[0] in app_locale(d), app_locale(d))
        time.sleep(3)
        nodes = d.dump()
        live = d.screen_texts(nodes)
        shot(d, "lang-%s-live" % locale)
        check.ok("[%s live] chat list stays English with the All Chats counter" % locale,
                 "Search Chats" in live and unread_counter(all_chats_tab(d, nodes)) > 0, str(live[:8]))
        relaunch(d)
        walk_english(d, check, locale, channel_id, photo_dm)
        set_app_locale(d, "")
        relaunch(d)
        walk_english(d, check, "%s-back" % locale, channel_id, photo_dm)
    check.ok("per-app locale cleared", app_locale(d) == "", repr(app_locale(d)))
    # A language chosen in an older build (mainconfig.xml) is ignored.
    check.ok("language=de written to the app settings", write_language_pref(d, "de"))
    relaunch(d)
    walk_english(d, check, "pref-de", channel_id, photo_dm)


# MARK: - 3–7. leftovers and menus

def scenario_chat_list_search(d, check):
    to_tab(d, "Chats")
    before = [n for n in d.dump() if n.desc.startswith("Threads")]
    search = d.wait_for(text="Search Chats", timeout=10)
    if search is None:
        check.ok("chat-list search opens", False)
        return
    d.tap_node(search)
    time.sleep(1.5)
    nodes = d.dump()
    shot(d, "search")
    threads = [n for n in nodes if n.desc.startswith("Threads")]
    check.ok("chat-list search hides the Threads button (and its badge)", before and not threads, "before %d, during %d" % (len(before), len(threads)))
    check_no_telegram(d, check, "search", nodes, take_shot=False)
    ime_off(d)
    back(d)


def scenario_dm(d, check, bob, carol, dm, posts):
    open_chat(d, dm["id"], wait=4)
    nodes, texts = check_no_telegram(d, check, "dm")
    # Composer: long text, no AI button.
    composer = [n for n in d.dump() if n.cls.endswith("EditText")]
    if composer:
        d.tap_node(composer[0])
        d.type_text("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
                    "seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour twentyfive twentysix")
        time.sleep(2)
        nodes = d.dump()
        shot(d, "composer-long")
        check.ok("composer: no AI Editor button with long text", not d.find(desc="AI Editor", nodes=nodes), "")
        check.ok("composer: no AI hint", not any("using AI" in t for t in d.screen_texts(nodes)))
        composer = [n for n in nodes if n.cls.endswith("EditText")]
        d.clear_field(composer[0], 260)
        ime_off(d)

    # Reactions read out.
    cell = message_cell(d, "reactions probe")
    desc = (cell.desc or cell.text) if cell else ""
    check.ok("reactions: accessibility text names 👍 and ❤️", "👍" in desc and "❤" in desc, repr(desc[-80:]))

    # Message menu: Pin, Copy Link, Select.
    if tap_message(d, "menu probe") is None:
        check.ok("DM probe message shown", False)
        return
    items = menu_items(d)
    shot(d, "dm-menu")
    check.ok("DM menu has Pin, Copy Link and Select", all(i in items for i in ("Pin", "Copy Link", "Select")), str(items[:16]))
    check_no_telegram(d, check, "dm-menu", take_shot=False)
    if a6.tap_text(d, "Copy Link"):
        time.sleep(2)
        log = d.logcat("RelayDmLinks")
        link = re.findall(r"copied (\S+)", log)
        want = "/pl/" + posts["menu"]["id"]
        check.ok("Copy Link copies the Mattermost permalink", link and link[-1].endswith(want) and link[-1].startswith(r.MM_URL), str(link[-1:]))
    if tap_message(d, "menu probe") and a6.tap_text(d, "Pin"):
        time.sleep(1.5)
        texts = texts_of(d)
        shot(d, "pin-dialog")
        check.ok("pin dialog: no \"Notify all members\" / \"Pin also for\" choice", not any("Notify all" in t or "also for" in t for t in texts), str(texts))
        if a6.tap_text(d, "Pin"):
            pinned = wait_until(lambda: carol.get("/posts/%s" % posts["menu"]["id"]).get("is_pinned"), timeout=15)
            check.ok("Pin in a DM → REST is_pinned", bool(pinned))
            carol.call("POST", "/posts/%s/unpin" % posts["menu"]["id"])
    time.sleep(2)
    if tap_message(d, "menu probe") and a6.tap_text(d, "Select"):
        time.sleep(1.5)
        texts = texts_of(d)
        shot(d, "select")
        # Selection mode: the bottom bar's Reply / Forward buttons, the menu gone.
        check.ok("Select starts the selection (Reply / Forward bar)", "Reply" in texts and "Forward" in texts and "Copy Link" not in texts, str(texts[-6:]))
        back(d)
        time.sleep(1)

    # Photo viewer: no Create Sticker; Save to gallery → Pictures/Relay.
    photo = []
    w, h = a6.screen_size(d)
    for attempt in range(4):
        photo = [n for n in d.dump() if (n.text or n.desc).startswith("Photo\n")]
        if photo:
            break
        # Telegram's message list publishes its accessibility nodes lazily; reopening the chat refreshes them.
        open_chat(d, dm["id"], wait=3)
    if not photo:
        check.ok("bob's photo shown in the DM", False, str(texts_of(d)[-10:]))
        return
    d.shell("rm -rf /sdcard/Pictures/Relay /sdcard/Pictures/Telegram", check=False)
    d.tap_node(photo[-1])
    time.sleep(2.5)
    if a6.tap_desc(d, "More options", pick=lambda ns: max(ns, key=lambda n: n.bounds[0])):
        nodes, texts = check_no_telegram(d, check, "viewer-menu")
        check.ok("photo viewer menu has no Create Sticker", "Create Sticker" not in texts and "Save to gallery" in texts, str(texts))
        if a6.tap_text(d, "Save to gallery"):
            time.sleep(3)
            relay = d.shell("ls /sdcard/Pictures/Relay 2>/dev/null", check=False).split()
            tg = d.shell("ls /sdcard/Pictures/Telegram 2>/dev/null", check=False).split()
            check.ok("Save to gallery writes to Pictures/Relay", relay and not tg, "Relay=%s Telegram=%s" % (relay, tg))
            d.shell("rm -rf /sdcard/Pictures/Relay", check=False)
    back(d, 2)


def scenario_attach(d, check, carol, dm):
    open_chat(d, dm["id"], wait=3)
    if not a6.tap_desc(d, "Attach media"):
        check.ok("attach menu opens", False)
        return
    time.sleep(1.5)
    check_no_telegram(d, check, "attach")
    if a6.tap_text(d, "File"):
        time.sleep(2)
        nodes, texts = check_no_telegram(d, check, "file-picker")
        back(d)
    back(d, 2)


def scenario_gallery_card(d, check, carol, dm):
    """Without photo access the attach menu shows the permission card, which quotes the server's MaxFileSize."""
    limit = int(carol.get("/config/client?format=old").get("MaxFileSize", "0"))
    for permission in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED"):
        d.shell("pm revoke %s android.permission.%s" % (PKG, permission), check=False)
    relaunch(d)
    open_chat(d, dm["id"], wait=3)
    texts = []
    if a6.tap_desc(d, "Attach media"):
        time.sleep(2.5)
        for _ in range(4):
            texts = texts_of(d)
            if any("Share" in t for t in texts):
                break
            time.sleep(1)
        shot(d, "gallery-card")
        # A system permission prompt may cover it; dismiss and re-read.
        for label in ("Don’t allow", "Don't allow"):
            deny = d.find(text=label)
            if deny:
                d.tap_node(deny)
                time.sleep(1.5)
                texts = texts_of(d)
        card = [t for t in texts if t.startswith("Share")]
        quoted = [float(m.group(1)) * (1024 if m.group(2) == "GB" else 1) for t in card for m in [re.search(r"([\d.]+) (MB|GB)", t)] if m]
        want_mb = limit / 1024.0 / 1024.0
        check.ok("gallery card quotes the server's MaxFileSize (%.0f MB), not 2 GB" % want_mb,
                 card and not any("2 GB" in t for t in texts) and (not limit or any(abs(q - want_mb) < 0.5 for q in quoted)), str(card))
        back(d, 2)
    for permission in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED"):
        d.shell("pm grant %s android.permission.%s" % (PKG, permission), check=False)
    relaunch(d)


def scenario_logout_screen(d, check):
    if not to_tab(d, "Settings"):
        return
    for _ in range(3):
        a6.scroll_down(d)
    # The Log Out row asks at once; Telegram's Log Out screen (alternatives) is in the ⋮ menu.
    a6.swipe(d, up=False)
    a6.swipe(d, up=False)
    a6.swipe(d, up=False)
    if not a6.tap_desc(d, "More options", pick=lambda ns: max(ns, key=lambda n: n.bounds[0])) or a6.tap_text(d, "Log out") is None:
        check.ok("Log Out screen opens", False)
        return
    time.sleep(1.5)
    nodes, texts = check_no_telegram(d, check, "logout")
    check.ok("Log Out alternatives: Mattermost wording, no cloud", any("Mattermost account" in t for t in texts) and not any("cloud" in t for t in texts),
             str(texts))
    back(d)  # never logs out


def scenario_settings_sweep(d, check):
    """Every Settings screen Relay keeps (and Storage Usage below Data and Storage): no "Telegram", scrolled."""
    for row, sub in (("Chat Settings", None), ("Notifications", None), ("Data and Storage", "Storage Usage"),
                     ("Devices", None), ("Power Saving", None)):
        if not to_tab(d, "Settings"):
            return
        for _ in range(2):
            if d.find(text=row):
                break
            a6.scroll_down(d)
        if a6.tap_text(d, row) is None:
            check.ok("Settings → %s opens" % row, False)
            continue
        time.sleep(2)
        name = "sweep-" + row.lower().replace(" ", "-")
        texts = texts_of(d)
        for _ in range(3):
            a6.scroll_down(d)
            texts += texts_of(d)
        hits = telegram_hits(texts)
        check.ok("%s: no \"Telegram\" (whole screen)" % row, texts and not hits, "; ".join(hits) if hits else "%d texts" % len(texts))
        if sub:
            for _ in range(3):
                a6.swipe(d, up=False)
            if a6.tap_text(d, sub):
                time.sleep(4)
                texts = texts_of(d)
                shot(d, name + "-" + sub.lower().replace(" ", "-"))
                a6.scroll_down(d)
                texts += texts_of(d)
                hits = telegram_hits(texts)
                check.ok("%s: no \"Telegram\"" % sub, texts and not hits, "; ".join(hits) if hits else "%d texts" % len(texts))
                back(d)
        back(d)


def unlock_pin(d, pin):
    """Types the PIN on the lock screen's own keypad (not the system keyboard)."""
    for digit in pin:
        keys = [n for n in d.dump() if n.text == digit]
        if keys:
            d.tap_node(keys[0])
            time.sleep(0.4)


def scenario_passcode(d, check):
    """Turns a passcode on, locks the app, reads the lock screen, unlocks and turns the passcode off again."""
    if not to_tab(d, "Settings"):
        return
    a6.scroll_down(d)
    if a6.tap_text(d, "Passcode Lock") is None:
        check.ok("Passcode Lock opens", False)
        return
    time.sleep(1.5)
    nodes, texts = check_no_telegram(d, check, "passcode-intro")
    check.ok("passcode intro spells \"appear\"", not any("apear" in t for t in texts), str([t for t in texts if "lock icon" in t]))
    if a6.tap_text(d, "Enable Passcode") is None and a6.tap_text(d, "Enable passcode") is None:
        check.ok("passcode can be enabled", False, str(texts))
        back(d)
        return
    time.sleep(1.5)
    for _ in range(2):
        d.type_text("1357")
        time.sleep(1.5)
    time.sleep(1.5)
    back(d, 2)
    to_tab(d, "Chats")
    lock = d.wait_for(desc="Lock application", timeout=8)
    if lock:
        d.tap_node(lock)
        time.sleep(2)
    else:
        d.force_stop()
        d.launch()
        time.sleep(4)
    nodes, texts = check_no_telegram(d, check, "locked")
    check.ok("lock screen says \"Unlock to use Relay\"", any("Unlock to use Relay" in t for t in texts) or any("Relay Locked" in t for t in texts),
             str(texts[:10]))
    unlock_pin(d, "1357")
    time.sleep(3)
    # Turn it off again.
    if to_tab(d, "Settings"):
        a6.scroll_down(d)
        if a6.tap_text(d, "Passcode Lock"):
            time.sleep(1.5)
            if d.find(text="Turn Passcode Off") is None:
                d.type_text("1357")  # "Enter your passcode" (system keyboard)
                time.sleep(2)
            if a6.tap_text(d, "Turn Passcode Off"):
                a6.tap_text(d, "Turn Off")
            time.sleep(1.5)
            back(d)
    off = d.shell("run-as %s cat shared_prefs/userconfing.xml" % PKG, check=False)
    passcode_left = 'name="passcodeHash1"' in off and re.search(r'name="passcodeHash1">[^<]+<', off)
    check.ok("passcode turned off again", not passcode_left)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--user", default="carol")
    ap.add_argument("--skip-languages", action="store_true")
    ap.add_argument("--only", help="comma list of: search,languages,dm,attach,gallery,sweep,logout,passcode (login always runs)")
    args = ap.parse_args()
    d = r.Device(args.serial)
    check = r.Check("polish2b")
    password = r.mm_password()
    me = Rest(args.user, password)
    bob = Rest("bob", password)
    if not args.no_install:
        print(d.install())

    dm = bob.call("POST", "/channels/direct", [bob.me["id"], me.me["id"]])
    tmp = tempfile.mkdtemp(prefix="relay-p2b-")
    posts = {}
    created = []
    try:
        info = upload(bob, dm["id"], make_photo(tmp), "image/jpeg")
        posts["photo"] = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "", "file_ids": [info["id"]]})
        posts["reactions"] = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": PROBE + " reactions probe"})
        for emoji in ("+1", "heart"):
            bob.call("POST", "/reactions", {"user_id": bob.me["id"], "post_id": posts["reactions"]["id"], "emoji_name": emoji})
        posts["menu"] = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": PROBE + " menu probe"})
        created += [p["id"] for p in posts.values()]
        photo_dm = posts["photo"]
        # The newest post is a photo, so the chat list previews the DM as "Photo".
        posts["photo2"] = bob.call("POST", "/posts", {"channel_id": dm["id"], "message": "", "file_ids": [upload(bob, dm["id"], make_photo(tmp), "image/jpeg")["id"]]})
        created.append(posts["photo2"]["id"])

        scenario_login_labels(d, check)
        if login(d, check, args.user, password) is None:
            check.ok("chat list shown", False)
            return
        check_no_telegram(d, check, "chatlist")
        only = set(args.only.split(",")) if args.only else None

        def want(name):
            return only is None or name in only
        if want("search"):
            scenario_chat_list_search(d, check)
        if want("languages") and not args.skip_languages:
            scenario_languages(d, check, dm["id"], photo_dm)
        if want("dm"):
            scenario_dm(d, check, bob, me, dm, posts)
        if want("attach"):
            scenario_attach(d, check, me, dm)
        if want("gallery"):
            scenario_gallery_card(d, check, me, dm)
        if want("sweep"):
            scenario_settings_sweep(d, check)
        if want("logout"):
            scenario_logout_screen(d, check)
        if want("passcode"):
            scenario_passcode(d, check)
        crashes = d.crashes()
        check.ok("no crashes", not crashes, "\n".join(crashes[:5]))
    finally:
        set_app_locale(d, "")
        for permission in ("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO"):
            d.shell("pm grant %s android.permission.%s" % (PKG, permission), check=False)
        d.shell("rm -rf /sdcard/Pictures/Relay", check=False)
        for post_id in created:
            try:
                bob.call("DELETE", "/posts/%s" % post_id)
            except Exception as e:  # noqa: BLE001
                print("cleanup %s: %s" % (post_id, e))
    print(check.summary())
    sys.exit(0 if check.passed else 1)


if __name__ == "__main__":
    main()
