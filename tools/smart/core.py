"""Prototype of the Smart texture and the reference copies of the app's four existing ones."""
import math

import numba as nb
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

W, H = 240, 416

# ink order = the app's codes: 0 black, 1 white, 2 yellow, 3 red
INK_SRGB = np.array([[0, 0, 0], [255, 255, 255], [255, 255, 0], [255, 0, 0]], np.float32)
INK_LIN = INK_SRGB / 255.0
PAPER = np.array([[0x1C, 0x1B, 0x1A], [0xE8, 0xE5, 0xDA], [0xE9, 0xC2, 0x2E], [0xB7, 0x39, 0x2D]], np.uint8)
LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)


def srgb_to_lin(v):
    v = np.clip(v / 255.0, 0, 1)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4).astype(np.float32)


def lin_to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055) * 255.0


def load_panel(path, contrast=0.15, saturation=0.3, brightness=0.0, look=True):
    """What Composer.compose hands to the quantizer: fill-crop, 2x supersample, the Look sliders."""
    im = Image.open(path).convert("RGB")
    # the app decodes at most 1400 px on the long side
    longest = max(im.size)
    if longest > 1400:
        k = 1400 / longest
        im = im.resize((max(1, int(im.width * k)), max(1, int(im.height * k))), Image.BILINEAR)
    s = max(W / im.width, H / im.height) * 2
    nw, nh = max(W * 2, round(im.width * s)), max(H * 2, round(im.height * s))
    im = im.resize((nw, nh), Image.BILINEAR)
    x0, y0 = (nw - W * 2) // 2, (nh - H * 2) // 2
    a = np.asarray(im.crop((x0, y0, x0 + W * 2, y0 + H * 2)), np.float32)
    a = a.reshape(H, 2, W, 2, 3).mean(axis=(1, 3))
    if not look:
        return a
    c = 1 + contrast * 1.5 if contrast >= 0 else 1 + contrast * 0.8
    sat = 1 + saturation * 1.5 if saturation >= 0 else 1 + saturation
    luma = (a * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1, keepdims=True)
    a = luma + (a - luma) * sat
    a = np.clip((a - 128) * c + 128 + brightness * 110, 0, 255)
    return a.astype(np.float32)


# ----------------------------------------------------------------- the app's existing textures

@nb.njit(cache=True)
def _nearest(inks, n, r, g, b):
    best, bd = 0, 1e30
    for k in range(n):
        dr, dg, db = r - inks[k, 0], g - inks[k, 1], b - inks[k, 2]
        d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
        if d < bd:
            bd, best = d, k
    return best


@nb.njit(cache=True)
def _diffuse(rgb, allowed, taps, divisor):
    h, w, _ = rgb.shape
    out = np.zeros((h, w), np.uint8)
    inks = INK_SRGB[allowed]
    n = len(allowed)
    for y in range(h):
        fwd = y % 2 == 0
        xs = range(w) if fwd else range(w - 1, -1, -1)
        step = 1 if fwd else -1
        for x in xs:
            r = min(max(rgb[y, x, 0], -64), 319)
            g = min(max(rgb[y, x, 1], -64), 319)
            b = min(max(rgb[y, x, 2], -64), 319)
            k = _nearest(inks, n, r, g, b)
            out[y, x] = allowed[k]
            er, eg, eb = (r - inks[k, 0]) / divisor, (g - inks[k, 1]) / divisor, (b - inks[k, 2]) / divisor
            for t in range(taps.shape[0]):
                nx, ny = x + taps[t, 0] * step, y + taps[t, 1]
                if nx < 0 or nx >= w or ny >= h:
                    continue
                rgb[ny, nx, 0] += er * taps[t, 2]
                rgb[ny, nx, 1] += eg * taps[t, 2]
                rgb[ny, nx, 2] += eb * taps[t, 2]
    return out


FLOYD = np.array([[1, 0, 7], [-1, 1, 3], [0, 1, 5], [1, 1, 1]], np.int64)
ATKINSON = np.array([[1, 0, 1], [2, 0, 1], [-1, 1, 1], [0, 1, 1], [1, 1, 1], [0, 2, 1]], np.int64)
BAYER8 = np.array([
    0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
], np.float32).reshape(8, 8)


