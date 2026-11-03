#!/usr/bin/env bash
# Close-ups of every page of the main system window for ui-audit.md (one HD frame per page, zh + en). Start right after
# a launch with the camera level and facing the window (it opens straight ahead). Pages rebuild up to 20 s after a
# change on the idle emulator, so each shot waits 15 s. Run from tools/: ./shots_panel.sh [DIR]
set -u
J="$(dirname "$0")/pc.sh"; H="$(dirname "$0")/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}"
DIR="${1:-C:/Users/Admin/Downloads/spatial-app-factory/apps/pruning-class/build/shots}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
w() { "$H" fast on >/dev/null 2>&1; "$ADB" -s "$DEV" shell sleep "$1"; }
shot() { local name="$1"; shift; for c in "$@"; do "$J" cmd "$c"; done; w 4; w 4; w 4; w 3; "$J" shot "$name" "$DIR" >/dev/null; echo "$name: $("$J" state | sed 's/.*PC-STATE //')"; }
"$J" cmd 'state=S02'; w 2; "$J" cmd 'lesson=R1'; w 4; w 4; w 4; w 4
for lang in zh en; do
  "$J" cmd "lang=$lang"
  shot win-$lang-S02-picker 'state=S02'
  shot win-$lang-S05-rules 'state=S05:A2'
  shot win-$lang-S06-control 'state=S06:R1'
  shot win-$lang-S07-control 'state=S07:R1'
  shot win-$lang-S10-control 'state=S10:R2'
  shot win-$lang-S11-result 'state=S11:R2'
  shot win-$lang-S12-result 'view=yours'
  shot win-$lang-S14-card 'state=S14'
  shot win-$lang-S15-settings 'state=S15:R1'
  shot win-$lang-S15b-confirm 'state=S15b:quit'
  shot win-$lang-S20-cancel 'state=S20:R1'
  shot win-$lang-S13-graduated 'state=S13:A3'
  shot win-$lang-S16-dialog 'state=S16'
  "$J" cmd 'blocked=0'
done
"$J" cmd 'lang=zh'; "$J" cmd release
