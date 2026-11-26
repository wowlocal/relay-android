# Telegram-Android internals for the Relay TL bridge

Base: Telegram-Android 13.2.2 (fork at `upstream-13.2.2-7303`). This document backs `PLAN.md`
(phases A2–A6). Every reference is `file:line`. Paths are relative to
`TMessagesProj/src/main/java/org/telegram/` unless they start with `jni/`, which is
`TMessagesProj/jni/`.

| Abbrev. | File |
|---|---|
| **CM** | `tgnet/ConnectionsManager.java` |
| **MC** | `messenger/MessagesController.java` |
| **MS** | `messenger/MessagesStorage.java` |
| **UC** | `messenger/UserConfig.java` |
| **SMH** | `messenger/SendMessagesHelper.java` |
| **MDC** | `messenger/MediaDataController.java` |
| **LA** | `ui/LaunchActivity.java` |
| **LoginA** | `ui/LoginActivity.java` |
| **DA** | `ui/DialogsActivity.java` |
| **CA** | `ui/ChatActivity.java` |
| **TL** | `tgnet/TLRPC.java` |
| **CMcpp** | `jni/tgnet/ConnectionsManager.cpp` |

## 0. Decisions in one screen

1. **Interception point.** The first line of `CM.sendRequestInternal` (CM:422) catches every request. Both the async path (`sendRequest`, CM:414-420, already on `Utilities.stageQueue`) and `sendRequestSync` (CM:408, used by `FileLoadOperation`:2525) pass through it.
   - The bridge gets the live `TLObject` and the delegates.
   - It answers by **serialising its response and deserialising it with `object.deserializeResponse(...)`** (the same call as CM:448), using `SerializedData` in pure Java. The callback then runs on `Utilities.stageQueue`, exactly like CM:487-499.
2. **tgnet never connects.** Keep `NativeLoader` and `native_setJava`, which the other native code needs. **Skip `native_init`** (CM:715) and turn the other `native_*` calls that schedule work into no-ops (list in §1.6).
   - The bridge then has to do two things native code used to do:
     - Call `MessagesController.updateTimerProc()` about every 1 s. Native `onUpdate` drove it (CM:855, CMcpp:293).
     - Push the connection state through `CM.onConnectionStateChanged(state, account)` (CM:863).
3. **Boot.** A Relay login fragment replaces `IntroActivity`/`LoginActivity` (LA:1122-1127, LA:1295, plus the add-account call sites).
   - After a Mattermost login, it runs a trimmed copy of `LoginA.onAuthSuccess` (LoginA:1680-1712) with a synthesised `TL_user` (`self=true`).
   - `UserConfig.isClientActivated()` means only `currentUser != null` (UC:238).
4. **pts scheme.**
   - One account-wide **pts** sequence for private-chat updates (DMs). One **pts per channel** for supergroups (all Mattermost channels and GMs).
   - Every pts-bearing update gets `pts_count = 1`. `seq = 0` always. The bridge assigns pts on a single serial executor in delivery order and persists the counters.
   - `getDifference` / `getChannelDifference` are answered from a small local update log. If the gap is not in the log, the answer is `differenceTooLong` / `channelDifferenceTooLong` (§6.4).
5. **Startup traffic** is about 60 distinct TL methods from cold start to an idle chat list (§3).
   - About 12 need real data: `getDialogs`, `getPinnedDialogs`, `getDialogFilters`, `getState`/`getDifference`, `users.getFullUser`, `getPeerDialogs`, `getHistory`, `upload.getFile`, `help.getAppConfig`, notify settings, `getChannelDifference`, `contacts.getContacts`.
   - The rest are "empty / not-modified" stubs.

---

## 1. Request boundary

### 1.1 Overload funnel

| Entry | Line | Goes to |
|---|---|---|
| `sendRequest(obj, delegate)` | CM:384 | → CM:400 → CM:404 → CM:414 |
| `sendRequest(obj, delegate, flags)` | CM:388 | → CM:404 (`DEFAULT_DATACENTER_ID`, `ConnectionTypeGeneric`, `immediate=true`) |
| `sendRequest(obj, delegate, flags, connType)` | CM:392 | → CM:404 |
| `sendRequest(obj, RequestDelegateTimestamp, flags, connType, dc)` | CM:396 | → CM:414 with `onCompleteTimestamp` |
| `sendRequest(obj, delegate, QuickAckDelegate, flags)` | CM:400 | → CM:404 |
| `sendRequest(…8 args)` | CM:404 | → CM:414 |
| **`sendRequest(…9 args)`** | CM:414 | allocates a token (`lastRequestToken`, CM:415) and **posts to `Utilities.stageQueue`** → `sendRequestInternal` (CM:417). Returns the token immediately |
| `sendRequestSync(…)` | CM:408 | token, then `sendRequestInternal` **on the caller's thread** (FileLoadOperation:2525) |
| `sendRequestTyped(TLMethod<T>, [Executor], Callback2, [dc], [flags])` | CM:322-341 | wraps → CM:414. The executor is applied *after* the stageQueue hop |
| `sendRequestTypedAndProcessUpdates(...)` | CM:366-381 | `sendRequestTyped`, then `getMessagesController().processUpdates(result, false)` **before** the completion runs. The bridge returns a plain `TLRPC.Updates` |
| `RequestException` (CM:343-363) | | helper for typed-callback users |

`sendRequestInternal` (CM:422-519) does the following:
1. Serialises `object` into a `NativeByteBuffer` (CM:427-429).
2. Registers callbacks with `listen(token, …)` (CM:436, CM:534).
3. Calls `native_sendRequest(...)` (CM:515).

When native calls back with `onRequestComplete(account, token, responsePtr, errCode, errText, netType, ts, msgId, dc)` (CM:569), the lambda at CM:436-503 runs on the **tgnet network thread** and does the following:
- **Parses** with `object.deserializeResponse(buff, magic, true)` (CM:448). A parse failure is `FileLog.fatal` and the callback is never called (CM:449-455).
- **Builds** `TL_error{code,text}` when `response == 0 && errorText != null` (CM:456-463).
- Feeds bandwidth stats for download connections (CM:464-469).
- **Delivers** on `Utilities.stageQueue.postRunnable` (CM:487-499) to the first non-null of `onComplete`, `onCompleteTimestamp`, or (when neither exists and the response is `Updates`) `MessagesController.processUpdates(...)` (CM:492-495). It then calls `freeResources()`.

**Thread contract.** Callers expect the delegate on **stageQueue** (a background `DispatchQueue`) and hop to UI with `AndroidUtilities.runOnUIThread` themselves.
- Never call the delegate synchronously inside `sendRequest`. Many callers store `reqId = sendRequest(...)` and compare it in the callback (e.g. `checkingPromoInfoRequestId`, MC:10915-10916).
- Never call it on the UI thread.
- Do Mattermost I/O on a bridge executor and post the result with `Utilities.stageQueue.postRunnable`.

### 1.2 Bridge hook (proposed shape)

```java
// CM.sendRequestInternal, first lines
if (RelayBridge.enabled(currentAccount)) {
    RelayBridge.get(currentAccount).handle(object, onComplete, onCompleteTimestamp, onQuickAck,
                                           flags, datacenterId, connectionType, requestToken);
    return;
}
```

`RelayBridge.handle` is responsible for the following:
- It looks the handler up by `object.getClass()`. Unknown methods are logged once per class and answered with `TL_error(400, "RELAY_UNSUPPORTED")`, or with the stub listed in §3.
- Response → `SerializedData out; resp.serializeToStream(out)` → `SerializedData in = new SerializedData(out.toByteArray()); int magic = in.readInt32(true); TLObject r = object.deserializeResponse(in, magic, true)` → `stageQueue.post(() -> onComplete.run(r, null))`.
- Why round-trip?
  - (a) Optional TL fields only survive when their flag bit is set. For example `TL_dialog.pts` needs FLAG_0 (TL:61633), and the client reads raw `flags` (MC:12642-12662, MC:19370). Round-tripping shows the client exactly what a server would.
  - (b) Callers mutate responses in place (e.g. `processUpdates` removes items, MC:17924), so the bridge's caches must never be aliased.
  - (c) `Vector`/`Bool` return types go through `deserializeResponse` the same way as on the network path.
  - `upload.file` with large `bytes` may skip the round trip.
- When neither `onComplete` nor `onCompleteTimestamp` is set and the result is `Updates`, call `processUpdates` (mirrors CM:492).
- For `RequestDelegateTimestamp`, pass `timestamp = System.currentTimeMillis()`.
- On the native path CM:429 calls `object.freeResources()` after serialising. The bridge skips that path, so it must call `object.freeResources()` itself once it has read the request. This matters most for `upload.saveFilePart.bytes`, which is a pooled `NativeByteBuffer`.

### 1.3 Request flags (CM:100-112) and what the bridge must honour

| Flag | Value | Native meaning | Bridge |
|---|---|---|---|
| `RequestFlagEnableUnauthorized` | 1 | send before auth | ignore |
| `RequestFlagFailOnServerErrors` | 2 | **Without it**, native silently retries 500s, negative codes and FLOOD_WAIT, and the caller never sees them (CMcpp:1372-1425). With it, the caller gets the error | **Without the flag, retry transient Mattermost/network failures internally (backoff, cap about 30 s). With it, return `TL_error(500,"INTERNAL")` / `(-1000,"")` promptly.** Many callers have no error branch |
| `RequestFlagCanCompress` | 4 | gzip | ignore |
| `RequestFlagWithoutLogin` | 8 | allowed before login. On logout, other requests fail with code −1000 (CMcpp:630-640) | ignore; fail pending requests with −1000 on logout |
| `RequestFlagTryDifferentDc` / `ForceDownload` | 16 / 32 | DC routing | ignore |
| **`RequestFlagInvokeAfter`** | 64 | `invokeAfterMsg`: execute after the previous InvokeAfter request. Used for every send/edit/delete/read (SMH:2947, 8040, 8585; MC:10304-16150, 16 sites) | **Run all InvokeAfter requests on one serial executor in submission order.** This preserves send order and read-after-send |
| `RequestFlagNeedQuickAck` | 128 | native calls `onQuickAck` when the server acks receipt. SMH:8578-8585 sets `send_state = SENT` and posts `messageReceivedByAck` | optional: call `onQuickAck` after the Mattermost POST returns 201. Use stageQueue → the delegate posts to UI itself |
| `RequestFlagDoNotWaitFloodWait` | 1024 | do not wait on FLOOD_WAIT | ignore |
| `RequestFlagListenAfterCancel` | 2048 | callback still delivered after cancel | honour it if the cancel path is implemented |
| `RequestFlagFailOnServerErrorsExceptFloodWait` | 65536 | | treat as flag 2 |
| `RequestFlagSmallDownload`, `RequestFlagDownloadByteBudget` | 131072 / 262144 | download scheduling (CM:506-514) | ignore (the bridge has its own HTTP pool) |
| `connectionType` | `ConnectionTypeGeneric` 1, `Download` 2, `Upload` 4, `Push` 8, `Download2` (CM:86-90) | | use it to pick an executor: downloads and uploads must not block the generic queue |

Error codes the client reacts to:
- 401 (MC:16289 `loadCurrentState` retries unless 401).
- `-2000 "CANCELLED_REQUEST"` (CMcpp:2216).
- `-1000` (logout).
- `FILE_REFERENCE_*`, `FLOOD_WAIT_n`, `MESSAGE_NOT_MODIFIED`, `MESSAGE_ID_INVALID`, `CHANNEL_PRIVATE`.

Use `-1000` or `500 INTERNAL` for "Mattermost unreachable".
- On a Mattermost 401 (token revoked), call `CM.onLogout(account)` (CM:870), which does `clearConfig` + `performLogout(0)`.

### 1.4 Cancel and guid binding

| API | Line | Native behaviour | Bridge |
|---|---|---|---|
| `cancelRequest(token, notifyServer[, onCancelled])` | CM:612-625 | stageQueue → `listenCancel` + `native_cancelRequest`. Native later calls `onRequestClear(token, cancelled=true)` (CM:549), which runs `onCancelled` (re-posted to stageQueue) and drops callbacks | Keep `token → Future` and drop the delegate (never call `onComplete` after a cancel unless `ListenAfterCancel`). Post `onCancelled` to stageQueue. Cancel the HTTP call (important for `upload.getFile`) |
| `bindRequestToGuid(token, guid)` | CM:637 | native list per guid | `guid → Set<token>` |
| `cancelRequestsForGuid(guid)` | CM:631 | cancels every request bound to a closing fragment (79 call sites) | cancel that set |
| `failNotRunningRequest(token)` | CM:173 | delivers `-2000 CANCELLED_REQUEST` if the request is still queued (CMcpp:2205-2222) | same |
| `discardConnection`, `cleanup(resetKeys)` | CM:167, CM:627 | native | no-op / clear pending |

### 1.5 Paths that bypass `sendRequest` (native → Java callbacks, called from `jni/TgNetWrapper.cpp`)

