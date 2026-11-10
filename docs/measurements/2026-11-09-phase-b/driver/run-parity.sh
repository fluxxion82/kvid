#!/bin/bash
# Phase B device driver. Usage: run.sh <label> <TestClass> KEY=VALUE...
# One instrumentation run (fresh process) per call, against the installed test APK. Appends every raw
# line to $OUT/raw.log and a thermal reading before and after to $OUT/thermal.log.
set -u
SERIAL=${SERIAL:?set SERIAL to the adb serial}
OUT=${OUT:?}
INST=${INST:-com.kvid.embed.test/androidx.test.runner.AndroidJUnitRunner}
label=$1; cls=$2; shift 2
A() { adb -s "$SERIAL" "$@"; }
thermal() {
  local t s
  t=$(A shell dumpsys battery | awk '/temperature/{print $2/10; exit}')
  s=$(A shell dumpsys thermalservice | awk -F': ' '/^Thermal Status/{print $2; exit}')
  skin=$(A shell dumpsys thermalservice | grep -m1 -o 'mName=VIRTUAL-SKIN[^,]*, mType=[0-9]*, mValue=[0-9.]*' | sed 's/.*mValue=//')
  echo "battery_c=$t thermal_status=$s skin_c=${skin:-na}"
}
mkdir -p "$OUT"
args=(); for kv in "$@"; do args+=(-e "${kv%%=*}" "${kv#*=}"); done
before=$(thermal); start=$(date -u +%FT%TZ)
A logcat -c
res=$(A shell am instrument -w -r -e class "com.kvid.embed.$cls" "${args[@]}" "$INST" 2>&1)
after=$(thermal)
lines=$(A logcat -d -s System.out | grep -o '\[kvid-[a-z-]*\].*')
status=$(echo "$res" | grep -E 'INSTRUMENTATION_CODE|^OK \(|FAILURES' | tr '\n' ' ')
{
  echo "### $start label=$label class=$cls args=$* status=[$status]"
  echo "### thermal before: $before"
  echo "$lines"
  echo "### thermal after: $after"
} >> "$OUT/raw.log"
echo "$start $label before[$before] after[$after] status[$status]" >> "$OUT/thermal.log"
echo "$res" | grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|shortMsg=Process crashed' && { echo "$res" > "$OUT/fail-$label-$(date +%s).txt"; echo "FAIL $label"; exit 1; }
echo "$lines" | tail -3
