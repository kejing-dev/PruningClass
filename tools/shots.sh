#!/usr/bin/env bash
# Every designed state (design stateModel.qaCombos) on the factory emulator. Run from tools/: ./shots.sh [DIR]
# Recipe: the first lesson is entered the real way (the scene finds the room's floor, y = 0.40 here); later jumps reuse it.
# Camera: tree shots with the camera pitched ~30 degrees down (PITCH=1 assumed by the caller); panel shots by shots_panel.
set -u
J="$(dirname "$0")/pc.sh"; H="$(dirname "$0")/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}"
DIR="${1:-C:/Users/Admin/Downloads/spatial-app-factory/apps/pruning-class/build/shots}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
w() { "$H" fast on >/dev/null 2>&1; "$ADB" -s "$DEV" shell sleep "$1"; }
shot() { local name="$1"; shift; for c in "$@"; do "$J" cmd "$c"; done; w 4; w 4; w 3; "$J" shot "$name" "$DIR" >/dev/null; echo "$name: $("$J" state)"; }
"$J" cmd release; "$J" cmd 'lang=zh'
shot S02 'state=S02'
shot S04-R1 'lesson=R1'
w 4; w 4
shot S05-R1 'release'
shot S06-R1 'state=S06:R1'
shot S07-R1 'state=S07:R1'
shot S08-R1 'state=S08:R1'
shot S09-R1 'state=S09:R1'
shot S10-R2 'state=S10:R2'
shot S06-R2-testF 'state=S07:R2' 'testtex=1'
"$J" cmd 'testtex=0'
shot S11-R1 'state=S11:R1'
shot S12-R2-yours 'state=S12:R2'
shot S12-R2-model 'view=model'
shot S06-A1 'state=S06:A1'
shot S09-A2-overPrune 'state=S06:A2' 'autocut=over25' 'explain=overPrune'
shot S11-A3-over 'state=S06:A3' 'autocut=over25' 'done'
w 8; w 8; w 8
shot S12-A3-yours 'view=yours'
w 8; w 8
shot S12-A3-yours2 'hmd'
shot S13-R3 'state=S13:R3'
shot S14 'state=S14'
shot S15 'state=S15:R1'
shot S15b 'state=S15b:quit'
shot S16 'state=S16'
shot S20 'blocked=0' 'release' 'state=S20:R1'
shot S21 'state=S21:R1' 'pose'
"$J" cmd 'lang=en'
shot S17-S02 'release' 'state=S02'
shot S17-S06 'state=S06:R2'
shot S17-S11 'state=S11:R2'
shot S17-S12 'view=yours'
"$J" cmd 'lang=zh'; "$J" cmd release; "$J" cmd 'state=S02'
