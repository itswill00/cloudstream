#!/usr/bin/env bash
# =============================================================================
#  build.sh - Smart build script for CloudStream (itswill00/cloudstream)
# =============================================================================
#  The app is built for arm64 only, that is the architecture of the devices
#  that are used. The APK is 45 MB instead of 73 MB.
# =============================================================================
#  Usage:
#    ./build.sh                     # default: prerelease debug APK
#    ./build.sh debug               # prerelease debug APK
#    ./build.sh release             # prerelease release APK
#    ./build.sh stable              # stable release APK
#    ./build.sh all                 # debug + release
#    ./build.sh check               # lint, unit tests and ABI check
#    ./build.sh desktop             # Kotlin Multiplatform desktop app
#    ./build.sh <gradle-task...>    # arbitrary gradle task
#
#  Options:
#    --install        install missing JDK / Android SDK components first
#    --clean          run `clean` first
#    --offline        gradle --offline
#    --no-daemon      gradle --no-daemon
#    --cc             enable gradle configuration cache (default: disabled)
#    --jobs N         set gradle worker parallelism
#    --flavor <f>     flavour to build: prerelease or stable (default: prerelease)
#    --no-sign        do not load the signing key, the APK stays unsigned
#    --no-build       do not build, only do what --send asks for
#    --send           upload the APK to the test group on Telegram
#    --message <text> extra text for the caption of the upload
#    --config <file>  file with TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID
#    -h|--help        show help
#
#  Env overrides:
#    REPO_DIR, ANDROID_HOME/ANDROID_SDK_ROOT, JAVA_HOME, BUILD_LOG_DIR,
#    KEYS_ENV_FILE (file with SIGNING_KEY_ALIAS and the passwords),
#    TELEGRAM_CONFIG (file with the bot token and the chat id)
#    TG_API_CONFIG (file with TELEGRAM_API_ID and TELEGRAM_API_HASH)
#    TELEGRAM_API_BASE (url of the Bot API, default: http://127.0.0.1:8081)
#
#  The upload goes through a self-hosted telegram-bot-api server in --local
#  mode, so the APK is sent as one single document, never split into parts.
# =============================================================================
set -uo pipefail

# ------------------------------ config ---------------------------------------
REPO_DIR="${REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/root/android-sdk}}"
JDK_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
LOG_DIR="${BUILD_LOG_DIR:-/root/build-logs}"
API_LEVEL=37
BUILD_TOOLS="37.0.0"

# ------------------------------ helpers --------------------------------------
c_reset=$'\033[0m'; c_red=$'\033[31m'; c_grn=$'\033[32m'; c_ylw=$'\033[33m'
c_blu=$'\033[34m'; c_dim=$'\033[2m'; c_bold=$'\033[1m'
if [ ! -t 1 ]; then c_reset=; c_red=; c_grn=; c_ylw=; c_blu=; c_dim=; c_bold=; fi