| Callback | Line | What it does | With tgnet off, the bridge must |
|---|---|---|---|
| `onUnparsedMessageReceived(addr, acc, msgId)` | CM:831 | server push → `TLRPC.Updates` → stageQueue `processUpdates(u, false)` | **call `MC.processUpdates(updates, false)` on stageQueue** for every WebSocket event (see §6) |
| `onUpdate(acc)` | CM:855 | `updateTimerProc()` on every native loop wake-up (≤1 s). The loop stops when the network is paused (CMcpp:293) | **schedule `updateTimerProc()` about every 1000 ms while `appResumeCount>0`** (CM:792-828). Typing expiry, online status (`account.updateStatus`), read tasks, short-poll channels and the update-queue timeouts all live there (MC:10549-10860) |
| `onSessionCreated(acc)` | CM:859 | `getDifference()` | call `MC.getDifference()` after each WebSocket (re)connect |
| `onConnectionStateChanged(state, acc)` | CM:863 | UI thread: sets `connectionState`, posts `didUpdateConnectionState` | call it: 3 when the WebSocket is open, 1 while reconnecting, 2 when `!ApplicationLoader.isNetworkOnline()` |
| `onLogout(acc)` | CM:870 | `clearConfig` + `performLogout(0)` | call on Mattermost 401 |
| `onUpdateConfig(addr, acc)` | CM:968 | native `help.getConfig` result → `MC.updateConfig(TL_config)` (MC:5652). **Java never sends `help.getConfig` itself** | **once per boot, call `MC.updateConfig(cfg)` on stageQueue** with Relay values: `edit_time_limit` (default `maxEditTime`=3600, MC:1588), `message_length_max` (Mattermost 16383), `megagroup_size_max`, `revoke_time_limit`, `pinned_dialogs_count_max`, `caption_length_max`, `me_url_prefix`, `suggested_lang_code` |
| `onInternalPushReceived` | CM:981 | `KeepAliveJob.startJob()` | — |
| `onRequestNewServerIpAndPort`, `getHostByName`, `onProxyError`, `onBytesSent/Received`, `onPremiumFloodWait`, `onIntegrityCheckClassic`, `onCaptchaCheck` | CM:900-966, 1517-1592 | DNS, proxy, stats, captcha | never fire; nothing to do |
| `native_getCurrentTime()` | CM:306 | **pure clock + `timeDifference`** (CMcpp:577). Works without `native_init` (`timeDifference=0`, the device clock) | Optional: replace it with `System.currentTimeMillis()/1000 + mmServerSkew`. It is used everywhere for "now" and message dates (e.g. MC:16935) |
| `getConnectionState()` | CM:648 | returns `Updating` (5) when `isUpdating && Connected`. `isUpdating` is set by `setIsUpdating` (CM:1078) from `getDifference` (MC:16941) | answer `getDifference` quickly, or the title stays "Updating…" |

Other Java → native calls outside CM:
- `ApplicationLoader`:340 `native_setJava(false)`. Keep it (it checks that the library loads).
- MC:1700 `native_isTestBackend`. Pure read, false.
- `CaptchaController`:29.
- `Utilities`:255 `native_isGoodPrime` (secret chats).

### 1.6 Keeping tgnet offline

Native work only runs on the network thread, which `native_init` creates via `ConnectionsManager::init` → `pthread_create` (CMcpp:3722-3779). `ConnectionsManager::sendRequest` and the setters only `scheduleTask` into `pendingTasks` (CMcpp:311-316, 1968).

Consequences of **skipping `native_init`** (CM:715):
- There are no sockets, no `help.getConfig`, and no DC config load (`loadConfig()` is never called).
- `native_getConnectionState` stays at `ConnectionStateConnecting` (CMcpp header `connectionState = ConnectionStateConnecting`; CM constructor reads it at CM:215). The bridge **must** flip it with `onConnectionStateChanged(3, acc)`, or the title shows "Connecting…" (LA:8474-8494, DA, `ChatAvatarContainer`).
- Any `native_*` setter still called just queues a lambda that never runs: a slow leak, but no crash. Guard these with `if (RelayBridge.enabled) return;`:
  - `init` (CM:672-717, including `native_setProxySettings`), `checkConnection` (CM:659, called on every connectivity change from `ApplicationLoader`:217), `setAppPaused` → `native_pauseNetwork/resumeNetwork` (CM:814/826; keep the `appResumeCount` bookkeeping, the timer uses it).
  - `setUserId` (CM:655, LoginA:1682), `setPushConnectionEnabled`, `setLangCode`/`setRegId`/`setSystemLangCode` (CM:719-748, static loops over all 4 accounts).
  - `switchBackend`, `resumeNetworkMaybe`, `updateDcSettings` (CM:764, LoginA:1695), `setDefaultDatacenterId`, `applyDatacenterAddress`, `checkProxy`, `cleanup` (CM:627, from `performLogout` MC:16176/16186), `cancelRequest*`/`bindRequestToGuid` (bridge versions), `discardConnection`, `failNotRunningRequest`, `setProxySettings` (CM:985).
- Native libraries keep working: image/video/ffmpeg/WebRTC/`Utilities` JNI live in the same `libtmessages` but do not depend on tgnet init. `NativeByteBuffer` allocation still works because it only needs `native_setJava`.
- `ConnectionsManager.getInstance(a)` is created for **all 4** account slots at boot (`ApplicationLoader.postInitApplication`:251-264), so the guard must hold for unactivated slots too.
- `getCurrentDatacenterId()` returns `INT_MAX` without init (CMcpp:585). Only `MediaController`:3613 (sets `document.dc_id` for local docs), `PasskeysController` and `WearAuthSheet` use it.

**What the UI needs to show "connected":**
- `connectionState == 3` (set via `onConnectionStateChanged`).
- `isUpdating == false`: answer `updates.getState`/`getDifference` and clear it.
- Nothing else. "Waiting for network" only appears when the bridge reports state 2.

---

## 2. Auth and boot

### 2.1 "Logged in" state

| Item | Line | Notes |
|---|---|---|
| `UserConfig.MAX_ACCOUNT_COUNT = 4`, `MAX_ACCOUNT_DEFAULT_COUNT = 3` | UC:28-29 | slot 4 is "premium only" in the add-account UI |
| `UserConfig.selectedAccount` (static) | UC:27 | persisted by account 0's `saveConfig` (UC:146), read in `loadConfig` (UC:298) |
| prefs file | UC:412-417 | `userconfing` (sic) for account 0, `userconfig{n}` for the others. Each account's DB is `files/account{n}/` (CM:207-211) |
| `isClientActivated()` | UC:238 | **`currentUser != null`**, nothing else |
| `getClientUserId()` | UC:244 | `currentUser.id` |
| `setCurrentUser(user)` | UC:262 | sets `currentUser`/`clientUserId`, then `checkPremiumSelf` → `updatePremium`, `loadPremiumPromo` (UC:270-293). Keep `premium=false` |
| `saveConfig(withFile)` | UC:138 | runs on `doOnIdle`. `withFile=true` serialises `currentUser` as base64 into pref `"user"` (UC:212-222). **Must be `true` after login** |
| `loadConfig()` | UC:295-382 | deserialises `"user"` → `currentUser` |
| `clearConfig()` | UC:462 | wipes prefs. Sets `migrateOffsetId=-1` (no legacy dialog migration), `hasValidDialogLoadIds=true`, `filtersLoaded=false`, `notificationsSettingsLoaded=false` |
| boot | `ApplicationLoader.postInitApplication`:191-271 | for each slot: `UserConfig.loadConfig`, `MessagesController.getInstance`, `ConnectionsManager.getInstance` (→ `native_init`). If `currentUser != null`: `putUser` + `SendMessagesHelper.checkUnsentMessages` (:259-263). Then `initPushServices` (:267) |

The Relay Mattermost session (server URL, token, user id) should live **beside** this in a per-account pref such as `relay_account{n}`, encrypted, and be loaded by `RelayBridge` before the first request.

### 2.2 Routing

- `LaunchActivity.onCreate` (LA:405) calls `ApplicationLoader.postInitApplication()` (LA:419) and sets `currentAccount = UserConfig.selectedAccount` (LA:421).
- If the account is not activated, LA:574-575 calls `addFragmentToStack(getClientNotActivatedFragment())`, which returns `LoginActivity` when a login is in progress and `IntroActivity` otherwise (LA:1122-1127). If it is activated, LA:577 adds `MainTabsActivity`, which hosts `DialogsActivity`.
- On logout: `switchToAvailableAccountOrLogout` (LA:1274-1296) switches to another activated slot, or `presentFragment(new IntroActivity().setOnLogout())` (LA:1295).
- Add account: `new LoginActivity(freeAccount)` at `MainTabsActivity`:659, `ProfileActivity`:14728, `LogoutActivity`:149, `UserInfoActivity`:488. The intro "Start messaging" button goes to `IntroActivity`:388/417.
- Account switch: `switchToAccount(account, removeAll[, provider])` (LA:1224-1272) pauses CM, sets `selectedAccount`, rebuilds a `MainTabsActivity`, and posts `activeAccountChanged`.

### 2.3 What `onAuthSuccess` does (LoginA:1680-1712)

| # | Call | Keep in Relay? |
|---|---|---|
| 1 | `MessagesController.cleanup()` (×2, MC:6425) | yes |
| 2 | `ConnectionsManager.setUserId(id)` | no-op (guarded) |
| 3 | `UserConfig.clearConfig()` | yes |
| 4 | `UserConfig.syncContacts = …` | set false |
| 5 | **`UserConfig.setCurrentUser(user)` + `saveConfig(true)`** | **yes** |
| 6 | `MessagesStorage.cleanup(true)` (MS:856) | **yes.** It reopens the DB and then calls `getDifference()` (MS:861), which reaches `loadCurrentState()` → `updates.getState` because pts==0 (MC:16913-16915) |
| 7 | `putUsersAndChats([user])`, `MC.putUser(user,false)` | yes |
| 8 | `ContactsController.checkAppAccount()` | optional (Android account sync) |
| 9 | `MC.checkPromoInfo(true)` | drop (`help.getPromoData`) |
| 10 | `CM.updateDcSettings()` | drop |
| 11 | `MC.loadAppConfig()` | yes. The bridge answers `help.getAppConfig` (§3) |
| 12 | `loadWebBrowserConfig()`, `checkPeerColors(false)` | stub responses, or drop |
| 13 | `AuthTokensHelper.saveLogInToken` | drop |
| 14 | `MediaDataController.loadStickersByEmojiOrName(placeholder)` | drop |
| 15 | `needFinishActivity(...)` (LoginA:1626-1665) | yes: present `MainTabsActivity` (or `switchToAccount` for a new account), post `mainUserInfoChanged`. Drop `loadRemoteLanguages` |

### 2.4 Plugging in the Mattermost login

- Add `org.telegram.relay.ui.RelayLoginActivity extends BaseFragment` with an `(int account)` constructor. It takes the server URL, login id and password (MFA and SSO later), calls `POST /api/v4/users/login`, and stores the token in `relay_account{n}`.
- Replace the fragment returned at **LA:1122-1127**, **LA:1295**, and the 4 add-account sites. Keep `IntroActivity` reachable only when `RelayFeatures.TELEGRAM_LOGIN` is set.
- Call `RelayAuth.completeLogin(account, me)`. It is a static copy of the "yes" rows above, runs on the UI thread, and is then followed by `needFinishActivity`-equivalent navigation.
- Build the fake `TL_user`. Use `TLRPC.TL_user`, then round-trip it so the flags are consistent.

| Field | Value |
|---|---|
| `id` | bridge id map. Use 1 ≤ id < 2^48; avoid Telegram special ids 333000, 777000, 42777, 489000/489001, 2666000, 708513, 1271266957 (`UserObject`:23-27, :42, :202); never set bits 61/62 (folder/encrypted markers, `DialogObject`:30-35, :109-114) |
| `self` | true |
| `access_hash` | non-zero, stable (e.g. a hash of the Mattermost id) |
| `first_name`/`last_name`/`username` | from the Mattermost profile |
| `phone` | "" (UI that shows a phone must be hidden, §9) |
| `status` | `TL_userStatusOnline{expires=now+300}` |
| `photo` | `TL_userProfilePhoto` (§8) or `TL_userProfilePhotoEmpty` |
| `premium`, `bot`, `verified`, `contact` | false |
| `flags` | consistent with the fields (round-trip) |

- **The Mattermost self-DM** (the channel `<me>__<me>`) maps to dialog id `clientUserId`, so it becomes "Saved Messages" automatically.
- **Multi-account:** one `RelayBridge` per slot, keyed by `currentAccount`. Every controller is per account (`AccountInstance`), and so are the DB and prefs. Nothing global needs changing except `ConnectionsManager`'s static `setLangCode`/`setRegId` loops (guard them).

---

## 3. Startup traffic (cold start → idle chat list, logged-in account)

The order follows the boot sequence:
1. `ApplicationLoader.postInitApplication` (constructors for MC/CM/`DownloadController`/`ContactsController`/MDC/`LocaleController`).
2. `LaunchActivity` → `MainTabsActivity` → `DialogsActivity.onFragmentCreate` (DA:2751) → `DA.loadDialogs` (DA:2906).
3. `MS` open → `getDifference`.
4. Timers.

Only the chats tab is bound at start; the contacts, settings and profile tabs send nothing until they are opened.

"Error safe?" means returning `TL_error` causes no loop and no crash.

### 3.1 Real data (the bridge must implement these properly)

