#!/usr/bin/env bash
# Runs the built app on an iOS simulator and checks the store lifecycle:
#   1. launching creates and seeds the store and keeps it open in the foreground;
#   2. moving to the background (another app in front) closes it cleanly.
set -euo pipefail
APP="${1:?usage: smoke-test.sh path/to/KvidNotes.app}"
BUNDLE=com.kvid.notes
CHECK="$(dirname "$0")/../check_store.py"

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
runtimes = json.load(sys.stdin)["devices"]
def version(key):
    return [int(part) for part in key.rsplit("iOS-", 1)[-1].split("-") if part.isdigit()]
for runtime in sorted((k for k in runtimes if "iOS" in k), key=version, reverse=True):
    for device in runtimes[runtime]:
        if device["name"].startswith("iPhone"):
            print(device["udid"]); sys.exit()
sys.exit("no available iPhone simulator")')
echo "Simulator $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl install "$UDID" "$APP"
xcrun simctl launch "$UDID" "$BUNDLE"
sleep 20
if ! xcrun simctl spawn "$UDID" launchctl list | grep -q "$BUNDLE"; then
  echo "::error::$BUNDLE is not running after launch"; exit 1
fi
STORE="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)/Documents/notes.kvid"
python3 "$CHECK" "$STORE" 0 6                  # open in the foreground, seeded

xcrun simctl launch "$UDID" com.apple.Preferences
sleep 8
python3 "$CHECK" "$STORE" 1 6                  # closed cleanly in the background
echo "iOS sample smoke test passed"
