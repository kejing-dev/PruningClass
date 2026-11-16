#!/usr/bin/env bash
# Branch picking check (QA round 4 q4-1, design rules.judge.tapTargets 4): the crown is one target, the hit point picks;
# branches close together ask with letter rings, which must meet the D17 floor and be hit by ray and eye-hand pinch.
#   tools/crown_sweep.sh <lesson R1..A3> "<branch ids>" <out dir>
# Per branch, from S06 (nothing selected):
#   tapat  - `tapat=<id>:0.5` (a crown tap at the middle of that branch, the same code as a pinch there): TAP crown
#            near=[...] must hold the branch; one -> PC-STATE sel=<id>; more -> letter rings (S07a): geom on them,
#            `ray=letter<id>` on each, then a real eye-hand pinch on each letter (centre and off-centre): TAP letter
#            branch=<id> and PC-STATE sel=<id>; [换一根枝] (`pickanother`) and tapat again between letters;
#   pinch  - a real eye-hand pinch on the middle of the branch (projected from the left eye): TAP system ... -> TAP crown;
#            if the body hit is that branch, near must hold it.
# Writes <out>/crown-<lesson>-b<id>.txt and one line per branch.
set -u
D="$(dirname "$0")"; J="$D/pc.sh"; H="$D/harness/emu.sh"
export DEV="${DEV:-emulator-5560}" AVD="${AVD:-Pico_Emulator_6_1_fresh}" PKG=io.embodit.pruningclass TAG=PruningClass
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PY="${PY:-python}"
L=$1; BRANCHES=$2; OUT=$3; mkdir -p "$OUT"
w() { "$ADB" -s "$DEV" shell sleep "$1"; }
lastlog() { "$ADB" -s "$DEV" logcat -d -s PruningClass:V | grep -E "$1" | tail -1 | sed 's/.*PruningClass: //'; }
treeaz() { lastlog "PC-PLACE" | "$PY" -c "
import re,sys,math
s=sys.stdin.read(); t=re.search(r'tree=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s); h=re.search(r'head=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',s)
print(round(math.degrees(math.atan2(float(t.group(1))-float(h.group(1)),-(float(t.group(3))-float(h.group(3))))),1) if t and h else '')"; }
# a real eye-hand pinch; exit 3 = emulator under 50 fps, click not sent: retried up to 3 times. Sets RC and SYS (system taps)
click() {
  local n=0
  while :; do
    "$ADB" -s "$DEV" logcat -c; "$H" fast on >/dev/null 2>&1; "$H" click "$1" "$2" >/dev/null 2>&1; RC=$?; w 3
    n=$((n + 1)); [ "$RC" = 3 ] && [ "$n" -lt 3 ] && continue
    break
  done
  SYS=$("$ADB" -s "$DEV" logcat -d -s PruningClass:V | grep -cE "TAP system")
}

"$D/cam_to.sh" 0 0 >/dev/null
"$J" cmd release; "$J" cmd 'state=S02'; w 6; "$J" cmd "lesson=$L"; w 30
AZ=$(treeaz); [ -z "$AZ" ] && AZ=63
for b in $BRANCHES; do
  f="$OUT/crown-$L-b$b.txt"; : > "$f"
  "$D/cam.sh" pose | grep -q "POSE hmd" || { echo "ABORT $L branch $b: the Stage does not answer" | tee -a "$f"; exit 3; }
  "$D/cam_to.sh" 0 0 >/dev/null; "$J" cmd release; "$J" cmd "state=S06:$L"; w 12; "$J" cmd release
  "$D/cam_to.sh" "$AZ" -20 >/dev/null; w 6
  letters=0; lok=0; lbad=0; nosent=0; rayok=0; raybad=0; first="?"
  "$ADB" -s "$DEV" logcat -c; "$J" cmd "tapat=$b:0.5"; w 3
  crown=$(lastlog "TAP crown"); st=$(lastlog "PC-STATE")
  echo "tapat $b:0.5 -> $crown / $st" >> "$f"
  near=$(echo "$crown" | grep -oE "near=\[[0-9, ]*\]" | tr -d 'near=[] ')
  case ",$near," in *",$b,"*) first="ok" ;; *) first="bad (branch not offered)" ;; esac
  n=$(echo "$near" | tr ',' '\n' | grep -c .)
  if [ "$n" -gt 1 ]; then
    letters=$n
    w 2; "$ADB" -s "$DEV" logcat -c; "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=null letters=true")
    pose=$("$D/cam.sh" pose)
    echo "$rings" >> "$f"; "$PY" "$D/targets.py" geom "$pose" "$rings" >> "$f"
    "$J" shot "crown-$L-b$b-letters" "$OUT" >/dev/null
    ids=$(echo "$rings" | grep -oE " [0-9]+:\(" | tr -d ' :(')
    for id in $ids; do
      "$ADB" -s "$DEV" logcat -c; "$J" cmd "ray=letter$id"; w 2
      hit=$(lastlog "RAY hit="); echo "ray letter$id -> $hit" >> "$f"
      case "$hit" in *ladder_ring*) rayok=$((rayok + 1)) ;; *) raybad=$((raybad + 1)) ;; esac
    done
    "$J" cmd 'ray=off'; w 1
    for pass in centre off; do
      for id in $ids; do
        # letters up again (the last pinch selected a branch)
        "$J" cmd pickanother; w 2; "$J" cmd "tapat=$b:0.5"; w 3
        "$ADB" -s "$DEV" logcat -c; "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=null letters=true")
        pose=$("$D/cam.sh" pose)
        if [ "$pass" = centre ]; then pt=$("$PY" "$D/targets.py" project "$pose" "$rings" --only "$id")
        else pt=$("$PY" "$D/targets.py" project "$pose" "$rings" --jitter 0.8 --seed "$id$b" --only "$id"); fi
        read -r _ px py <<< "$pt"
        if [ -z "$px" ]; then echo "pinch $pass letter$id -> letter not shown after [换一根枝] + tapat ($rings)" >> "$f"; lbad=$((lbad + 1)); continue; fi
        click "$px" "$py"
        tap=$(lastlog "TAP letter|TAP ladder|TAP crown|TAP elsewhere|TAP system"); st=$(lastlog "PC-STATE")
        echo "pinch $pass letter$id at $px,$py -> $tap / $st [click rc=$RC system-taps=$SYS]" >> "$f"
        if [ "$RC" = 3 ] && [ "$SYS" = 0 ]; then nosent=$((nosent + 1))
        else case "$tap $st" in *"TAP letter branch=$id"*"sel=$id "*) lok=$((lok + 1)) ;; *) lbad=$((lbad + 1)) ;; esac; fi
      done
    done
  else
    case "$st" in *"sel=$b "*) ;; *) first="bad (not selected)" ;; esac
  fi
  # a real eye-hand pinch on the middle of the branch. D52: with ANOTHER branch selected first (a tap on another branch
  # picks again; a ring of the selected branch's ladder on the line wins, q3-1)
  other=$(echo "$near" | tr ',' '\n' | grep -v "^$b$" | head -1)
  "$J" cmd pickanother; w 2
  if [ -n "$other" ]; then "$J" cmd "select=$other"; w 4; fi
  "$ADB" -s "$DEV" logcat -c; "$J" cmd "tapat=$b:0.5:look"; w 2
  look=$(lastlog "CMD tapat look")
  pose=$("$D/cam.sh" pose)
  pt=$("$PY" "$D/targets.py" project "$pose" "RINGS edge=0.05 $b:$(echo "$look" | grep -oE '\([-0-9., ]+\)')")
  read -r _ px py <<< "$pt"
  click "$px" "$py"
  sys=$(lastlog "TAP system"); crown=$(lastlog "TAP crown"); st=$(lastlog "PC-STATE")
  echo "pinch branch middle at $px,$py -> $sys / $crown / $st [click rc=$RC system-taps=$SYS]" >> "$f"
  "$PY" -c "
