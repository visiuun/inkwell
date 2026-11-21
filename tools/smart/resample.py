"""Going from the photo to the panel's 240 x 416 pixels.

sup(path, k) is the photo drawn at k times the panel's size (what the app's canvas does);
reduce(...) turns that into panel pixels. With detail = 0 that is a plain average of each k x k
block. With detail > 0 samples that differ from their surroundings count for more, so a line
thinner than a pixel is kept as a line instead of fading into a faint grey smear
(after Weber et al., "Rapid, detail-preserving image downscaling", 2016).
"""
import numpy as np
from PIL import Image
from scipy.ndimage import convolve

import core

W, H = core.W, core.H
_sup = {}


def sup(path, k=4):
    key = (path, k)
    if key not in _sup:
        im = Image.open(path).convert("RGB")
        longest = max(im.size)
        if longest > 1400:
            f = 1400 / longest
            im = im.resize((max(1, int(im.width * f)), max(1, int(im.height * f))), Image.BILINEAR)
        s = max(W / im.width, H / im.height) * k
        nw, nh = max(W * k, round(im.width * s)), max(H * k, round(im.height * s))
        im = im.resize((nw, nh), Image.BILINEAR)
        x0, y0 = (nw - W * k) // 2, (nh - H * k) // 2
        _sup[key] = np.asarray(im.crop((x0, y0, x0 + W * k, y0 + H * k)), np.float32)
    return _sup[key]


def reduce(a, k, detail=0.0):
    blocks = a.reshape(H, k, W, k, 3).transpose(0, 2, 1, 3, 4).reshape(H, W, k * k, 3)
    mean = blocks.mean(2)
    if detail <= 0:
        return mean
    kern = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], np.float32) / 16
    around = np.stack([convolve(mean[..., c], kern, mode="nearest") for c in range(3)], -1)
    dist = np.sqrt(((blocks - around[:, :, None, :]) ** 2).sum(-1)) / (255 * np.sqrt(3))
    w = dist ** detail + 1e-3
    return (blocks * w[..., None]).sum(2) / w.sum(2, keepdims=True)


def look(a, contrast=0.0, saturation=0.0, brightness=0.0):
    c = 1 + contrast * 1.5 if contrast >= 0 else 1 + contrast * 0.8
    sat = 1 + saturation * 1.5 if saturation >= 0 else 1 + saturation
    luma = (a * np.array([0.299, 0.587, 0.114], np.float32)).sum(-1, keepdims=True)
    a = luma + (a - luma) * sat
    return np.clip((a - 128) * c + 128 + brightness * 110, 0, 255).astype(np.float32)


def panel_rgb(path, k=4, detail=0.0, contrast=0.0, saturation=0.0):
    return look(reduce(sup(path, k), k, detail), contrast, saturation)