usage() {
cat <<'USAGE'
=============================================================================
 build.sh - Smart build script for CloudStream (itswill00/cloudstream)
=============================================================================
 The app is built for arm64 only, the APK is 45 MB instead of 73 MB.
=============================================================================
 Usage:
   ./build.sh                     default: prerelease debug APK
   ./build.sh debug               prerelease debug APK
   ./build.sh release             prerelease release APK
   ./build.sh stable              stable release APK
   ./build.sh all                 debug + release
   ./build.sh check               lint, unit tests and ABI check
   ./build.sh notes               write the description of the pre-release
   ./build.sh desktop             Kotlin Multiplatform desktop app
   ./build.sh <gradle-task...>    arbitrary gradle task

 Options:
   --install        install missing JDK / Android SDK components first
   --clean          run `clean` first
   --offline        gradle --offline
   --no-daemon      gradle --no-daemon
   --cc             enable gradle configuration cache
   --jobs N         set gradle worker parallelism
   --flavor <f>     flavour to build: prerelease or stable (default: prerelease)
   --no-sign        do not load the signing key, the APK stays unsigned
   --no-build       do not build, only upload the last build
   --send           upload the APK to the test group on Telegram
   --release        upload the APK to the GitHub release and move the tag,
                    the in app updater compares the tag with the installed commit
   --message <text> extra text for the caption of the upload
   --config <file>  file with TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID
   --dry-run        only show what would be done, do not change anything
   -h|--help        show this help

 Env overrides:
   REPO_DIR, ANDROID_HOME/ANDROID_SDK_ROOT, JAVA_HOME, BUILD_LOG_DIR,
   KEYS_ENV_FILE (file with SIGNING_KEY_ALIAS and the passwords),
   TELEGRAM_CONFIG (file with the bot token and the chat id)
   TG_API_CONFIG (file with TELEGRAM_API_ID and TELEGRAM_API_HASH)
   TELEGRAM_API_BASE (url of the Bot API, default: http://127.0.0.1:8081)

 The APK is uploaded through a self-hosted telegram-bot-api server in --local
 mode, so it is sent as one single document and never split into parts.
=============================================================================
USAGE
}

say()  { printf '%s[build]%s %s\n' "$c_blu" "$c_reset" "$*"; }
ok()   { printf '%s[ ok ]%s %s\n' "$c_grn" "$c_reset" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$c_ylw" "$c_reset" "$*"; }
die()  { printf '%s[fail]%s %s\n' "$c_red" "$c_reset" "$*"; exit 1; }

# --------------------------- arg parsing ------------------------------------
TASKS=(); DO_INSTALL=0; DO_CLEAN=0; OFFLINE=0; NO_DAEMON=0; USE_CC=0; JOBS=""
FLAVOR="prerelease"; NO_SIGN=0; DO_BUILD=1; DO_SEND=0; DO_RELEASE=0; DRY_RUN=0
EXTRA_MESSAGE=""; TELEGRAM_CONFIG="${TELEGRAM_CONFIG:-/root/keys/telegram-bot.env}"
while [ $# -gt 0 ]; do
  case "$1" in
    --install)   DO_INSTALL=1 ;;
    --clean)     DO_CLEAN=1 ;;
    --offline)   OFFLINE=1 ;;
    --no-daemon) NO_DAEMON=1 ;;
    --cc)        USE_CC=1 ;;
    --jobs)      JOBS="${2:-}"; shift ;;
    --flavor)    FLAVOR="${2:-}"; shift ;;
    --no-sign)   NO_SIGN=1 ;;
    --no-build)  DO_BUILD=0 ;;
    --send)      DO_SEND=1 ;;
    --release)   DO_RELEASE=1 ;;
    --message)   EXTRA_MESSAGE="${2:-}"; shift ;;
    --config)    TELEGRAM_CONFIG="$2"; shift ;;
    --dry-run)   DRY_RUN=1 ;;
    -h|--help)   usage; exit 0 ;;
    *)           TASKS+=("$1") ;;
  esac
  shift
done

case "$FLAVOR" in
  prerelease|stable) ;;
  *) die "unknown flavor: $FLAVOR (use prerelease or stable)" ;;
esac

# default task set
if [ ${#TASKS[@]} -eq 0 ]; then TASKS=("debug"); fi
resolve_target() {
  local flavor="${FLAVOR^}" # Prerelease or Stable
  case "$1" in
    debug)   echo "assemble${flavor}Debug" ;;
    release) echo "assemble${flavor}Release" ;;
    stable)  echo "assembleStableRelease" ;;
    all)     echo "assemble${flavor}Debug assemble${flavor}Release" ;;
    check)   echo "lint${flavor}Debug test${flavor}DebugUnitTest :library:checkKotlinAbi" ;;
    notes)   echo "" ;;
    desktop) echo ":desktopApp:build" ;;
    *)       echo "$1" ;;
  esac
}
GRADLE_TASKS=()
DO_NOTES=0
for t in "${TASKS[@]}"; do
  if [ "$t" = "notes" ]; then DO_NOTES=1; continue; fi
  for one in $(resolve_target "$t"); do GRADLE_TASKS+=("$one"); done
done

