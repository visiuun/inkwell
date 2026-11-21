"""The panel as its datasheet describes it, and a simulation of how a picture on it looks.

Source: the specification of the 3.7 inch 416 x 240, 130 dpi four-colour panel (20-29 C column):
white L* 63 (a* <= 0, b* <= 2.5), black L* 9 (a* <= 8), red L* >= 24 with a* 40 (b* <= 34),
yellow L* >= 54 with b* 66 (a* <= 18). Where the sheet gives a limit instead of a typical value
a value a little inside the limit is used.
"""
import itertools

import numpy as np
from scipy.ndimage import gaussian_filter

LAB = {  # black, white, yellow, red in the app's ink-code order
    0: (9.0, 3.0, -2.0),
    1: (63.0, -1.0, 1.5),
    2: (57.0, 9.0, 66.0),
    3: (26.0, 40.0, 28.0),
}
D50 = np.array([0.96422, 1.0, 0.82521])
D65 = np.array([0.95047, 1.0, 1.08883])
XYZ_TO_RGB = np.array([[3.2404542, -1.5371385, -0.4985314], [-0.9692660, 1.8760108, 0.0415560], [0.0556434, -0.2040259, 1.0572252]])
LUMA = np.array([0.2126, 0.7152, 0.0722])


def lab_to_xyz(lab):
    L, a, b = lab
    fy = (L + 16) / 116
    fx, fz = fy + a / 500, fy - b / 200
    f = np.array([fx, fy, fz])
    return np.where(f > 6 / 29, f ** 3, 3 * (6 / 29) ** 2 * (f - 4 / 29)) * D50


def panel_inks():
    """Linear RGB of the four pigments with the panel's white as (1,1,1) and its black as (0,0,0)."""
    xyz = {k: lab_to_xyz(v) for k, v in LAB.items()}
    rgb = {k: XYZ_TO_RGB @ (xyz[k] / xyz[1] * D65) for k in xyz}
    out = np.zeros((4, 3))
    for k in range(4):
        out[k] = (rgb[k] - rgb[0]) / (rgb[1] - rgb[0])
    return np.clip(out, 0.004, 1.0) * (np.arange(4) != 0)[:, None]


REAL = panel_inks()
CONTRAST = lab_to_xyz(LAB[1])[1] / lab_to_xyz(LAB[0])[1]


def oklab(lin):
    lin = np.maximum(lin, 0)
    l = 0.4122214708 * lin[..., 0] + 0.5363325363 * lin[..., 1] + 0.0514459929 * lin[..., 2]
    m = 0.2119034982 * lin[..., 0] + 0.6806995451 * lin[..., 1] + 0.1073969566 * lin[..., 2]
    s = 0.0883024619 * lin[..., 0] + 0.2817188376 * lin[..., 1] + 0.6299787005 * lin[..., 2]
    l, m, s = np.cbrt(l), np.cbrt(m), np.cbrt(s)
    return np.stack([
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s], -1)


def blur(a, sigma):
    return np.stack([gaussian_filter(a[..., c], sigma, mode="nearest") for c in range(a.shape[-1])], -1)


def seen(ink, sigma=1.2, n=1.47):
    """Linear RGB of the panel as the eye sees it. Neighbouring dots darken each other a little
    (Yule-Nielsen with exponent n; n = 1 would be plain averaging of light)."""
    m = REAL[ink] ** (1.0 / n)
    return blur(m, sigma) ** n


OPP = np.array([[0.2126, 0.7152, 0.0722], [1.0, -1.0, 0.0], [0.5, 0.5, -1.0]])


