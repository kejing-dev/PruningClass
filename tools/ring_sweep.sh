#!/usr/bin/env bash
# Bud-ring check (QA round 3 q3-1 + round 4 q4-1, profile D17): with a branch selected, every ring of its bud ladder
# (every page) is big enough (>= 4 cm, >= 2 deg from the eye, seen gaps >= 1.5 cm and >= 0.3 x edge), is hit by the
# controller ray AND by an eye-hand pinch (dead centre and off-centre), the selection never jumps, and the scissors
# confirm the cut.
#   tools/ring_sweep.sh <lesson R1..A3> "<branch ids>" <out dir>
# Per branch: the lesson is entered once the real way (room floor), the branch selected (debug `select=`, the same
# event as a letter / single-branch tap), the camera turned to the ladder; then per page
#   geom  - tools/targets.py geom on the `rings` line and the head pose: distance, edge, angle, seen gap of every ring;
#   ray   - `ray=ring<k>` for every bud k (stand-in controller ray, same hit code as the real one): RAY hit must be a ring;
#   pinch - every ring projected to screen pixels from the left eye and pinched with the emulator's gaze (harness click =
#           a real system tap), once at the centre and once at a random point inside 0.8 of the half edge: the TAP must
#           be "TAP ladder bud=<k>" and PC-STATE sel unchanged;
#   more  - `ray=more` + `ray=tap` turns the page while there is one.
# Then the scissors: a pinch on the scissors ghost of the shown preview must cut (CUT branch=<id>), `undo` puts it back.
# Prints one line per branch and writes <out>/sweep-<lesson>-b<id>.txt + a screenshot per page.
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
pinch() { # $1 = expected entry, $2 px, $3 py -> echoes every TAP line, the state, and the harness result
  # (exit 3 = emulator under 50 fps, click not sent: retried up to 3 times; every attempt is logged)
  local tap st sys rc n=0
  while :; do
    "$ADB" -s "$DEV" logcat -c
    "$H" fast on >/dev/null 2>&1
    "$H" click "$2" "$3" >/dev/null 2>&1; rc=$?; w 3
    n=$((n + 1)); [ "$rc" = 3 ] && [ "$n" -lt 3 ] && continue
    break
  done
  sys=$("$ADB" -s "$DEV" logcat -d -s PruningClass:V | grep -cE "TAP system")
  tap=$("$ADB" -s "$DEV" logcat -d -s PruningClass:V | grep -E "TAP ladder|TAP letter|TAP crown|TAP preview|TAP elsewhere" | sed 's/.*PruningClass: //' | paste -sd'|')
  st=$(lastlog "PC-STATE")
  echo "$tap / $st [click rc=$rc tries=$n system-taps=$sys]"
}

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
  rings_total=0; rayok=0; raybad=0; pinok=0; pinbad=0; nosent=0; double=0; geombad=0; page=1; done_buds=" "; last=""
  while :; do
    "$ADB" -s "$DEV" logcat -c
    "$J" cmd rings; w 2
    rings=$(lastlog "RINGS sel=$b ")
    [ -z "$rings" ] && { echo "ABORT $L branch $b: no ladder for this branch" | tee -a "$f"; break; }
    pose=$("$D/cam.sh" pose)
    echo "page $page: $rings" >> "$f"
    echo "pose: $pose" >> "$f"
    "$PY" "$D/targets.py" geom "$pose" "$rings" >> "$f"
    tail -1 "$f" | grep -q "GEOM ok" || geombad=$((geombad + 1))
    # pages overlap by one ring: a ring already checked on the page before is not pinched again (a second pinch on the
    # previewed ring would cut)
    buds=$(for k in $(echo "$rings" | grep -oE " -?[0-9]+:\(" | tr -d ' :(' | grep -vE "^-"); do case "$done_buds" in *" $k "*) ;; *) echo "$k" ;; esac; done)
    done_buds="$done_buds$(echo $buds) "
    rings_total=$((rings_total + $(echo "$buds" | wc -w)))
    for k in $buds; do
      "$ADB" -s "$DEV" logcat -c; "$J" cmd "ray=ring$k"; w 2
      hit=$(lastlog "RAY hit=")
      echo "ray ring$k -> $hit" >> "$f"
      case "$hit" in *ladder_ring*) rayok=$((rayok + 1)) ;; *) raybad=$((raybad + 1)) ;; esac
    done
    "$J" cmd 'ray=off'; w 1
    "$J" shot "sweep-$L-b$b-p$page" "$OUT" >/dev/null
    # eye-hand pinch on every ring: centre, then off-centre (0.8 of the half edge); neighbours alternate, so no ring is
    # pinched twice in a row (a second pinch on the previewed ring would cut)
    for pass in centre off; do
      pose=$("$D/cam.sh" pose)
      "$ADB" -s "$DEV" logcat -c; "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=$b ")
      if [ "$pass" = centre ]; then pts=$("$PY" "$D/targets.py" project "$pose" "$rings" --only "$(echo $buds | tr ' ' ',')")
      else pts=$("$PY" "$D/targets.py" project "$pose" "$rings" --jitter 0.8 --seed "$page$b" --only "$(echo $buds | tr ' ' ',')"); fi
      # the loop reads fd 3: adb and the harness inside it would otherwise swallow the rest of the list from stdin
      while read -r k px py <&3; do
        [ -z "$k" ] && continue
        r=$(pinch "$k" "$px" "$py")
        echo "pinch $pass ring$k at $px,$py -> $r" >> "$f"
        case "$r" in
          *"system-taps=1]"*)
            if [ "$k" = "$last" ]; then
              # the ring just pinched is the previewed one (a page with one new ring): a second tap cuts, by design (S08)
              case "$r" in "TAP ladder bud=$k branch=$b / "*"cuts=1"*) pinok=$((pinok + 1)); echo "  (second tap on the previewed ring: cut, as designed)" >> "$f" ;; *) pinbad=$((pinbad + 1)) ;; esac
            else
              case "$r" in "TAP ladder bud=$k branch=$b / "*"sel=$b "*) pinok=$((pinok + 1)) ;; *) pinbad=$((pinbad + 1)) ;; esac
            fi ;;
          *"rc=3 "*"system-taps=0]"*) nosent=$((nosent + 1)) ;;
          *"system-taps=0]"*) pinbad=$((pinbad + 1)) ;;   # sent, but nothing hit: a real miss
          *) double=$((double + 1)) ;;
        esac
        last=$k
        case "$r" in *"cuts=1"*|*"CUT "*) "$J" cmd undo; w 2; "$J" cmd "select=$b"; w 3; last="" ;; esac
      done 3<<< "$pts"
    done
    echo "$rings" | grep -q -- " -3:(" || break
    "$J" cmd 'ray=more'; w 2; "$J" cmd 'ray=tap'; w 3; "$J" cmd 'ray=off'; w 1
    page=$((page + 1))
  done
  # the scissors confirm the previewed cut
  "$ADB" -s "$DEV" logcat -c; "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=$b ")
  if ! echo "$rings" | grep -q "scissors:"; then
    # no preview shown (the last pinch cut and was undone): preview the top ring with the ray, then pinch the scissors
    top=$(echo "$rings" | grep -oE " [0-9]+:\(" | tr -d ' :(' | tail -1)
    "$J" cmd "ray=ring$top"; w 2; "$J" cmd 'ray=tap'; w 3; "$J" cmd 'ray=off'; w 1
    "$ADB" -s "$DEV" logcat -c; "$J" cmd rings; w 2; rings=$(lastlog "RINGS sel=$b ")
  fi
  pose=$("$D/cam.sh" pose)
  sc=$("$PY" "$D/targets.py" project "$pose" "$rings" --only scissors)
  scis="none"
  if [ -n "$sc" ]; then
    "$PY" "$D/targets.py" geom "$pose" "$rings" | grep scissors >> "$f"
    read -r _ px py <<< "$sc"
    r=$(pinch scissors "$px" "$py")
    cut=$(lastlog "CUT branch=$b")
    echo "pinch scissors at $px,$py -> $r / $cut" >> "$f"
    case "$cut" in *"CUT branch=$b"*) scis="ok" ;; *) scis="bad" ;; esac
    "$J" cmd undo; w 2
  fi
  "$J" cmd release
  echo "$L branch $b: rings $rings_total pages $page | geom bad $geombad | ray ok $rayok bad $raybad | pinch ok $pinok bad $pinbad (harness: no tap $nosent, two taps $double) | scissors $scis" | tee -a "$f"
done
"$D/cam_to.sh" 0 0 >/dev/null
