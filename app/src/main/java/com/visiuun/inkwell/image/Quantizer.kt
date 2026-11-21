package com.visiuun.inkwell.image

/** Turns full-colour pixels into the handful of inks the panel has. */
object Quantizer {
    private class Kernel(val divisor: Float, val taps: Array<IntArray>)

    // Each tap is (dx, dy, weight), for a left-to-right pass.
    private val FLOYD = Kernel(16f, arrayOf(intArrayOf(1, 0, 7), intArrayOf(-1, 1, 3), intArrayOf(0, 1, 5), intArrayOf(1, 1, 1)))
    private val ATKINSON = Kernel(
        8f,
        arrayOf(intArrayOf(1, 0, 1), intArrayOf(2, 0, 1), intArrayOf(-1, 1, 1), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1), intArrayOf(0, 2, 1)),
    )

    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * @param rgb interleaved r, g, b in 0..255, row by row; it is used as scratch space.
     * @param inks the pigments allowed in the result (black and white are always worth including).
     * @param meanLuma for the Smart textures: the picture's average brightness (0..1) before the
     *   sliders, or NaN to measure [rgb].
     * @param refine for the Smart textures: false gives a quick first version.
     * @param keepGoing for the Smart textures: return false to abandon the work (it then throws a
     *   CancellationException).
     */
    fun quantize(
        rgb: FloatArray,
        width: Int,
        height: Int,
        inks: List<Ink>,
        dither: Dither,
        meanLuma: Float = Float.NaN,
        refine: Boolean = true,
        keepGoing: () -> Boolean = { true },
    ): InkImage {
        require(rgb.size == width * height * 3 && inks.isNotEmpty())
        // Smart mixes around black and white; without both there is nothing for it to work with.
        if (dither.smart && Ink.BLACK in inks && Ink.WHITE in inks) {
            val yellow = Ink.YELLOW in inks
            val red = Ink.RED in inks
            val pixels = if (dither == Dither.SMART1) {
                Smart1.quantize(rgb, width, height, yellow, red, meanLuma, refine, keepGoing)
            } else {
                Smart2.quantize(rgb, width, height, yellow, red, meanLuma, refine, keepGoing)
            }
            return InkImage(width, height, pixels)
        }
        val out = ByteArray(width * height)
        when (dither) {
            Dither.FLAT -> for (i in out.indices) out[i] = nearest(inks, rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]).code.toByte()
            Dither.PATTERN -> for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                // Spread of roughly half the distance between neighbouring inks.
                val t = (BAYER8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f - 0.5f
                val bias = t * 150f
                out[i] = nearest(inks, rgb[i * 3] + bias, rgb[i * 3 + 1] + bias, rgb[i * 3 + 2] + bias).code.toByte()
            }
            Dither.SMOOTH, Dither.SMART1, Dither.SMART2 -> diffuse(rgb, width, height, inks, FLOYD, out)
            Dither.CRISP -> diffuse(rgb, width, height, inks, ATKINSON, out)
        }
        return InkImage(width, height, out)
    }

    /** Serpentine error diffusion: each pixel's rounding error is pushed onto its unvisited neighbours. */
    private fun diffuse(rgb: FloatArray, width: Int, height: Int, inks: List<Ink>, kernel: Kernel, out: ByteArray) {
        for (y in 0 until height) {
            val forward = y and 1 == 0
            var x = if (forward) 0 else width - 1
            val step = if (forward) 1 else -1
            while (x in 0 until width) {
                val i = (y * width + x) * 3
                val r = rgb[i].coerceIn(-64f, 319f)
                val g = rgb[i + 1].coerceIn(-64f, 319f)
                val b = rgb[i + 2].coerceIn(-64f, 319f)
                val ink = nearest(inks, r, g, b)
                out[y * width + x] = ink.code.toByte()
                val er = (r - ink.r) / kernel.divisor
                val eg = (g - ink.g) / kernel.divisor
                val eb = (b - ink.b) / kernel.divisor
                for (tap in kernel.taps) {
                    val nx = x + tap[0] * step
                    val ny = y + tap[1]
                    if (nx < 0 || nx >= width || ny >= height) continue
                    val j = (ny * width + nx) * 3
                    rgb[j] += er * tap[2]
                    rgb[j + 1] += eg * tap[2]
                    rgb[j + 2] += eb * tap[2]
                }
                x += step
            }
        }
    }

    private fun nearest(inks: List<Ink>, r: Float, g: Float, b: Float): Ink {
        var best = inks[0]
        var bestD = Float.MAX_VALUE
        for (k in inks.indices) {
            val ink = inks[k]
            val dr = r - ink.r
            val dg = g - ink.g
            val db = b - ink.b
            val d = 2f * dr * dr + 4f * dg * dg + 3f * db * db
            if (d < bestD) { bestD = d; best = ink }
        }
        return best
    }
}