| TL method | Caller | Trigger | Minimum response | Error safe? |
|---|---|---|---|---|
| `updates.getState` | MC:16273 `loadCurrentState` ← `getDifference` when `lastPts==0` (MC:16913) ← MS:861 (login) / `onSessionCreated` | first boot after login | `TL_updates_state{pts, qts=0, seq=0, date=now, unread_count=0}` | **No: retries forever** unless code 401 (MC:16288-16290) |
| `updates.getDifference` | MC:16921 | every reconnect, pts gap | §6.4. Sets "Updating…" until answered | yes (clears `isUpdating`) |
| `messages.getDialogs` | MC:12513 (`loadDialogs`), MC:12899 (`resetDialogs`), MC:13136 (`migrateDialogs`, unused after `clearConfig`) | cache empty or end reached; auto-pages up to 400 dialogs | §4.2 | yes (no retry; list stays as cached) |
| `messages.getPinnedDialogs{folder_id=0}` | MC:17397 ← DA:2875 | once per install (pref) | `messages.peerDialogs` **with `state`** (§4.3) | yes |
| `messages.getDialogFilters` | MC:2445 ← `processLoadedDialogFilters` MC:2247 | until `UC.filtersLoaded` | `messages.dialogFilters{filters=[dialogFilterDefault, team folders…]}` (§4.4) | yes |
| `users.getUsers` / `messages.getChats` / `channels.getChannels` / `messages.getPeerDialogs` | MC:2015-2075 `loadFilterPeers` | folder peers not in local DB | users / `messages.chats` / `messages.peerDialogs` with state | yes |
| `messages.getPeerDialogs` | `reloadDialogsReadValue` (MC:13547) | cached dialog with `read_*_max_id==0` | `peerDialogs` | yes |
| `users.getFullUser(self)` | DA:2913 → `MC.loadUserInfo` (MC:10500) → `MS.loadUserInfo` → `processUserInfo` (MC:10519) → `loadFullUser` (MC:7762) | cache older than 60 s | `users.userFull{full_user: TL_userFull{id, settings: TL_peerSettings{} (non-null), notify_settings (non-null), about, profile_photo?}, chats, users}` | yes |
| `help.getAppConfig{hash}` | MC:424-439 fetcher ← constructor MC:1973 (+2 s) and every 4 min (MC:2618-2629) | always | `help.appConfig{hash, config: jsonObject}`. Relay keys: `dialog_filters_limit_default`/`_premium` (≥ number of teams), `dialog_filters_chats_limit_default`, `premium_purchase_blocked=true`, `stars_purchase_blocked=true`, `stories_posting="disabled"`, `reactions_uniq_max`, `channels_limit_default`, `upload_max_fileparts_default`; otherwise `help.appConfigNotModified` | yes (logged; the cache stays) |
| `account.getNotifySettings` ×3 (`inputNotifyChats`/`Users`/`Broadcasts`) + `account.getReactionsNotifySettings` | MC:12627-12636, MC:12703 ← DA:2910 `loadGlobalNotificationsSettings` | until `notificationsSettingsLoaded` | `TL_peerNotifySettings{flags=0}` (or set `show_previews` flag 1, `mute_until` flag 4); `account.reactionsNotifySettings{}` | yes, but not marked loaded, so it is re-sent next start |
| `account.getContactSignUpNotification` | MC:12756 (via MC:12727) | until loaded | `boolTrue` (true = "joined" off) | yes |
| `account.registerDevice{token_type=2 FCM, token}` | MC:16239 `registerForPush` ← `getDifference` (MC:16912) and the push-token callback | when the token changes | `boolTrue`. Later: register with the Mattermost push proxy (`PUT /api/v4/users/sessions/device`) | yes |
| `messages.getHistory` / `getReplies` / `getPeerDialogs` | §5 | opening a chat | §5 | — |
| `updates.getChannelDifference` | MC:16698 | chat open (short poll), unknown pts | `channelDifferenceEmpty{final, pts, timeout}` (§6.4) | yes |
| `upload.getFile` | `FileLoadOperation`:2465 | avatars and thumbs in the list | §8 | yes (`onFail`) |
| `contacts.getContacts{hash}` | `ContactsController`:1522 ← `checkAppAccount`/`readContacts` (`ApplicationLoader`:274) | DB has no contacts, or 24 h | `contacts.contacts{contacts=[], saved_count=0, users=[]}`. Later: fill it with Mattermost DM partners or the team roster so "New Message" works | yes (but the contacts list stays "loading") |

### 3.2 Periodic, from `updateTimerProc` (needs the bridge timer, §1.5)

| TL method | Caller | Trigger | Response |
|---|---|---|---|
| `account.updateStatus{offline}` | MC:10565, 10586 | every 55 s in the foreground; offline after a 2 s pause | `boolTrue`. Map it to Mattermost `PUT /users/me/status` or drop it (the WebSocket keeps you online) |
| `help.getPromoData` | MC:10914 (`checkPromoInfoInternal` MC:10891, from MC:10859) | `nextPromoInfoCheckTime` | `help.promoDataEmpty{expires=now+86400}` |
| `help.getTermsOfServiceUpdate` | MC:10868 (`checkTosUpdate`) | `nextTosCheckTime` | `help.termsOfServiceUpdateEmpty{expires=now+86400}` |
| `messages.getOnlines` | MC:10788 | megagroup open | `chatOnlines{onlines=n}` |
| `messages.getMessagesViews` | MC:10628 | broadcast channels only | empty |
| `updates.getChannelDifference` | MC:10771 | short poll | §6.4 |

### 3.3 Stubs: answer once, cheaply

Group A is always or regularly sent.
- In the "Trigger" column, "1 h", "24 h" and so on are the pref-based re-send intervals.
- **Bold** marks a response that must be a specific non-error object.

| TL method | Caller | Trigger | Minimum response | Error safe? |
|---|---|---|---|---|
| `help.getPeerColors`, `help.getPeerProfileColors` | MC:24283/24300 ← constructor MC:1975 (+400 ms) | hash-based | `help.peerColorsNotModified` | yes |
| `account.getWebBrowserSettings` | MC:25514 ← MC:1974 | 1 h fetcher | `webBrowserSettingsNotModified` | yes |
| `messages.getAvailableEffects` | MC:24515 ← MDC:8882 `checkAllMedia` | fetcher | `messages.availableEffectsNotModified` or `availableEffects{hash=0, effects=[], documents=[]}` | yes |
| `account.getAutoDownloadSettings` | `DownloadController`:356 | 24 h | **`account.autoDownloadSettings` with non-null `low`/`medium`/`high`** (`TL_autoDownloadSettings` defaults) | yes |
| `contacts.getStatuses` | `ContactsController`:2654 | after contacts load, 3 h | empty `Vector` | yes |
| `help.getInviteText` | `ContactsController`:357 ← DA:2914 | 24 h | `help.inviteText{message="Join me on Relay"}` (non-empty) | yes |
| `account.getGlobalPrivacySettings` | `ContactsController`:2695 ← DA:2889 | until success | `globalPrivacySettings{flags=0}` | yes |
| `stories.getAllReadPeerStories` | `StoriesController`:200 ← DA:2874 | until success | **any `TLRPC.Updates`** (`TL_updates`, empty). **Any other type crashes** (unchecked cast, `StoriesController`:202) | yes |
| `stories.getAllStories` ×2 (`include_hidden` false/true) | `StoriesController`:385/398 ← DA:2874/2886 | every start | `stories.allStories{count=0, state="", peer_stories=[], chats=[], users=[], **stealth_mode=storiesStealthMode{}**}` | yes |
| `account.getBirthdays` | `BirthdayController`:108 ← DA:2899 | 12 h | `account.contactBirthdays{contacts=[], users=[]}` | yes |
| `messages.getFavedStickers` | MDC:2026 | 1 h | `favedStickersNotModified` | yes |
| `messages.getStickers` ("👋⭐" greetings, "📂⭐" premium) | MDC:2030/2035 | 1 h / every start | `messages.stickersNotModified` | yes |
| **`messages.getFeaturedStickers`, `messages.getFeaturedEmojiStickers`** | MDC:2326/2322 | 1 h | **`featuredStickers{hash=1, count=0, sets=[], unread=[]}`** | **NO: an error with hash 0 gives an infinite 1 s retry loop** (MDC:2355-2362) |
| `messages.getAvailableReactions` | MDC:781 | every start (MDC:821 `\|\| true`) | `availableReactions{hash, reactions=[...]}`: the Mattermost emoji set mapped to Unicode `reactionEmoji`; `NotModified` on an unchanged hash | yes |
| `messages.getRecentReactions`, `messages.getTopReactions` | MDC:9857/9877 | every start | `messages.reactionsNotModified` | yes |
| `messages.getAttachMenuBots` | MDC:568 | 1 h | `attachMenuBots{hash=0, bots=[], users=[]}` | yes |
| `help.getPremiumPromo` | MDC:678 | 24 h | `help.premiumPromo{status_text="", status_entities=[], video_sections=[], videos=[], period_options=[], users=[]}` | yes |
| `messages.getStickerSet` (`PremiumGifts`, `TonGifts`, `EmojiGenericAnimations`, dice 🎲 🎯, `tg_placeholders_android`) | MDC:2694, 2726, 2758, 2848 | 24 h (re-sent while failing) | error is fine | yes (re-sent every start) |
| **`messages.getStickerSet(inputStickerSetAnimatedEmoji)`** | MDC:3020 ← DA:6966 (`onResume`) | 1 h | **`messages.stickerSet{set=TL_stickerSet{id≠0, short_name="relay_emoji"}, packs=[], keywords=[], documents=[]}`** | **NO: an error or `NotModified` gives an infinite 1 s loop** (MDC:3183-3189) |
| `account.getDefaultProfilePhotoEmojis`, `account.getDefaultGroupPhotoEmojis` | MDC:10005 | 24 h | `emojiListNotModified` | yes |
| `contacts.getTopPeers` | MDC:5273 ← `DialogsSearchAdapter`:362 (200 ms after visible) | 24 h | `contacts.topPeers{categories=[], chats=[], users=[]}`. **Never `topPeersDisabled`** (it turns hints off for good) | yes |
| `langpack.getLanguages` | `LocaleController`:3381 | while the list is empty | empty `Vector`. **Don't list "en"**, or a remote langpack download starts | yes |
| `help.saveAppLog` | `PushListenerController`:70 | push-token stats | `boolTrue` | yes |

Group B is conditional: it is avoided entirely when the bridge's data is minimal.

| TL method | Condition | How to avoid |
|---|---|---|
| `help.getAppUpdate` | Standalone flavor only (LA:6151) | not built, or `help.noAppUpdate` |
| `langpack.getStrings` ×2, `getLangPack`, `langpack.getDifference` | `TL_config.suggested_lang_code` differs from the shown one (LA:8346); a remote locale is active | send `suggested_lang_code` = the current locale in the injected `TL_config`; return no languages |
| `account.getThemes` (`Theme.loadRemoteThemes`), `checkUpdateForCurrentRemoteLocale` | fired by `MC.updateConfig` (MC:5704-5791) | `account.themesNotModified`; `lang_pack_version=0` |
| `contacts.importContacts` | READ_CONTACTS granted | `syncContacts=false` (§9) |
| `account.getAccountTTL` + 14× `account.getPrivacy` | `BIRTHDAY_SETUP` suggestion | no `pending_suggestions` in appConfig |
| `messages.getSavedDialogs` | `savedViewAsChats` pref | — |
| `messages.getDialogUnreadMarks` | MC:17213, only if `!unreadDialogsLoaded`; `clearConfig` sets it true (UC:490) | not sent |
| `help.getRecentMeUrls` | MC:12285, only with `installReferer` | not sent |
| `messages.getSuggestedDialogFilters` | DA:13144 (folder hint), `FiltersSetupActivity` | empty `Vector` |
| `messages.getChannelRecommendations`, `getSponsoredMessages`, `getMessagesReactions`, `channels.getFullChannel` | opening a channel | §5, and an empty or error result |

**Count:** §3.1 has 15 methods with real data; with the §3.2 timers and the stubs in §3.3 group A, about 45 distinct methods are sent on a normal cold start, and about 60 once group B and the first chat open are counted.

The bridge should do two things:
- log every unknown class once (`RELAY_UNSUPPORTED`);
- keep a table-driven stub registry (`Map<Class, Supplier<TLObject>>`) so new methods can be added without code paths.

---

## 4. Chat list

### 4.1 `MessagesController.loadDialogs(folderId, offset, count, fromCache)` (MC:12497-12596)

1. `DA.loadDialogs` (DA:2906-2922) calls `loadDialogs(0, 0, 100, true)` once per account.
2. `fromCache` → `MS.getDialogs` → `processLoadedDialogs(..., loadType=DIALOGS_LOAD_TYPE_CACHE)`. If the cache is empty, `processLoadedDialogs` calls `loadDialogs(folderId, 0, count, false)` (MC:13372). Otherwise it loads from the server when the cache reaches its end (MC:13810-13812).
3. Server request `messages.getDialogs` (MC:12513-12586):
   - `limit = count` (100), `exclude_pinned = true`, `folder_id` (flag 2) only for the archive (1).
   - Offsets come from `UC.getDialogLoadOffsets(folderId)` (UC:545). After `clearConfig` they are all 0 because `hasValidDialogLoadIds=true`.
   - First page: `offset_id=0, offset_date=0, offset_peer=inputPeerEmpty`. Later pages: the `(date, id, peer)` of the **oldest** `top_message` in the previous page (MC:13437-13492, saved via `setDialogsLoadOffset`, UC:556).
   - If a page has no message with a different id, the offset becomes `Integer.MAX_VALUE`. That is the end of the list, and no more requests are sent (MC:12523-12528).
4. Auto-paging: after each server page, if `totalDialogsCount < 400` and the end has not been reached, the next page loads (MC:13797-13800). `dialogsEndReached = size==0 || size != count` (MC:13784).
5. Callback: `processLoadedDialogs(res, …, loadType=0)` (MC:12588). Errors are ignored and nothing retries.

### 4.2 `messages.getDialogs` response: what to fill

Return `TL_messages_dialogsSlice{count=total}` or `TL_messages_dialogs`.

**Page selection:** order dialogs by `top_message.date` descending, with ties broken by `(date, peer, id)`. Return those strictly older than `(offset_date, offset_id, offset_peer)` and **exclude pinned dialogs**. An empty page means the end.

| Vector | Content and requirements |
|---|---|
| `dialogs` | `TL_dialog` per conversation (fields below) |
| `messages` | the `top_message` `TL_message` of every dialog. **Required:** `processLoadedDialogs` builds the last-message preview from it, and its `date` sets ordering and paging (MC:13404-13440). `date==0` messages are skipped |
| `users` | the DM peer, every `from_id` in `messages`, and self |
| `chats` | a `TL_channel` per Mattermost channel or GM |

