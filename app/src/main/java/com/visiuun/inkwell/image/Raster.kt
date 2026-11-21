package com.visiuun.inkwell.image

import kotlin.math.ceil
import kotlin.math.sqrt

/** From the photo drawn large to the panel's own pixels, with the sliders applied. */
internal object Raster {
    class Reduced(
        /** Interleaved r, g, b in 0..255, one triple per panel pixel. */
        val rgb: FloatArray,
        /** Average brightness (0..1) of the picture before the sliders. */
        val meanLuma: Float,
    )

    /**
     * How many times the panel's size the photo should be drawn at. The canvas only looks at
     * four source pixels per pixel it draws, so shrinking by more than half in one go skips
     * detail and makes edges crawl; drawing larger and averaging the rest away avoids that.
     */
    fun oversample(scale: Float): Int = ceil(0.5f / scale.coerceAtLeast(0.01f)).toInt().coerceIn(2, 4)

    /**
     * @param raw ARGB pixels of the photo drawn at [k] times the panel's size.
     *
     * For most textures a panel pixel is the plain average of its k x k samples. For
     * [Dither.SMART2] samples that stand out from their surroundings count for more, so a line
     * thinner than a pixel stays a line instead of fading into a faint smear (after Weber et al.,
     * "Rapid, detail-preserving image downscaling", 2016).
     *
     * The contrast and colour sliders start out above zero because the classic textures wash a
     * picture out, and Smart 1 was tuned with it. Smart 2 works from the true picture, so for it
     * those starting values count as "leave it alone".
     */
    fun reduce(raw: IntArray, width: Int, height: Int, k: Int, look: Look): Reduced {
        val n = width * height
        val smart = look.dither == Dither.SMART2
        val samples = (k * k).toFloat()
        val mean = FloatArray(n * 3)
        var lumaSum = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in 0 until k) {
                val row = (y * k + dy) * width * k + x * k
                for (dx in 0 until k) {
                    val c = raw[row + dx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
            }
            val i = (y * width + x) * 3
            mean[i] = r / samples
            mean[i + 1] = g / samples
            mean[i + 2] = b / samples
            lumaSum += 0.299f * mean[i] + 0.587f * mean[i + 1] + 0.114f * mean[i + 2]
        }

        val rgb = if (smart) keepDetail(raw, mean, width, height, k) else mean

        val c = if (smart) (look.contrast - Look.DEFAULT_CONTRAST).coerceAtLeast(-1f) else look.contrast
        val s = if (smart) (look.saturation - Look.DEFAULT_SATURATION).coerceAtLeast(-1f) else look.saturation
        val contrast = if (c >= 0f) 1f + c * 1.5f else 1f + c * 0.8f
        val saturation = if (s >= 0f) 1f + s * 1.5f else 1f + s
        val lift = look.brightness * 110f
        for (i in 0 until n) {
            val j = i * 3
            val luma = 0.299f * rgb[j] + 0.587f * rgb[j + 1] + 0.114f * rgb[j + 2]
            for (ch in 0..2) {
                val v = luma + (rgb[j + ch] - luma) * saturation
                rgb[j + ch] = ((v - 128f) * contrast + 128f + lift).coerceIn(0f, 255f)
            }
        }
        return Reduced(rgb, (lumaSum / n / 255.0).toFloat())
    }

    private fun keepDetail(raw: IntArray, mean: FloatArray, width: Int, height: Int, k: Int): FloatArray {
        // What each pixel's neighbourhood looks like: the averages, smoothed 1-2-1 both ways.
        val tmp = FloatArray(mean.size)
        val around = FloatArray(mean.size)
        for (y in 0 until height) for (x in 0 until width) for (ch in 0..2) {
            val l = mean[(y * width + (x - 1).coerceAtLeast(0)) * 3 + ch]
            val m = mean[(y * width + x) * 3 + ch]
            val r = mean[(y * width + (x + 1).coerceAtMost(width - 1)) * 3 + ch]
            tmp[(y * width + x) * 3 + ch] = (l + 2 * m + r) / 4
        }
        for (y in 0 until height) for (x in 0 until width) for (ch in 0..2) {
            val u = tmp[((y - 1).coerceAtLeast(0) * width + x) * 3 + ch]
            val m = tmp[(y * width + x) * 3 + ch]
            val d = tmp[((y + 1).coerceAtMost(height - 1) * width + x) * 3 + ch]
            around[(y * width + x) * 3 + ch] = (u + 2 * m + d) / 4
        }
        val out = FloatArray(mean.size)
        val far = 255f * sqrt(3f)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 3
            var r = 0f
            var g = 0f
            var b = 0f
            var total = 0f
            for (dy in 0 until k) {
                val row = (y * k + dy) * width * k + x * k
                for (dx in 0 until k) {
                    val c = raw[row + dx]
                    val cr = ((c shr 16) and 0xFF).toFloat()
                    val cg = ((c shr 8) and 0xFF).toFloat()
                    val cb = (c and 0xFF).toFloat()
                    val er = cr - around[i]
                    val eg = cg - around[i + 1]
                    val eb = cb - around[i + 2]
                    val w = sqrt(er * er + eg * eg + eb * eb) / far + 1e-3f
                    r += w * cr; g += w * cg; b += w * cb
                    total += w
                }
            }
            out[i] = r / total
            out[i + 1] = g / total
            out[i + 2] = b / total
        }
        return out
    }
}
