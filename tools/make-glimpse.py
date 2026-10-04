#!/usr/bin/env python
"""
Compose the README's banner out of the screenshots in docs/screenshots.

## Why this is a tool and not a one-off

The glimpse strip went stale and stayed stale for several rounds. `check-glimpse` reported it
every time and it was skipped every time, because fixing it meant re-shooting the screens AND
then rebuilding a hand-assembled image - and the second half is the part that quietly made the
first half not worth starting. A strip nobody can rebuild in one command is a strip that goes
stale again the week after it is made.

So: re-shoot into `docs/screenshots/`, run this, commit. `check-glimpse` refuses a banner that
is older than a screenshot it was built from, so the second step cannot be forgotten.

    python tools/make-glimpse.py

## What it draws

A wide hero in the app's own dark palette: the mark and the promise on the left, five phone
frames arced across the right. Straight frames rather than rotated ones - a tilted screenshot
reads as a stock template, and the content is the thing being sold.

Everything is derived from `SHOTS` below. Adding a screen is one line there.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS_DIR = os.path.join(ROOT, "docs", "screenshots")
ICON = os.path.join(ROOT, "docs", "icon.png")
OUT = os.path.join(ROOT, "docs", "glimpse.png")

# The app's own dark palette, from ui/theme/Slate.kt. Kept in sync by hand and asserted
# against that file below, because a banner in the wrong colours is worse than no banner.
BG = (0x16, 0x16, 0x14)
SURFACE = (0x1C, 0x1C, 0x1A)
LINE = (0x2E, 0x2E, 0x2A)
FG = (0xE8, 0xE6, 0xE1)
FG2 = (0x9A, 0x96, 0x8D)
FG3 = (0x6B, 0x68, 0x60)
ACCENT = (0x7F, 0xA8, 0xB8)
ACCENT_DIM = (0x31, 0x43, 0x4B)

W, H = 2560, 1080

#: The five screens the banner shows, in the order they are arced.
SHOTS = [
    "02-browse.png",
    "05-context-menu.png",
    "01-home.png",
    "06-extract.png",
    "09-remotes.png",
]

CHIPS = ["Search", "Archives", "APKs", "Lua", "WebDAV"]

TAGLINE = [
    "A file manager for doing real",
    "work on your phone, without",
    "reaching for a PC.",
]

SUB = [
    "Whole-device search in milliseconds.",
    "Edit files inside archives. Serve the lot",
    "to any browser on your network.",
]

FONTS = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Fonts"
)


def font(name, size, weight=None):
    """
    Load a font, setting a variable axis where the file has one.

    Fraunces and Inter both ship as variable fonts here, and a variable font's default
    instance is whatever the designer set - for Inter that is Thin, which at heading size
    looks like a rendering fault rather than a choice.
    """
    for folder in (FONTS, r"C:\Windows\Fonts"):
        path = os.path.join(folder, name)
        if os.path.exists(path):
            f = ImageFont.truetype(path, size)
            if weight is not None:
                try:
                    f.set_variation_by_axes([weight])
                except Exception:
                    pass
            return f
    return ImageFont.load_default(size)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=255)
    return m


def shadow(size, radius, blur, spread):
    """A soft drop shadow for a rounded rectangle, as its own RGBA layer."""
    pad = blur * 3
    layer = Image.new("RGBA", (size[0] + pad * 2, size[1] + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(
        [pad - spread, pad - spread, pad + size[0] + spread, pad + size[1] + spread],
        radius + spread,
        fill=(0, 0, 0, 165),
    )
    return layer.filter(ImageFilter.GaussianBlur(blur)), pad


def phone(shot_path, height):
    """
    One screenshot in a device frame.

    The frame is a hairline rather than a drawn handset: a fake bezel with a fake notch dates
    the image to whatever phone was current, and the screenshot is what anybody is looking at.
    """
    shot = Image.open(shot_path).convert("RGB")
    bezel = max(8, height // 90)
    inner_h = height - bezel * 2
    inner_w = round(shot.width * inner_h / shot.height)
    shot = shot.resize((inner_w, inner_h), Image.LANCZOS)
    w, h = inner_w + bezel * 2, height
    radius = round(height * 0.055)

    frame = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(frame)
    draw.rounded_rectangle([0, 0, w - 1, h - 1], radius, fill=SURFACE + (255,), outline=LINE + (255,), width=2)
    inner_mask = rounded_mask((inner_w, inner_h), max(2, radius - bezel))
    frame.paste(shot, (bezel, bezel), inner_mask)
    # A hairline of accent just inside the bezel, which is what stops the screenshot from
    # looking pasted onto a grey rectangle.
    draw.rounded_rectangle(
        [bezel - 1, bezel - 1, w - bezel, h - bezel],
        max(2, radius - bezel) + 1,
        outline=ACCENT_DIM + (170,),
        width=2,
    )
    # A light rim on the outside. Without it a near-black frame on a near-black ground has no
    # edge at all, and five of them overlapping read as one dark smear.
    draw.rounded_rectangle([0, 0, w - 1, h - 1], radius, outline=(0x4A, 0x47, 0x42, 255), width=2)
    return frame


def recede(frame, amount):
    """
    Push a frame back by dimming it toward the ground colour.

    Overlapping frames at identical brightness have no depth order, so the eye reads the pile
    as flat clutter rather than as a fan with a front.
    """
    if amount <= 0:
        return frame
    wash = Image.new("RGBA", frame.size, BG + (round(255 * amount),))
    out = Image.alpha_composite(frame, wash)
    out.putalpha(frame.getchannel("A"))
    return out


def chip(text, f):
    pad_x, pad_y = 20, 11
    w = round(f.getlength(text)) + pad_x * 2
    h = f.size + pad_y * 2
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], h // 2, fill=ACCENT_DIM + (120,), outline=ACCENT_DIM + (255,), width=2)
    d.text((pad_x, pad_y - 2), text, font=f, fill=ACCENT)
    return img


def glow(size, centre, radius, colour, strength):
    """A soft radial wash, built by blurring a filled circle."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse(
        [centre[0] - radius, centre[1] - radius, centre[0] + radius, centre[1] + radius],
        fill=colour + (strength,),
    )
    return layer.filter(ImageFilter.GaussianBlur(radius // 2))


def check_palette():
    """
    Assert the colours here are still the app's.

    The banner is a claim about what the app looks like, so it is the same kind of claim as
    anything else in the README and gets the same treatment: checkable, or it drifts.
    """
    slate = os.path.join(
        ROOT, "app", "src", "main", "java", "dev", "niccc2007", "filet", "ui", "theme", "Slate.kt"
    )
    if not os.path.exists(slate):
        return
    src = open(slate, encoding="utf-8").read()
    for name, rgb in (("BgD", BG), ("SurfaceD", SURFACE), ("LineD", LINE),
                      ("FgD", FG), ("AccentD", ACCENT), ("AccentDimD", ACCENT_DIM)):
        want = "val %s = Color(0xFF%02X%02X%02X)" % (name, rgb[0], rgb[1], rgb[2])
        if want not in src:
            sys.exit(
                "The banner's palette has drifted from the app's.\n"
                "  Slate.kt no longer contains: %s\n"
                "  Update the constants at the top of this file to match." % want
            )


def main():
    check_palette()
    missing = [s for s in SHOTS if not os.path.exists(os.path.join(SHOTS_DIR, s))]
    if missing:
        sys.exit("missing screenshots: " + ", ".join(missing))

    img = Image.new("RGB", (W, H), BG)

    # Two washes: one warm low-left so the ground is not flat, one accent behind the phones.
    img.paste(
        Image.alpha_composite(img.convert("RGBA"), glow((W, H), (260, H + 100), 820, (104, 80, 56), 74)).convert("RGB"),
        (0, 0),
    )
    img.paste(
        Image.alpha_composite(img.convert("RGBA"), glow((W, H), (1720, 470), 860, ACCENT, 74)).convert("RGB"),
        (0, 0),
    )

    draw = ImageDraw.Draw(img)

    # ── the phones, arced, outside first so the centre lands on top ───────────────────────
    height = 760
    frames = [phone(os.path.join(SHOTS_DIR, s), height) for s in SHOTS]
    overlap = 0.72
    step = round(frames[0].width * overlap)
    total = frames[0].width + step * (len(frames) - 1)
    left = W - 110 - total
    base = (H - height) // 2 - 16
    lift = [84, 32, 0, 32, 84]
    order = [0, 4, 1, 3, 2]
    # Dimmer the further from the middle, so the fan has a front and a back.
    dim = [0.30, 0.15, 0.0, 0.15, 0.30]
    for i in order:
        frames[i] = recede(frames[i], dim[i])
        x = left + step * i
        y = base + lift[i]
        sh, pad = shadow(frames[i].size, round(height * 0.055), 26, 4)
        img.paste(
            Image.alpha_composite(img.crop((x - pad, y - pad, x - pad + sh.width, y - pad + sh.height)).convert("RGBA"), sh).convert("RGB"),
            (x - pad, y - pad),
        )
        img.paste(frames[i], (x, y), frames[i])

    # ── the left block ───────────────────────────────────────────────────────────────────
    x = 150
    y = 250
    title = font("Fraunces-0.ttf", 124, weight=600)
    if os.path.exists(ICON):
        icon = Image.open(ICON).convert("RGBA").resize((96, 96), Image.LANCZOS)
        img.paste(icon, (x, y + 30), icon)
        draw.text((x + 126, y), "Filet", font=title, fill=FG)
    else:
        draw.text((x - 6, y), "Filet", font=title, fill=FG)
    y += 172

    draw.rounded_rectangle([x, y, x + 104, y + 6], 3, fill=ACCENT)
    y += 46

    tag = font("Inter-0.ttf", 40, weight=560)
    for line in TAGLINE:
        draw.text((x, y), line, font=tag, fill=FG)
        y += 54
    y += 22

    sub = font("Inter-0.ttf", 29, weight=400)
    for line in SUB:
        draw.text((x, y), line, font=sub, fill=FG2)
        y += 41
    y += 34

    chipf = font("Inter-0.ttf", 25, weight=520)
    cx = x
    for text in CHIPS:
        c = chip(text, chipf)
        if cx + c.width > 950:
            cx = x
            y += c.height + 12
        img.paste(c, (cx, y), c)
        cx += c.width + 11
    y += 92

    foot = font("Inter-0.ttf", 24, weight=420)
    draw.text((x, y), "GPL-3.0  \u00b7  Android 8.0+  \u00b7  no ads, no accounts, no telemetry",
              font=foot, fill=FG3)

    img.save(OUT, optimize=True)
    size_kb = os.path.getsize(OUT) // 1024
    print("wrote %s  (%dx%d, %d KB, %d screens)" % (
        os.path.relpath(OUT, ROOT).replace("\\", "/"), W, H, size_kb, len(SHOTS)))


if __name__ == "__main__":
    main()
