#!/bin/bash
# prints: battery_c thermal_status skin_c charge_status level wakefulness
S=${SERIAL:?set SERIAL to the adb serial}
adb -s "$S" shell 'dumpsys battery; echo ===; dumpsys thermalservice; echo ===; dumpsys power | grep mWakefulness=' | awk -v K="${SKIN_SENSOR:-VIRTUAL-SKIN}" '
/^===/{sec++; next}
sec==0 && /temperature:/{b=$2/10}
sec==0 && /status:/{c=$2}
sec==0 && /level:/ && !/Capacity/{l=$2}
sec==1 && /^Thermal Status/{split($0,a,": "); t=a[2]}
sec==1 && /Temperature\{/ && index($0,"mName=" K ","){match($0,/mValue=[0-9.]+/); k=substr($0,RSTART+7,RLENGTH-7)}
sec==2 && /mWakefulness=/{split($0,a,"="); w=a[2]}
END{printf "battery_c=%.1f thermal_status=%s skin_c=%.1f charge_status=%s level=%s wake=%s\n", b,t,k,c,l,w}'
