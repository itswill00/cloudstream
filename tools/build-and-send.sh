#!/usr/bin/env bash
# Build the APK with the test signing key and send it to the Telegram group.
#
# The Gradle build only signs when SIGNING_KEY_ALIAS is set, and it looks for
# the keystore in ~/work/_temp/keystore, so both are prepared here instead of
# being retyped on every build.
#
#   ./tools/build-and-send.sh              # release APK, Telegram + GitHub release
#   ./tools/build-and-send.sh --no-send    # build only
#   ./tools/build-and-send.sh debug        # debug APK instead
#
# The GitHub release is uploaded by default because the in app updater of the
# prerelease flavour compares the commit of the installed build with the commit
# of the pre-release tag. Without moving the tag every install keeps being told
# to update.
#
# Everything after the known flags is passed straight to build.sh, so
# --abi, --flavor, --config and the rest keep working.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_SRC="${KEYSTORE_SRC:-/root/keys/test.keystore}"
KEYSTORE_DIR="$HOME/work/_temp/keystore"
KEY_ALIAS="${SIGNING_KEY_ALIAS:-testkey}"
STORE_PASS="${SIGNING_STORE_PASSWORD:-testpass}"
KEY_PASS="${SIGNING_KEY_PASSWORD:-testpass}"

die() { echo "error: $*" >&2; exit 1; }

[ -f "$KEYSTORE_SRC" ] || die "keystore not found: $KEYSTORE_SRC"
[ -f "$REPO_DIR/build.sh" ] || die "build.sh not found in $REPO_DIR"

# The Gradle signing config takes the first file in this directory, so it has
# to contain the keystore and nothing else.
mkdir -p "$KEYSTORE_DIR"
rm -f "$KEYSTORE_DIR"/*
cp "$KEYSTORE_SRC" "$KEYSTORE_DIR/$(basename "$KEYSTORE_SRC")"

export SIGNING_KEY_ALIAS="$KEY_ALIAS"
export SIGNING_STORE_PASSWORD="$STORE_PASS"
export SIGNING_KEY_PASSWORD="$KEY_PASS"

# Without an explicit mode build.sh builds a debug APK, the release one is what
# gets tested on a device.
MODE="release"
SEND=1
RELEASE=1
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-send) SEND=0 ;;
        --no-release) RELEASE=0 ;;
        --release) RELEASE=1 ;;
        debug|release|stable|all|check) MODE="$arg" ;;
        *) ARGS+=("$arg") ;;
    esac
done

CMD=("$REPO_DIR/build.sh" "$MODE")
[ "$SEND" -eq 1 ] && CMD+=(--send)
[ "$RELEASE" -eq 1 ] && CMD+=(--release)
CMD+=("${ARGS[@]}")

echo "[build-and-send] ${CMD[*]}"
exec "${CMD[@]}"