**`TL_dialog`** (serialised at TL:61618-61645; optional fields need their flag):
- `peer`: `peerUser{user_id}` for DMs, `peerChannel{channel_id}` for channels and GMs.
- `top_message`: the newest message id. **It must equal the newest id returned by `getHistory(offset_id=0)`.**
- `read_inbox_max_id`, `read_outbox_max_id`: Mattermost `last_viewed_at` mapped to the newest post id ≤ that time. They must never decrease (`Math.max`, MC:13551-13561).
- `unread_count`, `unread_mentions_count` (`mention_count`), `unread_reactions_count=0`.
- **`notify_settings`: never null** (written unconditionally at TL:61632). Use `TL_peerNotifySettings{flags=0}` or `mute_until` (flag 2).
- **`pts` + `flags |= 1`** for every channel dialog. It seeds `channelsPts` (MC:13538). If it is missing, the channel's live updates are dropped as an "unknown channel" (MC:18275-18285).
- `pinned` (flag 4): only in `getPinnedDialogs`. `folder_id` (flag 16): 1 = archive.
- `draft` (flag 2): optional, maps to Mattermost drafts.
- When the client finds `read_inbox_max_id == 0 || read_outbox_max_id == 0` in cached dialogs, it calls `reloadDialogsReadValue` (MC:13547-13549), which sends `messages.getPeerDialogs`. Report at least 1 for non-empty dialogs to avoid that.

**`TL_channel`** for a Mattermost channel or GM:
- `id`: mapped long.
- `access_hash != 0`: required, or `getChannelDifference` and `getInputChannel` are never sent (MC:16676).
- `megagroup = true`, `broadcast = false`, `left = false`, `kicked = false`, `min = false`.
- `title`, `date` = `create_at`, `photo` = `TL_chatPhotoEmpty` or `TL_chatPhoto` (§8), `participants_count` (flag), `username` (only for public/open channels if wanted), `creator`/`admin_rights` for channel admins, `default_banned_rights` (all false).
- **Channels with `left` or `kicked` are dropped** from the list (MC:13533-13535, `ChatObject.isNotInChat`).

**Private chats** need a `TL_user` for the peer. Bots (Mattermost bot accounts) can use `bot=true`, but that triggers bot UI (start button, menu), so prefer `bot=false` plus a badge later.

### 4.3 Pinned dialogs

- `loadPinnedDialogs(folderId, 0, null)` (MC:17392) runs at DA:2875, **once per folder per install** (gated by pref `2pinnedDialogsLoaded{folder}`, UC:512). It sends `messages.getPinnedDialogs{folder_id}`.
- The response is `TL_messages_peerDialogs{dialogs (pinned=true, in order), messages, chats, users, state}`. **`state` must be non-null**: it is a `TL_updates_state` that is serialised unconditionally (TL:51531-51550). Return the current getState values.
- After that first load, pinned changes arrive only as updates: `updateDialogPinned{pinned, peer:dialogPeer}` (MC:20102) or `updatePinnedDialogs{order[]}` (MC:20116).
- Mapping: Mattermost "Favorites" sidebar category ↔ pinned. Also reset `TL_config.pinned_dialogs_count_max` (default 5) through `updateConfig` (§1.5) so more than 5 favourites fit.
- `resetDialogs` (MC:12881-12910, triggered by `differenceTooLong`) re-sends **both** `getPinnedDialogs` and `getDialogs(limit 100)`.

### 4.4 Folders = Mattermost teams (`messages.getDialogFilters`)

Load path:
1. On DB open, `MS.loadDialogFilters` → `MC.processLoadedDialogFilters(..., remote=0)`.
2. That calls `loadRemoteFilters(false)` (MC:2245-2247), gated by `UC.filtersLoaded`.
3. `loadRemoteFilters` (MC:2434-2480) sends `messages.getDialogFilters`.
4. It accepts either `TL_messages_dialogFilters{tags_enabled, filters}` (current) or a bare `Vector<DialogFilter>`.
5. Then `MS.checkLoadedRemoteFilters` (MS:3389) diffs against local filters. For peers it doesn't know, it sends `users.getUsers`, `messages.getChats`, `channels.getChannels` and `messages.getPeerDialogs` (`MC.loadFilterPeers`, MC:2015-2075). The bridge must implement those four.

Live changes: `updateDialogFilter{id, filter}`, `updateDialogFilterOrder{order}`, `updateDialogFilters` (MC:20711-20716). Forcing `loadRemoteFilters(true)` re-fetches.

Fields (`DialogFilter`, TL:20979-21027; `TL_dialogFilter` 0xaa472651, TL:21140):

| Field | Use for a team |
|---|---|
| `id` | **≥ 2**. 0 is "All chats" (`DialogFilter.isDefault()`, MC:1389), and 1 is never used by the client (new filters start at 2, `FilterCreateActivity`:199). Use a stable map from team id to id |
| `title` | `TL_textWithEntities{text=team.display_name, entities=[]}` |
| `emoticon` (flag 25), `color` (flag 27) | optional (folder icon and tag colour) |
| `contacts`, `non_contacts` (flags 0,1) | **true**: DMs show in every team, matching Mattermost |
| `groups` (flag 2) | false (otherwise every channel of every team would show) |
| `broadcasts`, `bots` | false |
| `include_peers` | `inputPeerChannel{channel_id, access_hash}` for each channel of the team, **plus every GM** (GMs are cross-team) |
| `pinned_peers` | optional: the team's favourites |
| `exclude_peers` | empty |
| `exclude_muted`, `exclude_read`, `exclude_archived` | false |

- Put a `TL_dialogFilterDefault` (TL:21132, id 0) first in `filters` so "All" stays the first tab. Single-team servers may return no filters at all.
- **Limits:** `dialogFiltersLimitDefault=10` (MC:1652). Above the limit, non-premium users get extra folders **locked** (`lockFiltersInternal`, MC:878-899). `dialogFiltersChatsLimitDefault=100` (MC:1654). Raise both through `help.getAppConfig` keys `dialog_filters_limit_default`, `dialog_filters_chats_limit_default` (MC:3505-3540), e.g. to 500 / 10000.
- The suggested-filters UI sends `messages.getSuggestedDialogFilters` (MC:2411; DA:13144). Return an empty vector.
- Editing folders in-app (`messages.updateDialogFilter`, `updateDialogFiltersOrder`) should be refused (`TL_error`), or the folder editor hidden (§9).

---

## 5. History

### 5.1 `loadMessages` → `loadMessagesInternal`

- Overloads:
  - MC:11541, 16 args. Passes `replyFirstUnread` as `first_unread` only when `threadMessageId != 0`.
  - MC:11545, 20 args.
  - MC:11549 `loadMessagesInternal`, 24 args.
- `load_type` constants (MC:11535-11539): `LOAD_BACKWARD=0`, `LOAD_FORWARD=1`, `LOAD_FROM_UNREAD=2`, `LOAD_AROUND_MESSAGE=3`, `LOAD_AROUND_DATE=4`.
- `fromCache=true` goes to `MS.getMessages` (MS:10178) → `getMessagesInternal` (MS:9077) → `processLoadedMessages(isCache=true)`.
  - If the cache returns 0 rows (not the initial load, or the last server query was more than 60 s ago), MC:12010-12067 calls `loadMessagesInternal(fromCache=false)` again.
  - In that call, `max_id` becomes `first_unread` when `load_type==2 && queryFromServer`.

| Param | Meaning |
|---|---|
| `dialogId` | user > 0, chat/channel < 0 |
| `mergeDialogId` | old basic group migrated to this supergroup. `loadIndex==1` pages that chat |
| `count` | page size: initial `initialMessagesSize`, scroll 50 (CA:14418-14435), jump 20/30 (CA:17725), date 30 (CA:12988) |
| `max_id` | **anchor id, sent as `offset_id`**: oldest loaded (backward) / newest loaded (forward) / target (around). 0 = top |
| `offset_date` | only for `load_type 4` |
| `fromCache`, `midDate` | try SQLite first; date bound for cache queries |
| `classGuid` | `bindRequestToGuid` (§1.4) |
| `last_message_id` | dialog `top_message`. 0 with `load_type` 2/3 → `getPeerDialogs` first |
| `mode` | 0 default, 1 scheduled, saved, pinned, quick replies |
| `threadMessageId` | thread root / topic id |
| `first_unread`, `unread_count`, `last_date`, `mentionsCount` | from the dialog row or `getPeerDialogs` |
| `queryFromServer` | the unread anchor came from `dialogs.inbox_max`; use it as `offset_id` on server reload |

### 5.2 Which TL method

| Case | Method | Line |
|---|---|---|
| default chat | `messages.getHistory` | MC:11778 |
| `load_type` 2/3 with `last_message_id==0` | `messages.getPeerDialogs` first, then a recursive call with `top_message` / `unread_count` / `unread_mentions_count` + `putDialogs(…,2)` | MC:11746-11775 |
| thread (`threadMessageId!=0`, mode 0) | `messages.getReplies` | MC:11659 |
| saved/monoforum | `messages.getSavedHistory` (same offsets) | MC:11599 |
| scheduled | `messages.getScheduledHistory` (hash; `NotModified` ignored) | MC:11721 |
| open comments/thread | `messages.getDiscussionMessage`, then `getReplies` | CA:36861 |
| reply previews | `channels.getMessages` / `messages.getMessages` | MDC:6687/6719 (`loadReplyMessagesForMessages` MDC:6251) |
| pinned / legacy reload | `channels.getMessages` / `messages.getMessages` | MDC:6103/6125, MC:7869/7874 |
| dialog sanity | `getHistory limit=1` | MC:13981 `checkLastDialogMessage` |
| in-chat search, shared media, pinned list | `messages.search` | MDC:3930/3978, 4169 (`media_holes_v2`), 5977 |
| unread mentions | `messages.getUnreadMentions` | CA:47611, MC:13861 |

Jump-to-message and jump-to-date do **not** use `messages.search`. Both are `getHistory` calls with `load_type` 3 or 4.

### 5.3 `load_type` → `getHistory` args (MC:11779-11798; same for `getSavedHistory` MC:11604)

The request always has `max_id = min_id = 0`, `offset_id = max_id(param)` and `limit = count`. In the channel branch `limit += 1` is dead code, because `limit = count` overwrites it right after.

**Semantics the bridge must implement.** Let L be the peer's messages, newest first (id DESC, dates monotonic).
- p is the index of the first message with `id < offset_id`. If only `offset_date` is given, it is the first message with `date < offset_date`. If neither is given, p = 0.
- Return `L[max(0, p+add_offset) … max(0, p+add_offset+limit))`, clipped to L.
- Do **not** refill when the window is clamped at 0. A short page is how the client detects the top.
- `max_id`/`min_id` are exclusive filters when non-zero.

| `load_type` | `add_offset` | Window (X at index i ⇒ p=i+1) |
|---|---|---|
| 0, `max_id=0` | 0 | newest `count` |
| 0, private/basic | 0 | `count` with id < X |
| 0, channel, X≠0 | −1 | X itself + `count−1` older |
| 1 forward | −count−1 | the `count` with id > X (fewer near the top) |
| 2 unread, X=`max(1, read_inbox_max_id)` (MS:9315) | −count+6 (replies −count+10) | 6 older, X, `count−7` newer |
| 2, `max_id=0` | 0 | newest `count` |
| 3 around | −count/2 | `count/2` older, X, `count/2−1` newer |
| 4 date, `offset_id=0` | −count+5 | 5 with `date < D`, then `count−5` with `date ≥ D` |

If the response has more than `count` messages, the client drops the newest (MC:11804). With `offset_date` set, it recomputes `mid` (MC:11807-11816).

### 5.4 Consuming the result, and holes

- **`processLoadedMessages`** (MC:11956):
  - When the response is `TL_messages_channelMessages` and no channel pts is stored, it stores `res.pts`, sets `createDialog=true` and calls `getChannelDifference` (MC:11968-11982).
  - Every `message.unread` is **overwritten** with `(out ? read_outbox_max : read_inbox_max) < id` (MC:12121-12125). Server `unread` is ignored.
  - Then `MS.putMessages(res, dialogId, load_type, max_id, createDialog, mode, thread)` (MC:12129). Non-topic thread replies are not persisted.
  - Network results always have `isEnd=false`.
  - Unread marker: `first_unread_final` is the smallest incoming id > `first_unread` (MC:12229-12239).
- **Hole closing** (MS:16389-16411):

| Result | Action |
|---|---|
| empty page, `load_type 0` | `doneHolesInTable` (MS:15662) deletes the start=0 hole (all holes if `max_id==0`) and inserts the sentinel (1,1) = "start of history". Cache reads then report `isEnd=true` (MS:9434) |
| `load_type 0` | `closeHoles(min=last.id, max=max_id)` |
| `load_type 1` | `closeHoles(max_id, first.id)` |
| `load_type` 2/3/4 | `closeHoles(last.id, (max_id==0 && type!=4) ? INT_MAX : first.id)`, so a `max_id=0` load is assumed to reach the newest message |

  - `closeHolesInTable` (MS:15910) deletes a hole when `max >= end−1 && min <= start+1`, and otherwise trims or splits it.
  - **The client assumes each page is gap-free between the returned ids and `offset_id`.**
  - Media holes work the same way (`closeHolesInMedia` MS:15803).
