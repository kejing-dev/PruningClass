"""Blank-space meter for the main window (factory D44: blank outside the content <= 25 % of the window height,
no internal blank band > 20 %). Usage: python tools/blank_meter.py <png> [<png> ...]

For tools/shots_panel.sh frames: emulator-5560 HD (2560 px), camera level and facing the window, which then spans
x 840 - 1761, y 669 - 1866 (measured on the QA round-2 frames; the window always opens there). A row counts as
content when it has sharp edges (text, icons, control outlines: > 28 grey levels between neighbours, >= 3 times);
the blurred room behind the glass has none. Card and button interiors without text count as blank, so this reads a
little stricter than measuring by eye (QA round 2: S02 45 % by eye, 48 % here; S05 inner 69 / 70 %).
Prints per image: blank above + below the content (% of the window height) and the largest blank band inside it.
"""
import sys
from PIL import Image
import numpy as np

L, R, T, B = 840, 1761, 669, 1866
INSET = 42  # the window's corner radius / padding


def measure(path):
    im = np.asarray(Image.open(path).convert("L"), dtype=np.float32)
    region = im[T + 8:B - 8, L + INSET:R - INSET]
    dx = np.abs(np.diff(region, axis=1))
    rows = np.where((dx > 28).sum(axis=1) >= 3)[0]
    H = B - T
    if len(rows) == 0:
        return 100.0, 100.0
    top = rows[0] + 8
    bottom = H - (rows[-1] + 8)
    inner = (np.diff(rows).max() - 1) if len(rows) > 1 else 0
    return 100.0 * (top + bottom) / H, 100.0 * inner / H


if __name__ == "__main__":
    bad = 0
    for p in sys.argv[1:]:
        outer, inner = measure(p)
        ok = outer <= 25 and inner <= 20
        bad += 0 if ok else 1
        print(f"{'OK' if ok else 'OVER':4} outer {outer:5.1f}%  inner {inner:5.1f}%  {p.replace(chr(92), '/').split('/')[-1]}")
    sys.exit(1 if bad else 0)
