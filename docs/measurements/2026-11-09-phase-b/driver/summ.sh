#!/bin/bash
# summ.sh raw.log : one row per run
awk '
/^### 20/ {match($0,/label=[^ ]+/); label=substr($0,RSTART+6,RLENGTH-6); split($2,t,"T"); start=t[2]}
/^### thermal before/ {match($0,/skin_c=[0-9.]+/); sb=substr($0,RSTART+7,RLENGTH-7); match($0,/battery_c=[0-9.]+/); bb=substr($0,RSTART+10,RLENGTH-10)}
/^\[kvid-(embedding-tune|index-measure)\]/ {line=$0}
/^### thermal during/ {match($0,/thermal_status=[0-9]/); s=substr($0,RSTART+15,1); if(s>mx)mx=s}
/^### thermal after/ {match($0,/skin_c=[0-9.]+/); sa=substr($0,RSTART+7,RLENGTH-7); match($0,/thermal_status=[0-9]/); s=substr($0,RSTART+15,1); if(s>mx)mx=s;
  n=split(line,f," "); out="";
  for(i=1;i<=n;i++){ if(f[i]~/^(per_note_ms|tokens_per_s|open_ms|mem_peak_kb|mem_after_kb|mem_after_close_kb|padded_tokens|tokens|p50_ms|p95_ms|notes_per_s|early_notes_per_s|late_notes_per_s|index_total_ms|mem_after_index_kb)=/) out=out" "f[i]}
  printf "%s %s skin=%s>%s batt=%s maxstatus=%d%s\n", start,label,sb,sa,bb,mx,out; mx=0; line=""}' "$1"
