# relay-mattermost

Pure-Java Mattermost client library for Relay for Android (phase A1 of `docs/relay/PLAN.md`).
It is a port of the iOS `MattermostKit` (REST, WebSocket, models) and of the message-id allocator
from `MattermostSyncCore`. The TL bridge (`org.telegram.relay.bridge`) will sit on top of it.

- Package `org.telegram.relay.mattermost` (models in `.model`, WebSocket in `.ws`, id allocator in `.ids`).
- Dependencies are only what the app already ships: `gson 2.11.0` and `nv-websocket-client 2.14`.
  HTTP goes through `HttpURLConnection`.
- The sources are plain Java 8-style code. They avoid every API that minSdk 23 lacks without
  desugaring (no `java.time`, `java.util.function`, streams, `Optional`, `List.of`, `Map` default
  methods). `src/main/java` compiles with `-source 8` against `android.jar` and dexes with
  `d8 --min-api 23` without warnings. The app module can add `relay-mattermost/src/main/java` as a
  source dir, because nothing in it depends on Gradle.

## API

`MattermostClient` methods are synchronous. Run them on a background thread. They throw
`MattermostException`, which carries the kind (`HTTP`/`TRANSPORT`/`DECODING`), the status code, the
server error id and `Retry-After`. It also has helpers: `isUnauthorized`, `isNotFound`,
`isTransient`, `isMfaRequired`.

| Area | Calls |
|---|---|
| Server | `ping` (+ `X-Version-Id`), `clientConfig` (`format=old`), `clientLicense`, `serverVersion`, `isServerVersionAtLeast` |
| Auth | `login(loginId, password, mfaToken, deviceId)` (stores the token from the `Token` header), `logout`, `attachDevice`, `isTokenAccepted`, `Listener.onUnauthorized` |
| Users | `me`, `user`, `userByUsername`, `users(ids[, since])`, `usersByUsernames`, `searchUsers`, `usersInChannel`, `userStatus`, `userStatuses`, `updateStatus`, `updateCustomStatus`, `removeCustomStatus`, `profileImage(userId, lastPictureUpdate)` (`_=` cache key), `profileImageUrl`, `preferences`, `savePreferences`, `publishTyping` (REST) |
| Teams | `teams`, `team`, `teamMembers`, `teamsUnread` |
| Channels | `channels(teamId)`, `allChannels` (all teams), `channelMembers(teamId)`, `allChannelMembers` (read state: `last_viewed_at`, `msg_count`, `mention_count` and the `_root` variants), `channel`, `channelByName`, `channelMember`, `channelStats`, `createDirectChannel`, `createGroupChannel`, `createChannel`, `add/removeChannelMember`, `viewChannel` (mark read), `updateChannelNotifyProps` |
| Posts | `posts(channelId, PostsQuery.latest/page/before/after/since)`, `postsAroundUnread`, `post`, `postThread(ThreadQuery)`, `pinnedPosts`, `postFiles`, `createPost(PostDraft)`, `editPost`, `patchPost`, `updatePost`, `deletePost`, `pin/unpinPost`, `markPostUnread`, `searchPosts`, `flaggedPosts` |
| Reactions | `addReaction`, `removeReaction`, `reactions(postId)` |
| Threads (CRT) | `userThreads(teamId, UserThreadsQuery)`, `userThread`, `markThreadRead`, `setThreadFollowing` |
| Files | `uploadFiles`/`uploadFile` (streamed multipart, progress callback), `fileInfo`, `downloadFile(id[, start, end])` (`Range`, returns a `FileDownload` stream with 206/`Content-Range` parsed), `fileThumbnail`, `filePreview`, `fileUrl` |
| Emoji | `customEmojis`, `customEmojiByName`, `customEmojiImage` |
| WebSocket | `createWebSocket(listener)` |

The models are `User`, `CustomStatus`, `UserStatus`, `Team`, `TeamMember`, `TeamUnread`,
`Channel`, `ChannelMember`, `ChannelStats`, `ChannelViewResponse`, `Post` (props, `metadata` with
files/embeds/reactions/emojis/images/priority, root_id, edit_at, delete_at, is_pinned, type),
`PostList` (order + posts map), `PostSearchResults`, `PostDraft`, `FileInfo`, `FileUploadResponse`,
`Reaction`, `CustomEmoji`, `UserThread`, `ThreadList`, `Preference`, `ClientConfig`,
`ServerVersion`, `PingResponse`, `AppError` and `LoginResult`.

Decoding is lenient, the way the iOS client's is:

- A `null` string decodes as `""`, and a `null` list or map decodes as an empty one.
- Numbers may arrive as strings.
- A `JsonObject` field such as `props` may be `null`.

### WebSocket (`ws.MattermostWebSocket`)

The port follows the iOS behaviour:

- **Auth.** The socket sends `Authorization: Bearer` on the upgrade, or an `authentication_challenge`, or both.
- **Handshake probe.** A `ping` goes out right after connecting. A reply that arrives before `hello`
  means the connection was resumed. A `not_authenticated` reply means the token was rejected.
- **Sequence tracking.** The socket tracks `seq` and drops events that are replayed twice. A gap in
  `seq` makes it reconnect with `?connection_id=…&sequence_number=…`, so the server replays the
  missed events.
- **Resume reporting.** `ConnectionInfo.resumed` says whether a reconnect was resumed. When it is
  false, the caller must catch up over REST (`PostsQuery.since`).
- **Backoff.** Reconnects use exponential backoff with jitter (`BackoffPolicy`). There is a
  handshake timeout and a keep-alive `ping`.
