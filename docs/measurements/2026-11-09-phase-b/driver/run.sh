#!/bin/bash
# OUT=dir run.sh <label> <TestClass> KEY=VALUE...   one fresh instrumentation process per call
set -u
SERIAL=${SERIAL:?set SERIAL to the adb serial}
OUT=${OUT:?}
INST=${INST:-com.kvid.embed.test/androidx.test.runner.AndroidJUnitRunner}
P=$(dirname "$0")
label=$1; cls=$2; shift 2
A() { adb -s "$SERIAL" "$@"; }
mkdir -p "$OUT"
args=(); for kv in "$@"; do args+=(-e "${kv%%=*}" "${kv#*=}"); done
before=$($P/therm.sh); start=$(date -u +%FT%TZ)
A logcat -c
samples=$(mktemp)
( while :; do sleep ${SAMPLE_S:-15}; echo "$(date -u +%T) $($P/therm.sh)" >> "$samples"; done ) & sp=$!
res=$(A shell am instrument -w -r -e class "com.kvid.embed.$cls" "${args[@]}" "$INST" 2>&1)
kill $sp 2>/dev/null; wait $sp 2>/dev/null
after=$($P/therm.sh)
lines=$(A logcat -d -s System.out | grep -o '\[kvid[a-z-]*\].*')
status=$(echo "$res" | grep -E 'INSTRUMENTATION_CODE|^OK \(|FAILURES' | tr '\n' ' ')
{ echo "### $start label=$label class=$cls args=$* status=[$status]"
  echo "### thermal before: $before"
  echo "$lines"
  sed 's/^/### thermal during: /' "$samples"
  echo "### thermal after: $after end=$(date -u +%FT%TZ)"; } >> "$OUT/raw.log"
echo "$start $label before[$before] after[$after] status[$status]" >> "$OUT/thermal.log"
rm -f "$samples"
if echo "$res" | grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|shortMsg=Process crashed' || ! echo "$res" | grep -q '^OK ('; then
  echo "$res" > "$OUT/fail-$label-$(date +%s).txt"; echo "FAIL $label"; exit 1; fi
echo "$lines" | tail -2
