# Relay for Android — calls (phase A8)

Relay calls run on self-hosted LiveKit through the Mattermost plugin `org.relaychat.calls` (contract version 2,
the same wire contract as iOS and macOS: `relay-calls-integration/ios-device/docs/livekit-calls-native-ui.md`).
The front end is Telegram's own call UI: `VoIPService` + `VoIPFragment` for 1:1 calls (with Telegram's incoming
call notification), `GroupCallActivity` and the chat's join panel for channel calls. Nothing Telegram-specific is
reached: tgcalls never starts, the bridge answers Telegram's `phone.*` methods.

## WebRTC: two copies side by side

Telegram ships its own WebRTC: Java classes in `org.webrtc` (TMessagesProj sources) and the native part compiled into
`libtmessages.49.so` with tgcalls. LiveKit's Android SDK (`io.livekit:livekit-android`) depends on
`io.github.webrtc-sdk:android-prefixed`, which is the same WebRTC **relocated**: Java classes in
`livekit.org.webrtc`, JNI symbols `Java_livekit_org_webrtc_*`, native library `liblkjingle_peerconnection_so.so`
(checked with `llvm-nm`: 225 `Java_livekit_*` exports, no `Java_org_webrtc_*`). So both live in the APK without
duplicate classes, JNI clashes or exclusions; the `checkAfatDebugDuplicateClasses` task passes. Android loads each
native library with its own symbol scope, so the two C++ WebRTC builds do not interpose either.

Decision: no relocation or exclusion of our own; LiveKit **2.30.0** (minSdk 21, compiled with Kotlin 1.9 — fine with
the project's Kotlin Gradle plugin 2.1.0; minSdk 23 and AGP 8.13 unchanged). In Relay mode only LiveKit's copy does
media; Telegram's copy is still used for rendering (`org.webrtc.TextureViewRenderer` in Telegram's screens): LiveKit
frames are converted (`RelayFrames`: LiveKit `VideoFrame` → I420 planes wrapped, not copied, into Telegram's
`JavaI420Buffer`). APK growth: about 13 MB per ABI (`liblkjingle` 12 MB, `liblivekit_ffi`, `libjnidispatch`), plus
LiveKit's Kotlin/OkHttp/protobuf-lite dex. LiveKit's `audioswitch` fork is on JitPack only (repository filtered to
`com.github.davidliu`).

## Modules

| Where | What |
|---|---|
| `relay-livekit/` (Android library, Kotlin) | `RelayRoom`: one LiveKit room with a Java-friendly API (callbacks, snapshots on the main thread), port of iOS `RelayRoomEngine`: connect/disconnect, microphone, camera (preview capture separate from publishing), screen share (MediaProjection), remote/local frame sinks, per-user volume. LiveKit runs with `NoAudioHandler`: audio routing stays with VoIPService. |
| `relay-mattermost/…/calls/` (pure Java, JVM tests) | Port of iOS `RelayCallsCore`: `RelayCall` models, `RelayCallsClient` (plugin REST), `RelayCallsEvent` (WebSocket events), `RelayDirectCall`, `RelayGroupCall`, `RelayLocalSource` (microphone/camera retries), `RelayMedia.Engine`. 52 JVM tests (`./gradlew test --tests '*calls*'`). |
| `TMessagesProj/…/relay/calls/` | `RelayCallManager` (per account: events, `phone.*` for 1:1), `RelayGroupSession` (video chats), `RelayVoip` + `RelayPrivateInstance`/`RelayGroupInstance` (the "tgcalls instances"), `RelayLiveKitEngine`, `RelayCallRecord`. |
| `TMessagesProj/…/relay/bridge/RelayCallsTl.java`, `RelayGroupCallsTl.java` | The bridge handlers. |

## How Telegram's flow maps (1:1)

| Telegram | Relay |
|---|---|
| `messages.getDhConfig` | Telegram's well-known 2048-bit prime, g = 3 (passes `isGoodPrime`) |
| `phone.requestCall` | `RelayDirectCall.outgoing` → `POST /channels/<dm>/ring`; answered `phoneCallWaiting` once the plugin has the call |
| callee device `received` | `updatePhoneCall(phoneCallWaiting.receive_date)` → "Ringing", ringback |
| callee answered | `updatePhoneCall(phoneCallAccepted{g_b})` → Telegram's `confirmCall` → `phoneCall` |
| plugin `incoming` event | `updatePhoneCall(phoneCallRequested)` → Telegram's incoming call notification (full-screen intent, `VoIPPreNotificationService`) |
| `phone.acceptCall` | `POST /calls/<id>/accept`; then `updatePhoneCall(phoneCall)` |
| `phone.discardCall` | hang up / decline (`busy` for a second ring) |
| any end | `updatePhoneCall(phoneCallDiscarded)`: declined/busy → busy (busy tone), missed/cancelled → missed, failed → disconnect, else hangup |
| tgcalls instance | `RelayPrivateInstance`: Connecting until the peer's device is in the room, established, reconnecting, failed; signal bars from LiveKit's connection quality; remote mic/camera state; remote video (camera or screen) into Telegram's renderer |
| `setMuteMicrophone`, `setVideoState`, `switchCamera`, screencast | the controller (microphone retries, camera, screen share) |

