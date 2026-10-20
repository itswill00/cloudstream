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
   --message <text> extra text for the caption of the upload
   --config <file>  file with TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID
   -h|--help        show this help

 Env overrides:
   REPO_DIR, ANDROID_HOME/ANDROID_SDK_ROOT, JAVA_HOME, BUILD_LOG_DIR,
   KEYS_ENV_FILE (file with SIGNING_KEY_ALIAS and the passwords),
   TELEGRAM_CONFIG (file with the bot token and the chat id)
=============================================================================
USAGE
}

say()  { printf '%s[build]%s %s\n' "$c_blu" "$c_reset" "$*"; }
ok()   { printf '%s[ ok ]%s %s\n' "$c_grn" "$c_reset" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$c_ylw" "$c_reset" "$*"; }
die()  { printf '%s[fail]%s %s\n' "$c_red" "$c_reset" "$*"; exit 1; }

# --------------------------- arg parsing ------------------------------------
TASKS=(); DO_INSTALL=0; DO_CLEAN=0; OFFLINE=0; NO_DAEMON=0; USE_CC=0; JOBS=""
FLAVOR="prerelease"; NO_SIGN=0; DO_BUILD=1; DO_SEND=0
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
    --message)   EXTRA_MESSAGE="${2:-}"; shift ;;
    --config)    TELEGRAM_CONFIG="$2"; shift ;;
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
    desktop) echo ":desktopApp:build" ;;
    *)       echo "$1" ;;
  esac
}
GRADLE_TASKS=()
for t in "${TASKS[@]}"; do
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
      # shellcheck disable=SC1090
      source "$keys_env"
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
SPLIT_SIZE=$((48 * 1024 * 1024)) # Telegram allows 50 MB, keep some room

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
  local response
  response=$(curl -s --max-time 900 \
    -F "chat_id=$TELEGRAM_CHAT_ID" \
    -F "caption=$caption" \
    -F "document=@$file" \
    "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendDocument")
  if echo "$response" | grep -q '"ok":true'; then
    local msg_id
    msg_id=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['message_id'])" 2>/dev/null)
    ok "sent $file (message id $msg_id)"
  else
    local desc
    desc=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('description', response))" 2>/dev/null)
    die "upload failed: $desc"
  fi
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

  local size; size=$(stat -c%s "$apk")
  local name; name=$(basename "$apk")
  local caption; caption=$(telegram_caption "$size")

  if [ "$size" -gt "$SPLIT_SIZE" ]; then
    warn "the APK is $(awk -v s="$size" 'BEGIN{printf "%.1f MB", s/1048576}'), it is split into parts"
    local tmp; tmp=$(mktemp -d)
    split -b "$SPLIT_SIZE" -d "$apk" "$tmp/$name.part"
    local parts=("$tmp"/"$name".part*)
    caption="$caption

The APK is too big for Telegram, it is split into ${#parts[@]} parts.
Join them on the device with a terminal:
  cat ${name}.part* > $name"
    for part in "${parts[@]}"; do
      telegram_upload "$part" "$caption"
    done
    rm -rf "$tmp"
  else
    telegram_upload "$apk" "$caption"
  fi
}

# ------------------------------ main -----------------------------------------
main() {
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

  return $rc
}

main "$@"
