#!/usr/bin/env bash
# Emulator camera turns (needs the toolbar camera_rotation_button on: harness/uia_cam.ps1 set camera_rotation_button on).
#   tools/cam.sh left <deg>  |  tools/cam.sh up <deg>      negative = right / down; measured 0.0383 deg per fb px
#   tools/cam.sh pose                                      the app's `hmd` line (fwd vector)
set -u
H="$(dirname "$0")/harness/emu.sh"; J="$(dirname "$0")/pc.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}" PKG=io.embodit.pruningclass TAG=PruningClass
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
move() { # $1 dx px, $2 dy px (screen), in chunks of <= 500 px
  local dx=$1 dy=$2
  while [ "$dx" != 0 ] || [ "$dy" != 0 ]; do
    local sx=$dx sy=$dy
    [ "$sx" -gt 500 ] && sx=500; [ "$sx" -lt -500 ] && sx=-500
    [ "$sy" -gt 500 ] && sy=500; [ "$sy" -lt -500 ] && sy=-500
    "$H" fast on >/dev/null
    "$H" drag 1080 1080 $((1080 + sx)) $((1080 + sy)) -Button right -NoFast -Steps 10 -StepMs 25 >/dev/null
    dx=$((dx - sx)); dy=$((dy - sy))
  done
  "$H" fast on >/dev/null; "$ADB" -s "$DEV" shell sleep 1
}
px() { python -c "print(int(round(float('$1') / 0.0383)))"; }
case "${1:-}" in
  left) move $(( -$(px "$2") )) 0 ;;
  up) move 0 $(( -$(px "$2") )) ;;
  pose) "$J" cmd hmd; sleep 1; "$J" log POSE | tail -1 | sed 's/.*POSE/POSE/' ;;
  *) sed -n 2,4p "$0" ;;
esac
