#!/usr/bin/env bash
# D41 evidence (2026-11-04): every interactive phase, three shots each:
#   <phase>-front  camera level, facing the window (the user's front view: nothing over the tree)
#   <phase>-side   camera level, 35 deg right: the window's right edge and the tree in one frame (the gap)
#   <phase>-ray    camera facing the tree, 25 deg down, `ray=tree` (stand-in controller ray + hit dot)
# tools/shots_d41.sh <lang zh|en> <lesson R1..A3> <out dir>
set -u
D="$(dirname "$0")"; J="$D/pc.sh"; export DEV="${DEV:-emulator-5560}"
LANG_=$1; L=$2; OUT=$3; mkdir -p "$OUT"
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
w() { "$ADB" -s "$DEV" shell sleep "$1"; }
treeaz() { # azimuth (deg right) of the tree from the last PC-PLACE line
  "$J" log PC-PLACE | tail -1 | python -c "
import re,sys,math
s=sys.stdin.read(); t=re.search(r'tree=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s); h=re.search(r'head=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s)
tx,_,tz=map(float,t.groups()); hx,_,hz=map(float,h.groups()); print(round(math.degrees(math.atan2(tx-hx,-(tz-hz))),1))"
}
three() { # $1 phase name; $2 settle seconds before the first shot
  local p=$1
  # a Stage that is not running answers no `hmd`: stop rather than shoot the launcher
  "$D/cam.sh" pose | grep -q "POSE hmd" || { echo "ABORT $p: the Stage does not answer (not in front?)"; exit 3; }
  "$D/cam_to.sh" 0 0 >/dev/null; w "${2:-15}"; "$J" shot "$p-front" "$OUT" >/dev/null
  "$D/cam_to.sh" 35 0 >/dev/null; w 6; "$J" shot "$p-side" "$OUT" >/dev/null
  if [ -n "${AZ:-}" ]; then "$D/cam_to.sh" "$(python -c "print($AZ+${AZOFF:-0})")" -25 >/dev/null; else "$D/cam_to.sh" 0 -45 >/dev/null; fi
  "$J" cmd "ray=${RAYT:-tree}"; w 6; "$J" shot "$p-ray" "$OUT" >/dev/null
  "$J" log "RAY hit" | tail -1 | sed 's/.*PruningClass: /  /'
  "$J" cmd "ray=off"; "$D/cam_to.sh" 0 0 >/dev/null
  echo "$p done"
}
"$D/cam_to.sh" 0 0 >/dev/null
"$J" cmd "lang=$LANG_"; "$J" cmd release; "$J" cmd 'state=S02'; w 8
"$J" cmd "lesson=$L"; w 30     # the real way once: the room's floor
jumpto() { # $1 state, $2 settle s: jumps start from the level, window-facing camera (a new lesson places the tree by the view)
  "$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd "state=$1:$L"; w "$2"
  local a; a=$(treeaz 2>/dev/null); [ -n "$a" ] && AZ=$a
  echo "$1 $("$J" state) tree az $AZ"
}
AZ=$(treeaz); "$J" log PC-PLACE | tail -1 | sed 's/.*PC-PLACE/PC-PLACE/'
jumpto S06 25; three "S06-pruning" 5
jumpto S07 6; three "S07-selected" 5
jumpto S09 6; AZOFF=15 three "S09-explain" 18
jumpto S12 25; three "S12-nextyear" 10
jumpto S15 6; three "S15-settings" 8
jumpto S13 25; three "S13-graduation" 8
# the picker last: the room's floor is known by now, so the ray lands a dot on it
"$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd 'state=S02'; w 8
AZ= RAYT=floor three "S02-picker" 15
"$J" cmd release; "$J" log PC-LAYOUT | tail -3 | sed 's/.*PC-LAYOUT/PC-LAYOUT/'
