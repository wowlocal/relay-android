"""Relay launcher / notification / splash icons from the iOS Relay.icon artwork.

  python3 -m venv /tmp/v && /tmp/v/bin/pip install pillow
  /tmp/v/bin/python Tools/relay-icons/make_icons.py \
      ~/Developer/relay-calls-integration/ios-device/Telegram/Telegram-iOS/Relay.icon/Assets/Relay.png \
      TMessagesProj/src/main/res

Writes mipmap-*/relay_icon_background.png + relay_icon_monochrome.png (adaptive icon layers,
used by mipmap-anydpi-v26/ic_launcher*.xml), mipmap-*/ic_launcher*.png (legacy),
drawable-*/notification.webp (status-bar icon) and drawable-nodpi/relay_splash.png (Android 12
splash). The ball position (536, 505, r 72) is specific to the 1024 px artwork.
"""
import sys, os
from PIL import Image, ImageDraw, ImageFilter, ImageChops

src_path, res = sys.argv[1], sys.argv[2]
SRC = Image.open(src_path).convert("RGB")  # 1024x1024, full-bleed photo
N = SRC.size[0]

DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

def feathered(img, feather):
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    d.rectangle([feather, feather, w - feather, h - feather], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    return mask

def adaptive_full(size):
    """108dp layer: the photo scaled to PHOTO_DP, blended into a blurred, enlarged copy of itself."""
    PHOTO_DP = 76.0
    # background: a smooth gradient of the photo's own edge colours (corners and edge midpoints)
    k = N // 16
    def avg(x, y):
        return SRC.crop((x, y, x + k, y + k)).resize((1, 1), Image.BOX).getpixel((0, 0))
    grid = Image.new("RGB", (3, 3))
    for gy, y in enumerate((0, (N - k) // 2, N - k)):
        for gx, x in enumerate((0, (N - k) // 2, N - k)):
            grid.putpixel((gx, gy), avg(x, y))
    canvas = grid.resize((size, size), Image.BICUBIC)
    ps = int(round(size * PHOTO_DP / 108.0))
    photo = SRC.resize((ps, ps), Image.LANCZOS)
    mask = feathered(photo, max(2, ps // 14))
    off = (size - ps) // 2
    canvas.paste(photo, (off, off), mask)
    return canvas, PHOTO_DP

def silhouette(threshold=150):
    """Alpha mask of the cream paper shape and the ball (anything much less red than the background)."""
    r, g, b = SRC.split()
    # background is saturated red (r - g large); the paper is cream, the ball orange
    redness = ImageChops.subtract(r, g)
    m = redness.point(lambda v: 255 if v < 110 else 0).filter(ImageFilter.MedianFilter(7))
    # fill holes: flood the background from the corners, everything else is the shape
    work = m.copy()
    for corner in ((0, 0), (N - 1, 0), (0, N - 1), (N - 1, N - 1)):
        if work.getpixel(corner) == 0:
            ImageDraw.floodfill(work, corner, 128)
    m = work.point(lambda v: 0 if v == 128 else 255).filter(ImageFilter.MedianFilter(9))
    # the ball inside the cup: a ring cut around it keeps it readable at 24dp
    d = ImageDraw.Draw(m)
    cx, cy, rb = 536, 505, 72
    d.ellipse([cx - rb - 26, cy - rb - 26, cx + rb + 26, cy + rb + 26], fill=0)
    d.ellipse([cx - rb, cy - rb, cx + rb, cy + rb], fill=255)
    return m

def save(img, path, fmt=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if fmt == "webp":
        img.save(path, "WEBP", lossless=True)
    else:
        img.save(path, "PNG", optimize=True)

def rounded_mask(size, radius_frac):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * radius_frac), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def circle_mask(size):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)

sil = silhouette()

for dens, f in DENS.items():
    # adaptive background layer (108dp)
    s108 = int(round(108 * f))
    bg, photo_dp = adaptive_full(s108)
    save(bg, f"{res}/mipmap-{dens}/relay_icon_background.png")
    # monochrome layer: silhouette placed like the photo
    ps = int(round(s108 * photo_dp / 108.0))
    mono = Image.new("RGBA", (s108, s108), (0, 0, 0, 0))
    a = sil.resize((ps, ps), Image.LANCZOS)
    white = Image.new("RGBA", (ps, ps), (255, 255, 255, 255))
    off = (s108 - ps) // 2
    mono.paste(white, (off, off), a)
    save(mono, f"{res}/mipmap-{dens}/relay_icon_monochrome.png")
    # legacy launcher icons (48dp): full photo with 1dp-ish inset, rounded square and circle
    s48 = int(round(48 * f))
    inner = int(round(s48 * 44 / 48.0))
    icon = SRC.resize((inner, inner), Image.LANCZOS)
    sq = Image.new("RGBA", (s48, s48), (0, 0, 0, 0))
    sq.paste(icon, ((s48 - inner) // 2, (s48 - inner) // 2), rounded_mask(inner, 0.22))
    save(sq, f"{res}/mipmap-{dens}/ic_launcher.png")
    rd = Image.new("RGBA", (s48, s48), (0, 0, 0, 0))
    rd.paste(icon, ((s48 - inner) // 2, (s48 - inner) // 2), circle_mask(inner))
    save(rd, f"{res}/mipmap-{dens}/ic_launcher_round.png")
    # notification small icon (24dp, white on transparent): silhouette cropped to its bbox
    if dens != "xxxhdpi":
        s24 = int(round(24 * f))
        bbox = sil.getbbox()
        crop = sil.crop(bbox)
        cw, ch = crop.size
        side = max(cw, ch)
        sq_m = Image.new("L", (side, side), 0)
        sq_m.paste(crop, ((side - cw) // 2, (side - ch) // 2))
        inner = int(round(s24 * 22 / 24.0))
        m = sq_m.resize((inner, inner), Image.LANCZOS)
        n = Image.new("RGBA", (s24, s24), (255, 255, 255, 0))
        n.paste(Image.new("RGBA", (inner, inner), (255, 255, 255, 255)), ((s24 - inner) // 2, (s24 - inner) // 2), m)
        save(n, f"{res}/drawable-{dens}/notification.webp", "webp")

# splash icon (Android 12+): icon of 240dp with a 160dp visible circle; nodpi 768px
S = 768
inner = int(S * 160 / 240)
icon = SRC.resize((inner, inner), Image.LANCZOS)
sp = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sp.paste(icon, ((S - inner) // 2, (S - inner) // 2), circle_mask(inner))
save(sp, f"{res}/drawable-nodpi/relay_splash.png")
print("ok", sil.getbbox())
