"""Smart, second generation: everything is worked out against the panel's measured pigments."""
import math

import numpy as np
from scipy.ndimage import gaussian_filter

import core
import panel

P2 = dict(
    mix_gamma=1.5,                      # sRGB value ^ this = the space where ink shares add up (n = 2.2 / this)
    bright=0.87, expose=0.4, expose_min=0.8, expose_cap=3.0,
    sharpen=0.6, sharpen_sigma=1.0,
    w_light=1.0, w_colour=1.0,          # how gamut mapping weighs brightness against colour
    chroma=1.0,                         # how much louder a coloured dot counts than its lightness alone
    calm=0.1,                           # how much accuracy to trade for a quieter dot pattern
    snap=1.0, clean=0.03, mask=0.004,
    sigma_l=1.0, sigma_c=1.6, lam_c=0.4,
    gain_eps=0.04, gain_pow=0.6667,
    dbs_space="linear",                 # "linear": judge patterns in light; "mix": in mixing space
    passes=8, swaps=True, dbs=True,
    table=0,                            # 0: exact colour mapping; otherwise the size of the lookup grid
)

_gamuts = {}


def gamut(allowed, n, wl, wc, calm=0.0, chroma=1.0):
    key = (tuple(allowed), round(n, 4), wl, wc, calm, chroma)
    if key not in _gamuts:
        _gamuts[key] = panel.Gamut(allowed, n, wl, wc, calm, chroma)
    return _gamuts[key]


def tone(rgb, mean, p):
    """sRGB 0..255 -> mixing space, sharpened and lifted."""
    src = rgb.astype(np.float32)
    if p["sharpen"] > 0:
        soft = np.stack([gaussian_filter(src[..., c], p["sharpen_sigma"], mode="nearest") for c in range(3)], -1)
        src = np.clip(src + p["sharpen"] * (src - soft), 0, 255)
    v = np.clip(src / 255.0, 0, 1)
    if mean is None:
        mean = float((v * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1).mean())
    e = p["bright"]
    if p["expose"] > 0 and 0 < mean < p["expose"]:
        e *= max(p["expose_min"], math.log(p["expose"]) / math.log(mean))
    if e != 1.0:
        y = (v * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1, keepdims=True)
        v = np.clip(v * np.minimum(np.maximum(y, 1e-4) ** (e - 1), p["expose_cap"]), 0, 1)
    return v.astype(np.float64) ** p["mix_gamma"]


def tidy(shares, k, tau):
    """Pull each colour towards its main ink and drop ink that would only be stray dots."""
    w = np.maximum(shares, 0) ** k
    w /= np.maximum(w.sum(-1, keepdims=True), 1e-9)
    if tau > 0:
        x = np.clip(w / (2 * tau), 0, 1)
        f = w * (x * x * (3 - 2 * x))
        lost = (w - f).sum(-1)
        top = f.argmax(-1)
        np.put_along_axis(f, top[..., None], np.take_along_axis(f, top[..., None], -1) + lost[..., None], -1)
        w = f
    return w


def target(rgb, allowed, mean=None, **kw):
    """Shares of each allowed ink per pixel, and the gamut they live in."""
    p = dict(P2); p.update(kw)
    n = 2.2 / p["mix_gamma"]
    g = gamut(allowed, n, p["w_light"], p["w_colour"], p["calm"], p["chroma"])
    m = tone(rgb, mean, p)
    shares = g.shares_fast(m, p["table"]) if p["table"] else g.shares(m)
    return tidy(shares, p["snap"], p["clean"]), g, p


def smart(rgb, allowed, mean=None, **kw):
    allowed = [int(a) for a in allowed]
    shares, g, p = target(rgb, allowed, mean, **kw)
    h, w, _ = shares.shape
    codes = np.asarray(allowed, np.int64)
    vert = np.zeros((4, 3), np.float32)
    vert[allowed] = g.v
    t = (shares @ g.v).astype(np.float32)
    mask = np.zeros((h, w, 4), np.bool_)
    mask[..., allowed] = shares > p["mask"]
    out = core._init_diffuse(t, codes, vert, mask)
    if not p["dbs"]:
        return out
    look = np.zeros((4, 3))
    look[allowed] = g.v if p["dbs_space"] == "mix" else panel.REAL[allowed]
    ink_opp = core.opponent(look.astype(np.float32))
    topp = (shares @ ink_opp[allowed]).astype(np.float32)
    lum = gaussian_filter(topp[..., 0], p["sigma_l"], mode="nearest")
    gain = (1.0 / (np.maximum(lum, 0) + p["gain_eps"]) ** p["gain_pow"]).astype(np.float32)
    gain /= gain.min()
    cpp = np.zeros((3, 33, 33), np.float32)
    rad = np.zeros(3, np.int64)
    for c, sg in enumerate((p["sigma_l"], p["sigma_c"], p["sigma_c"])):
        s2 = sg * math.sqrt(2)
        R = min(16, int(math.ceil(s2 * 2.6)))
        cpp[c, 16 - R:17 + R, 16 - R:17 + R] = core.gauss_kernel(s2, R)
        rad[c] = R
    lam = np.array([1.0, p["lam_c"], p["lam_c"]], np.float32)
    core._dbs(out, topp, gain, codes, ink_opp, cpp, rad, lam, p["passes"], p["swaps"], mask)
    return out


def best_possible(src_lin, allowed=(0, 1, 2, 3), n=1.47):
    """The picture with every colour replaced by the closest one the panel can show, no dots at all."""
    g = gamut(list(allowed), n, 1.0, 1.0)
    return g.colour(g.shares(np.clip(src_lin, 0, 1) ** (1.0 / n)))
