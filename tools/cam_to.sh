#!/usr/bin/env bash
# Turn the emulator camera to an absolute heading: tools/cam_to.sh <yaw right deg> <pitch up deg>
# (0 0 = level, facing where the app started). Closed loop on the app's `hmd` line: cam.sh's 0.0383 deg/px is
# 0.0537 on this emulator (2026-11-04), so each pass corrects what is left; three passes, 1 deg tolerance.
set -u
D="$(dirname "$0")"
want_y=$1; want_p=$2
for pass in 1 2 3; do
  line=$("$D/cam.sh" pose)
  read -r yaw pitch < <(python -c "
import re,math,sys
m=re.search(r'fwd=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', sys.argv[1]); x,y,z=map(float,m.groups())
print(round(math.degrees(math.atan2(x,-z)),2), round(math.degrees(math.asin(max(-1,min(1,y)))),2))" "$line")
  dy=$(python -c "print(round(($want_y)-($yaw),2))"); dp=$(python -c "print(round(($want_p)-($pitch),2))")
  echo "pass $pass yaw=$yaw pitch=$pitch -> d $dy $dp"
  python -c "import sys; sys.exit(0 if abs($dy)<1 and abs($dp)<1 else 1)" && break
  # cam.sh left <deg>: negative = right; scale by 0.0383/0.0537
  [ "$(python -c "print(abs($dy)>=1)")" = True ] && "$D/cam.sh" left "$(python -c "print(round(-($dy)*0.0383/0.0537,2))")"
  [ "$(python -c "print(abs($dp)>=1)")" = True ] && "$D/cam.sh" up "$(python -c "print(round(($dp)*0.0383/0.0537,2))")"
done
