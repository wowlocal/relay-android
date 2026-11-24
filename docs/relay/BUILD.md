# Relay for Android — build

Phase A0: a debug APK of `TMessagesProj_App` built from the command line, installed and
launched on the emulator. The UI is still stock Telegram; only the build and the package
name differ.

## One-time setup

| What | Where |
|---|---|
| JDK 17 | `/opt/homebrew/opt/openjdk@17` (the script sets `JAVA_HOME`) |
| Android SDK | `~/Library/Android/sdk`: `platforms;android-36`, `build-tools;36.0.0`, `ndk;27.2.12479018`, `cmake;3.22.1`, `platform-tools`, `emulator`, `system-images;android-35;google_apis;arm64-v8a` |
| Gradle home | `/Volumes/X5/relay-android/gradle-home` (`GRADLE_USER_HOME`; the Gradle 8.13 wrapper and all Maven deps, about 2 GB) |
| Build outputs | `/Volumes/X5/relay-android/build/<project path>`, CMake staging in `.../build/TMessagesProj/cxx` |
| Logs | `/Volumes/X5/relay-android/logs/build-<timestamp>.log` |

```sh
cd ~/Developer/relay-android
git submodule update --init --recursive --jobs 8 --depth 1   # 21 submodules, about 1.5 min
cat > local.properties <<'EOF'                               # untracked, per machine
sdk.dir=/Users/michael/Library/Android/sdk
relay.buildRoot=/Volumes/X5/relay-android/build
relay.abis=arm64-v8a
EOF
```

Submodules are required: `TMessagesProj_Modules/media` (media3, part of `settings.gradle`),
`jlatexmath`, and the native sources (`td`, `tgcalls`, `boringssl` headers, `absl`, ...).
The fork tracks them as gitlinks, as upstream does.

## Build, install, launch

```sh
Tools/relay-build.sh                                   # :TMessagesProj_App:assembleAfatDebug, arm64-v8a
Tools/relay-build.sh -s emulator-5554 -l -S /tmp/x.png # + install, launch, screenshot, crash check
Tools/relay-build.sh --no-build -s R58R36F7VFD -l      # reinstall the last APK on the Samsung
Tools/relay-build.sh --reverse -s emulator-5554 -l     # + adb reverse tcp:8065 (local Mattermost)
Tools/relay-build.sh --clean                           # clean the App + TMessagesProj modules first
Tools/relay-build.sh --abis all                        # upstream's four ABIs (4x native work)
Tools/relay-build.sh -- --offline --info               # extra Gradle args after --
```