# ------------------------------ preflight ------------------------------------
preflight() {
  say "repo      : $REPO_DIR"
  say "sdk       : $SDK_DIR"
  say "java home : $JDK_HOME"
  [ -d "$REPO_DIR" ] || die "repo dir not found: $REPO_DIR"
  [ -f "$REPO_DIR/gradlew" ] || die "gradlew not found in $REPO_DIR (not a gradle project?)"

  # --- java ---
  local need_jdk=0
  [ -x "$JDK_HOME/bin/java" ] || need_jdk=1
  if [ $need_jdk -eq 1 ]; then
    for c in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/default-java /usr/lib/jvm/*17*; do
      [ -x "$c/bin/java" ] && { JDK_HOME="$c"; need_jdk=0; break; }
    done
  fi
  if [ $need_jdk -eq 1 ] && [ $DO_INSTALL -eq 1 ]; then
    say "installing openjdk-17-jdk-headless ..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless unzip \
      >/dev/null 2>&1 || warn "apt install failed, continuing"
    JDK_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  fi
  [ -x "$JDK_HOME/bin/java" ] || die "JDK 17 not found. Jalankan dengan --install"
  local jv; jv="$("$JDK_HOME/bin/java" -version 2>&1 | head -1)"
  ok "java      : $jv"
  case "$jv" in
    *\"17.*) ;;
    *) warn "java is not 17 ($jv) - AGP 9 biasanya butuh JDK 17+" ;;
  esac

  # --- android sdk ---
  if [ ! -d "$SDK_DIR" ]; then
    if [ $DO_INSTALL -eq 1 ]; then
      say "installing android cmdline-tools ..."
      mkdir -p "$SDK_DIR/cmdline-tools"
      local zip="/tmp/opencode/cmdline-tools.zip"
      mkdir -p /tmp/opencode
      curl -sSL -o "$zip" "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip" || die "download cmdline-tools failed"
      unzip -q -o "$zip" -d "$SDK_DIR/cmdline-tools" || die "unzip cmdline-tools failed"
      rm -rf "$SDK_DIR/cmdline-tools/latest"
      mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    else
      die "Android SDK tidak ada di $SDK_DIR. Jalankan dengan --install"
    fi
  fi
  [ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ] || die "sdkmanager tidak ditemukan di $SDK_DIR"
  export ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR" JAVA_HOME="$JDK_HOME"
  export PATH="$JDK_HOME/bin:$SDK_DIR/platform-tools:$SDK_DIR/cmdline-tools/latest/bin:$PATH"

  # licenses
  yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1

  # platform + build-tools
  local have_platform=0
  ls -d "$SDK_DIR/platforms/android-$API_LEVEL"* >/dev/null 2>&1 && have_platform=1
  if [ $have_platform -eq 0 ] && [ $DO_INSTALL -eq 1 ]; then
    say "installing platform android-$API_LEVEL + build-tools $BUILD_TOOLS ..."
    "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "platform-tools" \
      "platforms;android-$API_LEVEL.0" "build-tools;$BUILD_TOOLS" >/dev/null 2>&1 \
      || warn "sdkmanager install partially failed"
    ls -d "$SDK_DIR/platforms/android-$API_LEVEL"* >/dev/null 2>&1 && have_platform=1
  fi
  if [ $have_platform -eq 1 ]; then
    ok "platform  : android-$API_LEVEL installed"
  else
    warn "platform android-$API_LEVEL belum ada (butuh --install)"
  fi

  # signing key
  if [ $NO_SIGN -eq 1 ]; then
    say "signing    : disabled (--no-sign)"
  elif [ -n "${SIGNING_KEY_ALIAS:-}" ]; then
    ok "signing    : key '$SIGNING_KEY_ALIAS' from the environment"
  else
    local keys_env="${KEYS_ENV_FILE:-/root/keys/env.sh}"
    if [ -f "$keys_env" ]; then
      # The values have to be exported: app/build.gradle.kts reads SIGNING_KEY_ALIAS and
      # the passwords from the environment, and a plain `source` only sets shell
      # variables, which the gradle child process never sees. That made every "signed"
      # build come out unsigned.
      set -a
      # shellcheck disable=SC1090
      source "$keys_env"
      set +a
      ok "signing    : key '${SIGNING_KEY_ALIAS:-?}' from $keys_env"
    else
      warn "signing    : no key found ($keys_env), the APK stays unsigned"
    fi
  fi
  if [ $NO_SIGN -eq 0 ] && [ -n "${SIGNING_KEY_ALIAS:-}" ]; then
    local keystore_dir="$HOME/work/_temp/keystore"
    if ls "$keystore_dir"/*.jks >/dev/null 2>&1; then
      ok "keystore   : $(ls "$keystore_dir"/*.jks | head -1)"
    else
      warn "keystore   : no .jks in $keystore_dir, the APK stays unsigned"
    fi
  fi

  # local.properties
  local lp="$REPO_DIR/local.properties"
  if ! grep -q '^sdk.dir=' "$lp" 2>/dev/null; then
    say "menulis local.properties (sdk.dir=$SDK_DIR)"
    printf 'sdk.dir=%s\n' "$SDK_DIR" > "$lp"
  fi

  # memory hints
  local mem_gb; mem_gb=$(free -g 2>/dev/null | awk '/^Mem:/{print $2}')
  say "ram       : ${mem_gb:-?} GB total"
  mkdir -p "$LOG_DIR"
}

# ------------------------------ run gradle -----------------------------------
run_gradle() {
  local log="$1"; shift
  local -a args=(--console=plain --stacktrace -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8")
  [ $OFFLINE -eq 1 ]   && args+=(--offline)
  [ $NO_DAEMON -eq 1 ] && args+=(--no-daemon)
  [ $USE_CC -eq 1 ]    && args+=(--configuration-cache)
  [ -n "$JOBS" ]       && args+=(--max-workers="$JOBS")
  [ $DO_CLEAN -eq 1 ]  && args+=(clean)

  say "gradle    : ./gradlew ${args[*]} $*"
  printf '\n%s=== build start %s ===%s\n' "$c_dim" "$(date '+%F %T')" "$c_reset" >> "$log"

  # stream output live (unbuffered) ke terminal + file
  set -o pipefail
  stdbuf -oL -eL "$REPO_DIR/gradlew" "${args[@]}" "$@" 2>&1 | tee -a "$log"
  return "${PIPESTATUS[0]}"
}

# ------------------------------ summary -------------------------------------
summary() {
  local log="$1"
  echo
  say "artifacts:"
  local found=0
  while IFS= read -r apk; do
    found=1
    local sz; sz=$(du -h "$apk" | cut -f1)
    local sha; sha=$(sha256sum "$apk" | cut -c1-16)
    local ver=""
    if [ -x "$SDK_DIR/build-tools/$BUILD_TOOLS/aapt" ]; then
      ver=$("$SDK_DIR/build-tools/$BUILD_TOOLS/aapt" dump badging "$apk" 2>/dev/null \
            | grep -m1 "package: name" | sed 's/.*versionName=\([^ ]*\).*/\1/')
    fi
    printf '  %s%-70s%s %s%s%s\n' "$c_bold" "${apk#"$REPO_DIR"/}" "$c_reset" "$c_ylw" "$sz" "$c_reset"
    [ -n "$ver" ] && printf '    version=%s  sha256=%s…\n' "$ver" "$sha"
  done < <(find "$REPO_DIR/app/build/outputs/apk" -name '*.apk' 2>/dev/null | sort)
  [ $found -eq 0 ] && say "(belum ada APK)"

  say "logs      : $log"
}