- **Unauthorized.** The socket stops on a 401 upgrade, or when a silent server is confirmed as a
  bad token through the `TokenVerifier`.
- **Actions.** `sendAction`, `ping`, `getStatuses`, `updateActiveStatus` and `userTyping`.

The `WebSocketListener` gets state changes and events on the socket's own thread. Each
`WebSocketEvent` keeps the raw frame and has typed accessors:

- **Posts:** `getPosted`, `getEditedPost`, `getDeletedPost`, `getPostUnread`.
- **Reactions and typing:** `getReaction` (added/removed), `getTyping`.
- **Read state:** `getChannelsViewed`, for `channel_viewed` and `multiple_channels_viewed`.
- **Users:** `getStatusChange`, `getUser` (`user_updated`).
- **Channels:** `getChannel` (`channel_updated`), `getChannelChange` (created/deleted/restored/converted), `getChannelMember`.
- **Membership:** `getMembership` (`user_added`/`user_removed`, team join/leave), `getDirectAdded` (`direct_added`/`group_added`).
- **Threads:** `getThread` (`thread_updated`), `getThreadReadChanged`.
- **Other:** `getEmoji`, `getPreferences`.

The transport sits behind `WebSocketTransport`, with `NvWebSocketTransport` as the default
implementation, so tests can script it.

### Message ids (`ids.MessageIdAllocator`)

This is a port of the iOS sparse allocator, option 2 in iOS `docs/plan/02-domain-mapping.md`.
It is pure logic over a `MessageIdStore`. `InMemoryMessageIdStore` is the reference store, and the
bridge will add a SQLite one.

- The first post gets 2^30, and pages are spaced by `STEP` 16 away from their anchor.
- A late insert takes the midpoint of its neighbours.
- A permalink jump becomes an island, placed proportionally to time. When a page reaches another
  island, the hole between them closes.
- Holes are reported as `IdRange`s, and `fillCursor`/`aroundCursor` turn them into `before`/`after` cursors.
- Deleted posts become tombstones, and `PostParts` gives each file of a post its own message id.
- When an id gap is exhausted, a minimal renumber window is chosen. The allocator returns it as a
  `RenumberPlan` (the renumber signal).

## Tests

```sh
cd relay-mattermost
export GRADLE_USER_HOME=/Volumes/X5/relay-android/gradle-home   # shared with the Android build
./gradlew test                 # everything; live tests skip themselves when the server is down
./gradlew test -PskipLive      # offline only
./gradlew liveTest             # only the live integration tests
```

Never run Gradle in the Android root project for this. This is a separate build with its own
wrapper (Gradle 8.13, the same distribution as the root). When `/Volumes/X5/relay-android/build`
exists, build outputs go to `/Volumes/X5/relay-android/build/relay-mattermost`.

- **Fixture tests** (`model.ModelFixtureTest`, `ws.WebSocketEventFixtureTest`) decode every model and
  event from the recorded Mattermost 11.7.14 fixtures in `src/test/resources/fixtures`. The
  fixtures are copied from the iOS repo's `infra/dev-server/fixtures`. Events that are not in the
  recording are tested with synthetic frames.
- **Client and socket tests** (`MattermostClientStubTest`, `ws.MattermostWebSocketTest`) need no
  server. The client tests check request formation (headers, path and query encoding, `Range`,
  multipart order, error mapping) against a local stub HTTP server. The socket tests run the
  WebSocket state machine against a scripted transport: handshake, duplicate and gap handling,
  resume, failed resume, 401, `not_authenticated`, the token verifier, actions, and
  disconnect/connect.
- **Allocator tests** (`ids.MessageIdAllocatorTest`, `ids.MessageIdPropertyTest`) are ports of the
  Swift scenario tests and the Spike C cases:
  - an initial page, and backward paging over 10,000 posts;
  - a permalink island that joins the latest island, and late inserts;
  - many posts in one millisecond or one second, and 2013 imports;
  - gap exhaustion, tombstones and post parts;
  - the randomized property simulation (`SIM_STATS=1` prints the operation counts).
- **Live tests** (`live.MattermostLiveTest`) run against the local dev server `http://localhost:8065`.
  They log in as alice and bob and cover:
  - teams and channels;
  - paging the `firehose` channel backwards into the allocator;
  - bob posting in the DM, with alice's WebSocket receiving `posted`, `post_edited`,
    `reaction_added`/`removed`, `typing` and `post_deleted`;
  - a file upload with a ranged download round trip, plus thumbnail and preview;
  - mark-read moving `last_viewed_at` and producing `multiple_channels_viewed`;
  - a WebSocket resume that replays a missed event.

  The tests delete the posts they create. Configure them with:
  - `RELAY_MM_URL` for the server URL (default `http://localhost:8065`);
  - `RELAY_MM_PASSWORD` for the users' shared password. Otherwise the tests read
    `SEED_USER_PASSWORD` from `RELAY_MM_ENV_FILE`, which defaults to
    `~/Developer/relay-calls-integration/ios-device/infra/dev-server/.env`. The password is never
    stored in this repo.
  - `RELAY_MM_LIVE=0` to skip them.

## Android notes

- Models are bound by Gson reflection. Keep them under R8, for example with
  `-keep class org.telegram.relay.mattermost.model.** { *; }`,
  `-keep class org.telegram.relay.mattermost.ws.WebSocketBroadcast { *; }` and
  `-keep class org.telegram.relay.mattermost.Json$* { *; }`.
- Debug builds talk to `http://localhost:8065` through `adb reverse`, so they need cleartext
  traffic allowed.
