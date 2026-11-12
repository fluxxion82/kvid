#!/bin/bash
# OUT=dir SERIAL=x SKIN_MAX=x CHECKER=path queue5.sh blocks.txt configs.txt
# blocks.txt: "<block> <config> <config>..." in run order.  configs.txt: "<config> <setting>".
# A block is valid when every run of it is valid. A block that loses a run is listed in blocks-excluded and
# repeated whole under a new tag (b1, b1r1, b1r2). At most BLOCKS_PER_SESSION valid blocks per call.
# Exit: 0 queue done, 1 instrumentation failed, 2 phone left, 3 gate timer ran out, 6 checker failed (1 and 6 are
# harness faults: nothing further is run), 9 this session's blocks are done and others remain.
set -u
P=$(dirname "$0"); OUT=${OUT:?}; SERIAL=${SERIAL:?}; SKIN_MAX=${SKIN_MAX:?}; CHECKER=${CHECKER:?}; export OUT SERIAL CHECKER
B=$1; C=$2; GATE_S=${GATE_S:-7200}; PER=${BLOCKS_PER_SESSION:-2}; MAXA=${MAX_ATTEMPTS:-3}
TEMPLATE=${TEMPLATE:-corpus=mixed+notes=5000+%s+edits=200+queries=200+pause=300}
mkdir -p "$OUT"; touch "$OUT/blocks-valid" "$OUT/blocks-excluded"; made=0
gate() {  # $1 label; 0 ok, 2 phone left, 3 timer ran out
  local t0=$(date +%s) line fg st skin cs why
  while :; do
    line=$($P/therm5.sh); st=$(echo "$line" | sed -n 's/.*thermal_status=\([0-9][0-9]*\).*/\1/p')
    if [ -z "$st" ]; then sleep 30; adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "$(date -u +%FT%TZ) gate label=$1 phone left" >> "$OUT/gate.log"; return 2; }; continue; fi
    fg=$($P/fg.sh); skin=$(echo "$line" | sed 's/.*skin_c=\([0-9.]*\).*/\1/'); cs=$(echo "$line" | sed 's/.*charge_status=\([0-9]*\).*/\1/'); why=
    echo "$line" | grep -Eq "wake=(1|Awake)" || { adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP; echo "$(date -u +%FT%TZ) screen was off before $1; wake key sent ($line)" >> "$OUT/gate.log"; sleep 5; continue; }
    [ "$fg" != launcher ] && why="$why front"
    [ "$st" != 0 ] && why="$why status"
    [ "$cs" = 2 ] && why="$why charging"
    awk "BEGIN{exit !($skin<=$SKIN_MAX)}" || why="$why skin"
    if [ -z "$why" ]; then echo "$(date -u +%FT%TZ) gate label=$1 waited_s=$(( $(date +%s) - t0 )) gate=ok skin_max=$SKIN_MAX front=$fg $line" >> "$OUT/gate.log"; return 0; fi
    if [ $(( $(date +%s) - t0 )) -gt $GATE_S ]; then echo "$(date -u +%FT%TZ) gate label=$1 waited_s=$(( $(date +%s) - t0 )) gate=timeout:$why skin_max=$SKIN_MAX front=$fg $line" >> "$OUT/gate.log"; return 3; fi
    sleep 20
  done
}
while read -r block order <&3; do
  [ -z "$block" ] && continue
  while ! grep -qx "$block" "$OUT/blocks-valid"; do
    n=$(grep -c "^$block " "$OUT/blocks-excluded"); [ "$n" -ge "$MAXA" ] && { echo "SKIP $block: excluded $n times"; break; }
    [ $made -ge $PER ] && { echo "SESSION DONE: $made blocks; $block and later need another session"; exit 9; }
    tag=$block; [ "$n" -gt 0 ] && tag="${block}r$n"; ok=1
    for c in $order; do
      cfg=$(awk -v c="$c" '$1==c{print $2}' "$C"); [ -z "$cfg" ] && { echo "no configuration $c"; exit 1; }
      setting=$(printf "$TEMPLATE" "$cfg")
      gate "$tag-$c"; g=$?
      [ $g -eq 2 ] && { echo "$block $tag phone left before $c" >> "$OUT/blocks-excluded"; exit 2; }
      [ $g -eq 3 ] && { echo "$block $tag gate timer ran out before $c" >> "$OUT/blocks-excluded"; echo "GATE TIMEOUT before $tag-$c"; exit 3; }
      echo "== $tag-$c $setting"
      bash $P/run5.sh "$tag-$c" IndexingMeasurementTest "$setting"; rc=$?
      case $rc in
        0) ;;
        2) echo "$block $tag phone left during $c" >> "$OUT/blocks-excluded"; exit 2;;
        5) echo "$block $tag another app in front during $c" >> "$OUT/blocks-excluded"; ok=0; break;;
        7) echo "$block $tag settings of $c differ from the queue's" >> "$OUT/blocks-excluded"; ok=0; break;;
        *) echo "HARNESS FAULT rc=$rc at $tag-$c: stopping"; exit $rc;;
      esac
    done
    [ $ok = 1 ] && { echo "$block" >> "$OUT/blocks-valid"; echo "$(date -u +%FT%TZ) $block valid as $tag" >> "$OUT/runs.log"; made=$((made+1)); }
  done
done 3< "$B"
echo QUEUE DONE