on_error() {
  local log="$1"
  echo
  warn "build GAGAL - ringkasan error:"
  grep -n -E "^(FAILURE|\* What went wrong|Caused by|Execution failed|> )" "$log" 2>/dev/null | tail -25 | sed 's/^/    /'
  warn "cek log lengkap: $log"
}

# ------------------------------ telegram -------------------------------------
# The APK is sent to the test group, so that a fix can be tested on a device
# before a release is tagged.
#
# The upload goes through a self-hosted telegram-bot-api server running in
# --local mode, so the 50 MB limit of the public Bot API does not apply and the
# APK is always sent as one single document (no split parts).

# Base URL of the Bot API. Points at the local server started by tg_start.
TELEGRAM_API_BASE="${TELEGRAM_API_BASE:-http://127.0.0.1:8081}"
TG_CONTAINER="${TG_CONTAINER:-telegram-bot-api}"
TG_API_CONFIG="${TG_API_CONFIG:-/root/keys/telegram-api.env}"

newest_apk() {
  local dir="$REPO_DIR/app/build/outputs/apk/$FLAVOR"
  [ -d "$dir" ] || return 1
  find "$dir" -name '*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-
}

telegram_caption() {
  local size="$1"
  local commit branch subject body
  commit=$(git rev-parse --short=7 HEAD 2>/dev/null || echo "?")
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
  subject=$(git log -1 --pretty=%s 2>/dev/null || echo "")
  body=$(git log -1 --pretty=%b 2>/dev/null | sed '/^$/d' | grep -v '^Signed-off-by:' | head -8)

  local caption="CloudStream test build
commit : $commit ($branch)
flavor : $FLAVOR
abi    : arm64-v8a
size   : $(awk -v s="$size" 'BEGIN{printf "%.1f MB", s/1048576}')

$subject"
  [ -n "$body" ] && caption="$caption

$body"
  [ -n "$EXTRA_MESSAGE" ] && caption="$caption

$EXTRA_MESSAGE"
  # Telegram limits the caption to 1024 characters
  echo "${caption:0:1000}"
}

