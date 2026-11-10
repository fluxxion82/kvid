#!/bin/bash
# fg.sh            -> prints "launcher" or "other" for the app in front
# fg.sh FROM TO    -> counts foreground changes to an app other than the launcher between two device-local
#                     times ("YYYY-MM-DD HH:MM:SS"); app names are not printed
S=${SERIAL:?set SERIAL to the adb serial}
if [ $# -eq 0 ]; then
  adb -s "$S" shell 'dumpsys activity activities | grep -m1 topResumedActivity' 2>/dev/null | grep -q nexuslauncher && echo launcher || echo other
else
  adb -s "$S" shell 'dumpsys usagestats' 2>/dev/null | awk -v a="$1" -v b="$2" '
    /type=ACTIVITY_RESUMED/ && !/nexuslauncher/ && !/com\.kvid/ { if (match($0,/time="[^"]+"/)) { t=substr($0,RSTART+6,19); if (t>=a && t<=b) seen[t]=1 } }
    END{n=0; for (k in seen) n++; print n}'
fi
