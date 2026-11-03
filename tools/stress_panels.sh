#!/usr/bin/env bash
# N rounds of page changes during a lesson (S06): TabBar settings -> card -> lesson, and every 5th round the window's ×
# and the "open panel" chip (close, wait for ON_DESTROY, reopen). Counts after; screenshots are the caller's.
set -u
N="${1:-30}"; J="$(dirname "$0")/pc.sh"; DEV="${DEV:-emulator-5560}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
for i in $(seq 1 "$N"); do
  "$J" cmd 'tab=settings'; "$ADB" -s $DEV shell sleep 0.8; "$J" cmd 'tab=card'; "$ADB" -s $DEV shell sleep 0.8
  "$J" cmd 'tab=lesson'; "$ADB" -s $DEV shell sleep 0.8
  if [ $((i % 5)) = 0 ]; then
    "$J" cmd 'window=close'; "$ADB" -s $DEV shell sleep 4; "$J" cmd 'ui open_window'; "$ADB" -s $DEV shell sleep 5
  fi
done
echo "rounds=$N pages=$("$J" log 'EVENT TabSelected' | wc -l) closes=$("$J" log 'WINDOW ON_DESTROY' | wc -l) opens=$("$J" log 'WINDOW ON_CREATE' | wc -l) overflow=$("$J" log OVERFLOW | wc -l) crash=$("$ADB" -s $DEV logcat -d -b crash | grep -c pruningclass)"