telegram_upload() {
  local file="$1" caption="$2"
  local name; name=$(basename "$file")
  local response
  response=$(curl -s --max-time 1800 \
    -F "chat_id=$TELEGRAM_CHAT_ID" \
    -F "caption=$caption" \
    -F "document=@$file;filename=$name" \
    "$TELEGRAM_API_BASE/bot$TELEGRAM_BOT_TOKEN/sendDocument")
  if echo "$response" | grep -q '"ok":true'; then
    local msg_id
    msg_id=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['message_id'])" 2>/dev/null)
    ok "sent $name ($(awk -v s="$(stat -c%s "$file")" 'BEGIN{printf "%.1f MB", s/1048576}'), message id $msg_id)"
  else
    local desc
    desc=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('description', sys.stdin.read()))" 2>/dev/null)
    die "upload failed: $desc"
  fi
}

# Make sure the local Bot API server is up. It runs in --local mode, which is
# what lifts the 50 MB upload limit, so the APK can be sent as a single file.
tg_start() {
  [ -f "$TG_API_CONFIG" ] || die "config not found: $TG_API_CONFIG (needs TELEGRAM_API_ID and TELEGRAM_API_HASH)"
  # shellcheck disable=SC1090
  source "$TG_API_CONFIG"
  [ -n "${TELEGRAM_API_ID:-}" ] || die "TELEGRAM_API_ID is not set in $TG_API_CONFIG"
  [ -n "${TELEGRAM_API_HASH:-}" ] || die "TELEGRAM_API_HASH is not set in $TG_API_CONFIG"

  # already answering? nothing to do
  if curl -s --max-time 5 "$TELEGRAM_API_BASE/bot$TELEGRAM_BOT_TOKEN/getMe" | grep -q '"ok":true'; then
    ok "bot api : local server already running at $TELEGRAM_API_BASE"
    return 0
  fi

  command -v docker >/dev/null 2>&1 || die "docker not found, it is needed for the local Bot API server"
  local img="aiogram/telegram-bot-api:latest"
  docker image inspect "$img" >/dev/null 2>&1 || {
    say "pulling $img ..."
    docker pull "$img" >/dev/null 2>&1 || die "docker pull $img failed"
  }

  # recreate cleanly if a stopped container with the same name is around
  docker rm -f "$TG_CONTAINER" >/dev/null 2>&1

  say "starting the local Bot API server ($TG_CONTAINER) ..."
  mkdir -p /root/telegram-bot-api
  docker run -d --name "$TG_CONTAINER" --restart unless-stopped \
    --env-file "$TG_API_CONFIG" \
    -p 127.0.0.1:8081:8081 \
    -v /root/telegram-bot-api:/var/lib/telegram-bot-api \
    "$img" --local --http-port=8081 >/dev/null \
    || die "failed to start the Bot API container"

  local i
  for i in $(seq 1 30); do
    if curl -s --max-time 3 "$TELEGRAM_API_BASE/bot$TELEGRAM_BOT_TOKEN/getMe" | grep -q '"ok":true'; then
      ok "bot api : local server up at $TELEGRAM_API_BASE"
      return 0
    fi
    sleep 1
  done
  docker logs "$TG_CONTAINER" 2>&1 | tail -10 | sed 's/^/    /'
  die "the local Bot API server did not come up"
}

send_to_telegram() {
  local apk
  apk=$(newest_apk) || die "no APK found for the flavour $FLAVOR"
  [ -n "$apk" ] || die "no APK found for the flavour $FLAVOR"

  [ -f "$TELEGRAM_CONFIG" ] || die "config not found: $TELEGRAM_CONFIG"
  # shellcheck disable=SC1090
  source "$TELEGRAM_CONFIG"
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] || die "TELEGRAM_BOT_TOKEN is not set in $TELEGRAM_CONFIG"
  [ -n "${TELEGRAM_CHAT_ID:-}" ] || die "TELEGRAM_CHAT_ID is not set in $TELEGRAM_CONFIG"

  tg_start

  local size; size=$(stat -c%s "$apk")
  local caption; caption=$(telegram_caption "$size")

  # one single document, no split
  telegram_upload "$apk" "$caption"
}

