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
import math
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

CHIPS = ["Search", "Archives", "APKs", "Metadata", "Lua"]

#: Rings and arrows drawn onto a copy of a single screenshot, for the detail strip.
#:
#: **Not onto the banner.** A banner points at nothing: it is the first thing anybody sees and
#: its job is to look like the product, not to teach. Annotation belongs on the shots further
#: down, where somebody is already reading and a pointer answers a question they now have.
#:
#: Each box is in fractions of the SCREENSHOT rather than in pixels, so it survives the shot
#: being re-taken at another size.
CALLOUTS = {
    "01-home.png": [
        {
            "box": (0.512, 0.286, 0.964, 0.395),
            "label": "your PC, mounted as a drive",
            "side": "left",
        },
    ],
}

#: The one positioning line, shared with the README and the repository description.
#:
#: Written the way a flagship app writes one: a plain declarative, the category named, one
#: claim, no comparison to anything else. The line it replaces ended "without reaching for a
#: PC", which defines the app by what it is not - and a product that introduces itself by
#: naming its competition has conceded the frame before it has said what it does.
TAGLINE = [
    "A file manager",
    "that does real work.",
]

#: The four things the app is for, in the order the repository description names them.
#:
#: They started life as four virtues - versatile, convenient, powerful, seamless - and each one
#: here is the same claim with its proof substituted in. A virtue is something every competitor
#: also claims and nobody can check; what replaced it is something a reader can go and try.
#: Same four ideas, same order, said in a way that survives being tested.
SUB = [
    "Search the whole device in milliseconds.",
    "Edit files inside archives and APKs.",
    "Script it in Lua.",
    "Share to any browser on your network.",
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


def annotate(shot, callouts):
    """
    A copy of a screenshot with something ringed and named.

    The label goes in a strip added ABOVE the screen rather than on top of it. An arrow drawn
    over the content hides the content, which on a 540-pixel-wide shot is most of the thing
    being pointed at - and a reader cannot tell whether what is underneath matters.

    The original file is never touched. `docs/screenshots` stays a record of the screen, and
    these copies live beside it as presentation.
    """
    band = 120
    out = Image.new("RGBA", (shot.width, shot.height + band), BG + (255,))
    out.paste(shot.convert("RGBA"), (0, band))
    d = ImageDraw.Draw(out)
    f = font("Inter-0.ttf", 25, weight=560)

    for c in callouts:
        bx = c["box"]
        pad = 6
        ring = [
            max(2, bx[0] * shot.width - pad),
            band + bx[1] * shot.height - pad,
            min(shot.width - 3, bx[2] * shot.width + pad),
            band + bx[3] * shot.height + pad,
        ]
        # A rounded rectangle, not an ellipse. The thing being ringed here is a card, and an
        # ellipse wide enough to contain a wide short box has to be far bigger than the box -
        # which is how the first version ended up circling the heading above the tile instead.
        radius = round(min(ring[2] - ring[0], ring[3] - ring[1]) * 0.28)
        halo = Image.new("RGBA", out.size, (0, 0, 0, 0))
        ImageDraw.Draw(halo).rounded_rectangle(
            [ring[0] - 6, ring[1] - 6, ring[2] + 6, ring[3] + 6], radius + 6, fill=ACCENT + (85,),
        )
        out = Image.alpha_composite(out, halo.filter(ImageFilter.GaussianBlur(13)))
        d = ImageDraw.Draw(out)
        d.rounded_rectangle(ring, radius, outline=ACCENT + (255,), width=4)

        label = c["label"]
        tw = round(f.getlength(label))
        # Off to the side the callout names, so the stem is a curve with somewhere to go
        # rather than a vertical line that reads as a crop mark.
        lx = 16 if c.get("side") == "left" else max(14, out.width - tw - 16)
        ly = 24
        d.text((lx, ly), label, font=f, fill=FG)

        sx = lx + tw // 2
        sy = ly + f.size + 10
        ex = ring[0] + (ring[2] - ring[0]) * (0.30 if c.get("side") == "left" else 0.70)
        ey = ring[1] - 3
        pts = []
        for i in range(33):
            t = i / 32
            u = 1 - t
            # One control point, pulled down under the label, so the stem leaves vertically
            # and arrives vertically with a bend in the middle.
            cx0, cy0 = sx, (sy + ey) * 0.62
            pts.append((
                u * u * sx + 2 * u * t * cx0 + t * t * ex,
                u * u * sy + 2 * u * t * cy0 + t * t * ey,
            ))
        d.line(pts, fill=ACCENT + (235,), width=3, joint="curve")
        ax, ay = pts[-1]
        bx2, by2 = pts[-5]
        ang = math.atan2(ay - by2, ax - bx2)
        size = 14
        d.polygon([
            (ax, ay),
            (ax - size * math.cos(ang - 0.45), ay - size * math.sin(ang - 0.45)),
            (ax - size * math.cos(ang + 0.45), ay - size * math.sin(ang + 0.45)),
        ], fill=ACCENT + (255,))

    return out.convert("RGB")


def write_callouts():
    """Write the annotated copies the README's detail strip points at."""
    folder = os.path.join(SHOTS_DIR, "callouts")
    os.makedirs(folder, exist_ok=True)
    made = []
    for name, spec in CALLOUTS.items():
        src = os.path.join(SHOTS_DIR, name)
        if not os.path.exists(src):
            continue
        out = os.path.join(folder, name)
        annotate(Image.open(src).convert("RGB"), spec).save(out, optimize=True)
        made.append(name)
    return made


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
    for name in write_callouts():
        print("annotated docs/screenshots/callouts/%s" % name)
    size_kb = os.path.getsize(OUT) // 1024
    print("wrote %s  (%dx%d, %d KB, %d screens)" % (
        os.path.relpath(OUT, ROOT).replace("\\", "/"), W, H, size_kb, len(SHOTS)))


if __name__ == "__main__":
    main()
