#!/usr/bin/env python3
"""Draws the app icon for computers and the website: the same white ground and black spark as the Android
icon (res/drawable/ic_bg.xml and ic_fg.xml), on a rounded square. Writes desktop/icons/icon.png and icon.ico."""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = 1024  # drawn large and scaled down, for smooth edges


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def ground():
    """Plain white, like the Android icon's ground."""
    return Image.new('RGB', (S, S), (255, 255, 255))


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
    ink = (0x11, 0x11, 0x14, 255)
    d.polygon(points, fill=ink)
    cx, cy = to((73, 33))
    r = 4.5 * S / 72
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ink)
    return layer


def main():
    icon = ground().convert('RGBA')
    icon = Image.alpha_composite(icon, spark())
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * 0.225), fill=255)
    icon.putalpha(mask)
    # A hairline at the edge, so the white icon still has a shape on a white page or a light desktop.
    ImageDraw.Draw(icon).rounded_rectangle((3, 3, S - 4, S - 4), radius=int(S * 0.225) - 3, outline=(0xD9, 0xD9, 0xE0, 255), width=6)
    out = os.path.join(ROOT, 'desktop', 'icons')
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(out, 'icon.png'))
    icon.resize((256, 256), Image.LANCZOS).save(os.path.join(out, 'icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print('wrote', out)


if __name__ == '__main__':
    main()
