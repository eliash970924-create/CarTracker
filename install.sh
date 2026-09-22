#!/usr/bin/env bash
#
# Builds the app and installs it on a connected phone.
#
#   ./install.sh              # latest main
#   ./install.sh some-branch  # that branch, for trying a PR before merging
#   ./install.sh --here       # whatever is checked out now, no fetch
#
# The fetch-and-checkout matters more than it looks: "git checkout main" on a
# local branch that already exists leaves it wherever it was, so you build the
# code you had last time and wonder why your change is missing. -B forces it
# to match the remote.

set -euo pipefail

cd "$(dirname "$0")"

BRANCH="main"
UPDATE=1
case "${1:-}" in
    --here) UPDATE=0 ;;
    "") ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) BRANCH="$1" ;;
esac

# --- find adb, so a missing phone fails now rather than after a long build ---

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
               "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then
            ADB="$sdk/platform-tools/adb"
            break
        fi
    done
fi

if [ -n "$ADB" ]; then
    if [ "$("$ADB" devices | grep -c -w device || true)" -eq 0 ]; then
        echo "No phone connected."
        echo
        echo "  - plug it in over USB"
        echo "  - turn on USB debugging in Developer options"
        echo "  - answer the 'Allow USB debugging?' prompt on the screen"
        echo
        echo "Then run this again. '$ADB devices' shows what it can see."
        exit 1
    fi
else
    echo "Note: adb not found, so a missing phone will only show up at install."
fi

# --- update the checkout ---

if [ "$UPDATE" -eq 1 ]; then
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "You have uncommitted changes. Commit or stash them first, or use"
        echo "./install.sh --here to build what you have."
        exit 1
    fi

    echo "Fetching $BRANCH..."
    git fetch origin "$BRANCH"
    git checkout -B "$BRANCH" "origin/$BRANCH"
fi

echo "Building and installing $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))..."
./gradlew installDebug

# --- launch it, so you land in the app rather than hunting for the icon ---

APP_ID="$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\(.*\)"/\1/p' \
    app/build.gradle.kts | head -1)"

if [ -n "$ADB" ] && [ -n "$APP_ID" ]; then
    "$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1 || true
fi

echo
echo "Installed."