# --------------------------- release notes -----------------------------------
# The description of the pre-release release on GitHub. It has to say what
# changed since the last build and how to test it, a one liner is not enough.
RELEASE_SLUG="${RELEASE_SLUG:-itswill00/cloudstream}"
RELEASE_TAG="${RELEASE_TAG:-pre-release}"

# The commit of the last pre-release, it is written in the description of the
# release. If it is not there, the last stable release is used.
previous_pre_release_commit() {
  local body sha
  body=$(gh release view "$RELEASE_TAG" --json body -q .body 2>/dev/null) || body=""
  sha=$(echo "$body" | grep -oE 'Build: `[0-9a-f]{7,40}`' | grep -oE '[0-9a-f]{7,40}' | head -1)
  if [ -n "$sha" ] && git cat-file -e "$sha^{commit}" 2>/dev/null; then
    echo "$sha"
    return 0
  fi
  local tag
  tag=$(git tag -l 'v*' --sort=-v:refname | head -1)
  if [ -n "$tag" ]; then
    echo "$tag"
    return 0
  fi
  echo ""
}

# The changelog, the commits since the last pre-release, grouped by what they
# do.
changelog() {
  local since="$1"
  local line type subject
  local new="" fixes="" build="" other=""
  if [ -n "$since" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      type=$(echo "$line" | sed -nE 's/^([a-z]+)(\([^)]*\))?: .*/\1/p')
      subject=$(echo "$line" | sed -E 's/^[a-z]+(\([^)]*\))?: //')
      [ -z "$subject" ] && subject="$line"
      subject="${subject^}"
      case "$type" in
        feat|feature) new="$new- $subject"$'\n' ;;
        fix|bugfix)   fixes="$fixes- $subject"$'\n' ;;
        build|ci|perf) build="$build- $subject"$'\n' ;;
        *)            other="$other- $subject"$'\n' ;;
      esac
    done < <(git log --no-merges --pretty=format:'%s' "$since"..HEAD 2>/dev/null)
  fi
  local first=1
  section() {
    [ -z "$1" ] && return
    [ $first -eq 0 ] && printf '\n'
    first=0
    printf '### %s\n\n%b' "$2" "$1"
  }
  section "$new" "New"
  section "$fixes" "Fixes"
  section "$build" "Build"
  section "$other" "Other"
  if [ $first -eq 1 ]; then
    printf 'There is nothing new since the last build.\n'
  fi
}

# Writes the description of the pre-release release.
generate_release_notes() {
  local out="$1"
  local commit previous last_stable apk size
  commit=$(git rev-parse --short=7 HEAD)
  previous=$(previous_pre_release_commit)
  last_stable=$(git tag -l 'v*' --sort=-v:refname | head -1)

  apk=""
  size=""
  local found
  found=$(newest_apk 2>/dev/null)
  if [ -n "$found" ] && [ -f "$found" ]; then
    apk=$(basename "$found")
    size=$(awk -v s="$(stat -c%s "$found")" 'BEGIN{printf "%.1f MB", s/1048576}')
  fi

  # Say what the APK actually is, instead of assuming it was signed.
  local signing_note="unsigned"
  if [ -n "$apk" ] && [ -f "$found" ]; then
    if "$SDK_DIR"/build-tools/"$BUILD_TOOLS"/apksigner verify "$found" >/dev/null 2>&1; then
      signing_note="signed with the release key"
    fi
  fi

  {
    echo "Pre-release build of the fork, it is for testing on a device before a"
    echo "release is tagged."
    echo
    echo "Build: \`$commit\` ($(date '+%F %T'))"
    if [ -n "$apk" ]; then
      echo "APK: \`$apk\` ($size, arm64-v8a, $signing_note)"
    fi
    echo
    echo "## What is new"
    echo
    if [ -n "$previous" ] && [ "$previous" != "$last_stable" ]; then
      echo "The changes since the last build (\`$previous\`):"
    elif [ -n "$last_stable" ]; then
      echo "The changes since the last release (\`$last_stable\`):"
    else
      echo "The changes of this build:"
    fi
    echo
    changelog "$previous"
    if [ -n "$last_stable" ] && [ "$previous" != "$last_stable" ]; then
      echo
      echo "## Everything since the last release"
      echo
      echo "All the changes since \`$last_stable\`:"
      echo
      changelog "$last_stable"
    fi
    echo
    echo "## How to test"
    echo
    echo "1. Install the APK, it replaces the previous build, the signing key is"
    echo "   the same so it can be installed over it."
    echo "2. The build is for arm64 only."
    echo "3. Report the problems in the test group on Telegram."
    echo
    echo "AI assistance: this build was written with AI (Claude Code), it was checked"
    echo "with the linter, the unit tests and the binary compatibility check."
  } > "$out"
}