- **Hole creation:**
  - `createFirstHoles(did, top_message)` inserts (0, top), or (1,1) when top=1, the first time a dialog arrives via `putDialogs` (MS:17681, MS:17449).
  - `getMessagesInternal` adds (0, min(mid)) when no hole starting at 0 or 1 exists (MS:9428-9450).
  - Holes are also created by `deleteDialog` / `clearLocalDatabase` (MS:4617, 1634) and by `overwriteChannel` on `channelDifferenceTooLong` (MC:16851).
  - The cache SQL orders by `date DESC, mid DESC` within hole bounds (MS:9580-9720), so **dates must rise monotonically with ids**.
- **End detection (CA):**
  - backward: `size < count` → `endReached` (type 2: only when empty) (CA:22258-22270)
  - forward: `size != count` → `forwardEndReached` (CA:22219)
  - a message with `id == last_message_id` → `forwardEndReached` (CA:21912)
  - channel: a message with id 1 → `endReached` (CA:21893)
  - `count` and `inexact` are not read on this path.
- **Response type:**
  - Private chats: `TL_messages_messages`, or `TL_messages_messagesSlice{count}` (TL:3311/3430).
  - Supergroups: **`TL_messages_channelMessages{pts=current channel pts, count, topics=[], messages, chats (incl. the channel, megagroup, access_hash≠0), users}`** (TL:3466).
  - Every message needs `id`, `peer_id`, `from_id` (groups), `date`, `out` and `message`, ordered newest first. `users` must cover every `from_id` and every reply/forward peer.

### 5.5 Jump, first unread, dialog counters

- **First open** (CA:3236-3258) uses `load_type 2` with `fromCache` and `max_id=0`.
  - Storage reads `dialogs.inbox_max`, `unread_count` and `unread_count_i` (MS:9310-9320) and sets `queryFromServer=true`.
  - If `unread_count < 1` it falls back to "latest" (MS:9408-9418).
  - `last_message_id` = max(mid) in the DB (MS:9475/9692).
- **Jump to message:** `scrollToMessageId` (CA:17548). If the message isn't loaded, it calls `loadMessages(20/30, max_id=id, load_type 3)` (CA:17725).
- **Jump to date:** `jumpToDate` (CA:12943) → `loadMessages(30, offset_date=D, load_type 4)` (CA:12988).
- **`TL_dialog` counters:**
  - `read_inbox_max_id` / `read_outbox_max_id` go through `Math.max` (they never decrease) and are stored as `dialogs.inbox_max/outbox_max`.
  - `unread_count` drives the badge, the first-unread anchor and `unread_to_load` (CA:21624-21636).
  - `unread_mentions_count` drives the mention button.
  - `top_message` is `last_message_id` and the first hole.

### 5.6 Read state

- **Outgoing.** `markDialogAsRead` (MC:14711) queues a ReadTask, sent after 5 s unless `readNow` (MC:14847). `completeReadTask` (MC:14594) then sends one of:
  - thread: `messages.readDiscussion` → Bool (MC:14597)
  - channel: `channels.readHistory{channel, max_id}` → **Bool** (MC:14615)
  - otherwise: `messages.readHistory{peer, max_id}` → **`messages.affectedMessages{pts, pts_count}`** (MC:14620). The client calls `processNewDifferenceParams(-1, pts, -1, pts_count)` (MC:14625, 8229). Safe answer: `pts = current, pts_count = 0`, or a real +1 that is logged as `updateReadHistoryInbox`.
  - Mattermost call: `POST /api/v4/channels/members/me/view {channel_id}`.
- **Incoming** (§6.3):
  - `updateReadHistoryInbox{peer, max_id, still_unread_count, pts, pts_count}` (MC:18854)
  - `updateReadHistoryOutbox{peer, max_id, pts, pts_count}` (MC:18872)
  - `updateReadChannelInbox{channel_id, max_id, still_unread_count, pts}` (MC:19378)
  - `updateReadChannelOutbox{channel_id, max_id}` (MC:19406)
  - discussion variants (MC:19744/19758)

### 5.7 Supergroup vs private chat

| Aspect | Private chat (DM) | Supergroup (Mattermost channel/GM) |
|---|---|---|
| Message ids | **one id space for the whole account**. Bare-id updates (delete, read contents) resolve via `mid IN(…) AND is_channel=0` (MS:6913, 14785) | per channel. Storage PK is `(mid, uid)` (MS:553) |
| pts | account pts (`getState`/`getDifference`) | `channelsPts[id]`, seeded from `channelMessages.pts` or `dialog.pts` |
| History response | `messages`/`messagesSlice` | `channelMessages{pts}` |
| Read | `messages.readHistory` → `affectedMessages` | `channels.readHistory` → Bool |
| Delete | `messages.deleteMessages` → `affectedMessages` (common pts) | `channels.deleteMessages` → `affectedMessages` (channel pts) |

`getChannelDifference` (MC:16622) is sent in these cases:
- the first `channelMessages` for a channel without stored pts (MC:11978-11980)
- **chat open**, via `startShortPoll` (CA:2730/2737 → MC:16579, type 3, `force=false`)
- poll timeout while the chat is open (MC:10764-10771)
- `updateChannelTooLong` (MC:19345-19375)
- a pts gap (MC:8224, 16399, 18422)
- pending tasks at startup (MS:1013-1072)

It is never sent when the channel's `access_hash==0` (MC:16676). Minimal answer: `channelDifferenceEmpty{final=true, pts, timeout}` (TL:35186). `final=false` makes the client call again immediately.

DM id allocation: an account-wide counter in the bridge (`next_dm_msg_id`), with each Mattermost DM post mapped once. Channel ids are allocated per channel. PLAN's "sparse allocation, STEP 16, start 2^30" works for both, as long as the order within a dialog equals the order of `create_at`.

### 5.8 Threads (Mattermost CRT)

- **`messages.getReplies`** `{peer, msg_id=root, offset_id, offset_date, add_offset, limit, max_id, min_id, hash}` (MC:11659). Same window rules; `load_type 2` uses −count+10.
- **`getDiscussionMessage`** (CA:36861) → `TL_messages_discussionMessage{messages=[root], max_id (flag 0), read_inbox_max_id (flag 1), read_outbox_max_id (flag 2), unread_count, chats, users}` (TL:19728).
  - The client follows with `getReplies(offset_id = read_inbox_max_id or 1, add_offset=−20, limit=30)` (CA:36921-36933).
  - It drops the result if the first reply's `reply_to_top_id`/`reply_to_msg_id` isn't the root id (CA:36746-36752).
- Channel messages need `replies: TL_messageReplies{replies, replies_pts, max_id, read_max_id, comments=false}` (flag 23) for the "N replies" UI.
  `recent_repliers` (flag 1, peers; the bridge sends the newest first, at most 3) are the avatars in front of "N Replies"; the users must be
  in the response's `users`.
- **Private chats (DM threads).** The thread view also runs for a user peer: `ChatActivity` with `user_id` + `setThreadMessages(objects, null,
  root, …, null)` (Telegram 13 does the same for bot forum topics, there with a topic). `loadMessagesInternal` sends `getReplies` with
  `inputPeerUser` (no cache path for non-topic threads), reads go through `readDiscussion` with the user peer (`completeReadTask`), and
  new messages are filtered by `getReplyTopMsgId()/getReplyMsgId() == threadMessageId` as for megagroups. Answer a DM's `getReplies` with
  `messages.messagesSlice{count}`, not `channelMessages`. Telegram has no private-chat "too long": dropping a DM's cached history is
  done client-side (`RelayDmCache`, the DM counterpart of `MessagesStorage.overwriteChannel`).

### 5.9 Bridge rules for history

1. Give every dialog a stable, strictly increasing int id order (gaps are fine). Dates never decrease as ids increase. DM ids are unique account-wide.
2. Implement the full window rule in 5.3. Return newest first, never more than `limit`, and never refill a clamped window.
3. Return **contiguous** slices. Skipping a message between the returned ids and `offset_id` loses it for good, because the client closes the hole.
4. Past the oldest message, return an **empty list**, not an error. That writes the (1,1) sentinel.
5. `offset_id=0` returns the true newest messages, and `dialog.top_message` equals that id.
6. Supergroups always get `channelMessages` with a `pts` that matches what the update stream and `getChannelDifference` use.
7. `read_*_max_id` are accurate and monotonic; the client derives per-message unread state and ticks from them.
8. Fill `users`/`chats` for every peer that is referenced.
9. Side calls still need answers when there is no data: `getScheduledHistory` (empty `messages`), `channels.getMessages`/`messages.getMessages` (`messageEmpty` for missing ids), `getReplies`, `getDiscussionMessage`.

---

## 6. Updates

### 6.1 Entry: `MC.processUpdates(Updates, fromQueue)` (MC:17917-18456), always on stageQueue

| Constructor | Path | Checks |
|---|---|---|
| `TL_updates` / `TL_updatesCombined` | MC:17918-17936. First every `updateMessageID` is extracted and sent through `processMessageIDUpdate`, and the rest is re-posted after a storageQueue hop. Then MC:18140-18385 | see 6.2 |
| `TL_updateShort` | MC:17941-17944 → `processUpdateArray([update])` | none. **Use it for type-3 updates** (typing, status, reactions, read-channel) |
| `TL_updateShortMessage` / `TL_updateShortChatMessage` | MC:17945-18138 | all of `user`, `from`, and `fwd` must already be known; otherwise `missingData` → `getDifference` (MC:18020-18034). Then `lastPts + pts_count == pts` (MC:18036) |
| `TL_updateShortSentMessage` | send-response path only (§7) | |
| `TL_updatesTooLong` | MC:18402-18406 | forces `getDifference` |

**The bridge should always emit `TL_updates{updates, users, chats, date=now, seq=0}`** with every referenced user and chat included, and never `updateShortMessage`. That avoids the missing-data path and keeps one code path.

### 6.2 Validation inside `TL_updates` (MC:18140-18385)

1. **Min channels:** if any `chats[]` entry is `min` and not already known non-min → `getDifference` (MC:18140-18185). Never send `min=true`.
2. `putUsersAndChats(users, chats)` (MC:18187), then updates are sorted by `updatesComparator` (pts order).
3. Grouping by `getUpdateType` (MC:17751-17765):
   - **Type 0, common box**: `updateNewMessage`, `updateEditMessage`, `updateDeleteMessages`, `updateReadHistoryInbox`, `updateReadHistoryOutbox`, `updateReadMessagesContents`, `updateWebPage`, `updateFolderPeers`, `updatePinnedMessages`, `updateMessageWidgetData`. Consecutive updates are merged while `pts2 == pts + count2`. Then:
     - `lastPts + pts_count == pts` → apply, `setLastPtsValue` (MC:18200-18230).
     - `lastPts == pts` → silently dropped (duplicate).
     - Otherwise → `updatesQueuePts`, and after 1.5 s (`updatesStartWaitTimePts`) → `getDifference()`.
   - **Type 1**: `updateNewEncryptedMessage` (qts). Unused.
   - **Type 2, per channel**: `updateNewChannelMessage`, `updateEditChannelMessage`, `updateDeleteChannelMessages`, `updateChannelWebPage`, `updatePinnedChannelMessages`. The channel's pts comes from `channelsPts` / `MS.getChannelPtsSync`.
     - **If it is 0 → `loadUnknownChannel` and the update is skipped** (MC:18275-18291).
     - `channelPts + pts_count == pts` → apply and `saveChannelPts` (MC:18309-18322).
     - `!=` → per-channel queue, and after 1.5 s → `getChannelDifference(channelId)` (MC:18323-18349, MC:18415-18423).
   - **Type 3, everything else**: no pts check. The loop `break`s at the first type-3 update (MC:18357). The remainder is applied by `processUpdateArray` if `seq == 0 || seq == last+1 || seq == last` (MC:18367-18378).
4. `processUpdateArray(updates, users, chats, false, date)` (MC:18474): a return of `false` (an unknown chat or user referenced by a message, MC:18600-18646) forces `getDifference` / `getChannelDifference`.

### 6.3 Per-update notes (all in `processUpdateArray`)

| Update | Line | Type | Bridge notes |
|---|---|---|---|
| `updateNewMessage{message, pts, pts_count}` | MC:18562 | 0 | DM post. `message.out` = from self. **`unread` is set by the client** from `read_*_max` |
| `updateNewChannelMessage{message, pts, pts_count}` | MC:18562-18575 | 2 | channel/GM post. `out` is forced true if `from_id==self` (MC:18571). A message with `reply_to` bumps the thread reply counters (MC:18668-18687) |
| `updateEditMessage` / `updateEditChannelMessage{message, pts, pts_count}` | MC:19496 | 0 / 2 | full message with `edit_date` (flag 15) |
| `updateDeleteMessages{messages[], pts, pts_count}` | MC:18895 | 0 | DM ids. The id space is account-wide |
| `updateDeleteChannelMessages{channel_id, messages[], pts, pts_count}` | MC:19421 | 2 | |
| `updateMessageReactions{peer, msg_id, top_msg_id?, reactions}` | MC:19658, UI MC:20695 | 3 | full `messageReactions` state, not a delta (see §7) |
| `updateUserTyping{user_id, top_msg_id?, action}` | MC:18963 | 3 | DM typing. `action = TL_sendMessageTypingAction`. Expired by `updateTimerProc` (MC:10800-10845), about 6 s, **only if the bridge ticks the timer** |
| `updateChannelUserTyping{channel_id, top_msg_id?, from_id:peerUser, action}` | MC:18951 | 3 | Mattermost `typing` event with `parent_id` → `top_msg_id` |
| `updateUserStatus{user_id, status}` | MC:19084, UI MC:20019 | 3 | `userStatusOnline{expires}` / `userStatusOffline{was_online}` / `userStatusRecently`. Maps to Mattermost `status_change` (online/away/dnd/offline) |
| `updateReadHistoryInbox{peer, max_id, still_unread_count, pts, pts_count}` | MC:18854 | 0 | another device read a DM |
| `updateReadHistoryOutbox{peer, max_id, pts, pts_count}` | MC:18872 | 0 | the peer read our DM (blue ticks). From Mattermost `channel_viewed`/`multiple_channels_viewed`, or the other member's `last_viewed_at` |
| `updateReadChannelInbox{folder_id?, channel_id, max_id, still_unread_count, pts}` | MC:19378 | 3 | `pts` is informational only |
| `updateReadChannelOutbox{channel_id, max_id}` | MC:19406 | 3 | |
| `updateChannelTooLong{channel_id, pts?}` | MC:19345 | 3 | asks the client to `getChannelDifference`. Use it when the bridge loses track of a channel |
| `updateChannel{channel_id}` | MC:20350 | 3 | channel joined/left/changed. If the chat is in `chats[]` and has no dialog, this triggers `getChannelDifference(id, 1)` |
| `updateUserName`, `updateUserEmojiStatus`, `updateNotifySettings`, `updateDraftMessage`, `updateDialogPinned`, `updatePinnedDialogs`, `updateDialogFilter*`, `updatePinnedChannelMessages` | MC:19101, 19095, 20233, 20458, 20102, 20116, 20711, 19602 | 3 / 2 | profile / mute / draft / favourites / team / pinned-post changes |

