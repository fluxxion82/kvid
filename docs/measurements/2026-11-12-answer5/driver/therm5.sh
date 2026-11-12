#!/bin/bash
# prints: phone_ms battery_c thermal_status skin_c charge_status level wake, from one adb shell call.
# phone_ms is the phone's clock (epoch ms), read first in that call.
S=${SERIAL:?set SERIAL to the adb serial}
adb -s "$S" shell 'date +%s%3N; echo ===; dumpsys battery; echo ===; dumpsys thermalservice; echo ===; dumpsys power | grep mWakefulness=' 2>/dev/null | awk -v K="${SKIN_SENSOR:-VIRTUAL-SKIN}" '
/^===/{sec++; next}
sec==0 && /^[0-9]+/{ms=$1}
sec==1 && /temperature:/{b=$2/10}
sec==1 && /status:/{c=$2}
sec==1 && /level:/ && !/Capacity/{l=$2}
sec==2 && /^Thermal Status/{split($0,a,": "); t=a[2]}
sec==2 && /Temperature\{/ && index($0,"mName=" K ","){match($0,/mValue=[0-9.]+/); k=substr($0,RSTART+7,RLENGTH-7)}
sec==3 && /mWakefulness=/{split($0,a,"="); w=a[2]}
END{printf "phone_ms=%s battery_c=%.1f thermal_status=%s skin_c=%.1f charge_status=%s level=%s wake=%s\n", ms,b,t,k,c,l,w}'