The DH values are played by the bridge so Telegram's own checks pass; LiveKit's transport encryption protects the
media, not Telegram's key, so the emoji key is hidden. The microphone publishes only after the answer (as iOS); a
video call's camera publishes on connect; a camera preview (Telegram's video preview sheets) captures without
publishing until "Share Video". Ring timeout: the plugin's (45 s); Telegram's own timers are longer.
ConnectionService: Telegram-Android disables it (`isDeviceCompatibleWithConnectionServiceAPI` returns false); Relay
rings exactly as Telegram does (notification with full-screen intent, ringtone).

## Video chats (channels and group messages)

Telegram's `createGroupCall` / `joinGroupCall` / `getGroupCall` / `getGroupParticipants` / `editGroupCallParticipant`
/ `leaveGroupCall` / `discardGroupCall` / `join|leaveGroupCallPresentation` / `checkGroupCall` are answered from a
`RelayGroupCall`: one Telegram participant per Mattermost user (devices merged), synthetic SSRCs, video endpoints
`relay:camera:<user>` / `relay:screen:<user>` (as iOS), speaking from audio levels, `updateGroupCallParticipants`
on every change. `channels.getFullChannel` carries the channel's live call (`chatFull.call`, `call_active`) and plugin
events push `updateGroupCall`, so the chat shows Telegram's join panel. Any member may start a call
(`RelayFeatures.canStartGroupCall`, iOS's `canStartRelayCall`); in a group message the start rings the members.
Hidden: admin menu (`canManageCall` false: titles, recording, join muted, end for everyone), invite links / "Invite
Members", scheduling, RTMP streams, join-as.

## Call records

The plugin's `custom_relay_call` posts become `messageActionPhoneCall` (direct; reason and duration, video flag) or
`messageActionGroupCall` (group/channel; duration once ended) — Telegram's call bubbles with "call back". The plugin
edits the post when the call ends; the edit updates the bubble.

## Test bed

- `adb reverse tcp:8065 tcp:8065`, `tcp:7880 tcp:7880`, `tcp:7881 tcp:7881`. UDP 7882 cannot be reversed: LiveKit's
  ICE falls back to TCP on 7881 (candidate `127.0.0.1:7881`), which works through `adb reverse` without changing
  `dev-livekit/livekit.yaml` (iOS simulators keep using UDP).
- The emulator ran with `-no-audio`: LiveKit's audio device still produces frames (100 frames/s of near silence),
  which is what the test checks. Cameras: `-camera-front emulated` (moving pattern), `hw.camera.back=virtualscene`.
- `Tools/relay-e2e/test_a8_calls.py` drives the app (dave) against the scripted peer (bob,
  `relay-calls-peer.py` with the `media-probe-venv` Python).

## Group-message rings (polish 2a)

An incoming group ring (`kind: group`, ringing for this user) rings with Telegram's own incoming group call
(conference) notification and screen (`VoIPGroupNotification`: full-screen intent, Answer / Decline, ringtone) through
`RelayGroupRing`; the caller's name and photo, the GM's title. Answer loads the GM's call (`chatFull.call`) and joins it
through Telegram's join path; `joinGroupCall` of a call still ringing for the user accepts the ring on the plugin
(`RelayGroupCall.incoming(…).accept()`, as iOS) instead of a plain join. Decline declines on the plugin; a ring that
stops (answered elsewhere, caller gone, expired) is hidden, and an unanswered ring ends as missed on the plugin.

## Known gaps

- No push wake-up for rings while the process is dead (rings arrive over the WebSocket; the keep-alive service or
  FCM from A7c keeps it alive). The plugin's VoIP push is APNs-only.
- Per-user volume works for the local mix only; "mute for everyone" is not backed (admin features hidden).
- Telegram's call log tab stays hidden (it needs `messages.search` with the phone-calls filter).