### 6.4 Recommended pts scheme (simplest consistent)

**State kept by the bridge, per account, in SQLite:**
- `commonPts` (int) for DMs, and `channelPts[channelId]` for each channel/GM.
- An update log of `(box, pts, TL update bytes, date)`, ring-buffered to e.g. 1000 entries per box or 3 days.
- `lastMmSyncAt` (ms).

**Emission:**
- One serial "update executor" per account does all of the following: converts each Mattermost WebSocket event, assigns `pts = ++box; pts_count = 1` (deletes of n ids: `pts += n`), appends to the log, and posts `processUpdates(TL_updates{seq=0})` to stageQueue.
- Responses that carry updates (`sendMessage`, `editMessage`, `deleteMessages`, `readHistory`) **use the same executor and counters**, so pts order always equals delivery order and the client never sees a gap.
- The bridge's own sends must not be emitted twice. When the WebSocket echoes `posted` for a post the bridge created (match `pending_post_id` = the bridge's `random_id`-derived id), skip it.
- Type-3 events (typing, status, reactions, read-channel) carry no pts. Send them as `TL_updates`/`TL_updateShort` straight away.

**Requests:**

| Request | Answer |
|---|---|
| `updates.getState` (MC:16268) | `TL_updates_state{pts=commonPts, qts=0, seq=0, date=now, unread_count=0}`. **An error makes the client retry forever** unless the code is 401 (MC:16288-16290) |
| `updates.getDifference{pts,date,qts}` (MC:16911-17205) | If `pts == commonPts` and no channel is stale: `TL_updates_differenceEmpty{date=now, seq=0}`, which clears "Updating…" (MC:17130-17135). If the log covers `pts..commonPts`: `TL_updates_difference{new_messages, other_updates (channel updates may go here too, filtered by channel pts at MC:16975-16989), users, chats, state}`. Otherwise `TL_updates_differenceTooLong{pts=commonPts}`, which runs `resetDialogs` = `getPinnedDialogs` + `getDialogs` reload (MC:16945-16951, MC:12881). An error also clears "Updating…" (MC:17147-17148) |
| `updates.getChannelDifference{channel, pts, limit, force}` (MC:16622-16900) | If `pts == channelPts`: `TL_updates_channelDifferenceEmpty{final=true, pts, timeout=30…300}`. The timeout sets the short-poll interval while the chat is open (MC:16857-16863, `updateTimerProc` MC:10764). If the log covers the gap: `TL_updates_channelDifference{final=true, pts, new_messages, other_updates, chats, users}`. Otherwise `channelDifferenceTooLong{dialog, messages, chats, users}`, which calls `MS.overwriteChannel`: the channel cache is wiped and its history reloads (MC:16840-16851) |

**Catch-up after offline or a WebSocket reconnect:**
1. On reconnect, call `MC.getDifference()` (what `onSessionCreated` did).
2. Before answering, the bridge runs a REST delta sync: `GET /users/me/teams/{t}/channels/members` plus `channels/{id}/posts?since=lastMmSyncAt` for the channels whose `last_post_at` changed.
3. It turns that delta into log entries with new pts values, so `getDifference` returns a real `difference`.
4. If the delta is too large (more than N posts), it returns `differenceTooLong` and lets the client re-list the dialogs. Channels whose gap is too large get `updateChannelTooLong`.

This gives one invariant: **`client pts ≤ bridge pts` and the log fills the gap.** With it the client never needs special-casing.

**"Never request differences" is not achievable.** `getDifference` runs on every login (MS:861) and on every reconnect, and `getChannelDifference` runs on every chat open (`startShortPoll`, CA:2730 → MC:16572-16579). So the `Empty` answers must be cheap and correct.

**Things that break the scheme:**
- Channel dialogs without `pts` (flag 0), which drops live updates.
- Channel messages from `getHistory` returned as `TL_messages_messages` instead of `channelMessages{pts}`, which leaves the channel with no pts (MC:11968-11982).
- Reusing pts values across reinstalls. Persist the counters in the bridge DB under `files/account{n}/`, which is wiped together with the client DB on logout.

---

## 7. Sending

A bare `:NNNN` is a line in **SMH** unless another file is named.

### 7.1 Entry points → TL

| UI action | Client code | TL | Response handled at |
|---|---|---|---|
| Text | `sendMessage(SendMessageParams)` :4441 → `TL_messages_sendMessage` :5458 → `performSendMessageRequest` :5516 | `messages.sendMessage` | callback :8140 |
| Text + link preview | `TL_messages_sendMedia` (webpage) :5393 → :5453 | `messages.sendMedia` | :8140 |
| Photo/video/file/voice | input media built at :5610 (`inputMediaUploadedPhoto`), :5659/:5809 (`inputMediaUploadedDocument`) → dispatch :6154-6184 → `performSendDelayedMessage` :6583/:6641 → `FileLoader.uploadFile` (:6650 photo, :6750 video, :6914 file, :6964 audio) → `fileUploaded` observer :1025 sets `media.file`/`.thumb` → `performSendMessageRequest` (:1099, :1109) | `upload.saveFilePart`/`saveBigFilePart`, then `messages.sendMedia` | :8140 |
| Album (`groupId≠0`) | `TL_messages_sendMultiMedia` :6005. Each item: `uploadMultiMedia` :7265 → `messages.uploadMedia` :7267 → `inputMediaPhoto`/`Document` (:7326-7365) → `performSendMessageRequestMulti` :7580 → :7735 | `messages.uploadMedia` ×N, then `messages.sendMultiMedia` | :7749 |
| Forward | `sendMessage(ArrayList<MessageObject>, …)` :2229 → `TL_messages_forwardMessages` :2721 | `messages.forwardMessages` | :2774 |
| Edit text | `editMessage(MessageObject, String, …)` :3591 → :3604 | `messages.editMessage` | :3630 → `processUpdates` |
| Edit media | :3054 → :3505 → edit branch of `performSendMessageRequest` :8263 | `messages.editMessage` | :8263-8309 |
| Delete | `MC.deleteMessages` MC:9348. Local delete comes first (`markMessagesAsDeleted`, `messagesDeleted`, about MC:9380-9398), then `TL_channels_deleteMessages` MC:9480 / `TL_messages_deleteMessages` MC:9514 (`revoke` = for all) | `channels.deleteMessages` / `messages.deleteMessages` | MC:9496 / MC:9530 |
| Reaction | `sendReaction` :3992 → `TL_messages_sendReaction` :4005. `reaction[]` is the user's **whole new set**; empty means remove | `messages.sendReaction` | :4037 → `processUpdates` |
| Typing | `MC.sendTyping` MC:11414 → `TL_messages_setTyping` MC:11452 (`FailOnServerErrors`) | `messages.setTyping` | ignored |

- Send flags are `CanCompress | InvokeAfter`, plus `NeedQuickAck` for `sendMessage` (:8584). The quick-ack lambda (:8578-8583) sets `send_state=SENT` and posts `messageReceivedByAck`.
- The temp message gets a negative local id from `UserConfig.getNewMessageId()` (UC:129, counts down). `random_id = getNextRandomId()` (:5008). `putToSendingMessages` (:7590) runs before the request.

### 7.2 How responses are processed (`performSendMessageRequest` callback :8140-8575)

The callback mutates `updates.updates`, so the bridge must return a fresh object each time (the §1.2 round-trip does this).

**`TL_updateShortSentMessage`** (:8321)
- Reads `id`, `date`, `entities`, `ttl_period` (flag 25), `media` (flag 9), and **`out` (:8328, flag bit 1: must be true)**.
- Then calls `processNewDifferenceParams(-1, pts, date, pts_count)` (:8353).
- It carries no users or chats, so use it at most for plain text in DMs.

**`TLRPC.Updates`** (:8355)
- The **first** `updateNewMessage` without an `action` becomes the sent message (:8363). The callback calls `processNewDifferenceParams(-1, pts, -1, pts_count)` and removes it from the list.
- `updateNewChannelMessage` (:8380) is handled the same way, through `processNewChannelDifferenceParams(pts, pts_count, channel_id)`, and also updates reply counters.
- The single-send path matches **whichever message it finds, not `random_id`**.
- `updateMediaPaths(msgObj, message, …)` (:8484) renames local upload files to the server cache names. Sizes match by the same `type`, or the same `w`/`h` (:8823).
- `newMsgObj.id = message.id` (:8486). Leftovers (`updateMessageID`, `updateMessageReactions`) go to `processUpdates` on stageQueue (:8498).
- **If no new-message update is found, the message is marked as a send error** (:8488, :8565-8574).

**Finalize** (:8507-8558)
- Calls `messageReceivedByServer(oldId, newId, …)`, `updateMessageStateAndId(random_id, peer, oldId, newId)`, `putMessages`, `processSentMessage` and `removeFromSendingMessages`.
- The temp row is re-keyed in place.

**Multi / album / forward** (`performSendMessageRequestMulti` :7749-8040, forward :2780-2840)
- **`updateMessageID(random_id → id)` is required.** Messages are matched through `newIds.get(random_id)` (:7839, :7933); a missing mapping is a send error (:7965/:7969).

**Fork note**
- `MC.processUpdates` first pulls out every `updateMessageID` and runs `processMessageIDUpdate` (MC:17898), then re-queues the rest after a storage-queue hop (MC:17918-17935).

**Edit**
- Expects `updateEditMessage` (type 0, common pts) or `updateEditChannelMessage` (type 2) in an `Updates`.

**Delete**
- Expects `messages.affectedMessages{pts, pts_count}`. DMs go through `processNewDifferenceParams` (MC:9533); channels go through `processNewChannelDifferenceParams` (MC:9499).

**Reaction**
- Expects `Updates{updateMessageReactions{peer, msg_id, reactions: messageReactions{results: [reactionCount{chosen_order (flag 0, for own reactions), reaction: reactionEmoji{emoticon}, count}], recent_reactions?}}}` (`TL_update.java`:1839, TL:45142).
- It is applied in `processUpdateArray` MC:19658, which writes storage.

**pts helpers**
- `processNewDifferenceParams` (MC:8229) and `processNewChannelDifferenceParams` (MC:8185) behave the same way:
  - `last + count == pts` applies the update.
  - `pts == last` is a no-op.
  - Anything else is queued, and `getDifference` runs after 1.5 s.
- **Safe no-op: `pts = current, pts_count = 0`.**

### 7.3 Errors (`AlertsCreator.processError` :343; send block about :501-575)

| Error text | Effect |
|---|---|
| `FILE_REFERENCE_*`, `FILEREF_EXPIRED` | send is retried through `FileRefController.requestReference` (:8141). **Never return this** |
| `CHAT_SEND_*_FORBIDDEN`, `PEER_FLOOD`, `USER_BANNED_IN_CHANNEL`, `SCHEDULE_TOO_MUCH` | alert, message marked as failed |
| `ALLOW_PAYMENT_REQUIRED_<n>` | Stars price flow (:8567). Never return this |
| `MESSAGE_NOT_MODIFIED` (edit) | silently ignored |
| anything else (400 `MESSAGE_ID_INVALID`, 500 `INTERNAL`) | red "!" with retry, no dialog. Use this for Mattermost 4xx/5xx |
| `FLOOD_WAIT_<s>` | alert |

### 7.4 Bridge responses

P is the common pts, CP is the channel pts. Both are assigned on the update executor (§6.4).

| Method | DM (peer user) | Megagroup (Mattermost channel/GM) |
|---|---|---|
| `sendMessage` / `sendMedia` | `TL_updates{seq=0, date, users=[self,peer], chats=[], updates=[updateMessageID{id, random_id}, updateNewMessage{msg, pts=P+1, pts_count=1}]}` | `TL_updates{…, chats=[full channel], updates=[updateMessageID, updateNewChannelMessage{msg, CP+1, 1}]}` |
| `sendMultiMedia` / `forwardMessages` | N×(`updateMessageID` + `updateNewMessage`) with consecutive pts and the same `grouped_id` | N×(`updateMessageID` + `updateNewChannelMessage`) |
| `editMessage` | `updateEditMessage{msg, P+1, 1}` | `updateEditChannelMessage{msg, CP+1, 1}` |
| `deleteMessages` / `channels.deleteMessages` | `affectedMessages{pts=P+n, pts_count=n}` | `affectedMessages{pts=CP+n, pts_count=n}` |
| `sendReaction` | `TL_updates{updates=[updateMessageReactions{peer, msg_id, reactions}]}` (no pts) | same, with `peerChannel` |
| `setTyping` | `boolTrue`, plus WebSocket `user_typing {channel_id, parent_id}` | same |
| `uploadMedia` | `messageMediaPhoto{photo}` / `messageMediaDocument{document}` built from the uploaded FileInfo | same |
| `readHistory` / `channels.readHistory` | `affectedMessages{pts=P, pts_count=0}` | `boolTrue` |

