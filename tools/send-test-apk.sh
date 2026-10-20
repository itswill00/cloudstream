#!/usr/bin/env bash
# =============================================================================
#  send-test-apk.sh - build the app and send the APK to the test group
# =============================================================================
#  Builds the app and uploads the APK to a Telegram group with a bot, so that
#  every small fix can be tested on a real device before a release is tagged.
#
#  Usage:
#    ./tools/send-test-apk.sh                     # build + send
#    ./tools/send-test-apk.sh --no-build          # send the last build
#    ./tools/send-test-apk.sh --message "fix X"  # custom caption
#
#  Options:
#    --abi <abi>          architecture of the build (default: arm64-v8a)
#    --flavor <flavor>    prerelease or stable (default: prerelease)
#    --no-build           do not build, send the existing APK
#    --message <text>     extra text for the caption
#    --config <file>      file with the bot token and the chat id
#    --dry-run            build and show what would be sent
#
#  The bot token and the chat id are read from the config file, which is kept
#  outside of this repository:
#
#    TELEGRAM_BOT_TOKEN=123456:ABCDEF
#    TELEGRAM_CHAT_ID=-1001234567890
#
#  The build is limited to a single architecture, that keeps the APK below the
#  50 MB upload limit of the Telegram API.
# =============================================================================
set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${TELEGRAM_CONFIG:-/root/keys/telegram-bot.env}"
ABI="${TARGET_ABI:-arm64-v8a}"
FLAVOR="prerelease"
DO_BUILD=1
DRY_RUN=0
EXTRA_MESSAGE=""
SPLIT_SIZE=$((48 * 1024 * 1024)) # Telegram allows 50 MB, keep some room

c_reset=$'\033[0m'; c_red=$'\033[31m'; c_grn=$'\033[32m'; c_ylw=$'\033[33m'; c_blu=$'\033[34m'
if [ ! -t 1 ]; then c_reset=; c_red=; c_grn=; c_ylw=; c_blu=; fi
say()  { printf '%s[telegram]%s %s\n' "$c_blu" "$c_reset" "$*"; }
ok()   { printf '%s[  ok  ]%s %s\n' "$c_grn" "$c_reset" "$*"; }
warn() { printf '%s[ warn ]%s %s\n' "$c_ylw" "$c_reset" "$*"; }
die()  { printf '%s[ fail ]%s %s\n' "$c_red" "$c_reset" "$*"; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --abi)     ABI="$2"; shift ;;
    --flavor)  FLAVOR="$2"; shift ;;
    --no-build) DO_BUILD=0 ;;
    --message) EXTRA_MESSAGE="$2"; shift ;;
    --config)  CONFIG="$2"; shift ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

[ -f "$CONFIG" ] || die "config not found: $CONFIG (expected TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID)"
# shellcheck disable=SC1090
source "$CONFIG"
[ -n "${TELEGRAM_BOT_TOKEN:-}" ] || die "TELEGRAM_BOT_TOKEN is not set in $CONFIG"
[ -n "${TELEGRAM_CHAT_ID:-}" ] || die "TELEGRAM_CHAT_ID is not set in $CONFIG"

case "$FLAVOR" in
  prerelease|stable) ;;
  *) die "unknown flavor: $FLAVOR (use prerelease or stable)" ;;
esac

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# The release key, if it is available, so that the test build can be installed
# over the previous one and can be updated to the real release later.
if [ -f /root/keys/env.sh ] && [ -z "${SIGNING_KEY_ALIAS:-}" ]; then
  # shellcheck disable=SC1091
  source /root/keys/env.sh
fi

cd "$REPO_DIR" || die "cannot enter $REPO_DIR"

APK="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"

if [ $DO_BUILD -eq 1 ]; then
  say "building $FLAVOR for $ABI ..."
  if ! ./gradlew ":app:assemble${FLAVOR^}Release" -PtargetAbi="$ABI" --console=plain -q; then
    die "build failed"
  fi
  ok "build finished"
else
  say "skipping the build"
fi

[ -f "$APK" ] || die "APK not found: $APK"
SIZE=$(stat -c%s "$APK")
APK_NAME="$(basename "$APK")"

# ---------------------------------------------------------------- caption ----
COMMIT=$(git rev-parse --short=7 HEAD 2>/dev/null || echo "?")
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
SUBJECT=$(git log -1 --pretty=%s 2>/dev/null || echo "")
BODY=$(git log -1 --pretty=%b 2>/dev/null | sed '/^$/d' | grep -v '^Signed-off-by:' | head -8)

CAPTION="CloudStream test build
commit : $COMMIT ($BRANCH)
flavor : $FLAVOR
abi    : $ABI
size   : $(awk -v s="$SIZE" 'BEGIN{printf "%.1f MB", s/1048576}')

$SUBJECT"
[ -n "$BODY" ] && CAPTION="$CAPTION

$BODY"
[ -n "$EXTRA_MESSAGE" ] && CAPTION="$CAPTION

$EXTRA_MESSAGE"

# Telegram limits the caption to 1024 characters
CAPTION="${CAPTION:0:1000}"

# ------------------------------------------------------------------ send ----
send_file() {
  local file="$1" caption="$2"
  if [ $DRY_RUN -eq 1 ]; then
    say "dry run, would send $file"
    echo "--- caption ---"
    echo "$caption"
    return 0
  fi
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

if [ "$SIZE" -gt "$SPLIT_SIZE" ]; then
  warn "APK is $(awk -v s="$SIZE" 'BEGIN{printf "%.1f MB", s/1048576}'), splitting it into parts"
  TMP_DIR=$(mktemp -d)
  split -b "$SPLIT_SIZE" -d "$APK" "$TMP_DIR/${APK_NAME}.part"
  PARTS=("$TMP_DIR"/${APK_NAME}.part*)
  PART_CAPTION="$CAPTION

The APK is too big for Telegram, it is split into ${#PARTS[@]} parts.
Join them on the device with a terminal:
  cat ${APK_NAME}.part* > $APK_NAME"
  for part in "${PARTS[@]}"; do
    send_file "$part" "$PART_CAPTION"
  done
  rm -rf "$TMP_DIR"
else
  send_file "$APK" "$CAPTION"
fi

ok "done"
