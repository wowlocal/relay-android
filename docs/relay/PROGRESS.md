# Relay for Android — progress log

Phases are defined in [PLAN.md](PLAN.md). Newest entries last.

## 2026-11-24

- Forked Telegram-Android 13.2.2 (7303) → `~/Developer/relay-android`, branch `relay`, tag `upstream-13.2.2-7303`.
- Local Mattermost `mmdev` recreated (OrbStack had been wiped) and seeded: alice/bob/carol/dave, teams engineering/design, firehose channel.
- Started in parallel: A0 toolchain + debug build; Telegram internals map for the bridge; A1 `relay-mattermost` library.
- **A0 done** (`97d5055`): debug APK `org.relaychat.android.debug` builds (clean 3m13s, Java incremental ~48 s), boots to Telegram's welcome screen on emulator-5554 (AVD `ntsd-xplat-api35`). Build output on X5. Firebase uses dummy client entries (push needs a real project later). Samsung not verified (locked).
- Telegram internals map done (`9d2ae0e`, `docs/relay/telegram-internals.md`). A2 (bridge core + Mattermost login) started.
- **A1 done** (`f6c2e50`): `relay-mattermost` — REST v4, WebSocket (resume, seq gaps), models, `MessageIdAllocator` port; 76 tests pass (30 fixture, 22 allocator, 17 offline, 7 live vs mmdev). Gson needs R8 keep rules (README).
- Started: markdown ↔ entities converter in `relay-mattermost.text`.
