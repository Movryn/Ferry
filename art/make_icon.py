import math

from PIL import Image, ImageDraw, ImageFilter

S = 2048
OUT = 512


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background():
    top, bottom = (31, 43, 77), (17, 94, 117)
    img = Image.new("RGB", (S, S))
    d = ImageDraw.Draw(img)
    for y in range(S):
        d.line([(0, y), (S, y)], fill=lerp(top, bottom, y / S))
    return img.convert("RGBA")


def waves(d):
    for i, (y, color, width) in enumerate([(1480, (120, 205, 225), 70), (1640, (90, 175, 205), 60), (1785, (65, 140, 180), 52)]):
        pts = []
        amp, period, shift = 46 - i * 6, 460, i * 150
        for x in range(-100, S + 101, 8):
            pts.append((x, y + amp * math.sin((x + shift) / period * 2 * math.pi)))
        r = width / 2
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            for k in range(4):
                x = x0 + (x1 - x0) * k / 4
                yy = y0 + (y1 - y0) * k / 4
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=color)


def boat(d):
    hull = [(560, 1250), (1490, 1250), (1350, 1450), (700, 1450)]
    d.polygon(hull, fill=(244, 240, 230))
    d.rectangle([700, 1300, 1350, 1325], fill=(255, 159, 67))
    d.rectangle([1005, 600, 1045, 1260], fill=(244, 240, 230))


def bubble(d):
    box = [1060, 560, 1560, 1000]
    d.rounded_rectangle(box, radius=120, fill=(255, 159, 67))
    d.polygon([(1110, 960), (1100, 1120), (1250, 990)], fill=(255, 159, 67))
    for cx in (1180, 1310, 1440):
        d.ellipse([cx - 42, 738, cx + 42, 822], fill=(31, 43, 77))


def flag(d):
    d.rounded_rectangle([640, 760, 985, 1020], radius=90, fill=(244, 240, 230))
    d.polygon([(930, 990), (990, 1110), (830, 1015)], fill=(244, 240, 230))
    for cx in (735, 812, 889):
        d.ellipse([cx - 28, 862, cx + 28, 918], fill=(17, 94, 117))


img = background()
layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(layer)
bubble(d)
flag(d)
boat(d)
waves(d)
shadow = layer.split()[3].filter(ImageFilter.GaussianBlur(24)).point(lambda a: int(a * 0.35))
shade = Image.new("RGBA", (S, S), (0, 0, 0, 255))
shade.putalpha(shadow)
img.alpha_composite(shade, (0, 18))
img.alpha_composite(layer)
img.resize((OUT, OUT), Image.LANCZOS).save("icon.png")
print("saved")
