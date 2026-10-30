"""Measured contrast on a console screenshot (ui-audit.md): text core = the darkest (or brightest) 2 % of the pixels
in a box, background = the median of the box; WCAG relative luminance. Anti-aliased edges fall between the two and
do not count.

    python tools/contrast.py SHOT.png x0 y0 x1 y1 [light]     light = the text is lighter than its background
"""
import sys

from PIL import Image


def lum(rgb):
    def ch(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb[:3]
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def measure(path, box, light=False):
    px = sorted(Image.open(path).convert("RGB").crop(box).getdata(), key=lum)
    bg = px[len(px) // 2]
    k = max(1, len(px) // 50)
    core = px[-k:] if light else px[:k]
    fg = tuple(sum(p[i] for p in core) // len(core) for i in range(3))
    a, b = lum(fg), lum(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05), fg, bg


if __name__ == "__main__":
    path = sys.argv[1]
    box = tuple(int(v) for v in sys.argv[2:6])
    ratio, fg, bg = measure(path, box, light=len(sys.argv) > 6)
    print(f"{ratio:.1f} : 1  text #{fg[0]:02X}{fg[1]:02X}{fg[2]:02X}  background #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}")
