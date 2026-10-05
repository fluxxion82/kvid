#!/usr/bin/env bash
# Runs the installed debug app on a connected emulator and checks the store lifecycle on a device:
#   1. a fresh launch creates and seeds the store and keeps it open in the foreground;
#   2. leaving the foreground closes it cleanly (ProcessLifecycleOwner ON_STOP);
#   3. a process killed with the store open relaunches, recovers and works.
# Returning a live process to the foreground does not reopen the store by itself; the next use does.
set -euo pipefail
PKG=com.kvid.sample
ACTIVITY="$PKG/com.kvid.sample.android.MainActivity"
WORK=$(mktemp -d)
CHECK="$(dirname "$0")/../check_store.py"

# Copies the store off the device. A copy can come back empty while the app is changing state, so
# retry until the file starts with the SQLite header, and log every failed attempt.
pull_store() {
  for attempt in 1 2 3 4 5; do
    if adb exec-out run-as "$PKG" cat files/notes.kvid > "$WORK/notes.kvid" 2> "$WORK/pull.err" \
        && [ "$(head -c 15 "$WORK/notes.kvid")" = "SQLite format 3" ]; then
      return 0
    fi
    echo "store copy attempt $attempt is not a SQLite file ($(wc -c < "$WORK/notes.kvid") bytes) $(cat "$WORK/pull.err")"
    sleep 2
  done
  adb shell run-as "$PKG" ls -l files || true
  echo "::error::could not copy the store from the device"; exit 1
}
assert_alive() {
  if ! adb shell pidof "$PKG" > /dev/null; then
    adb logcat -d -b crash || true
    echo "::error::$PKG is not running: $1"; exit 1
  fi
}
assert_no_crash() {
  if adb logcat -d -b crash | grep -q "$PKG"; then
    adb logcat -d -b crash
    echo "::error::$PKG crashed: $1"; exit 1
  fi
}

adb logcat -c
adb shell am start -W -n "$ACTIVITY"
sleep 15
assert_alive "after first launch"
assert_no_crash "after first launch"
pull_store
python3 "$CHECK" "$WORK/notes.kvid" 0 6        # open in the foreground, seeded

adb shell input keyevent KEYCODE_HOME
sleep 5
assert_no_crash "after leaving the foreground"
pull_store
python3 "$CHECK" "$WORK/notes.kvid" 1 6        # closed cleanly in the background

# Returning a live process to the foreground does not touch the store; it reopens on the next use.
# A fresh process opens it while composing the list, so restart to get an open store to kill.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 10
pull_store
python3 "$CHECK" "$WORK/notes.kvid" 0 6        # a new process reopened the store
adb shell am force-stop "$PKG"                 # kill with the store open
sleep 2
adb shell am start -W -n "$ACTIVITY"
sleep 10
assert_alive "after relaunching a killed process"
assert_no_crash "after relaunching a killed process"
adb shell input keyevent KEYCODE_HOME
sleep 5
pull_store
python3 "$CHECK" "$WORK/notes.kvid" 1 6        # recovered, used and closed cleanly again
echo "Android sample smoke test passed"