The script lives in git as `Tools/` (upstream's capitalised directory). macOS is case-insensitive,
so `tools/relay-build.sh` runs the same file.

Plain Gradle works too: `local.properties` carries the build root and ABIs.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 GRADLE_USER_HOME=/Volumes/X5/relay-android/gradle-home
./gradlew :TMessagesProj_App:assembleAfatDebug
```

- **APK:** `/Volumes/X5/relay-android/build/TMessagesProj_App/outputs/apk/afat/debug/app.apk`
  (82 MB, arm64-v8a only, signed with upstream's debug/release keystore `TMessagesProj/config/release.keystore`).
- **applicationId:** `org.relaychat.android.debug`. The release id is `org.relaychat.android`
  (`APP_PACKAGE` in `gradle.properties`). Java namespaces stay `org.telegram.messenger(.regular)`.
- **Launcher activity:** `org.relaychat.android.debug/org.telegram.messenger.DefaultIcon`
  (an alias of `org.telegram.ui.LaunchActivity`).

## Timings (Mac mini M-series, 12 cores, 24 GB; arm64-v8a only)

| Build | Time |
|---|---|
| First build, cold Gradle home (downloads wrapper + deps) | 4 min 27 s |
| Clean build, warm Gradle home (`--clean`) | 3 min 13 s (App + TMessagesProj incl. CMake; media3 modules stay up to date) |
| No-op | 2 s |
| New Java file in `TMessagesProj` (javac + dex + package) | 48 s |

The native part is quick because upstream ships ffmpeg, boringssl, libvpx, dav1d, opus, tde2e
and others as prebuilt `.a` files in `TMessagesProj/jni/prebuild/lib/<abi>/`. CMake only
compiles tgnet, voip/webrtc, tgcalls, sqlite, lottie and similar into `libtmessages.49.so` and
`libtwidget.so`. Peak use was about 14 clang processes and 50% of RAM free.
`org.gradle.jvmargs=-Xmx8g` and `org.gradle.parallel=true` come from upstream and are fine on
this machine.

## Emulator and devices

- Emulator: the existing AVD `ntsd-xplat-api35` (API 35 google_apis arm64, `pixel_tablet`
  profile, which renders the phone layout in portrait), reused per the owner's device rules.
  No `relay-pixel` AVD was created.
  ```sh
  ~/Library/Android/sdk/emulator/emulator -avd ntsd-xplat-api35 -no-window -no-audio -no-boot-anim -no-snapshot-save &
  adb -s emulator-5554 shell getprop sys.boot_completed   # 1 = ready
  ```
  The AVD's data partition is `<temp>`, so app data does not survive a cold boot.
- Samsung SM-A125F (`R58R36F7VFD`, Android 12, arm64-v8a): same APK. Unlock the phone first.
  With the screen locked, `adb install` hangs: Samsung's USB-install / Play Protect prompt waits
  behind the keyguard. Not yet verified on the phone (it was locked during A0).

## What Relay changed in the build

| File | Change |
|---|---|
| `gradle.properties` | `APP_PACKAGE=org.relaychat.android` (also used by the Huawei, HockeyApp, Standalone and Tests modules) |
| `TMessagesProj_App/build.gradle` | debug `applicationIdSuffix ".debug"` (was `.beta`); `afat` ABIs come from `relay.abis` when it is set |
| `TMessagesProj/build.gradle` | `ndk.abiFilters` from `relay.abis`; CMake `buildStagingDirectory` under `relay.buildRoot` |
| `build.gradle` (root) | reads `relay.buildRoot` / `relay.abis` (Gradle property or `local.properties`) and moves every project's `buildDir` to `<root>/<project path>` |
| `TMessagesProj_App/google-services.json` | client entries for `org.relaychat.android` and `.debug` with **dummy** app ids and a dummy API key |
| `TMessagesProj_App/src/afatDebug/` | manifest overlay + `res/xml/relay_network_security_config.xml`: cleartext allowed (localhost, 127.0.0.1, 10.0.2.2 and, as upstream allowed before, everywhere), user CAs trusted in debuggable builds |

With neither property set, the build behaves as upstream does: all four ABIs, in-tree `build/` and `.cxx`.

## Gotchas

- **Submodules not initialised** → settings fail on `TMessagesProj_Modules/media/core_settings.gradle`.
  The script checks for this.
- **Firebase:** `google-services` needs a client for each applicationId, or
  `processAfatDebugGoogleServices` fails. The Relay entries are dummies:
  `FirebaseApp` initialises, Firebase Installations gets `400` from Google, and FCM push does
  nothing. Push (A7) needs a real Firebase project, or the Mattermost push proxy, and a real
  `google-services.json`. `TMessagesProj/google-services.json` (library, `org.telegram.messenger`) is unchanged.
- **Cleartext:** upstream already set `usesCleartextTraffic="true"`. A network security config
  replaces that attribute, so the debug config keeps cleartext allowed for all hosts, so the
  in-app browser keeps working. Release builds have no NSC.
- **`relay.abis`** must include the device ABI: emulator and Samsung are both `arm64-v8a`.
  An x86_64 emulator image needs `--abis x86_64` (or `all`).
- `BuildVars.isBetaApp()` checks for the package `org.telegram.messenger.beta`, which no longer
  matches. Debug builds therefore act like the regular app, not "Telegram Beta". This is harmless for now.
- App name: `AppName` = "Relay", `AppNameBeta` = "Relay Debug" (debug manifest label), in every
  `values*/strings.xml`. Relay strings must live in `values/strings.xml`: Telegram's
  `TelegramStringsTask` (buildSrc) builds the string table `LocaleController` reads from that file
  only, so a string in another values file shows as `LOC_ERR:null`.
- Icons (A6): `Tools/relay-icons/make_icons.py` turns the iOS `Relay.icon` artwork into the adaptive
  launcher layers (`mipmap-*/relay_icon_background`, `relay_icon_monochrome`), legacy
  `ic_launcher*.png`, the status-bar `notification.webp` and the Android 12 splash `relay_splash.png`
  (needs Pillow, see the script header).
- Telegram's welcome screen talks to Telegram DCs on "Start Messaging"; A2 replaces that.
- `assembleAfatDebug` is the only debug variant: the `variantFilter` drops `bundleAfat` for
  non-release builds.
