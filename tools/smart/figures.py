"""figures.py <folder with the app's .ink results> <output folder>

Builds the comparison pictures shown in the README from what the app's own code produced
(see SmartTest.benchmark and bench2.py)."""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import bench2
import core
import core2

folder, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)
PAPER = core.PAPER
ZOOM = 2
PAD, HEAD = 12, 40


def font(size):
    for name in ("segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def inks(name, mode):
    ink = np.fromfile(os.path.join(folder, f"{name}.{mode}.ink"), np.uint8).reshape(core.H, core.W)
    im = Image.fromarray(PAPER[ink], "RGB")
    return im.resize((core.W * ZOOM, core.H * ZOOM), Image.NEAREST)


def original(path):
    a = core.load_panel(path, look=False).astype(np.uint8)
    return Image.fromarray(a, "RGB").resize((core.W * ZOOM, core.H * ZOOM), Image.BICUBIC)


def limit(path):
    """Every colour replaced by the nearest one the panel can mix, without any dots."""
    lin = core2.best_possible(core.srgb_to_lin(core.load_panel(path, look=False)).astype(np.float64))
    white = core.srgb_to_lin(PAPER[1].astype(np.float32))
    black = core.srgb_to_lin(PAPER[0].astype(np.float32))
    a = core.lin_to_srgb(black + lin * (white - black)).astype(np.uint8)
    return Image.fromarray(a, "RGB").resize((core.W * ZOOM, core.H * ZOOM), Image.BICUBIC)


def sheet(parts, target):
    w = sum(im.width for _, im in parts) + PAD * (len(parts) + 1)
    h = parts[0][1].height + HEAD + PAD
    page = Image.new("RGB", (w, h), (16, 16, 16))
    draw = ImageDraw.Draw(page)
    f = font(22)
    x = PAD
    for label, im in parts:
        draw.text((x + 2, 7), label, fill=(236, 232, 220), font=f)
        page.paste(im, (x, HEAD))
        x += im.width + PAD
    page.save(os.path.join(out_dir, target), optimize=True)
    print(target, page.size)


paths = {os.path.splitext(os.path.basename(f))[0]: f for f, *_ in bench2.pictures("all")}
sheet([("Original", original(paths["1_fox"])), ("The panel's limit", limit(paths["1_fox"])),
       ("Smooth (Floyd-Steinberg)", inks("1_fox", "smooth")), ("Smart", inks("1_fox", "smart"))], "portrait.png")
sheet([("The panel's limit", limit(paths["s1_ramps"])), ("Smooth (Floyd-Steinberg)", inks("s1_ramps", "smooth")),
       ("Crisp (Atkinson)", inks("s1_ramps", "crisp")), ("Smart", inks("s1_ramps", "smart"))], "ramps.png")
sheet([("Original", original(paths["s2_lines"])), ("Smooth (Floyd-Steinberg)", inks("s2_lines", "smooth")),
       ("Crisp (Atkinson)", inks("s2_lines", "crisp")), ("Smart", inks("s2_lines", "smart"))], "lines.png")