Every returned `TL_message` needs:
- `id > 0` (§5.9), `out=true`;
- `peer_id`: `peerUser(peer)` / `peerChannel(id)`;
- `from_id=peerUser(self)` (flag 8);
- `date`, `message`, `entities`;
- `reply_to` when replying (`root_id`), `grouped_id` for albums;
- media as in §8.

Mattermost calls:

| TL | Mattermost |
|---|---|
| `sendMessage` / `sendMedia` | `POST /api/v4/posts {channel_id, message, file_ids[], root_id, pending_post_id}` |
| `editMessage` | `PUT /posts/{id}/patch` |
| `deleteMessages` | `DELETE /posts/{id}` |
| `sendReaction` | `POST /reactions` / `DELETE /users/{uid}/posts/{pid}/reactions/{emoji}`, from a diff against the current own set |

- **Dedupe with the WebSocket:** derive `pending_post_id` from `random_id`. When the `posted` event comes back with that id, don't emit a second `updateNewMessage`.
- **Albums:** Telegram sends N messages; Mattermost has one post with up to 10 files. Suggestion: send one post and show it as N TL messages that share `grouped_id`.

---

## 8. Files

`FLO` = `messenger/FileLoadOperation.java`, `FUO` = `messenger/FileUploadOperation.java`, `FL` = `messenger/FileLoader.java`, `IL` = `messenger/ImageLocation.java`.

### 8.1 Download locations

`FL.loadFile` (:790 ImageLocation, :807 Document) → `loadFileInternal` :822 → `new FileLoadOperation(...)` (:887 / :890).

| Source | `InputFileLocation` (FLO:line) | Fields |
|---|---|---|
| Avatar (`ImageLocation.photoPeer`) | `TL_inputPeerPhotoFileLocation` :335 | `peer`, `photo_id`, `big` (= `photoPeerType == TYPE_BIG`) |
| Sticker-set thumb | `TL_inputStickerSetThumb` :344 | — |
| Photo size | `TL_inputPhotoFileLocation` :353 | `id`, `access_hash`, `file_reference`, `thumb_size = PhotoSize.type` |
| Document thumb | `TL_inputDocumentFileLocation` :364 | `thumb_size = type` |
| Full document | `TL_inputDocumentFileLocation` :444 | `thumb_size=""`. `dc = document.dc_id`, `totalBytesCount = document.size` (:467) |
| Legacy `fileLocation` | `TL_inputFileLocation` :376 | — |

A null `file_reference` becomes `byte[0]` (:372, :381, :450). `datacenterId = imageLocation.dc_id` (:387).

### 8.2 The `upload.getFile` loop

- **Request** (FLO:2465):
  - Fields: `location`, `offset`, `limit = currentDownloadChunkSize`, `cdn_supported = true`. `precise` is never set.
  - Sent with **`sendRequestSync`** (FLO:2524). `connectionType = Download | (n<<16)` (:2437).
  - Flags: `ForceDownload | DownloadByteBudget | SmallDownload (<20 KB) | ListenAfterCancel`.
- **Chunk sizes** (`start()` :872-893):
  - 32 KB by default.
  - `downloadChunkSizeBig` 128 KB (512 KB with experimental params or preload, :301) for files ≥ 10 MB (:171), and for streams.
  - 32 KB after `LIMIT_INVALID` (:2100).
  - `offset` is always a multiple of the chunk size, and `limit` is the chunk size.
- **Concurrency** (:2312-2318, :856-864): with `totalBytesCount > 0`, up to 4 (or 8) requests in flight; byte-budget mode allows **64 × 32 KB / 16 × 128 KB** (non-premium). **With `totalBytesCount == 0`, one request at a time.**
- **`upload.file`** (:2658 → `processRequestResult` :1858): only `bytes` is read.
  - Empty `bytes` means done.
  - With a known size, done when `downloaded >= totalBytesCount` (:1956), and no request goes past the size (:2412).
  - With an unknown size, done on a chunk shorter than `limit` (:1965).
  - **So return exactly `min(limit, size − offset)` bytes, and make `document.size` / `photoSize.size` exact or 0.** A size that is too big never completes; one that is too small truncates.
- **CDN:** the client switches to `getCdnFile` only after `upload.fileCdnRedirect` (:2592). Just never return it.
- **Errors** (:2096-2160):

| Error | Client behaviour |
|---|---|
| `FILE_REFERENCE_*` | refetches via `messages.getMessages`, `channels.getMessages`, `users.getFullUser`, `photos.getUserPhotos` (`FileRefController`:354, :546-690). **Never return it.** Any non-null `file_reference` works |
| `LIMIT_INVALID` | retries with 32 KB chunks |
| `FILE_MIGRATE_X` | switches DC |
| `OFFSET_INVALID` | treated as EOF if aligned |
| `-2000` | treated as cancel, so don't use it |
| other | `onFail` (:2159) |

  Map Mattermost 404 to `400 FILE_ID_INVALID`.
- **Validity gates** (`start()`):
  - Photo/avatar: `datacenterId` must not be 0 or `MIN_VALUE` (:989-993).
  - Document: `dc_id != 0 && id != 0` (:1013).
  - `ImageLocation` returns null when `photo.dc_id == 0 && location.dc_id == 0` (IL:136/236/280).
  - Memory cache needs `id != 0 && dc_id != 0` (:478; `ImageReceiver`:654).

### 8.3 Cache keys (`FL.getAttachFileName` :1653, `IL.getKey` :460)

| Object | Disk name |
|---|---|
| Document | `{dc_id}_{id}{ext}` |
| Photo size | `{volume_id}_{local_id}.jpg` |
| Avatar | `-{photo_id}_97.jpg` small ('a') / `-{photo_id}_99.jpg` big ('c') |

`volume_id`/`local_id` are **only synthesised during deserialisation**:
- `PhotoSize.TLdeserialize` (TL:44433) sets `volume_id = -photo.id`, `local_id = type char`. Document thumbs get `-doc.id`, `1000+char`.
- `TL_userProfilePhoto` / `TL_chatPhoto.readParams` (TL:32331, :713) build `photo_small`/`photo_big` and decode `strippedBitmap`.

This is the strongest reason for the §1.2 round-trip: hand-built objects give null locations and `ImageLocation` returns null (IL:378). Ids must be stable, and a new avatar must get a new `photo_id` to bust the cache.

### 8.4 Upload (FUO)

| Item | Value |
|---|---|
| Big file | `totalFileSize > 10 MB` → `saveBigFilePart{file_id, file_part, file_total_parts (−1 while encoding), bytes}`; otherwise `saveFilePart{file_id, file_part, bytes}` (:323, :525-541) |
| Part size | max(128 KB small / 512 KB big / 32 KB slow, size ÷ `uploadMaxFileParts` 4000), a divisor of 1024 KB (:331-350) |
| `file_id` | `Utilities.random.nextLong()` (:445). **Resumed after an app restart with the same id**, for 1.5 h (small) or 24 h (big) (:358-400). Keep parts keyed by `file_id` that long |
| Concurrency | 8 parts at once, up to 8 MB in flight (:45-47, :339). Parts arrive **out of order**, and part 0 may arrive last (`uploadFirstPartLater`, :271-279, :505-546). Assemble by `file_part` |
| Response | `boolTrue` (:609). Anything else fails the whole upload (:701). Calling `onWriteToSocket` speeds up the pipeline (:703) |
| Completion | `TL_inputFile{id, parts, name, md5_checksum=""}` / `TL_inputFileBig{id, parts, name}` (:626-635) → `fileUploaded` → SMH:1025 → `inputMediaUploadedPhoto.file`, or `inputMediaUploadedDocument.file` / `.thumb` (flag 4) |

**Bytes gotcha:** on the native path `sendRequestInternal` calls `object.freeResources()` after serialising (CM:428-429). That recycles `saveFilePart.bytes`. The bridge hooks in **before** that, so the bytes are intact. Copy them, then call `object.freeResources()` yourself to return the buffer to the pool.

On `sendMedia` / `uploadMedia`, the bridge:
1. concatenates parts `0..parts-1` (file on disk, not heap);
2. calls `POST /api/v4/files` (multipart `channel_id`, `files`) → `file_infos[]`;
3. calls `POST /api/v4/posts{file_ids}`;
4. returns media built from each FileInfo.

### 8.5 Mapping Mattermost files to TL

**Ids**
- A Mattermost id is 26 chars of base32 over 16 bytes (alphabet `ybndrfg8ejkmcpqxot1uwisza345h769`).
- `id = high64 & Long.MAX_VALUE` (non-zero), `access_hash = low64`. Keep a persistent `tl_id → mm_file_id` table (salted separately for photos and documents so the `-id_*` names never collide).
- `file_reference = {1}`. `dc_id` is a fixed non-zero value (e.g. 2) everywhere.

**`TL_photo`** (images with `has_preview_image`)
- Fields: `id`, `access_hash`, `file_reference`, `date`, `dc_id`, `sizes`.
- Sizes:

| `photoSize` | Max px | Source |
|---|---|---|
| `'s'` | ≤100 | `/api/v4/files/{id}/thumbnail` |
| `'x'` | ≤800 | `/files/{id}/preview` |
| `'y'` | ≤1280 | `/files/{id}/preview` |
| `'w'` (optional) | — | `/files/{id}` |

- Give each size the correct scaled `w`/`h`; the client uses `getClosestPhotoSizeWithSize(sizes, 1280)`. `size` must be exact, or 0 for sequential download.
- Route by `inputPhotoFileLocation.thumb_size`.
- On send, return the same `type`s and `w`/`h` as the client's local sizes so that `updateMediaPaths` renames them instead of re-downloading (SMH:8823).

**Stripped thumbs**
- `TL_photoStrippedSize` uses Telegram's header-less JPEG (`ImageLoader.getStrippedPhotoBitmap` :1806), with a fixed header and quant tables.
- Either generate a ≤40 px JPEG with those tables or leave it out. Mattermost's `mini_preview` is a full JPEG; it could be sent as `TL_photoCachedSize` instead.

**`TL_document`** (files, video, audio)
- Fields: `id`, `access_hash`, `file_reference`, `date`, `mime_type`, `size = FileInfo.size` (**exact**), `dc_id`, `thumbs=[photoSize 'm' → /thumbnail]` (optional).
- `attributes`: `documentAttributeFilename`, plus ImageSize, Video or Audio as fits.
- `thumb_size=""` → the original, served with `Range: bytes=offset-(offset+limit-1)` on `/api/v4/files/{id}`. Verify the storage backend honours Range.
- Better: on the first request for a location, start one streaming GET into a disk cache and serve 32/128 KB slices from it as they land. There are 16-64 parallel requests per file.
- Fetch thumbnails, previews and avatars whole.

**Avatars**
- Users: `TL_userProfilePhoto{photo_id = hash(userId, last_picture_update) ≠ 0, dc_id ≠ 0, stripped_thumb? (flag 1)}`.
- Megagroups: `TL_chatPhoto` the same way, with a non-zero `chat.access_hash` (IL:263).
- Download requests come as `inputPeerPhotoFileLocation{peer, photo_id, big}` → `GET /api/v4/users/{mm id}/image?_={last_picture_update}`. Big and small can be the same image.
- The size is unknown, so the client sends 32 KB chunks one at a time until a short chunk arrives. Prefetch the whole image on the first request.
- Return `userProfilePhotoEmpty` when `last_picture_update == 0`; the client then shows its letter avatar.

---

## 9. Hiding Telegram-only features

13.2.2 has **no `DrawerLayoutAdapter`**. Navigation lives in three places:
- bottom tabs in `MainTabsActivity`;
- the overflow `ItemOptions` in `DialogsActivity.showItemOptions()`;
- a separate `SettingsActivity`.

Counts below come from `grep -rF … | wc -l` over `ui/` and `messenger/`.

### 9.1 `RelayFeatures`

```java
package org.telegram.messenger;
public final class RelayFeatures {
    private RelayFeatures() {}
    // compile-time constants: javac folds `if (RelayFeatures.X)` and R8 strips dead code.
    // Never read them from prefs / make them non-final (BuildVars.IS_BILLING_UNAVAILABLE is a plain
    // `static boolean`, BuildVars.java:41, and is never folded).
    public static final boolean STORIES = false, PREMIUM = false, STARS_PAYMENTS = false, GIFTS = false,
        BUSINESS = false, WALLET = false, STICKERS = false, GIFS = false, TRENDING = false,
        PHONE_CONTACTS = false, SECRET_CHATS = false, TG_CALLS = false /* LiveKit later */, BOT_WEBAPPS = false,
        INLINE_BOTS = false, CHANNELS = false, BOOSTS_STATS = false, SPONSORED = false, FRAGMENT = false,
        QR_LOGIN = false, PASSPORT = false, PROXY = false, TG_SUPPORT = false, TRANSLATE = false,
        TELEGRAM_LOGIN = false;
    public static boolean isFragmentAllowed(org.telegram.ui.ActionBar.BaseFragment f) { /* instanceof deny-list */ }
}
```

Per-flavor values can use `buildConfigField "boolean"`. `BuildConfig` constants fold the same way.

