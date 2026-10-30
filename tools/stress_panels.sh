#!/usr/bin/env bash
# N rounds of panel in / out during a lesson (S06): settings open + close, then settings -> rule card -> close (back to
# the settings page) -> settings closed (the card opens from the picker or the settings page). Counts after.
set -u
N="${1:-30}"; J="$(dirname "$0")/pc.sh"; DEV="${DEV:-emulator-5560}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
for i in $(seq 1 "$N"); do
  "$J" cmd settings; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd settings; "$ADB" -s $DEV shell sleep 0.6
  "$J" cmd settings; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd card; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd close; "$ADB" -s $DEV shell sleep 0.6; "$J" cmd settings; "$ADB" -s $DEV shell sleep 0.8
done
echo "rounds=$N settings=$("$J" log 'PC-STATE S15' | wc -l) (3 per round) card=$("$J" log 'PC-STATE S14' | wc -l) back=$("$J" log 'PC-STATE S06' | wc -l) crash=$("$ADB" -s $DEV logcat -d -b crash | grep -c pruningclass)"
