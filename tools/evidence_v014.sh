#!/usr/bin/env bash
# v0.1.4 hand-off evidence on the final APK, one task at a time on emulator-5560 (QA round 2 rework).
#   default view (cold start, camera untouched) -> every window page zh + en (+ blank meter) -> the rose's stand / pot
#   close-up -> settings first screen (volume slider) -> reset confirm -> D41 phases (zh, rose) -> real quit + recents
set -u
D="$(dirname "$0")"; J="$D/pc.sh"; export DEV="${DEV:-emulator-5560}"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
B=/c/Users/Admin/Downloads/spatial-app-factory/apps/pruning-class/build
V="$B/v014"
w() { "$ADB" -s "$DEV" shell sleep "$1"; }
up() { # launch until the Stage answers `hmd`
  for k in 1 2 3; do
    "$J" launch; w 30
    "$D/cam.sh" pose | grep -q "POSE hmd" && return 0
    echo "launch $k: Stage not up, retrying"; "$J" stop; w 10
  done
  return 1
}
"$ADB" -s "$DEV" install -r "$D/../app/build/outputs/apk/debug/app-debug.apk" | tail -1; w 10
up || { echo "ABORT: app does not start"; exit 2; }
"$J" shot default-view "$B" >/dev/null; echo "default-view: $("$D/cam.sh" pose)"

mkdir -p "$V/pages"
"$D/shots_panel.sh" "$V/pages" > "$V/pages/shots.log" 2>&1
python "$D/blank_meter.py" "$V/pages"/win-*.png | tee "$V/pages/blank.txt"

# stand / pot close-up: the rose lesson the real way (room floor), camera at the tree, a little closer view
"$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd 'lang=zh'; "$J" cmd 'state=S02'; w 6; "$J" cmd 'lesson=R1'; w 30
"$J" cmd release; "$J" cmd 'state=S06:R1'; w 20
"$D/cam_to.sh" 64 -32 >/dev/null; w 12; "$J" shot stand-pot "$V" >/dev/null
"$D/cam_to.sh" 64 -20 >/dev/null; w 8; "$J" shot stand-pot-level "$V" >/dev/null
"$D/cam_to.sh" 0 0 >/dev/null

# settings first screen (the volume slider) and the reset confirm
"$J" cmd release; "$J" cmd 'tab=settings'; w 15; "$J" shot settings-firstscreen "$B" >/dev/null
"$J" cmd 'state=S15B:reset'; w 15; "$J" shot newgame-confirm "$B" >/dev/null; "$J" cmd release

# D41 regression (QA passed it in round 2; must not break): every phase front / side / ray, rose
mkdir -p "$V/d41-zh"
"$D/shots_d41.sh" zh R1 "$V/d41-zh" > "$V/d41-zh/shots.log" 2>&1
grep -h "RAY hit\|PC-LAYOUT t=1\|ABORT" "$V/d41-zh/shots.log"

# real quit from the settings buttons, then recents
"$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd 'state=S15'; w 6
"$J" cmd 'ui set_quit'; w 4; "$J" cmd 'ui confirm_yes'; w 10
echo "after quit pid=[$("$ADB" -s "$DEV" shell pidof io.embodit.pruningclass)]"
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell dumpsys activity recents > "$B/exit-recents.txt"
echo "recents lines with the package: $(grep -E 'Recent #[0-9]+:' "$B/exit-recents.txt" | grep -c pruningclass)"
echo "ANR windows: $("$ADB" -s "$DEV" shell "dumpsys window windows | grep -c 'Application Not Responding'")"
