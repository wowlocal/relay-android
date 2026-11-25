#!/usr/bin/env bash
# Relay for Android: debug build of TMessagesProj_App (+ optional install/launch).
# See docs/relay/BUILD.md.
#
#   tools/relay-build.sh                         # build afatDebug (arm64-v8a only)
#   tools/relay-build.sh -s emulator-5554 -l     # build, install on a serial, launch
#   tools/relay-build.sh --no-build -s R58... -l # install/launch the last APK only
#
# Options:
#   -s, --serial SERIAL   adb serial to install on (adb devices -l)
#   -l, --launch          launch the app after install (needs -s)
#   -S, --screenshot PNG  after launch, wait and save a screenshot (needs -l)
#       --abis LIST       comma list of ABIs (default arm64-v8a; "all" = upstream's four)
#       --clean           run :TMessagesProj_App:clean :TMessagesProj:clean first
#       --no-build        skip Gradle, reuse the existing APK
#       --reverse         also run `adb reverse tcp:8065 tcp:8065` (local Mattermost)
#   -- ARGS               extra Gradle arguments
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
X5="/Volumes/X5/relay-android"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# The build root: RELAY_BUILD_ROOT, else relay.buildRoot from this checkout's local.properties
# (worktrees set their own there), else the shared X5 build dir.
LOCAL_BUILD_ROOT="$(sed -n 's/^relay\.buildRoot=//p' "$ROOT/local.properties" 2>/dev/null | tail -1 | tr -d '[:space:]')"
if [[ -d "$X5" ]]; then
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$X5/gradle-home}"
  BUILD_ROOT="${RELAY_BUILD_ROOT:-${LOCAL_BUILD_ROOT:-$X5/build}}"
  LOG_DIR="$X5/logs"
else
  BUILD_ROOT="${RELAY_BUILD_ROOT:-$LOCAL_BUILD_ROOT}"
  LOG_DIR="$ROOT/build/relay-logs"
fi
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
ADB="$ANDROID_HOME/platform-tools/adb"

SERIAL="" LAUNCH=0 SHOT="" ABIS="arm64-v8a" CLEAN=0 BUILD=1 REVERSE=0
EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2 ;;
    -l|--launch) LAUNCH=1; shift ;;
    -S|--screenshot) SHOT="$2"; shift 2 ;;
    --abis) ABIS="$2"; shift 2 ;;
    --clean) CLEAN=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --reverse) REVERSE=1; shift ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

APP_ID="$(sed -n 's/^APP_PACKAGE=//p' "$ROOT/gradle.properties").debug"
if [[ -n "$BUILD_ROOT" ]]; then
  APK="$BUILD_ROOT/TMessagesProj_App/outputs/apk/afat/debug/app.apk"
else
  APK="$ROOT/TMessagesProj_App/build/outputs/apk/afat/debug/app.apk"
fi

if [[ $BUILD -eq 1 ]]; then
  if [[ ! -e "$ROOT/TMessagesProj_Modules/media/settings.gradle" && ! -e "$ROOT/TMessagesProj_Modules/media/core_settings.gradle" ]]; then
    echo "submodules missing: git submodule update --init --recursive --jobs 8 --depth 1" >&2
    exit 1
  fi
  mkdir -p "$LOG_DIR"
  LOG="$LOG_DIR/build-$(date +%Y%m%d-%H%M%S).log"
  GRADLE_ARGS=(--console=plain)
  [[ -n "$BUILD_ROOT" ]] && GRADLE_ARGS+=("-Prelay.buildRoot=$BUILD_ROOT")
  [[ "$ABIS" != "all" ]] && GRADLE_ARGS+=("-Prelay.abis=$ABIS")
  TASKS=()
  [[ $CLEAN -eq 1 ]] && TASKS+=(:TMessagesProj_App:clean :TMessagesProj:clean)
  TASKS+=(:TMessagesProj_App:assembleAfatDebug)
  echo "log: $LOG"
  echo "build root: ${BUILD_ROOT:-<in-tree>}"
  start=$(date +%s)
  set +e
  (cd "$ROOT" && ./gradlew "${GRADLE_ARGS[@]}" "${TASKS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}) 2>&1 | tee "$LOG"
  rc=${PIPESTATUS[0]}
  set -e
  echo "gradle exit $rc after $(( $(date +%s) - start ))s" | tee -a "$LOG"
  [[ $rc -eq 0 ]] || exit $rc
fi

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
echo "apk: $APK ($(du -h "$APK" | cut -f1))"

if [[ -n "$SERIAL" ]]; then
  "$ADB" -s "$SERIAL" wait-for-device
  "$ADB" -s "$SERIAL" install -r -t -d "$APK"
  [[ $REVERSE -eq 1 ]] && "$ADB" -s "$SERIAL" reverse tcp:8065 tcp:8065
  if [[ $LAUNCH -eq 1 ]]; then
    "$ADB" -s "$SERIAL" logcat -c || true
    ACT="$("$ADB" -s "$SERIAL" shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$APP_ID" | tail -1 | tr -d '\r')"
    "$ADB" -s "$SERIAL" shell am start -W -n "$ACT"
    if [[ -n "$SHOT" ]]; then
      sleep 8
      "$ADB" -s "$SERIAL" exec-out screencap -p > "$SHOT"
      echo "screenshot: $SHOT"
    fi
    if "$ADB" -s "$SERIAL" logcat -d -b crash | grep -q "$APP_ID"; then
      echo "CRASH in $APP_ID:" >&2
      "$ADB" -s "$SERIAL" logcat -d -b crash | tail -40 >&2
      exit 3
    fi
    pid="$("$ADB" -s "$SERIAL" shell pidof "$APP_ID" | tr -d '\r' || true)"
    echo "running: ${pid:-NOT RUNNING}"
    [[ -n "$pid" ]] || exit 3
  fi
fi