notes_target() {
  local file
  file=$(mktemp)
  generate_release_notes "$file"
  cat "$file"
  if [ $DRY_RUN -eq 1 ]; then
    rm -f "$file"
    echo
    say "dry run, the release was not changed"
    return 0
  fi
  echo
  gh release edit "$RELEASE_TAG" --notes-file "$file" --repo "$RELEASE_SLUG" \
    || { rm -f "$file"; die "could not update the release"; }
  rm -f "$file"
  ok "the description of the $RELEASE_TAG release is updated"
}

# --------------------------- github release ----------------------------------
# The in app updater of the prerelease flavour does not compare version names,
# it compares the commit of the installed build with the commit of the
# pre-release tag. If the tag still points at an old commit, every install is
# told to update, even when the installed APK is the newer one. Uploading the
# APK and moving the tag keeps both sides in sync.
release_upload() {
  local apk
  apk=$(newest_apk) || die "no APK found for the flavour $FLAVOR"
  [ -n "$apk" ] || die "no APK found for the flavour $FLAVOR"

  if [ "$NO_SIGN" -eq 1 ]; then
    die "refusing to publish an unsigned APK, drop --no-sign"
  fi

  local commit name
  commit=$(git rev-parse HEAD)
  name=$(basename "$apk")

  if [ $DRY_RUN -eq 1 ]; then
    say "dry run, would upload $name to $RELEASE_SLUG and move $RELEASE_TAG to $commit"
    return 0
  fi

  say "uploading $name to the $RELEASE_TAG release ..."
  gh release upload "$RELEASE_TAG" "$apk" --clobber --repo "$RELEASE_SLUG" \
    || die "could not upload the APK to the release"

  # The tag has to point at the commit that was just built, that is what the
  # updater compares against.
  say "moving the $RELEASE_TAG tag to $commit ..."
  gh api -X PATCH "repos/$RELEASE_SLUG/git/refs/tags/$RELEASE_TAG" \
    -f sha="$commit" -F force=true >/dev/null \
    || die "could not move the $RELEASE_TAG tag"

  ok "the $RELEASE_TAG release now has $name at ${commit:0:7}"
}

# ------------------------------ main -----------------------------------------
main() {
  # The notes do not need a build, they are only the description of a release
  if [ $DO_NOTES -eq 1 ] && [ ${#GRADLE_TASKS[@]} -eq 0 ]; then
    notes_target
    return $?
  fi

  preflight
  local stamp; stamp=$(date '+%Y%m%d-%H%M%S')
  local log="$LOG_DIR/build-$stamp.log"

  say "tasks     : ${GRADLE_TASKS[*]}"
  say "flavor    : $FLAVOR"
  say "abi       : arm64-v8a"
  say "log file  : $log"
  echo

  local rc=0
  if [ $DO_BUILD -eq 1 ]; then
    run_gradle "$log" "${GRADLE_TASKS[@]}"
    rc=$?

    echo
    if [ $rc -eq 0 ]; then
      ok "BUILD SUKSES (exit 0) - ${#GRADLE_TASKS[@]} task(s), $(date '+%T')"
      summary "$log"
    else
      printf '%s%sBUILD GAGAL (exit %s)%s\n' "$c_red" "$c_bold" "$rc" "$c_reset"
      on_error "$log"
      return $rc
    fi
  else
    say "skipping the build"
  fi

  if [ $DO_SEND -eq 1 ]; then
    echo
    say "uploading to the test group ..."
    send_to_telegram
  fi

  if [ $DO_NOTES -eq 1 ]; then
    echo
    say "writing the description of the $RELEASE_TAG release ..."
    notes_target
  fi

  if [ $DO_RELEASE -eq 1 ]; then
    echo
    release_upload
  fi

  return $rc
}

main "$@"