**Data-side hiding comes for free.** The bridge builds every `TL_user`, `TL_userFull` and `TL_channel`. It never sets these fields, which hides a lot of UI without touching it:
- `premium`, `emoji_status`, `stories_max_id`, `stories_unavailable=false`
- `phone_calls_available`, `video_calls_available`
- `stargifts_count`, `stories_pinned_available`, `bot_info`, `business_*`
- `pending_suggestions` in appConfig

### 9.2 Existing central predicates (patch these first)

| Predicate | Def | Semantics | Uses |
|---|---|---|---|
| `premiumFeaturesBlocked()` | MC:742 | `premiumLocked && !isPremium()`. `premiumLocked` comes from appConfig `premium_purchase_blocked` (MC:2826) | 89 (38 files) |
| `premiumPurchaseBlocked()` | MC:745 | `premiumLocked` | 10 |
| `starsPurchaseAvailable()` | MC:739 | `!starsLocked` (`stars_purchase_blocked`, MC:2836) | 15 |
| `storiesEnabled()` | MC:23807 | posting only. Viewing is not gated | 13 |
| `isPremiumUser(User)` | MC:927 | premium badges (e.g. `DialogCell`:1556) | 15 |
| `UserConfig.isPremium()` | UC:575 | self premium. Leave it alone: it is false because `premium=false` | 354 |
| `StoriesController.hasStories(long)` / `hasStories()` | `ui/Stories/StoriesController.java`:250 / :283 | rings, dialogs story header | 24 |
| `isSponsoredDisabled()` | MC:24462 | premium only | 4 |
| `TranslateController.isChatTranslateEnabled()` / `isContextTranslateEnabled()` | `TranslateController`:111 / :121 | translate | 3 / 11 |
| `config.walletAvailable` | `AppGlobalConfig`:115 | TON wallet | 15 |
| `UserConfig.showCallsTab` | UC:316 | calls vs settings in tab 2 | 15 |

Patches:
- `premiumFeaturesBlocked()` returns `!RelayFeatures.PREMIUM || (premiumLocked && !isPremium())`.
- `premiumPurchaseBlocked()` returns `!PREMIUM || …`.
- `starsPurchaseAvailable()` returns `STARS_PAYMENTS && !starsLocked`.
- `storiesEnabled()`, `hasStories*` and `StoriesController.loadStories` / `loadAllStories` start with `if (!STORIES) return false/return;`.
- `isPremiumUser()` returns `PREMIUM && …`.

Also return `premium_purchase_blocked=true` / `stars_purchase_blocked=true` from `help.getAppConfig` (§3.1) as defence in depth.

### 9.3 Entry points per feature

**Stories**
- Entry points:
  - Header `dialogStoriesCell` DA:5080, visibility `updateStoriesVisibility()` DA:12421.
  - Loads at DA:2874, 2883-2886 (`StoriesController`:189/287/308).
  - Camera FAB `floatingButtonStories` DA:4651 (visibility :8786, hint :4683).
  - Rings: `StoriesUtilities.drawAvatarWithStory` :99/:122 (11 sites).
  - Profile tabs: `SharedMediaLayout`:6700; post/archive `ProfileActivity`:3524/3652; `KEY_STORY` `ProfileActivity`:12592.
  - Repost CA:40349. `StoryRecorder.getInstance` (17 sites).
  - Upsell DA:13171.
- **Choke:** `hasStories*` + load early-return + `storiesEnabled()`, and null-guard the FAB.

**Premium**
- Entry points:
  - Settings rows `SettingsActivity`:816 (11 Premium), :821 (12 Stars), :827 (13 Gram), :831 (15 Business), :834 (16 Gift).
  - Legacy self profile rows `ProfileActivity`:11019-11035.
  - `new PremiumPreviewFragment` (52 sites), `new PremiumFeatureBottomSheet` (53).
  - Premium stickers are already gated (`filterPremiumStickers` MC:956/972).
- **Choke:** the 9.2 predicates, the fragment net (#1 below), and a guard in `PremiumFeatureBottomSheet.show()`.

**Stickers / GIFs / trending**
- Entry points:
  - `MDC.checkAllMedia()` MDC:8865 ← DA:2915.
  - `checkFeaturedStickers` MDC:897 (DA:13143), `checkStickers(TYPE_EMOJI)` DA:6966.
  - Panel tabs `EmojiView.setAllow` :291; `ChatActivityEnterView.setAllowStickersAndGifs` :6392 (CA:8099/2301/23578).
  - Trending `EmojiView`:5662. Suggestions `StickersAdapter`:57, `MentionsAdapter`:1065.
  - `ThemeActivity`:792 `stickersRow`. `new StickersAlert(` (26 sites).
- **Choke:** in `EmojiView.setAllow`, apply `allowStickers &= STICKERS; allowGifs &= GIFS`, and gate `checkAllMedia`.
- **Keep answering the §3.3 sticker stubs regardless.** Some loads run from constructors and loop on errors.

**Contacts / phone**
- Entry points:
  - `ContactsController.checkAppAccount()` :389 (`ApplicationLoader`:274, LoginA:1693), `checkContacts()` :466, `readContacts()` :599.
  - Permission prompts DA:6982/10298, `MainTabsActivity`:300, `ContactsActivity`:1081.
  - `ContactsAdapter`:589-595 (Invite Friends / Recent Calls).
  - Privacy `phoneNumberRow` `PrivacySettingsActivity`:752, `contactsSyncRow`/`SuggestRow` :806-808.
  - Profile `phoneRow` `ProfileActivity`:11073.
- **Choke:** `syncContacts = PHONE_CONTACTS && …` at UC:315 (32 refs), plus early returns in `checkAppAccount` and `readContacts`.

**Payments / Stars / gifts / business / wallet**
- Entry points:
  - Gift button `ChatActivityEnterView.updateGiftButton()` :11130.
  - Profile `KEY_GIFT` (`ProfileActivity`:9405/10707/12596), `gift_premium` item :12412, `TAB_GIFTS` (`SharedMediaLayout`:6701).
  - DA:6009 (birthday gift), :6296 (auctions). `new StarsIntroActivity(` (12 sites).
  - Wallet: `SettingsActivity`:794/811, DA:13400, `ProfileActivity`:12415.
- **Choke:** `starsPurchaseAvailable()`, `premiumPurchaseBlocked()`, and force `walletAvailable=false`.

**Bots / web apps / inline**
- Entry points:
  - Attach-menu bots `ChatAttachAlert`:6882-6891. Side-menu bots DA:13404, `SettingsActivity`:881.
  - Bot menu button `ChatActivityEnterView.updateBotButton()` :11492.
  - Inline `MentionsAdapter.searchForContextBot` :683; `inlineBots` in `MDC.loadHints` :5192.
- **Choke:** `MDC.getAttachMenuBots()` (MDC:521) returns empty; skip `checkMenuBots` (:442); gate `LaunchActivity.showAttachMenuBot` (:876); early-return in `searchForContextBot`.
- Keep bot commands if Mattermost slash commands get mapped onto them.

**Secret chats**
- Entry points:
  - `start_secret_chat` item `ProfileActivity`:12418 (handled :2788), key/timer rows :11139-11141.
  - The `createSecretChat` arg in `ContactsActivity`:196 has no callers.
- **Choke:** `SecretChatHelper.startSecretChat` :1941, plus hiding the menu item. `EncryptedChat` references (522) do nothing without data.

**Calls / video chats (LiveKit later)**
- Entry points:
  - Chat header CA:24786 (`showAudioCallAsIcon = userInfo.phone_calls_available`).
  - Profile action bar `ProfileActivity`:4010-4020; `KEY_CALL`/`VIDEO`/`VOICE_CHAT`/`STREAM` :12588-12594; voice chat :12453/12531.
  - Calls tab `showCallsTab` (`MainTabsActivity`:369/457/835). Recent calls `MainTabsActivity`:440, `ContactsAdapter`:593.
  - `DataSettingsActivity`:195, `PrivacySettingsActivity`:755.
- **Choke:** `VoIPHelper.startCall(User…)` :77 and `startCall(Chat…)` :129 (33 sites). Re-route these to LiveKit in A8, and mask `phone_calls_available`.

**Channels, boosts, stats, ads and misc.**

| Feature | Location |
|---|---|
| New Channel | `ContactsAdapter`:604-607, `ContactsActivity`:576-585. `new ChannelCreateActivity` at LA:630, `LinkManager`:241, `ActionIntroActivity`:544, `ChooseChannelFragment`:166 |
| Stats / boosts | `ProfileActivity`:12462, `ChatEditActivity`:1299, `BoostDialogs` (47) |
| Sponsored | CA:25778, `getSponsoredMessages` MC:21729, `DialogsSearchAdapter`:1135, `VideoAds`:199 |
| Fragment usernames | `ChangeUsernameActivity`:691/729, `ProfileActivity`:7632 |
| QR login | `SessionsActivity`:803 |
| Passport | `PrivacySettingsActivity`:786, LA:5296 |
| Proxy | `DataSettingsActivity`:199-200, DA:13440, LoginA:793, `LinkManager`:1034 |
| Support | `SettingsActivity`:840-843 (17 Ask, 18 FAQ, 23 Features, 19 Policy) |
| Translate | `isFeatureAvailable` :93 (16) |
| Deep links | `LaunchActivity.handleIntent` :1553 (e.g. boost :2170, `tg://resolve` :2338) |

### 9.4 Navigation surfaces

**`MainTabsActivity`**
- `TABS_COUNT=4` (:97): CHATS 0, CONTACTS 1, CALLS_OR_SETTINGS 2, PROFILE 3 (:98-102).
- Built at :330-334. Factory `createBaseFragmentAt` :827-857.
- Long-press: `openContactsSelector` :432, `openCallsSelector`.

**`DA.showItemOptions()`** DA:13294

| Item | Line |
|---|---|
| archive | :13339-13340 |
| theme | :13352 |
| New Group | :13388 |
| Saved Messages | :13392 (keep: maps to Mattermost saved/self-DM) |
| wallet / side-menu bots | :13400-13427 |
| Settings | :13431 |
| proxy | :13440-13453 |

**`SettingsActivity.fillItems()`** :717

| Row ids | Rows | Line |
|---|---|---|
| — | suggestions | :745-780 |
| — | accounts | :784 |
| 25 | Wallet | :794/811 |
| 1, 2, 3, 5, 6, 7, 8, 9, 10 | Account, Chat, Privacy, Notifications, Data, Folders, Devices, Power, Language | :798-806 |
| 11-16 | premium group | :816-834 |
| 17, 18, 23, 19 | support | :840-843 |
| 24, 26, 27, 20-22 | debug | :848-858 |

- Clicks are handled in `onClick` :880 (switch from :920).
- Settings search is `ProfileActivity.SearchAdapter.onCreateSearchArray` :14713 (141 entries). It must be filtered too.

**`ProfileActivity` (self, legacy)**
- Rows in `updateRowsIds` :10977-11053; `ProfileActionsView` keys :89-106.
- Row-index adapters (`rowCount++` in `ProfileActivity`, `PrivacySettingsActivity`, `DataSettingsActivity`, `SessionsActivity`) must skip the `rowCount++` itself, not just hide the view. Otherwise blank cells appear.

### 9.5 Highest-leverage hook points

| # | file:line | What to gate | Flag |
|---|---|---|---|
| 1 | `ui/ActionBar/ActionBarLayout.java`:2061 `presentFragment(NavigationParams)` | single navigation sink. Refuse PremiumPreviewFragment, StarsIntroActivity, ChannelCreateActivity, PassportActivity, ProxyListActivity, StatisticActivity, Business*, CallLogActivity | `isFragmentAllowed` |
| 2 | MC:742-747 | `premiumFeaturesBlocked` / `premiumPurchaseBlocked` → true (99 sites) | PREMIUM |
| 3 | MC:739 | `starsPurchaseAvailable` → false | STARS_PAYMENTS |
| 4 | MC:23807 | `storiesEnabled` → false | STORIES |
| 5 | `StoriesController`:250, 283, 189, 287 | `hasStories*` false, loads no-op | STORIES |
| 6 | MC:927 | `isPremiumUser` → false (badges) | PREMIUM |
| 7 | `SettingsActivity`:717 | rows 11-16, 17-23, 25 | PREMIUM/GIFTS/BUSINESS/TG_SUPPORT/WALLET |
| 8 | `ProfileActionsView`:565 `set(key, …)` | force off GIFT, CALL, VIDEO, VOICE_CHAT, STREAM, STORY | GIFTS/TG_CALLS/STORIES |
| 9 | `EmojiView`:291 `setAllow` | sticker/GIF tabs | STICKERS/GIFS |
| 10 | MDC:8865 `checkAllMedia`, MDC:521 `getAttachMenuBots` | preloads; empty bot list | TRENDING/BOT_WEBAPPS |
| 11 | `VoIPHelper`:77, 129 | calls (→ LiveKit in A8) | TG_CALLS |
| 12 | `MainTabsActivity`:827-857, 369 | tab set | TG_CALLS/PHONE_CONTACTS |
| 13 | DA:13294, 4651, 2874-2886 | overflow items, story FAB, story loads | STORIES/PROXY/BOT_WEBAPPS |
| 14 | UC:315, `ContactsController`:389, 599 | `syncContacts=false`, no phone-book read, no prompts | PHONE_CONTACTS |
| 15 | CA:24786, 25778 | call icon; sponsored | TG_CALLS/SPONSORED |
| 16 | `ContactsAdapter`:337-374, 589-607 | Invite / Recent Calls / New Channel rows | PHONE_CONTACTS/CHANNELS |
| 17 | `ProfileActivity`:12412-12531, 14713 | gift/wallet/secret/voice-chat/stats items; settings search index | multiple |
| 18 | LA:1122-1127, 1295 | Telegram login/intro → `RelayLoginActivity` (§2.4) | TELEGRAM_LOGIN |
