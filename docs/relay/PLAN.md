# Relay for Android — plan

Relay for Android is a hard fork of Telegram-Android (base: `upstream-13.2.2-7303`,
commit `21f7d10`, 2026-11-12) that talks to a **Mattermost** server instead of
Telegram, like Relay for iOS (`~/Developer/tg-mattermost-client`) and macOS
(`~/Developer/relay-mac`). Same product rules: keep Telegram's UI and UX, hide
Telegram-only features, back everything visible with Mattermost.

## Architecture: an in-process "TL bridge"

Telegram-Android sends every network call through one method:
`ConnectionsManager.sendRequestInternal(TLObject, RequestDelegate, …)`, and gets
server pushes through `MessagesController.processUpdates(TLRPC.Updates)`.
The screens and controllers (MessagesController, MessagesStorage,
SendMessagesHelper, FileLoader) only see TL objects.

So Relay keeps all of that and puts a **Mattermost bridge behind the request API**:

```
Screens (ui/*)  ──  MessagesController / MessagesStorage / SendMessagesHelper / FileLoader
                         │ sendRequest(TL method)            ▲ processUpdates(TL updates)
                         ▼                                    │
               org.telegram.relay.bridge.RelayBridge  (Java, in process)
               • one handler per TL method (messages.getDialogs, getHistory, sendMessage,
                 upload.getFile, saveFilePart, users.getFullUser, …)
               • unknown/hidden methods → empty result or TL_error, logged
               • ID map (SQLite): Mattermost string ids ↔ TL long ids / int message ids
               • local pts/update log → updates.getState / getDifference for catch-up
               • WebSocket events → TL updates → processUpdates
                         │
               relay-mattermost (pure Java library, JVM-tested)
               REST v4 · WebSocket · models · auth   (HttpURLConnection, nv-websocket-client, gson)
```

Native tgnet stays compiled but never connects in Mattermost mode (disconnect first,
delete later — same rule as iOS).

This differs from iOS (which wrote into Postbox) because Android's controllers are
built around server responses, not a local DB API. Emulating the server at the request
boundary means the 25k-line controllers stay nearly untouched.

## Domain mapping (from iOS `docs/plan/02-domain-mapping.md`)

| Mattermost | Telegram (TL) |
|---|---|
| user | `TL_user` (id = mapped long) |
| DM channel | private chat with the other user (`peerUser`) |
| GM, public/private channel | supergroup (`TL_channel`, megagroup) |
| team | chat-list folder (dialog filter) + "All" |
| post | `TL_message`; id = sparse allocation per dialog (STEP 16, start 2^30) |
| reply thread | `reply_to` + comments/replies (CRT) |
| reaction | `messageReactions` |
| file | `TL_messageMediaPhoto` / `Document`; bytes through `upload.getFile` |
| last_viewed_at | `read_inbox_max_id` |
| status / custom status | `userStatus*` / emoji status |

Telegram's DM message ids are one account-wide sequence; supergroup ids are per channel.
Relay uses **one `MessageIdAllocator` space per dialog, DMs included** (decided in A3): the client
stores messages keyed `(mid, uid)`, so per-dialog ids are safe there. The few client paths that
address DM messages by a bare id are made peer-aware in the bridge (see PROGRESS A3).

## Phases (each ends with integration tests against the local server)

