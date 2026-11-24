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
The bridge's ID map handles both.

## Phases (each ends with integration tests against the local server)

| # | Goal | Test |
|---|---|---|
| A0 | Toolchain: SDK/NDK, emulator, debug APK of upstream with Relay applicationId | APK installs and launches |
| A1 | `relay-mattermost` library: login, me, teams, channels, posts, users, files, WebSocket | JVM tests on fixtures + live mmdev |
| A2 | Boot without Telegram: Mattermost login screen → fake authorized account; tgnet never connects; bridge intercepts all requests | app opens to an empty chat list, no MTProto traffic |
| A3 | Read path: dialogs, history (paging, holes, jump), users, avatars, unread/read state | seeded chats render; scroll 11k-post firehose |
| A4 | Live path: WebSocket → updates (new/edit/delete posts, reactions, typing, read, status) | post from REST as bob → appears in app |
| A5 | Write path: send/edit/delete/reply, reactions, mark read, files/photos upload+download | sent from app → visible via REST/web |
| A6 | Fork hygiene: rename/branding, hide Telegram-only UI, settings, logout, multi-account | no Telegram-only entry points reachable |
| A7 | Parity: threads (CRT), teams as folders, search, channel info/members, create channel/DM, mentions/emoji autocomplete, markdown, pinned/saved, push (FCM via Mattermost push proxy) | per-feature e2e |
| A8 | Calls (LiveKit plugin `org.relaychat.calls`, same wire contract as iOS) | call between app and scripted peer |

## Test bed

- Local Mattermost `mmdev` (iOS repo `infra/dev-server`, compose project `mmdev`,
  `http://localhost:8065`, users alice/bob/carol/dave, passwords in its `.env`).
- Devices reach it with `adb reverse tcp:8065 tcp:8065`, so the server URL is
  `http://localhost:8065` on the device too (cleartext allowed in debug builds).
- Devices: emulator AVD `relay-pixel` (arm64, API 35) and the wired Samsung SM-A125F.
- e2e harness: `tools/relay-e2e/` (Python): seeds via REST, drives the app through
  `adb shell` (am/input/uiautomator dump) and a debug broadcast receiver, asserts both
  sides.

## Build

- `ANDROID_HOME=~/Library/Android/sdk`, JDK 17, NDK 27.2.12479018.
- Gradle home and build outputs on `/Volumes/X5/relay-android/` (fast disk).
- Variant: `TMessagesProj_App` afatDebug-style debug build, applicationId
  `org.relaychat.android` (debug suffix `.debug`).