class Gamut:
    """Everything the allowed inks can mix, in the space where shares of ink add up (colour^(1/n)).

    shares() returns, per pixel, the mix that is closest to the wanted colour. With calm > 0 it
    also counts how loud the mix would look as a pattern (the spread of lightness among its dots:
    black next to white is loud, yellow next to white is nearly silent) and gives up a little
    accuracy for a quieter mix.
    """

    def __init__(self, allowed, n, w_light=1.0, w_colour=1.0, calm=0.0, chroma=1.0):
        self.allowed = list(allowed)
        self.n = n
        self.calm = calm
        self.v = REAL[self.allowed] ** (1.0 / n)                   # vertices in mixing space
        self.metric = np.diag([w_light, w_colour, w_colour]) @ OPP   # mixing space -> where distance is judged
        self.pv = self.v @ self.metric.T
        # where each ink sits for the eye: lightness, and its colour counted `chroma` times over,
        # so that a coloured dot among greys is as loud as it looks
        self.look = oklab(REAL[self.allowed]) * np.array([1.0, chroma, chroma])
        self.subsets = []
        self._tables = {}
        k = len(self.allowed)
        for size in range(1, k + 1):
            for idx in itertools.combinations(range(k), size):
                base = self.pv[idx[0]]
                edges = np.array([self.pv[i] - base for i in idx[1:]]).reshape(-1, 3)
                q0 = self.look[idx[0]]
                qa = np.array([self.look[i] - q0 for i in idx[1:]]).reshape(-1, 3)
                pull = np.array([self.look[i] @ self.look[i] - q0 @ q0 - 2 * q0 @ (self.look[i] - q0) for i in idx[1:]])
                if len(edges):
                    m = edges @ edges.T - calm * qa @ qa.T
                    if abs(np.linalg.det(m)) < 1e-9:
                        continue
                    minv0 = np.linalg.inv(edges @ edges.T)
                    minv = np.linalg.inv(m)
                    shift = calm * pull / 2
                else:
                    minv0, minv, shift = np.zeros((0, 0)), np.zeros((0, 0)), np.zeros(0)
                self.subsets.append((idx, base, edges, minv0, minv, shift, qa, pull))

    def _solve(self, p, calm, within=None):
        best = np.full(len(p), np.inf)
        out = np.zeros((len(p), len(self.allowed)))
        for idx, base, edges, minv0, minv, shift, qa, pull in self.subsets:
            d = p - base
            if len(edges):
                u = (d @ edges.T - (shift if calm else 0)) @ (minv if calm else minv0).T
                resid = d - u @ edges
                cost = (resid ** 2).sum(1)
                if calm:
                    cost = cost + self.calm * (u @ pull - ((u @ qa) ** 2).sum(1))
                w0 = 1 - u.sum(1)
                ok = (w0 >= -1e-6) & (u >= -1e-6).all(1)
            else:
                u = np.zeros((len(p), 0)); w0 = np.ones(len(p))
                cost = (d ** 2).sum(1)
                ok = np.ones(len(p), bool)
            if within is not None:
                ok &= within[:, list(idx)].all(1)
            take = ok & (cost < best - 1e-12)
            best[take] = cost[take]
            out[take] = 0
            out[take, idx[0]] = w0[take]
            for j, i in enumerate(idx[1:]):
                out[take, i] = u[take, j]
        return np.clip(out, 0, 1)

    def shares(self, m):
        """Best mix for every pixel of m (h, w, 3), as shares of the allowed inks.

        First the closest mix. Then, if calm is on, the quietest acceptable mix made only of inks
        the closest one already uses: calming may thin an ink out or drop it, never add one, so
        greys stay grey."""
        p = m.reshape(-1, 3) @ self.metric.T
        out = self._solve(p, False)
        if self.calm > 0:
            out = self._solve(p, True, within=out > 1e-6)
        return out.reshape(m.shape[:-1] + (len(self.allowed),))

    def table(self, size):
        """shares() on a size^3 grid of mixing-space colours, for fast lookup."""
        if size not in self._tables:
            ax = np.linspace(0, 1, size)
            grid = np.stack(np.meshgrid(ax, ax, ax, indexing="ij"), -1)
            self._tables[size] = self.shares(grid)
        return self._tables[size]

    def shares_fast(self, m, size=33):
        """shares() by interpolation in the table: what the app does. Each grid cube is cut into
        six tetrahedra around its grey diagonal, so a grey only ever blends between greys."""
        t = self.table(size)
        f = np.clip(m, 0, 1) * (size - 1)
        i = np.minimum(f.astype(np.int64), size - 2)
        d = f - i
        order = np.argsort(-d, axis=-1)                       # axes from largest fraction to smallest
        ds = np.take_along_axis(d, order, -1)
        pos = i.copy()
        out = (1 - ds[..., 0])[..., None] * t[pos[..., 0], pos[..., 1], pos[..., 2]]
        for step in range(3):
            np.put_along_axis(pos, order[..., step:step + 1], np.take_along_axis(pos, order[..., step:step + 1], -1) + 1, -1)
            w = ds[..., step] - (ds[..., step + 1] if step < 2 else 0)
            out = out + w[..., None] * t[pos[..., 0], pos[..., 1], pos[..., 2]]
        return out

    def colour(self, shares):
        """Linear RGB a given mix of the allowed inks looks like."""
        return np.maximum(shares @ self.v, 0) ** self.n


if __name__ == "__main__":
    np.set_printoptions(precision=3, suppress=True)
    print("inks, linear RGB relative to the panel's white and black:\n", REAL)
    print("relative luminance:", REAL @ LUMA, " contrast %.1f:1" % CONTRAST)
    g = Gamut([0, 1, 2, 3], 1.47)
    tests = {"red": (1, 0, 0), "yellow": (1, 1, 0), "blue": (0, 0, 1), "green": (0, 1, 0), "skin": (1.0, 0.75, 0.6), "pink": (1.0, 0.4, 0.6), "grey": (0.5, 0.5, 0.5)}
    for name, c in tests.items():
        m = np.array(c, float) ** (1 / 1.47)
        print(f"{name:7s} black/white/yellow/red shares", g.shares(m[None, None])[0, 0])
