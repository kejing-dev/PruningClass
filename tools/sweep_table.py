"""Markdown tables for ui-audit from the v0.1.6 sweep files (ring_sweep.sh / crown_sweep.sh output).

  python tools/sweep_table.py <build/v016 dir>
"""
import glob
import os
import re
import sys

root = sys.argv[1]


def rings():
    print("| 课 / 枝 | 芽环（页） | 边长 | 最小视角 | 最小看到的间隙 | 射线 | 眼手捏合（正中 + 偏心 0.8） | 剪刀 |")
    print("|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(root, "rings", "sweep-*.txt"))):
        s = open(f, encoding="utf-8").read()
        summ = re.search(r"^(\w+) branch (\d+): rings (\d+) pages (\d+) \| geom bad (\d+) \| ray ok (\d+) bad (\d+) \| pinch ok (\d+) bad (\d+).*\| scissors (\w+)", s, re.M)
        if not summ:
            continue
        L, b, n, pages, gb, rok, rbad, pok, pbad, sc = summ.groups()
        geoms = re.findall(r"GEOM (\w+) rings=\d+ edge=([\d.]+)cm min_angle=([\d.]+) min_gap=([\d.]+)cm", s)
        edges = sorted({g[1] for g in geoms})
        ang = min(float(g[2]) for g in geoms) if geoms else 0
        gap = min(float(g[3]) for g in geoms) if geoms else 0
        sci = re.search(r"scissors: dist ([\d.]+) m box ([\d.]+) x ([\d.]+) cm angle ([\d.]+) deg gap-to-ring ([\d.]+) cm", s)
        designed = s.count("second tap on the previewed ring")
        sc_txt = sc + (f"（{sci.group(2)} × {sci.group(3)} cm，{sci.group(4)}°，离环 {sci.group(5)} cm）" if sci else "")
        p_txt = f"{pok} / 坏 {pbad}" + (f"（含 {designed} 次再点预览环 = 剪下，按设计）" if designed else "")
        print(f"| {L} {b} | {n}（{pages} 页） | {' / '.join(e + ' cm' for e in edges)} | {ang:.2f}° | {gap:.2f} cm | {rok} / 坏 {rbad} | {p_txt} | {sc_txt} |")


def crown():
    print("| 课 / 枝 | tapat 枝中段 → | 字母环 | 字母环几何 | 射线 | 捏字母 | 真捏枝中段 | 命中点离瞄准 | D52 先选别的枝再捏 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(root, "crown", "crown-*.txt"))):
        s = open(f, encoding="utf-8").read()
        summ = re.search(r"^(\w+) branch (\d+): tapat (.+?) \| letters (\d+) \(ray ok (\d+) bad (\d+), pinch ok (\d+) bad (\d+).*?\) \| pinch on branch: (.+)$", s, re.M)
        if not summ:
            continue
        L, b, first, n, rok, rbad, lok, lbad, pn = summ.groups()
        near = re.search(r"^tapat .*?near=\[([\d, ]*)\]", s, re.M)
        g = re.search(r"GEOM (\w+) rings=\d+ edge=([\d.]+)cm min_angle=([\d.]+) min_gap=([\d.]+)cm", s)
        notshown = s.count("letter not shown")
        # judged by the state after the pinch (the emulator also sends each click as a controller ray: the app ray takes
        # it and the system tap of the same press is dropped, so the last TAP line can be "TAP system dropped")
        lp = re.findall(r"pinch (?:centre|off) letter(\d+) at [\d,]+ -> .*?PC-STATE \S+ cuts=\d+ sel=(\w+)", s)
        lok = str(sum(1 for i, sel in lp if i == sel))
        lbad = str(sum(1 for i, sel in lp if i != sel) + notshown)
        real = re.search(r"pinch branch middle .*?TAP crown branch=(\d+).*?near=\[([\d, ]*)\]", s)
        hp = re.search(r"hit point vs aim, seen from the head: ([\d.]+) deg", s)
        g_txt = f"{g.group(2)} cm，{g.group(3)}°，间隙 {g.group(4)} cm，{g.group(1)}" if g else "—"
        l_txt = f"{lok} / 坏 {lbad}" + (f"（{notshown} 次是字母没出现：选中后树把那根枝转向你，再点同一点挨着的枝变了）" if notshown else "")
        r_txt = f"点中 {real.group(1)} 号枝体 → [{real.group(2)}]" if real else pn.split(" | ")[0]
        d52 = re.search(r"D52 (ok|bad)[^|]*", pn)
        print(f"| {L} {b} | [{near.group(1) if near else ''}] | {n} | {g_txt} | {rok} / 坏 {rbad} | {l_txt} | {r_txt} | {hp.group(1) + '°' if hp else '—'} | {d52.group(0) if d52 else '—'} |")


rings()
print()
crown()
