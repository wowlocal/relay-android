# Relay for Android — notifications and push

Phase A7c. How Relay notifies today, and what is needed for real push (FCM through a Mattermost push proxy).

## What works without push

| Situation | How new posts notify |
|---|---|
| App open or recently backgrounded (process alive) | The account's WebSocket (`RelayLive`) turns `posted` into Telegram updates; Telegram's `NotificationsController` shows the Android notification. |
| "Keep connected in background" on (Settings → Notifications) | `RelayKeepAlive`, a foreground service (`remoteMessaging` on Android 14+, `dataSync` before), keeps the process and its WebSocket alive; same path as above. A permanent low-priority notification "Relay is connected" is shown. Tested: a post 2 minutes after backgrounding notifies. |
| Process killed, keep-alive off | Nothing until the app starts again (the catch-up then shows what was missed). Needs FCM, below. |

Which posts notify follows Mattermost, not Telegram (`RelayNotifications.shouldNotify`, called from
`NotificationsController.processNewMessages`):

- own status **Do Not Disturb** → nothing;
- the push level: the channel's `notify_props.push`, or the user's `notify_props.push` when the channel says `default`;
  `none` → nothing, `mention` → DMs/GMs and mentions, `all` → everything;
- a **muted** channel or DM (`mark_unread=mention`) → mentions only (Telegram's own per-chat mute is kept equal to it).

Settings mapping:

| Telegram UI | Mattermost |
|---|---|
| Mute / unmute a chat (chat header, chat list, the chat's notification screen) | `PUT /channels/{id}/members/me/notify_props {mark_unread: mention / all}` |
| Settings → Notifications → Private chats / Groups off | user `notify_props.push = none` (on again: `mention`, or the level the user had) |
| A mute changed on another device | `channel_member_updated` → Telegram `updateNotifySettings` |

Reply and "Mark as read" from a notification are Telegram's (`WearReplyReceiver`, `AutoMessageHeardReceiver`):
they go through the bridge like any send/read while the process runs.

## Real push (FCM) — prepared, off

The code path is in place behind `RelayNotifications.FCM_PUSH` (`false`):

- `ApplicationLoader.initPushServices` requests the FCM token only when the flag is on (with the dummy
  `google-services.json` a token request just opens Google sockets, so it stays off).
- The token reaches the bridge as Telegram's `account.registerDevice`; the bridge attaches it to the Mattermost
  session: `PUT /users/sessions/device {device_id: "android_rn-v2:<token>"}` (the prefix the official Mattermost
  mobile app uses with push proxy v2; the proxy routes on the part before `-v2`).
- `GcmPushListenerService.onMessageReceived` hands Mattermost proxy payloads to `RelayNotifications.onPushMessage`
  instead of Telegram's encrypted `p` payload. It starts the app's accounts (bridge + WebSocket) and runs a
  catch-up (`getDifference`); the notification then comes from Telegram's NotificationsController like a live
  message, with all of the rules above. Payload fields used/expected: `type` (`message`, `clear`, `update_badge`,
  `session`), `channel_id`, `post_id`, `root_id`, `sender_id`, `sender_name`, `channel_name`, `message`, `badge`,
  `server_id`, `ack_id`, `version` (`v2`).

### What the owner must provide

1. **A Firebase project** with Android apps `org.relaychat.android` and `org.relaychat.android.debug`
   (the debug build's id) and its real `TMessagesProj_App/google-services.json` (replacing the dummy entries; keep it
   out of git if the project is private).
2. **A Firebase service account** (JSON key with the *Firebase Cloud Messaging API (V1)* role) for that project.
3. **A Mattermost push proxy** ([mattermost-push-proxy](https://github.com/mattermost/mattermost-push-proxy)) that the
   server can reach, configured with an Android entry for Relay, e.g. in `config/mattermost-push-proxy.json`:
   ```json
   "AndroidPushSettings": [
     { "Type": "android_rn", "ServiceFileLocation": "/path/to/relay-firebase-service-account.json" }
   ]
   ```
   The official `push.mattermost.com` cannot deliver to Relay: it holds Mattermost's own Firebase credentials, which
   only reach Mattermost's app package.
4. **Mattermost server settings** (System Console → Environment → Push Notification Server): *Enable Push
   Notifications* on, server = the proxy's URL; *Push Notification Contents* = "Full message content" (or
   "id_loaded" — Relay fetches the post anyway).
5. Then set `RelayNotifications.FCM_PUSH = true`, build, log in, and check the session's device in
   `GET /users/me/sessions` (`device_id` starts with `android_rn-v2:`).

Open after that: a notification straight from the payload when the catch-up cannot run (no network on wake), the
`clear` payload removing notifications read elsewhere (today: the catch-up's read state), and badge-only
`update_badge`.
