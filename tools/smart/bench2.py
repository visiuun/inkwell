"""Benchmark against a simulation of the real panel (measured pigments, 130 dpi).

Every result is turned into what the eye sees on the panel and compared with the original:
  colour  mean Oklab distance x100                      (lower is better)
  light   mean lightness of result minus original x100  (0 is exact, negative is too dark)
  ssim    structural similarity of lightness             (higher is better)
  edge    correlation of edge maps                       (higher is better)
  grain   lightness noise inside flat areas x100         (lower is better)
The row "panel limit" is the picture with every colour replaced by the nearest one the panel can
mix and no dots at all: nothing made of four inks can score better than that.
"""
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter, minimum_filter, sobel, uniform_filter
from skimage.metrics import structural_similarity

import core
import core2
import resample
import panel

HERE = os.path.dirname(os.path.abspath(__file__))
ALL = np.array([0, 1, 2, 3], np.int64)
N = 1.47


def make_synthetic():
    """Pictures with known answers: ramps, colour patches, lines and text."""
    d = os.path.join(HERE, "in2")
    os.makedirs(d, exist_ok=True)
    W, H = 480, 832
    y, x = np.mgrid[0:H, 0:W]
    # ramps: grey, red, yellow, orange, pink, skin, blue, green
    tops = [(255, 255, 255), (255, 0, 0), (255, 255, 0), (255, 128, 0), (255, 120, 180), (255, 205, 170), (40, 90, 230), (40, 200, 80)]
    im = np.zeros((H, W, 3), np.float32)
    for i, c in enumerate(tops):
        band = (x * len(tops) // W) == i
        t = (y / (H - 1))[..., None]
        ramp = np.where(t < 0.5, np.array(c) * (t * 2), np.array(c) + (255 - np.array(c)) * (t * 2 - 1))
        im[band] = ramp[band]
    Image.fromarray(im.astype(np.uint8)).save(os.path.join(d, "s1_ramps.png"))
    # line art and text
    art = Image.new("RGB", (W, H), (255, 255, 255))
    dr = ImageDraw.Draw(art)
    for i in range(12):
        dr.line([(20, 30 + i * 22), (460, 30 + i * 22 + i * 6)], fill=(0, 0, 0), width=1 + i // 3)
    for i, c in enumerate([(0, 0, 0), (220, 30, 30), (240, 200, 0), (90, 90, 90)]):
        dr.ellipse([40 + i * 100, 330, 120 + i * 100, 410], outline=c, width=3)
        dr.rectangle([40 + i * 100, 430, 120 + i * 100, 500], fill=c)
    for i in range(8):
        dr.text((30, 520 + i * 36), "Inkwell 0123456789 the quick brown fox", fill=(0, 0, 0) if i % 2 == 0 else (200, 20, 20), font_size=14 + i * 3)
    Image.fromarray(np.asarray(art)).save(os.path.join(d, "s2_lines.png"))
    try:
        from skimage import data
        for name in ("astronaut", "coffee", "chelsea"):
            Image.fromarray(getattr(data, name)()).save(os.path.join(d, f"p_{name}.png"))
    except Exception as e:          # the sample photos are optional
        print("no sample photos:", e)


def score(ink_or_lin, src_lin, n=N):
    """ink_or_lin: an ink-code image, or linear RGB for a dot-free picture."""
    def view(sigma):
        if ink_or_lin.ndim == 2:
            return panel.seen(ink_or_lin, sigma, n)
        return panel.blur(ink_or_lin, sigma)
    out = {}
    ref = panel.oklab(panel.blur(src_lin, 1.5))
    got = panel.oklab(view(1.5))
    out["colour"] = 100 * float(np.sqrt(((ref - got) ** 2).sum(-1)).mean())
    out["light"] = 100 * float((got[..., 0] - ref[..., 0]).mean())
    ref_l = panel.oklab(panel.blur(src_lin, 0.6))[..., 0]
    got_l = panel.oklab(view(1.0))[..., 0]
    out["ssim"] = float(structural_similarity(ref_l, got_l, data_range=1.0, win_size=7))
    ge = np.hypot(sobel(ref_l, 0), sobel(ref_l, 1))
    oe = np.hypot(sobel(got_l, 0), sobel(got_l, 1))
    out["edge"] = float(np.corrcoef(ge.ravel(), oe.ravel())[0, 1])
    sl = panel.oklab(src_lin)[..., 0]
    mean = uniform_filter(sl, 7)
    var = uniform_filter(sl * sl, 7) - mean * mean
    flat = minimum_filter((var < 0.012 ** 2).astype(np.float32), 9) > 0.5
    g15 = got[..., 0]
    wsum = gaussian_filter(flat.astype(np.float32), 3.0, mode="nearest")
    local = gaussian_filter(g15 * flat, 3.0, mode="nearest") / np.maximum(wsum, 1e-3)
    out["grain"] = 100 * float(np.sqrt(((g15 - local)[flat] ** 2).mean())) if flat.sum() > 500 else float("nan")
    return out


_cache = {}


def pictures(group):
    if group not in _cache:
        pats = {"user": ["in/*"], "extra": ["in2/*"], "all": ["in/*", "in2/*"]}[group]
        files = sorted(f for p in pats for f in glob.glob(os.path.join(HERE, p)))
        _cache[group] = [(f, core.load_panel(f), core.srgb_to_lin(core.load_panel(f, look=False)).astype(np.float64), core.source_mean(f)) for f in files]
    return _cache[group]


COLS = ["colour", "light", "ssim", "edge", "grain"]


def table(methods, group="user", per_picture=False):
    pics = pictures(group)
    print(f"{'':22s}" + "".join(f"{c:>9s}" for c in COLS) + ("   | ssim per picture" if per_picture else ""))
    res = {}
    for label, fn in methods.items():
        sc = [score(fn(f, rgb, src, mean), src) for f, rgb, src, mean in pics]
        res[label] = sc
        line = f"{label:22s}" + "".join(f"{np.nanmean([s[c] for s in sc]):9.3f}" for c in COLS)
        if per_picture:
            line += "   | " + " ".join(f"{s['ssim']:.3f}" for s in sc) + " | light " + " ".join(f"{s['light']:5.1f}" for s in sc)
        print(line)
    return res


_looks = {}
BASE = {
    "panel limit": lambda f, rgb, src, mean: core2.best_possible(src),
    "smooth": lambda f, rgb, src, mean: core.smooth(rgb, ALL),
    "crisp": lambda f, rgb, src, mean: core.crisp(rgb, ALL),
    "smart 1.2.0": lambda f, rgb, src, mean: core.smart(rgb, ALL, mean=mean, mix_gamma=1.7, expose=0.42, expose_min=0.65, expose_luma=False),
    "smart 1.2.1": lambda f, rgb, src, mean: core.smart(rgb, ALL, mean=mean),
}


def variants(spec):
    def make(kw):
        kw = dict(kw)
        look = kw.pop("_look", (0.0, 0.0))  # (contrast, saturation) as the quantizer sees them; Smart starts from the true picture
        detail = kw.pop("_detail", 0.0)     # detail-preserving downscale
        k = kw.pop("_k", 4)
        def run(f, rgb, src, mean):
            key = (f, look, detail, k)
            if key not in _looks:
                _looks[key] = resample.panel_rgb(f, k, detail, look[0], look[1])
            return core2.smart(_looks[key], ALL, mean=mean, **kw)
        return run
    return {k: make(kw) for k, kw in spec.items()}


def dump(folder):
    """The photos drawn at four times the panel's size, as the app's canvas hands them over."""
    os.makedirs(folder, exist_ok=True)
    for f, rgb, src, mean in pictures("all"):
        name = os.path.splitext(os.path.basename(f))[0]
        a = resample.sup(f, 4).astype(np.uint32)
        argb = (0xFF000000 | (a[..., 0] << 16) | (a[..., 1] << 8) | a[..., 2]).astype("<u4")
        argb.tofile(os.path.join(folder, name + ".sup"))
        open(os.path.join(folder, name + ".size"), "w").write(f"{core.W} {core.H} 4")


def kotlin(folder):
    """Scores what the app's own code produced, next to the prototype and the panel's limit."""
    modes = ["smooth", "crisp", "pattern", "flat", "smart"]
    rows = {}
    for group in ("user", "extra"):
        for f, rgb, src, mean in pictures(group):
            name = os.path.splitext(os.path.basename(f))[0]
            res = {"panel limit": core2.best_possible(src)}
            for m in modes:
                res[m] = np.fromfile(os.path.join(folder, f"{name}.{m}.ink"), np.uint8).reshape(core.H, core.W)
            res["prototype"] = core2.smart(resample.panel_rgb(f, 4, 1.0), ALL, mean=mean, table=33)
            agree = 100 * (res["smart"] == res["prototype"]).mean()
            for m, out in res.items():
                rows.setdefault((group, m), []).append(score(out, src))
                rows.setdefault(("all", m), []).append(score(out, src))
            print(f"{name:12s} app and prototype agree on {agree:5.1f}% of pixels; smart light {score(res['smart'], src)['light']:5.1f}")
    for group, title in (("user", "your four pictures"), ("extra", "ramps, line art and three photos"), ("all", "all nine")):
        print(f"\n{title}\n{'':14s}" + "".join(f"{c:>9s}" for c in COLS))
        for m in ["panel limit"] + modes + ["prototype"]:
            sc = rows[(group, m)]
            print(f"{m:14s}" + "".join(f"{np.nanmean([s[c] for s in sc]):9.3f}" for c in COLS))
    print(open(os.path.join(folder, "timing.txt")).read())


if __name__ == "__main__":
    if sys.argv[1] == "dump":
        dump(sys.argv[2]); sys.exit()
    if sys.argv[1] == "kotlin":
        kotlin(sys.argv[2]); sys.exit()
    if sys.argv[1] == "synth":
        make_synthetic(); sys.exit()
    group = sys.argv[1]
    spec = eval(sys.argv[2]) if len(sys.argv) > 2 else {"v2": {}}
    m = dict(BASE) if "--nobase" not in sys.argv else {}
    m.update(variants(spec))
    table(m, group, per_picture=True)
