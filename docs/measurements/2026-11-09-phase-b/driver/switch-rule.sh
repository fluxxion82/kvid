#!/bin/bash
# waits for the run in flight to end (or the phone to leave), stops the chain that uses the first rule while it is
# in the gate wait, and starts the supervisor with the second rule
set -u
P=$(cd "$(dirname "$0")" && pwd); cd "$P"; O=session2
n0=$(cat $O/done $O/disturbed $O/failed 2>/dev/null | wc -l)
while :; do
  n=$(cat $O/done $O/disturbed $O/failed 2>/dev/null | wc -l)
  [ "$n" -ne "$n0" ] && break
  adb -s "$SERIAL" get-state >/dev/null 2>&1 || { sleep 20; adb -s "$SERIAL" get-state >/dev/null 2>&1 || break; }
  pgrep -f p8-chain3.sh >/dev/null || break
  sleep 5
done
pkill -f 'supervise.sh 21600 ./p8-chain3.sh'; pkill -f p8-chain3.sh; pkill -f 'queue.sh q-s2d.txt'; sleep 2
pgrep -f 'run.sh idx-\|run.sh sweep-\|run.sh fin-\|run.sh lat-\|run.sh scr-' >/dev/null && echo "WARNING: a run.sh was still alive at the switch"
echo "$(date -u +%FT%TZ) switched to the second cooling rule (skin <= 36.4, session2/cooling-rule-2.txt); chain p8-chain4.sh" >> $O/gate.log
LOG=$P/$O/supervise.log exec ./supervise.sh 21600 ./p8-chain4.sh
