#!/usr/bin/env bash
# QA round 3 q3-1 check (profile D17): with a branch selected, every ring of its bud ladder is hit by the controller
# ray AND by an eye-hand pinch, and the selection never jumps to another branch.
#   tools/ring_sweep.sh <lesson R1..A3> "<branch ids>" <out dir>
# Per branch: the lesson is entered once the real way (room floor), the branch selected (debug `select=`, the same
# event as a tap), the camera turned to the ladder; then
#   ray   - `ray=ring<k>` for every bud k (stand-in controller ray, same hit code as the real one): RAY hit must be a
#           ladder ring; one `ray=tap` must give TAP ladder;
#   pinch - every ring projected to screen pixels from the head pose (`hmd`) and pinched with the emulator's gaze
#           (harness click = a real system tap): the TAP must be "TAP ladder bud=<k>" and PC-STATE sel unchanged.
# Prints one line per branch and writes <out>/sweep-<lesson>-b<id>.txt + a screenshot.
set -u
D="$(dirname "$0")"; J="$D/pc.sh"; H="$D/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}" PKG=io.embodit.pruningclass TAG=PruningClass
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
L=$1; BRANCHES=$2; OUT=$3; mkdir -p "$OUT"
w() { "$ADB" -s "$DEV" shell sleep "$1"; }
lastlog() { "$ADB" -s "$DEV" logcat -d -s PruningClass:V | grep -E "$1" | tail -1 | sed 's/.*PruningClass: //'; }
treeaz() { lastlog "PC-PLACE" | python -c "
import re,sys,math
s=sys.stdin.read(); t=re.search(r'tree=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s); h=re.search(r'head=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s)
print(round(math.degrees(math.atan2(float(t.group(1))-float(h.group(1)),-(float(t.group(3))-float(h.group(3))))),1) if t and h else '')"; }

"$D/cam_to.sh" 0 0 >/dev/null
"$J" cmd release; "$J" cmd 'state=S02'; w 6; "$J" cmd "lesson=$L"; w 30
for b in $BRANCHES; do
  f="$OUT/sweep-$L-b$b.txt"; : > "$f"
  "$D/cam.sh" pose | grep -q "POSE hmd" || { echo "ABORT $L branch $b: the Stage does not answer (crashed / not in front)" | tee -a "$f"; exit 3; }
  "$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd "state=S06:$L"; w 15
  "$J" cmd release; "$J" cmd "select=$b"; w 6
  AZ=$(treeaz); [ -z "$AZ" ] && AZ=63
  # look at the ladder: it stands 16 cm in front of the branch's middle
  "$D/cam_to.sh" "$AZ" -18 >/dev/null; w 8
  "$ADB" -s "$DEV" logcat -c
  "$J" cmd rings; w 2
  rings=$(lastlog "RINGS sel=$b ")
  [ -z "$rings" ] && { echo "ABORT $L branch $b: no ladder for this branch" | tee -a "$f"; continue; }
  echo "$rings" >> "$f"
  buds=$(echo "$rings" | grep -oE " -?[0-9]+:\(" | tr -d ' :(' | grep -vE "^-")
  rayok=0; raybad=0
  for k in $buds; do
    "$ADB" -s "$DEV" logcat -c; "$J" cmd "ray=ring$k"; w 2
    hit=$(lastlog "RAY hit=")
    echo "ray ring$k -> $hit" >> "$f"
    case "$hit" in *ladder_ring*) rayok=$((rayok + 1)) ;; *) raybad=$((raybad + 1)) ;; esac
  done
  first=$(echo "$buds" | head -1)
  "$J" cmd "ray=ring$first"; w 2; "$J" cmd 'ray=tap'; w 2
  echo "ray tap ring$first -> $(lastlog 'TAP ladder|TAP ray') / $(lastlog 'PC-STATE')" >> "$f"
  "$J" shot "sweep-$L-b$b" "$OUT" >/dev/null
  "$J" cmd 'ray=off'; "$J" cmd release; "$J" cmd "state=S06:$L"; w 6; "$J" cmd release; "$J" cmd "select=$b"; w 6
  # eye-hand pinch on every ring, projected from the left eye (f = 1506 px at the 2560 px frame, centre 1280 / 1279)
  pose=$("$D/cam.sh" pose)
  "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=$b ")
  pts=$(python - "$pose" "$rings" <<'PY'
import re, sys, math
pose, rings = sys.argv[1], sys.argv[2]
p = list(map(float, re.search(r'pos=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', pose).groups()))
qx, qy, qz, qw = map(float, re.search(r'q\(([-\d.]+), ([-\d.]+), ([-\d.]+), ([-\d.]+)\)', pose).groups())
def rot(v):
    x, y, z = v
    ix = qw * x + qy * z - qz * y; iy = qw * y + qz * x - qx * z; iz = qw * z + qx * y - qy * x; iw = -qx * x - qy * y - qz * z
    return (ix * qw + iw * -qx + iy * -qz - iz * -qy, iy * qw + iw * -qy + iz * -qx - ix * -qz, iz * qw + iw * -qz + ix * -qy - iy * -qx)
fwd, right, up = rot((0, 0, -1)), rot((1, 0, 0)), rot((0, 1, 0))
eye = [p[i] - right[i] * 0.032 for i in range(3)]  # the console frame is the left eye (32 mm off the head centre)
F, CX, CY, SCALE = 1506.0, 1280.0, 1279.0, 2160.0 / 2560.0  # harness pixels are the 2160 px console frame
for k, x, y, z in re.findall(r'(-?\d+):\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', rings):
    d = (float(x) - eye[0], float(y) - eye[1], float(z) - eye[2])
    zc = sum(a * b for a, b in zip(d, fwd)); xc = sum(a * b for a, b in zip(d, right)); yc = sum(a * b for a, b in zip(d, up))
    if int(k) < 0 or zc <= 0: continue
    print(k, round((CX + F * xc / zc) * SCALE), round((CY - F * yc / zc) * SCALE))
PY
)
  pinok=0; pinbad=0
  # the loop reads fd 3: adb and the harness inside it would otherwise swallow the rest of the list from stdin
  while read -r k px py <&3; do
    [ -z "$k" ] && continue
    "$ADB" -s "$DEV" logcat -c
    "$H" fast on >/dev/null 2>&1
    "$H" click "$px" "$py" >/dev/null 2>&1; w 3
    tap=$(lastlog "TAP ladder|TAP branch|TAP elsewhere|TAP system")
    st=$(lastlog "PC-STATE")
    echo "pinch ring$k at $px,$py -> $tap / $st" >> "$f"
    case "$tap $st" in *"TAP ladder bud=$k "*"sel=$b "*) pinok=$((pinok + 1)) ;; *) pinbad=$((pinbad + 1)) ;; esac
  done 3<<< "$pts"
  "$J" cmd release
  echo "$L branch $b: rings $(echo "$buds" | wc -w) | ray ok $rayok bad $raybad | pinch ok $pinok bad $pinbad" | tee -a "$f"
done
"$D/cam_to.sh" 0 0 >/dev/null
