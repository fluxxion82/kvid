#!/bin/bash
# OUT=dir SERIAL=x CHECKER=path run5.sh <label> <TestClass> <setting>
# One fresh instrumentation process. Writes, per run:
#   $OUT/raw/<label>.txt      the logcat lines of the three kvid tags, as printed (result line and samples lines)
#   $OUT/thermal/<label>.txt  thermal readings before, every 15 s during, and after, each with host UTC and phone_ms
#   $OUT/check/<label>.txt    the checker's output and exit status
# Exit: 0 valid, 1 instrumentation failed, 2 phone left, 5 another app in front, 6 checker failed,
#       7 the line's settings differ from the queue's.
set -u
SERIAL=${SERIAL:?}; OUT=${OUT:?}; CHECKER=${CHECKER:?}; export SERIAL
INST=${INST:-com.kvid.embed.test/androidx.test.runner.AndroidJUnitRunner}
VARIANTS=${VARIANTS:-/data/local/tmp/kvid-model-variants}
P=$(dirname "$0"); label=$1; cls=$2; setting=$3
case $cls in IndexingMeasurementTest) key=KVID_INDEX_MEASURE;; *) key=KVID_EMBEDDING_TUNE;; esac
A() { adb -s "$SERIAL" "$@"; }
hms() { python3 -c 'import time;print(int(time.time()*1000))'; }
mkdir -p "$OUT/raw" "$OUT/thermal" "$OUT/check" "$OUT/instrument"
[ -e "$OUT/raw/$label.txt" ] && { echo "label $label already used"; exit 1; }
T="$OUT/thermal/$label.txt"; start=$(date -u +%FT%TZ)
h0=$(hms); pm=$(A shell date +%s%3N | tr -d '\r'); h1=$(hms)
echo "### label=$label class=$cls $key=$setting start=$start" > "$T"
echo "### clock: host_ms_before=$h0 phone_ms=$pm host_ms_after=$h1 phone_minus_host_ms=$(( pm - (h0 + h1) / 2 ))" >> "$T"
fg0=$($P/fg.sh); dev0=$(A shell date "+%Y-%m-%d\ %H:%M:%S")
echo "before host_utc=$(date -u +%FT%TZ) $($P/therm5.sh)" >> "$T"
A logcat -c
( while :; do sleep ${SAMPLE_S:-15}; echo "during host_utc=$(date -u +%FT%TZ) $($P/therm5.sh)" >> "$T"; done ) & sp=$!
res=$(A shell am instrument -w -r -e class "com.kvid.embed.$cls" -e KVID_MODEL_VARIANTS_DIR "$VARIANTS" -e "$key" "$setting" "$INST" 2>&1)
kill $sp 2>/dev/null; wait $sp 2>/dev/null
echo "$res" > "$OUT/instrument/$label.txt"
if ! A get-state >/dev/null 2>&1; then
  echo "### phone left during the run; end=$(date -u +%FT%TZ)" >> "$T"
  echo "$(date -u +%FT%TZ) $label rc=2 phone left (started $start)" >> "$OUT/runs.log"; exit 2
fi
echo "after host_utc=$(date -u +%FT%TZ) $($P/therm5.sh)" >> "$T"
fg1=$($P/fg.sh); dev1=$(A shell date "+%Y-%m-%d\ %H:%M:%S"); other=$($P/fg.sh "$dev0" "$dev1")
echo "### foreground: front_before=$fg0 front_after=$fg1 other_app_resumes_during=$other end=$(date -u +%FT%TZ)" >> "$T"
A logcat -d -s System.out | grep -E "\[kvid-(index-measure|index-samples|embedding-tune)\]" > "$OUT/raw/$label.txt"
status=$(echo "$res" | grep -E 'INSTRUMENTATION_CODE|^OK \(|FAILURES' | tr -d '\r' | tr '\n' ' ')
rc=0; why=valid
if echo "$res" | grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|shortMsg=Process crashed' || ! echo "$res" | grep -q '^OK ('; then
  rc=1; why="instrumentation failed"
else
  python3 -I "$CHECKER" "$OUT/raw/$label.txt" > "$OUT/check/$label.txt" 2>&1; crc=$?; echo "exit=$crc" >> "$OUT/check/$label.txt"
  if [ $crc -ne 0 ]; then rc=6; why="checker failed"
  elif ! grep -qF " config=$setting " "$OUT/raw/$label.txt"; then rc=7; why="settings differ from the queue's"
  elif [ "$fg0" != launcher ] || [ "$fg1" != launcher ] || [ "$other" != 0 ]; then rc=5; why="another app in front"
  fi
fi
echo "$(date -u +%FT%TZ) $label rc=$rc $why status=[$status] started=$start" >> "$OUT/runs.log"
echo "$label rc=$rc $why"
exit $rc