import re,math,sys
a=re.search(r'\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', sys.argv[1]); b=re.search(r'at=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', sys.argv[2]); h=re.search(r'pos=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', sys.argv[3])
if a and b and h:
    H=[float(x) for x in h.groups()]; A=[float(x)-H[i] for i,x in enumerate(a.groups())]; B=[float(x)-H[i] for i,x in enumerate(b.groups())]
    d=sum(A[i]*B[i] for i in range(3))/math.sqrt(sum(x*x for x in A)*sum(x*x for x in B))
    print('hit point vs aim, seen from the head: %.2f deg (aim %.2f m, hit %.2f m)' % (math.degrees(math.acos(max(-1,min(1,d)))), math.sqrt(sum(x*x for x in A)), math.sqrt(sum(x*x for x in B))))
" "$look" "$crown" "$pose" >> "$f"
  hitb=$(echo "$sys" | grep -oE "target=branch:[0-9]+" | grep -oE "[0-9]+$")
  ring=$(lastlog "ring on the line"); lad=$(lastlog "TAP ladder")
  [ -n "$other" ] && echo "D52: branch $other selected first -> ${ring:+$ring / $lad / }$st" >> "$f"
  pn="ok"
  if [ -n "$ring" ]; then pn="ring of branch $other on the line (q3-1)"
  elif [ -z "$crown" ]; then pn="no crown tap ($sys)"
  elif [ "$hitb" = "$b" ]; then case "$crown" in *"near=["*"$b"*) ;; *) pn="bad (hit $b, not offered)" ;; esac
  else pn="ok (body in front: branch $hitb)"; fi
  [ -n "$other" ] && [ -z "$ring" ] && case "$st" in *"sel=$other "*) pn="$pn | D52 bad: still $other" ;; *) pn="$pn | D52 ok: picked again from $other" ;; esac
  "$J" cmd pickanother; w 1; "$J" cmd release
  echo "$L branch $b: tapat $first | letters $letters (ray ok $rayok bad $raybad, pinch ok $lok bad $lbad, harness no tap $nosent) | pinch on branch: $pn" | tee -a "$f"
done
"$D/cam_to.sh" 0 0 >/dev/null