def smooth(rgb, allowed):
    return _diffuse(rgb.copy(), allowed, FLOYD, 16.0)


def crisp(rgb, allowed):
    return _diffuse(rgb.copy(), allowed, ATKINSON, 8.0)


def _flat(rgb, allowed):
    inks = INK_SRGB[allowed]
    d = rgb[:, :, None, :] - inks[None, None]
    d = (d * d * np.array([2, 4, 3], np.float32)).sum(-1)
    return allowed[d.argmin(-1)].astype(np.uint8)


def flat(rgb, allowed):
    return _flat(rgb, allowed)


def pattern(rgb, allowed):
    t = (np.tile(BAYER8, (H // 8 + 1, W // 8 + 1))[:H, :W] + 0.5) / 64 - 0.5
    return _flat(rgb + (t * 150)[..., None], allowed)


# ----------------------------------------------------------------- Smart

def gamut_map(lin, yellow=True, red=True, weights=LUMA):
    """Closest colour the inks can mix, keeping luminance: with ideal inks that is r >= g >= b."""
    r, g, b = lin[..., 0].copy(), lin[..., 1].copy(), lin[..., 2].copy()
    wr, wg, wb = weights
    y_all = wr * r + wg * g + wb * b
    if yellow and red:
        rg = (wr * r + wg * g) / (wr + wg)
        gb = (wg * g + wb * b) / (wg + wb)
        out = np.stack([r, g, b], -1)
        a = g > r                      # pool r,g
        bb = (~a) & (b > g)            # pool g,b
        # case a
        pool_all_a = a & (b > rg)
        only_a = a & ~pool_all_a
        out[only_a, 0] = rg[only_a]; out[only_a, 1] = rg[only_a]
        # case b
        pool_all_b = bb & (gb > r)
        only_b = bb & ~pool_all_b
        out[only_b, 1] = gb[only_b]; out[only_b, 2] = gb[only_b]
        allp = pool_all_a | pool_all_b
        out[allp] = y_all[allp][:, None]
        return out
    if red:                            # black, white, red: (r, m, m) with r >= m
        m = (wg * g + wb * b) / (wg + wb)
        bad = m > r
        r = np.where(bad, y_all, r); m = np.where(bad, y_all, m)
        return np.stack([r, m, m], -1)
    if yellow:                         # black, white, yellow: (m, m, b) with m >= b
        m = (wr * r + wg * g) / (wr + wg)
        bad = b > m
        b = np.where(bad, y_all, b); m = np.where(bad, y_all, m)
        return np.stack([m, m, b], -1)
    return np.repeat(y_all[..., None], 3, -1)


def opponent(lin):
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    return np.stack([0.2126 * r + 0.7152 * g + 0.0722 * b, r - g, 0.5 * (r + g) - b], -1).astype(np.float32)


def gauss_kernel(sigma, radius):
    ax = np.arange(-radius, radius + 1)
    k = np.exp(-(ax[:, None] ** 2 + ax[None, :] ** 2) / (2 * sigma * sigma))
    return (k / k.sum()).astype(np.float32)


@nb.njit(cache=True)
def _init_diffuse(t, allowed, ink_lin, mask):
    """Floyd-Steinberg in linear light, as the starting point."""
    h, w, _ = t.shape
    buf = t.copy()
    out = np.zeros((h, w), np.uint8)
    n = len(allowed)
    for y in range(h):
        fwd = y % 2 == 0
        xs = range(w) if fwd else range(w - 1, -1, -1)
        step = 1 if fwd else -1
        for x in xs:
            r = min(max(buf[y, x, 0], -0.25), 1.25)
            g = min(max(buf[y, x, 1], -0.25), 1.25)
            b = min(max(buf[y, x, 2], -0.25), 1.25)
            best, bd = 0, 1e30
            for k in range(n):
                if not mask[y, x, allowed[k]]:
                    continue
                c = ink_lin[allowed[k]]
                dr, dg, db = r - c[0], g - c[1], b - c[2]
                d = 2 * dr * dr + 4 * dg * dg + 1 * db * db
                if d < bd:
                    bd, best = d, k
            ink = allowed[best]
            out[y, x] = ink
            er, eg, eb = r - ink_lin[ink, 0], g - ink_lin[ink, 1], b - ink_lin[ink, 2]
            for dx, dy, wt in ((1, 0, 7.0), (-1, 1, 3.0), (0, 1, 5.0), (1, 1, 1.0)):
                nx, ny = x + dx * step, y + dy
                if nx < 0 or nx >= w or ny >= h:
                    continue
                buf[ny, nx, 0] += er * wt / 16
                buf[ny, nx, 1] += eg * wt / 16
                buf[ny, nx, 2] += eb * wt / 16
    return out


@nb.njit(cache=True)
def _dbs(out, topp, gain, allowed, ink_opp, cpp, rad, lam, passes, swaps, mask):
    """Direct binary search: change or swap pixels while the filtered error keeps dropping."""
    h, w = out.shape
    n = len(allowed)
    # cpe = cpp (*) (gain * (ink - target)), per channel
    err = np.zeros((h, w, 3), np.float32)
    for y in range(h):
        for x in range(w):
            for c in range(3):
                err[y, x, c] = gain[y, x] * (ink_opp[out[y, x], c] - topp[y, x, c])
    cpe = np.zeros((h, w, 3), np.float32)
    for c in range(3):
        R = rad[c]
        for y in range(h):
            for x in range(w):
                acc = 0.0
                for dy in range(-R, R + 1):
                    yy = y + dy
                    if yy < 0 or yy >= h:
                        continue
                    for dx in range(-R, R + 1):
                        xx = x + dx
                        if xx < 0 or xx >= w:
                            continue
                        acc += cpp[c, dy + 16, dx + 16] * err[yy, xx, c]
                cpe[y, x, c] = acc
    total = 0
    for p in range(passes):
        changed = 0
        for y in range(h):
            for x in range(w):
                k0 = out[y, x]
                s0 = gain[y, x]
                best = -1e-9
                bk = -1
                bj = -1
                # change this pixel's ink
                for ki in range(n):
                    k1 = allowed[ki]
                    if k1 == k0 or not mask[y, x, k1]:
                        continue
                    d = 0.0
                    for c in range(3):
                        a = s0 * (ink_opp[k1, c] - ink_opp[k0, c])
                        d += lam[c] * (2 * a * cpe[y, x, c] + a * a * cpp[c, 16, 16])
                    if d < best:
                        best, bk, bj = d, k1, -1
                # swap with a neighbour
                if swaps:
                    for j in range(8):
                        dy = (-1, -1, -1, 0, 0, 1, 1, 1)[j]
                        dx = (-1, 0, 1, -1, 1, -1, 0, 1)[j]
                        yy, xx = y + dy, x + dx
                        if yy < 0 or yy >= h or xx < 0 or xx >= w:
                            continue
                        k1 = out[yy, xx]
                        if k1 == k0 or not mask[y, x, k1] or not mask[yy, xx, k0]:
                            continue
                        s1 = gain[yy, xx]
                        d = 0.0
                        for c in range(3):
                            dv = ink_opp[k1, c] - ink_opp[k0, c]
                            a0 = s0 * dv
                            a1 = -s1 * dv
                            d += lam[c] * (2 * a0 * cpe[y, x, c] + 2 * a1 * cpe[yy, xx, c]
                                           + (a0 * a0 + a1 * a1) * cpp[c, 16, 16] + 2 * a0 * a1 * cpp[c, dy + 16, dx + 16])
                        if d < best:
                            best, bk, bj = d, k1, j
                if bk < 0:
                    continue
                changed += 1
                # apply
                for c in range(3):
                    a = s0 * (ink_opp[bk, c] - ink_opp[k0, c])
                    R = rad[c]
                    for dy in range(-R, R + 1):
                        yy = y + dy
                        if yy < 0 or yy >= h:
                            continue
                        for dx in range(-R, R + 1):
                            xx = x + dx
                            if xx < 0 or xx >= w:
                                continue
                            cpe[yy, xx, c] += a * cpp[c, dy + 16, dx + 16]
                out[y, x] = bk
                if bj >= 0:
                    dy0 = (-1, -1, -1, 0, 0, 1, 1, 1)[bj]
                    dx0 = (-1, 0, 1, -1, 1, -1, 0, 1)[bj]
                    y1, x1 = y + dy0, x + dx0
                    s1 = gain[y1, x1]
                    for c in range(3):
                        a = s1 * (ink_opp[k0, c] - ink_opp[bk, c])
                        R = rad[c]
                        for dy in range(-R, R + 1):
                            yy = y1 + dy
                            if yy < 0 or yy >= h:
                                continue
                            for dx in range(-R, R + 1):
                                xx = x1 + dx
                                if xx < 0 or xx >= w:
                                    continue
                                cpe[yy, xx, c] += a * cpp[c, dy + 16, dx + 16]
                    out[y1, x1] = k0
        total += changed
        if changed < h * w // 2000:
            break
    return total, p + 1


# Roughly how the pigments look next to the panel's own white, in linear light. Only used to
# judge how visible a pattern is; tone still comes from the ideal inks.
REAL_LIN = np.array([[0, 0, 0], [1, 1, 1], [1.0, 0.80, 0.05], [0.58, 0.04, 0.03]], np.float32)

DEFAULTS = dict(
    mix_gamma=1.5, levels=0.0, clean=0.03, snap=1.25, lsnap=1.0, lclip=0.0, expose=0.65, expose_min=0.6, expose_luma=True, expose_cap=3.0, flatten=0.0, pool=(0.2126, 0.7152, 0.0722),
    sharpen=0.6, sharpen_sigma=1.0,
    sigma_l=1.0, sigma_c=1.6, lam_c=0.6, real=True,
    gain_eps=0.04, gain_pow=0.6667,
    passes=8, swaps=True, dbs=True, mask=0.004,
)


def tone(src, p):
    """sRGB 0..255 -> ink-mixing space 0..1."""
    v = np.clip(src / 255.0, 0, 1)
    if p["levels"] > 0:
        y = (v * LUMA).sum(-1)
        lo, hi = np.percentile(y, [0.5, 99.5])
        lo, hi = min(lo, 0.25) * p["levels"], 1 - (1 - max(hi, 0.75)) * p["levels"]
        v = np.clip((v - lo) / max(hi - lo, 1e-3), 0, 1)
    if p["expose"] > 0:
        # dark pictures drown on a reflective panel: lift the middle, leave black and white alone
        mean = p.get("mean")
        if mean is None:
            mean = float((v * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1).mean())
        if 0 < mean < p["expose"]:
            e = max(p["expose_min"], math.log(p["expose"]) / math.log(mean))
            if p["expose_luma"]:
                # brighten by brightness alone, so strong colours stay strong instead of going pale
                y = (v * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1, keepdims=True)
                v = np.clip(v * np.minimum(np.maximum(y, 1e-4) ** (e - 1), p["expose_cap"]), 0, 1)
            else:
                v = v ** e
    m = v ** p["mix_gamma"]
    return m.astype(np.float32)


def snap_weights(t, k):
    """Pulls every colour a little towards the ink it is mostly made of: fewer stray dots."""
    r, g, b = t[..., 0], t[..., 1], t[..., 2]
    w = np.maximum(np.stack([1 - r, b, g - b, r - g], -1), 0) ** k
    w /= np.maximum(w.sum(-1, keepdims=True), 1e-6)
    bb = w[..., 1]; gg = bb + w[..., 2]; rr = gg + w[..., 3]
    return np.stack([rr, gg, bb], -1).astype(np.float32)


def light_curve(t, k, clip):
    """Contrast on the one axis where dots are loud: light inks (white, yellow) against dark ones
    (black, red). Mixes inside either pair are left alone, they are quiet anyway."""
    r, g, b = t[..., 0], t[..., 1], t[..., 2]
    L = np.clip(g, 0, 1)                       # share of light ink
    a, c = L ** k, (1 - L) ** k
    f = a / np.maximum(a + c, 1e-9)
    f = np.clip((f - clip) / (1 - 2 * clip), 0, 1)
    up = np.where(L > 1e-6, f / np.maximum(L, 1e-6), 0)
    dn = np.where(L < 1 - 1e-6, (1 - f) / np.maximum(1 - L, 1e-6), 0)
    b2 = b * up                                # white
    g2 = f                                     # white + yellow
    r2 = f + (r - g) * dn                      # + red
    return np.stack([r2, g2, b2], -1).astype(np.float32)


def clean_weights(t, tau):
    """Drops ink that would only appear as a few stray dots."""
    r, g, b = t[..., 0], t[..., 1], t[..., 2]
    w = np.stack([1 - r, b, g - b, r - g], -1)          # black, white, yellow, red
    x = np.clip(w / (2 * tau), 0, 1)
    f = w * (x * x * (3 - 2 * x))
    lost = (w - f).sum(-1)
    top = f.argmax(-1)
    np.put_along_axis(f, top[..., None], np.take_along_axis(f, top[..., None], -1) + lost[..., None], -1)
    bb = f[..., 1]; gg = bb + f[..., 2]; rr = gg + f[..., 3]
    return np.stack([rr, gg, bb], -1).astype(np.float32)


def smart(rgb, allowed, yellow=True, red=True, stats=None, **kw):
    p = dict(DEFAULTS); p.update(kw)
    src = rgb.astype(np.float32)
    if p["flatten"] > 0:
        from skimage.restoration import denoise_bilateral
        src = denoise_bilateral(src / 255.0, sigma_color=p["flatten"], sigma_spatial=1.5, channel_axis=-1).astype(np.float32) * 255.0
    if p["sharpen"] > 0:
        blur = np.stack([gaussian_filter(src[..., c], p["sharpen_sigma"], mode="nearest") for c in range(3)], -1)
        src = np.clip(src + p["sharpen"] * (src - blur), 0, 255)
    t = gamut_map(tone(src, p), yellow, red, p["pool"]).astype(np.float32)
    if p["snap"] != 1.0:
        t = snap_weights(t, p["snap"])
    if p["clean"] > 0:
        t = clean_weights(t, p["clean"])
    if p["lsnap"] != 1.0 or p["lclip"] > 0:
        t = light_curve(t, p["lsnap"], p["lclip"])
    allowed = np.asarray(allowed, np.int64)
    r_, g_, b_ = t[..., 0], t[..., 1], t[..., 2]
    mask = np.stack([1 - r_, b_, g_ - b_, r_ - g_], -1) > p["mask"]
    out = _init_diffuse(t, allowed, INK_LIN, mask)
    if not p["dbs"]:
        return out
    if p["real"]:
        ink_opp = opponent(REAL_LIN)
        r, g, b = t[..., 0:1], t[..., 1:2], t[..., 2:3]
        topp = (b * ink_opp[1] + (g - b) * ink_opp[2] + (r - g) * ink_opp[3]).astype(np.float32)
    else:
        ink_opp = opponent(INK_LIN)
        topp = opponent(t)
    lum = gaussian_filter(topp[..., 0], p["sigma_l"], mode="nearest")
    gain = (1.0 / (np.maximum(lum, 0) + p["gain_eps"]) ** p["gain_pow"]).astype(np.float32)
    gain /= gain.min() if gain.min() > 0 else 1
    cpp = np.zeros((3, 33, 33), np.float32)
    rad = np.zeros(3, np.int64)
    for c, sg in enumerate((p["sigma_l"], p["sigma_c"], p["sigma_c"])):
        s2 = sg * math.sqrt(2)
        R = min(16, int(math.ceil(s2 * 2.6)))
        cpp[c, 16 - R:17 + R, 16 - R:17 + R] = gauss_kernel(s2, R)
        rad[c] = R
    lam = np.array([1.0, p["lam_c"], p["lam_c"]], np.float32)
    n, passes = _dbs(out, topp, gain, allowed, ink_opp, cpp, rad, lam, p["passes"], p["swaps"], mask)
    if stats is not None:
        stats["changes"] = int(n); stats["passes"] = int(passes)
    return out


def source_mean(path):
    """Average brightness before the sliders, which is what the app hands to Smart."""
    a = load_panel(path, look=False)
    return float((a * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1).mean() / 255.0)
