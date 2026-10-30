#!/usr/bin/env bash
# Panel close-ups for ui-audit.md (one HD frame per panel), camera level and facing 0 (see tools/cam.sh). Docked panels
# are brought 0.6 m straight ahead with the QA hook `dockfront=1` (normally 0.9 m, 50 degrees left). Each shot waits
# 15 s: pooled panels rebuild 1.5 / 4 / 8 / 12 s after a change (the idle emulator keeps half-drawn frames).
# Run from tools/: ./shots_panel.sh [DIR]
set -u
J="$(dirname "$0")/pc.sh"; H="$(dirname "$0")/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}"
DIR="${1:-C:/Users/Admin/Downloads/spatial-app-factory/apps/pruning-class/build/shots}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
w() { "$H" fast on >/dev/null 2>&1; "$ADB" -s "$DEV" shell sleep "$1"; }
shot() { local name="$1"; shift; for c in "$@"; do "$J" cmd "$c"; done; w 4; w 4; w 4; w 3; "$J" shot "$name" "$DIR" >/dev/null; echo "$name: $("$J" state | sed 's/.*PC-STATE //')"; }
"$J" cmd 'state=S02'; w 2; "$J" cmd 'lesson=R1'; w 4; w 4; w 4; w 4
"$J" cmd 'dockfront=1'
for lang in zh en; do
  "$J" cmd "lang=$lang"
  shot panel-$lang-S02-picker 'state=S02'
  shot panel-$lang-S05-rules 'state=S05:A2'
  shot panel-$lang-S06-control 'state=S06:R1' 'dockfront=1'
  shot panel-$lang-S07-control 'state=S07:R1'
  shot panel-$lang-S10-control 'state=S10:R2' 'dockfront=1'
  shot panel-$lang-S11-result 'state=S11:R2'
  shot panel-$lang-S12-result 'view=yours'
  shot panel-$lang-S09-explain 'state=S09:R1' 'dockfront=0'
  shot panel-$lang-S15-settings 'state=S15:R1' 'dockfront=1'
  shot panel-$lang-S15b-confirm 'state=S15b:quit'
  shot panel-$lang-S20-cancel 'state=S20:R1'
  shot panel-$lang-S14-card 'state=S14'
  shot panel-$lang-S13-graduated 'state=S13:A3'
  shot panel-$lang-S16-dialog 'state=S16'
  "$J" cmd 'blocked=0'
done
"$J" cmd 'dockfront=0'; "$J" cmd 'lang=zh'; "$J" cmd release
