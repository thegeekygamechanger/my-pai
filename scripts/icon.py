#!/usr/bin/env python3
"""Draws the app icon for computers and the website: the same gradient ground and white spark as the Android
icon (res/drawable/ic_bg.xml and ic_fg.xml), on a rounded square. Writes desktop/icons/icon.png and icon.ico."""
import math, os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 1024  # drawn large and scaled down, for smooth edges


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def ground():
    stops = [(0.0, (0x3B, 0x2F, 0xD9)), (0.52, (0x9A, 0x45, 0xE8)), (1.0, (0xFF, 0x7E, 0x79))]
    img = Image.new('RGB', (S, S))
    px = img.load()
    # The Android icon shows the middle two thirds of a 108-unit square; the same part is drawn here.
    unit = 72 / S
    ax, ay, bx, by = 8, 4, 100, 104
    dx, dy = bx - ax, by - ay
    length2 = dx * dx + dy * dy
    for y in range(S):
        for x in range(S):
            u, v = 18 + x * unit, 18 + y * unit
            t = min(1.0, max(0.0, ((u - ax) * dx + (v - ay) * dy) / length2))
            for i in range(len(stops) - 1):
                if t <= stops[i + 1][0]:
                    c = lerp(stops[i][1], stops[i + 1][1], (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0]))
                    break
            # warm glow low on the right, soft light high on the left
            g = max(0.0, 1 - math.hypot(u - 86, v - 90) / 50) * 0.55
            c = lerp(c, (0xFF, 0xC5, 0x6B), g)
            w = max(0.0, 1 - math.hypot(u - 28, v - 24) / 46) * 0.20
            c = lerp(c, (255, 255, 255), w)
            px[x, y] = tuple(int(round(k)) for k in c)
    return img


def bezier(p0, p1, p2, p3, n=40):
    out = []
    for i in range(n + 1):
        t = i / n
        m = 1 - t
        out.append((m ** 3 * p0[0] + 3 * m * m * t * p1[0] + 3 * m * t * t * p2[0] + t ** 3 * p3[0],
                    m ** 3 * p0[1] + 3 * m * m * t * p1[1] + 3 * m * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def spark():
    to = lambda p: ((p[0] - 18) * S / 72, (p[1] - 18) * S / 72)
    curves = [((54, 30), (56, 44), (62, 50), (76, 54)), ((76, 54), (62, 58), (56, 64), (54, 78)),
              ((54, 78), (52, 64), (46, 58), (32, 54)), ((32, 54), (46, 50), (52, 44), (54, 30))]
    points = [to(p) for c in curves for p in bezier(*c)]
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.polygon(points, fill=(255, 255, 255, 255))
    cx, cy = to((73, 33))
    r = 4.5 * S / 72
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(0xFF, 0xE9, 0xDC, 255))
    # a faint shadow under the spark lifts it off the colour
    shadow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x, y + S * 0.012) for x, y in points], fill=(40, 10, 90, 90))
    return Image.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(S * 0.02)), layer)


def main():
    icon = ground().convert('RGBA')
    icon = Image.alpha_composite(icon, spark())
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * 0.225), fill=255)
    icon.putalpha(mask)
    out = os.path.join(ROOT, 'desktop', 'icons')
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(out, 'icon.png'))
    icon.resize((256, 256), Image.LANCZOS).save(os.path.join(out, 'icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print('wrote', out)


if __name__ == '__main__':
    main()
