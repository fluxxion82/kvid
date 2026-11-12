#!/bin/bash
# SERIAL=x LOG=file supervise.sh <max_wait_s> <chain script> : runs the chain whenever the phone is attached;
# when the chain stops because the phone left (exit 2), waits for it to return and runs the chain again.
set -u
SERIAL=${SERIAL:?set SERIAL to the adb serial}; LOG=${LOG:?}; max=$1; shift
while :; do
  t0=$(date +%s)
  until adb -s "$SERIAL" get-state >/dev/null 2>&1; do
    [ $(( $(date +%s) - t0 )) -gt "$max" ] && { echo "$(date -u +%FT%TZ) gave up waiting for the phone" >> "$LOG"; exit 4; }
    sleep 30
  done
  echo "$(date -u +%FT%TZ) phone attached; starting $*" >> "$LOG"
  "$@"; rc=$?
  echo "$(date -u +%FT%TZ) chain ended rc=$rc" >> "$LOG"
  [ $rc -eq 2 ] || exit $rc
  sleep 20
done
