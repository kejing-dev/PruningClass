"""Tappable-target helpers for the emulator (QA round 4 q4-1, profile D17: edge >= 4 cm, >= 2 deg, gaps >= 1.5 cm and
>= 0.3 x the larger target).

  python tools/targets.py geom "<POSE line>" "<RINGS line>"
      one row per ring: distance from the eye, edge, angle, seen gap to the next ring; the scissors too; last line
      `GEOM ok|bad rings=N min_angle= min_gap=`
  python tools/targets.py project "<POSE line>" "<RINGS line>" [--jitter <fraction of half edge>] [--seed n] [--only k,..]
      `k px py` harness pixels (2160 px console frame) for an eye-hand pinch on every ring (and `scissors`),
      optionally off-centre by a random point inside jitter x half the edge

POSE = `tools/cam.sh pose` (`POSE hmd pos=(x, y, z) q(x, y, z, w) ...`); RINGS = the debug `rings` line
(`RINGS sel=<id> letters=<bool> edge=<m> <entry>:(x, y, z) ... [scissors:(x, y, z)]`). The console frame is the left
eye, 32 mm left of the head centre; f = 1506 px at the 2560 px frame, centre (1280, 1279).
"""
import math
import random
import re
import sys

F, CX, CY, SCALE = 1506.0, 1280.0, 1279.0, 2160.0 / 2560.0
SCISSORS_W, SCISSORS_H, SCISSORS_GAP = 1.6, 2.6, 0.8


def parse_pose(pose):
    p = list(map(float, re.search(r"pos=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", pose).groups()))
    q = tuple(map(float, re.search(r"q\(([-\d.]+), ([-\d.]+), ([-\d.]+), ([-\d.]+)\)", pose).groups()))
    return p, q


def rot(q, v):
    qx, qy, qz, qw = q
    x, y, z = v
    ix = qw * x + qy * z - qz * y
    iy = qw * y + qz * x - qx * z
    iz = qw * z + qx * y - qy * x
    iw = -qx * x - qy * y - qz * z
    return (ix * qw + iw * -qx + iy * -qz - iz * -qy, iy * qw + iw * -qy + iz * -qx - ix * -qz, iz * qw + iw * -qz + ix * -qy - iy * -qx)


def sub(a, b):
    return [a[i] - b[i] for i in range(3)]


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def norm(a):
    return math.sqrt(dot(a, a))


def parse_rings(rings):
    edge = float(re.search(r"edge=([-\d.]+)", rings).group(1))
    pts = [(k, [float(x), float(y), float(z)]) for k, x, y, z in re.findall(r"(-?\d+):\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", rings)]
    sc = re.search(r"scissors:\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", rings)
    return edge, pts, ([float(v) for v in sc.groups()] if sc else None)


def geom(pose, rings):
    p, q = parse_pose(pose)
    right = rot(q, (1, 0, 0))
    eye = [p[i] - right[i] * 0.032 for i in range(3)]
    head = p
    u, pts, sc = parse_rings(rings)
    out = []
    min_a, min_g, bad = 99.0, 99.0, 0
    for i, (k, c) in enumerate(pts):
        d = norm(sub(c, head))
        a = math.degrees(2 * math.atan(u / 2 / d))
        g = None
        if i + 1 < len(pts):
            n = pts[i + 1][1]
            mid = [(c[j] + n[j]) / 2 for j in range(3)]
            v = sub(head, mid)
            v = [x / norm(v) for x in v]
            step = sub(n, c)
            perp = [step[j] - v[j] * dot(step, v) for j in range(3)]
            g = norm(perp) - u
        ok = u >= 0.04 and a >= 2.0 and (g is None or (g >= 0.015 and g >= 0.3 * u))
        bad += 0 if ok else 1
        min_a = min(min_a, a)
        if g is not None:
            min_g = min(min_g, g)
        out.append(f"ring {k}: dist {d:.3f} m edge {u * 100:.1f} cm angle {a:.2f} deg" + (f" gap-to-next {g * 100:.2f} cm" if g is not None else "") + ("" if ok else "  BAD"))
    if sc is not None:
        d = norm(sub(sc, head))
        w = SCISSORS_W * u
        a = math.degrees(2 * math.atan(w / 2 / d))
        # gap to the ring it stands beside (the nearest)
        near = min(pts, key=lambda kc: norm(sub(kc[1], sc)))
        g = norm(sub(sc, near[1])) - u / 2 - w / 2
        ok = w >= 0.04 and a >= 2.0 and g >= 0.015 and g >= 0.3 * SCISSORS_H * u - 1e-4
        bad += 0 if ok else 1
        out.append(f"scissors: dist {d:.3f} m box {w * 100:.1f} x {SCISSORS_H * u * 100:.1f} cm angle {a:.2f} deg gap-to-ring {g * 100:.2f} cm" + ("" if ok else "  BAD"))
    out.append(f"GEOM {'ok' if bad == 0 else 'bad'} rings={len(pts)} edge={u * 100:.1f}cm min_angle={min_a:.2f} min_gap={min_g * 100:.2f}cm")
    return out


def project(pose, rings, jitter=0.0, seed=0, only=None):
    p, q = parse_pose(pose)
    fwd, right, up = rot(q, (0, 0, -1)), rot(q, (1, 0, 0)), rot(q, (0, 1, 0))
    eye = [p[i] - right[i] * 0.032 for i in range(3)]
    u, pts, sc = parse_rings(rings)
    rnd = random.Random(seed)
    targets = list(pts) + ([("scissors", sc)] if sc is not None else [])
    out = []
    for k, c in targets:
        if only is not None and str(k) not in only:
            continue
        c = list(c)
        if jitter > 0 and k != "scissors":
            # a random point inside the ring's face: square to the line of sight
            v = sub(eye, c)
            v = [x / norm(v) for x in v]
            r_ax = [right[j] - v[j] * dot(right, v) for j in range(3)]
            r_ax = [x / norm(r_ax) for x in r_ax]
            u_ax = [v[1] * r_ax[2] - v[2] * r_ax[1], v[2] * r_ax[0] - v[0] * r_ax[2], v[0] * r_ax[1] - v[1] * r_ax[0]]
            ang = rnd.uniform(0, 2 * math.pi)
            rad = math.sqrt(rnd.uniform(0, 1)) * jitter * u / 2
            c = [c[j] + (r_ax[j] * math.cos(ang) + u_ax[j] * math.sin(ang)) * rad for j in range(3)]
        d = sub(c, eye)
        zc, xc, yc = dot(d, fwd), dot(d, right), dot(d, up)
        if zc <= 0:
            continue
        out.append(f"{k} {round((CX + F * xc / zc) * SCALE)} {round((CY - F * yc / zc) * SCALE)}")
    return out


if __name__ == "__main__":
    cmd, pose, rings = sys.argv[1], sys.argv[2], sys.argv[3]
    if cmd == "geom":
        print("\n".join(geom(pose, rings)))
    else:
        args = sys.argv[4:]
        jit = float(args[args.index("--jitter") + 1]) if "--jitter" in args else 0.0
        seed = int(args[args.index("--seed") + 1]) if "--seed" in args else 0
        only = args[args.index("--only") + 1].split(",") if "--only" in args else None
        print("\n".join(project(pose, rings, jit, seed, only)))
