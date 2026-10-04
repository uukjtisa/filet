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

W, H = 2560, 1200

#: The screens the banner shows, in the order they are arced.
#:
#: Three, not five. A banner is read at about a third of its own width once a README scales it
#: to the column, and at that size the words have to be big - which means the fan has to leave
#: room for them. Two more phones mostly hidden behind the others bought nothing and cost the
#: text the space it needed to be legible.
SHOTS = [
    "02-browse.png",
    "01-home.png",
    "10-metadata.png",
]

#: The four pillars, named, each with the thing that makes it true.
#:
#: The same four the repository description names and in the same order, so the banner and the
#: About field are one message rather than two. The word on the left is the claim; the line
#: beside it is what a reader can go and check, which is the only reason the claim is allowed
#: to be an abstract noun at all.
#: Ordered long, short, long, short so the left column alternates instead of stacking its two
#: eleven-letter words on top of each other - and, read down, it is also the order the work
#: happens in: find it, do something to it, whatever it turns out to be, send it on.
#:
#: Every proof is one verb phrase of about the same length. They were four different shapes
#: before - a noun triplet, a noun phrase, two verbs, a preposition - which is why the right
#: column read as four unrelated sentences rather than as a set.
PILLARS = [
    ("Convenience", "search the whole device in milliseconds"),
    ("Power", "edit inside archives, APKs and dex"),
    ("Versatility", "open any archive, any drive, any format"),
    ("Sharing", "send to any browser, nothing installed"),
]

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

#: The category, said small, because the headline below no longer says it.
#:
#: A flagship page states what the thing IS in an eyebrow and spends the headline on what it
#: does for you. Dropping the eyebrow and letting the headline carry both is what produced "A
#: file manager that does real work" - a sentence that names the category twice over and
#: promises nothing a competitor would not also claim.
EYEBROW = "ANDROID FILE MANAGER"

#: The four pillars, named, each with the thing that makes it true.
#:
#: The same four the repository description names and in the same order, so the banner and the
#: About field are one message rather than two. The word on the left is the claim; the line
#: beside it is what a reader can go and check, which is the only reason the claim is allowed
#: to be an abstract noun at all.
#: Ordered long, short, long, short so the left column alternates instead of stacking its two
#: eleven-letter words on top of each other - and, read down, it is also the order the work
#: happens in: find it, do something to it, whatever it turns out to be, send it on.
#:
#: Every proof is one verb phrase of about the same length. They were four different shapes
#: before - a noun triplet, a noun phrase, two verbs, a preposition - which is why the right
#: column read as four unrelated sentences rather than as a set.
PILLARS = [
    ("Convenience", "search the whole device in milliseconds"),
    ("Power", "edit inside archives, APKs and dex"),
    ("Versatility", "open any archive, any drive, any format"),
    ("Sharing", "send to any browser, nothing installed"),
]

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

#: The category, said small, because the headline below no longer says it.
#:
#: A flagship page states what the thing IS in an eyebrow and spends the headline on what it
#: does for you. Dropping the eyebrow and letting the headline carry both is what produced "A
#: file manager that does real work" - a sentence that names the category twice over and
#: promises nothing a competitor would not also claim.
EYEBROW = "ANDROID FILE MANAGER"

#: The headline: four verbs, which are the four things anybody does to a file.
#:
#: They are the same four the repository description names, in the same order - find, open,
#: change, send - so the promise and the proof underneath it line up one to one.
#:
#: No comparison in it, deliberately. Two earlier versions defined the app against a laptop
#: ("without reaching for a PC"), and a product that introduces itself by naming what it is not
#: has conceded the frame before it has said what it does.
TAGLINE = [
    "Find it. Open it.",
    "Change it. Send it.",
]

FONTS = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Fonts"
)


#: Family and weight to the file that actually holds it.
#:
#: These are static instances, not variable fonts - asking one to set a weight axis raises and
#: the earlier version swallowed that, so every line on the banner rendered in Regular however
#: heavy it asked to be. A headline in the body weight is most of what made the type look
#: cheap, and it was invisible because the code said SemiBold.
FACES = {
    ("Fraunces", "Regular"): "Fraunces-0.ttf",
    ("Fraunces", "Bold"): "Fraunces-1.ttf",
    ("Fraunces", "Black"): "Fraunces-2.ttf",
    ("Inter", "Regular"): "Inter-0.ttf",
    ("Inter", "SemiBold"): "Inter-1.ttf",
    ("Inter", "ExtraBold"): "Inter-2.ttf",
    ("Inter", "Black"): "Inter-3.ttf",
}


def font(family, size, weight="Regular"):
    """Load one face. An unknown family or weight is a mistake worth stopping on, not guessing."""
    name = FACES.get((family, weight))
    if name is None:
        sys.exit("no face for %s %s - see FACES" % (family, weight))
    for folder in (FONTS, r"C:\Windows\Fonts"):
        path = os.path.join(folder, name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    sys.exit("%s is not installed; install it or change FACES" % name)


def spaced(draw, xy, text, f, fill, tracking):
    """
    Draw text with letter-spacing, which the imaging library has no setting for.

    An eyebrow without it is just small text; the spacing is what makes it read as a label.
    """
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=f, fill=fill)
        x += f.getlength(ch) + tracking
    return x - tracking


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
    f = font("Inter", 25, "SemiBold")

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
    height = 880
    frames = [phone(os.path.join(SHOTS_DIR, s), height) for s in SHOTS]
    overlap = 0.74
    step = round(frames[0].width * overlap)
    total = frames[0].width + step * (len(frames) - 1)
    left = W - 110 - total
    base = (H - height) // 2 - 16
    lift = [74, 0, 74]
    order = [0, 2, 1]
    # Dimmer the further from the middle, so the fan has a front and a back.
    dim = [0.26, 0.0, 0.26]
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
    y = 196
    title = font("Fraunces", 150, "Bold")
    if os.path.exists(ICON):
        icon = Image.open(ICON).convert("RGBA").resize((116, 116), Image.LANCZOS)
        img.paste(icon, (x, y + 36), icon)
        draw.text((x + 150, y), "Filet", font=title, fill=FG)
    else:
        draw.text((x - 6, y), "Filet", font=title, fill=FG)
    y += 206

    draw.rounded_rectangle([x, y, x + 128, y + 7], 4, fill=ACCENT)
    y += 52

    eyebrow = font("Inter", 31, "SemiBold")
    spaced(draw, (x + 1, y), EYEBROW, eyebrow, FG3, 4.2)
    y += 94

    # The pillars, hung off a shared vertical axis: the claims end where the proofs begin.
    #
    # Right-aligning the left column is the whole of the layout. Set flush left, four words of
    # five to eleven letters leave a ragged gutter down the middle of the block and the rows
    # stop looking related; hung off one axis they read as a table somebody drew on purpose.
    name = font("Inter", 42, "SemiBold")
    proof = font("Inter", 39, "Regular")
    axis = x + 300
    for i, (word, line) in enumerate(PILLARS):
        if i:
            # A hairline between rows rather than a box around the block. It separates without
            # enclosing, so the list stays part of the page instead of becoming a card on it.
            draw.line([(x, y - 26), (axis + 800, y - 26)], fill=LINE, width=2)
        draw.text((axis - draw.textlength(word, font=name), y), word, font=name, fill=ACCENT)
        draw.text((axis + 46, y + 3), line, font=proof, fill=FG2)
        y += 90
    y += 44

    foot = font("Inter", 34, "Regular")
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