| # | Goal | Test | Status |
|---|---|---|---|
| A0 | Toolchain: SDK/NDK, emulator, debug APK of upstream with Relay applicationId | APK installs and launches | Done (`97d5055`) |
| A1 | `relay-mattermost` library: login, me, teams, channels, posts, users, files, WebSocket | JVM tests on fixtures + live mmdev | Done (`f6c2e50`): relay-mattermost JVM suite |
| A2 | Boot without Telegram: Mattermost login screen → fake authorized account; tgnet never connects; bridge intercepts all requests | app opens to an empty chat list, no MTProto traffic | Done (`50ac9e8`); A2 e2e 27/27 |
| A3 | Read path: dialogs, history (paging, holes, jump), users, avatars, unread/read state | seeded chats render; scroll 11k-post firehose | Done (`7aee61b`); A3 e2e 31/31 |
| A4 | Live path: WebSocket → updates (new/edit/delete posts, reactions, typing, read, status) | post from REST as bob → appears in app | Done (`8749fb9`, `80df0b8`); A4 e2e 54/54 |
| A5 | Write path: send/edit/delete/reply, reactions, mark read, files/photos upload+download | sent from app → visible via REST/web | Done (`a274c26`, `808de04`); A5 e2e 34/34 |
| A6 | Fork hygiene: rename/branding, hide Telegram-only UI, settings, logout, multi-account | no Telegram-only entry points reachable | Merged (`57f2f4b`, A6b `b3bbed0`); A6 41/41, A6b 65/65 |
| A7 | Parity: threads (CRT), teams as folders, search, channel info/members, create channel/DM, mentions/emoji autocomplete, markdown, pinned/saved, push (FCM via Mattermost push proxy) | per-feature e2e | Merged: A7a `9334c27`, A7b `f795856`/`e603df0`, A7c `dd781ca`; A7a 46/46, A7b 70/70, A7c 52/52. Push: local notifications from the keep-alive WebSocket; FCM via a push proxy is prepared but off (needs the owner's Firebase project, PUSH.md) |
| A8 | Calls (LiveKit plugin `org.relaychat.calls`, same wire contract as iOS) | call between app and scripted peer | Merged (`337f280`); A8 e2e 52/52 |
| Polish 1 | Known issues of A3–A7b (own markdown, CRT previews, forwards, renumber migration, video probe, small photos) | `test_polish1.py` | Merged (`f8aa76e`); 33/33 |
| Polish 2a/2b | Exploratory QA findings (`QA-2026-11-25.md`): sync, notifications, bridge (2a); UI, English only, branding (2b) | `test_polish2a.py`, `test_polish2b.py` | Merged (2a `8ef6951`, 2b `80073d7`); 48/48, 119/119 |
| DM threads | DMs collapse threads as on iOS (thread view of a private chat) + replier avatars on "N Replies" | `test_dm_threads.py` | Branch `relay-dm-threads`; 43/43, A3 31, A4 54, A5 34, A7a 46, Polish 1 33 |
| Tab bar | Chats · Threads · Mentions · Settings · Profile as iOS (`bbf87df2`): Threads and Recent Mentions as main tabs, Threads badge, no chat-list Threads button, no Contacts tab | `test_tabs.py` | Branch `relay-tabs`; see PROGRESS "Tab bar" |
| Final | Clean-install regression of every suite, twice on emulator-5554, once on a 1080×2400 phone clone | all 13 suites | Done (2026-11-25): two consecutive green passes, phone green after test fixes; Samsung skipped (locked). See PROGRESS "Final regression" |

## Test bed

- Local Mattermost `mmdev` (iOS repo `infra/dev-server`, compose project `mmdev`,
  `http://localhost:8065`, users alice/bob/carol/dave, passwords in its `.env`).
- Devices reach it with `adb reverse tcp:8065 tcp:8065`, so the server URL is
  `http://localhost:8065` on the device too (cleartext allowed in debug builds).
- Devices: emulator AVD `ntsd-xplat-api35` (existing, arm64 API 35, serial emulator-5554) and the wired Samsung SM-A125F (R58R36F7VFD).
- e2e harness: `tools/relay-e2e/` (Python): seeds via REST, drives the app through
  `adb shell` (am/input/uiautomator dump) and a debug broadcast receiver, asserts both
  sides.

## Build

- `ANDROID_HOME=~/Library/Android/sdk`, JDK 17, NDK 27.2.12479018.
- Gradle home and build outputs on `/Volumes/X5/relay-android/` (fast disk).
- Variant: `TMessagesProj_App` afatDebug-style debug build, applicationId
  `org.relaychat.android` (debug suffix `.debug`).
