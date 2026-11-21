package com.visiuun.inkwell.image

import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Smart 2: the faithful one. Where the other textures push rounding errors along and hope, this one
 * works out the picture against the panel itself (see [Panel]) and decides where every pixel goes:
 *
 *  1. Tone. The picture is sharpened a touch, brightened a touch (dot patterns read darker than
 *     the light they reflect) and, if it is dark overall, lifted, by brightness alone so strong
 *     colours stay strong.
 *  2. Colour. Every pixel gets the mix of the panel's real pigments that comes closest to it,
 *     giving up a little accuracy where that buys a quieter pattern. Colours the pigments cannot
 *     make (blue, green, purple) land on the nearest one they can. Ink that would only show up as
 *     a few stray dots is dropped.
 *  3. Placement. Starting from an ordinary diffusion, pixels are changed and swapped for as long
 *     as that brings the picture, as the eye blurs it, closer to the mixes worked out above.
 *     Brightness is judged through a narrow blur and colour through a wide one, so lines and
 *     edges are drawn with the pixels that sit exactly on them while flat areas settle into an
 *     even weave.
 *
 * The numbers below were tuned on test pictures in a simulation of the panel, against five
 * scores: colour error, lightness error, structural similarity, agreement of edges, and grain in
 * areas the original has flat.
 */
internal object Smart2 {
    /** Brightness exponent applied to every picture; below 1 brightens. */
    private const val BRIGHTEN = 0.87

    /** Pictures darker on average than this are lifted towards it... */
    private const val EXPOSE_TARGET = 0.4

    /** ...by a further exponent no smaller than this, and no pixel by more than [EXPOSE_CAP] times. */
    private const val EXPOSE_MIN = 0.8
    private const val EXPOSE_CAP = 3.0

    private const val SHARPEN = 0.6f

    /** Shares of ink below about twice this are faded out: they would only be stray dots. */
    private const val CLEAN = 0.03f
    private const val SIGMA_LIGHT = 1.0
    private const val SIGMA_COLOUR = 1.6
    private const val COLOUR_WEIGHT = 0.4f
    private const val GAIN_FLOOR = 0.04
    private const val GAIN_POWER = 2.0 / 3.0
    private const val PASSES = 8

    /** An ink may only be placed on a pixel whose colour holds at least this share of it. */
    private const val PRESENT = 0.004f

    private const val LUT = 2048

    /** Brightness, red against green, yellow against blue. */
    private fun opponent(c: DoubleArray) = floatArrayOf(
        (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]).toFloat(),
        (c[0] - c[1]).toFloat(),
        (0.5 * (c[0] + c[1]) - c[2]).toFloat(),
    )

    private fun gaussian(sigma: Double, radius: Int): FloatArray {
        val k = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)).toFloat() }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Separable blur of one channel of an interleaved image, edges repeated. */
    private fun blur(src: FloatArray, width: Int, height: Int, stride: Int, offset: Int, sigma: Double): FloatArray {
        val radius = ceil(sigma * 4).toInt()
        val k = gaussian(sigma, radius)
        val tmp = FloatArray(width * height)
        val out = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var acc = 0f
            for (d in -radius..radius) acc += k[d + radius] * src[(y * width + (x + d).coerceIn(0, width - 1)) * stride + offset]
            tmp[y * width + x] = acc
        }
        for (y in 0 until height) for (x in 0 until width) {
            var acc = 0f
            for (d in -radius..radius) acc += k[d + radius] * tmp[(y + d).coerceIn(0, height - 1) * width + x]
            out[y * width + x] = acc
        }
        return out
    }

    /**
     * @param rgb interleaved r, g, b in 0..255; left untouched.
     * @param meanLuma average brightness (0..1) of the picture before the user's sliders, or NaN
     *   to measure [rgb] itself. It decides how much a dark picture is lifted.
     * @param refine false stops after the diffusion, for a quick preview.
     * @param keepGoing polled now and then; returning false abandons the work with a
     *   [CancellationException].
     */
    fun quantize(
        rgb: FloatArray,
        width: Int,
        height: Int,
        yellow: Boolean,
        red: Boolean,
        meanLuma: Float = Float.NaN,
        refine: Boolean = true,
        keepGoing: () -> Boolean = { true },
    ): ByteArray {
        val n = width * height
        val allowed = buildList {
            add(Ink.BLACK.code); add(Ink.WHITE.code)
            if (yellow) add(Ink.YELLOW.code)
            if (red) add(Ink.RED.code)
        }.toIntArray()
        val gamut = Panel.gamut(yellow, red)

        // ---- 1. tone -------------------------------------------------------------------------
        var mean = meanLuma
        if (mean.isNaN()) {
            var acc = 0.0
            for (i in 0 until n) acc += 0.299 * rgb[i * 3] + 0.587 * rgb[i * 3 + 1] + 0.114 * rgb[i * 3 + 2]
            mean = (acc / n / 255.0).toFloat()
        }
        val lift = BRIGHTEN * if (mean > 0f && mean < EXPOSE_TARGET) max(EXPOSE_MIN, ln(EXPOSE_TARGET) / ln(mean.toDouble())) else 1.0
        // The lift is a factor that depends on a pixel's brightness alone, so a strong colour
        // stays strong instead of going pale: by[brightness] is that factor.
        val by = FloatArray(LUT + 1) { min(max(it.toDouble() / LUT, 1e-4).pow(lift - 1.0), EXPOSE_CAP).toFloat() }
        // From an sRGB value to the space in which shares of ink add up.
        val mix = FloatArray(LUT + 1) { (it.toDouble() / LUT).pow(Panel.MIX).toFloat() }
        fun mixed(v: Float): Float {
            val f = v.coerceIn(0f, 1f) * LUT
            val i = min(f.toInt(), LUT - 1)
            return mix[i] + (mix[i + 1] - mix[i]) * (f - i)
        }

        val soft = arrayOf(
            blur(rgb, width, height, 3, 0, 1.0),
            blur(rgb, width, height, 3, 1, 1.0),
            blur(rgb, width, height, 3, 2, 1.0),
        )

        // ---- 2. colour: the share of every ink, per pixel ------------------------------------
        val share = FloatArray(n * 4)
        // Bit k set: ink k belongs to this pixel's colour. Keeps yellow out of greys and so on.
        val wanted = ByteArray(n)
        val w4 = FloatArray(4)
        for (i in 0 until n) {
            var r = ((rgb[i * 3] + SHARPEN * (rgb[i * 3] - soft[0][i])) / 255f).coerceIn(0f, 1f)
            var g = ((rgb[i * 3 + 1] + SHARPEN * (rgb[i * 3 + 1] - soft[1][i])) / 255f).coerceIn(0f, 1f)
            var b = ((rgb[i * 3 + 2] + SHARPEN * (rgb[i * 3 + 2] - soft[2][i])) / 255f).coerceIn(0f, 1f)
            val f = (0.299f * r + 0.587f * g + 0.114f * b) * LUT
            val at = min(f.toInt(), LUT - 1)
            val factor = by[at] + (by[at + 1] - by[at]) * (f - at)
            r *= factor; g *= factor; b *= factor

            gamut.shares(mixed(r), mixed(g), mixed(b), w4)

            val sum = max(w4[0] + w4[1] + w4[2] + w4[3], 1e-6f)
            var top = 0
            var lost = 0f
            for (k in 0..3) {
                val w = w4[k] / sum
                val x = (w / (2 * CLEAN)).coerceIn(0f, 1f)
                val kept = w * (x * x * (3 - 2 * x))
                lost += w - kept
                w4[k] = kept
                if (kept > w4[top]) top = k
            }
            w4[top] += lost
            var bits = 0
            for (k in 0..3) {
                share[i * 4 + k] = w4[k]
                if (w4[k] > PRESENT) bits = bits or (1 shl k)
            }
            wanted[i] = bits.toByte()
        }
        if (!keepGoing()) throw CancellationException()

        // ---- 3. placement: start from Floyd-Steinberg ... ----------------------------------------
        val out = ByteArray(n)
        diffuse(share, width, height, allowed, wanted, out)
        if (!refine) return out

        // ... then search. Everything is measured as brightness and two colour differences of
        // the light the pigments really reflect.
        val ink = Array(4) { opponent(Panel.seen[it]) }
        val aim = FloatArray(n * 3)
        for (i in 0 until n) for (c in 0..2) {
            aim[i * 3 + c] = share[i * 4 + 1] * ink[1][c] + share[i * 4 + 2] * ink[2][c] + share[i * 4 + 3] * ink[3][c]
        }
        // The eye is far pickier in the dark: weigh errors by how dark the surroundings are.
        val gain = blur(aim, width, height, 3, 0, SIGMA_LIGHT)
        var lowest = Float.MAX_VALUE
        for (i in 0 until n) {
            gain[i] = (1.0 / (max(gain[i], 0f) + GAIN_FLOOR).pow(GAIN_POWER)).toFloat()
            lowest = min(lowest, gain[i])
        }
        for (i in 0 until n) gain[i] /= lowest

        search(out, aim, gain, width, height, allowed, wanted, ink, keepGoing)
        return out
    }

    /** Serpentine Floyd-Steinberg in the space where shares of ink add up. */
    private fun diffuse(share: FloatArray, width: Int, height: Int, allowed: IntArray, wanted: ByteArray, out: ByteArray) {
        val corner = Array(4) { k -> FloatArray(3) { Panel.mixed[k][it].toFloat() } }
        val buf = FloatArray(width * height * 3)
        for (i in 0 until width * height) for (c in 0..2) {
            buf[i * 3 + c] = share[i * 4 + 1] * corner[1][c] + share[i * 4 + 2] * corner[2][c] + share[i * 4 + 3] * corner[3][c]
        }
        for (y in 0 until height) {
            val forward = y and 1 == 0
            var x = if (forward) 0 else width - 1
            val step = if (forward) 1 else -1
            while (x in 0 until width) {
                val i = (y * width + x) * 3
                val r = buf[i].coerceIn(-0.25f, 1.25f)
                val g = buf[i + 1].coerceIn(-0.25f, 1.25f)
                val b = buf[i + 2].coerceIn(-0.25f, 1.25f)
                var best = allowed[0]
                var bestD = Float.MAX_VALUE
                val bits = wanted[y * width + x].toInt()
                for (code in allowed) {
                    if (bits and (1 shl code) == 0) continue
                    val c = corner[code]
                    val dr = r - c[0]
                    val dg = g - c[1]
                    val db = b - c[2]
                    val d = 2f * dr * dr + 4f * dg * dg + db * db
                    if (d < bestD) { bestD = d; best = code }
                }
                out[y * width + x] = best.toByte()
                val c = corner[best]
                val er = (r - c[0]) / 16f
                val eg = (g - c[1]) / 16f
                val eb = (b - c[2]) / 16f
                spread(buf, width, height, x + step, y, er * 7f, eg * 7f, eb * 7f)
                spread(buf, width, height, x - step, y + 1, er * 3f, eg * 3f, eb * 3f)
                spread(buf, width, height, x, y + 1, er * 5f, eg * 5f, eb * 5f)
                spread(buf, width, height, x + step, y + 1, er, eg, eb)
                x += step
            }
        }
    }

    private fun spread(buf: FloatArray, width: Int, height: Int, x: Int, y: Int, r: Float, g: Float, b: Float) {
        if (x < 0 || x >= width || y >= height) return
        val j = (y * width + x) * 3
        buf[j] += r
        buf[j + 1] += g
        buf[j + 2] += b
    }

    /**
     * Direct binary search. The cost is the squared error after blurring, summed over the picture;
     * [seen] holds, for every pixel and channel, how much the cost reacts to a change there, so a
     * trial is a handful of multiplications and only an accepted change touches its surroundings.
     */
    private fun search(
        out: ByteArray,
        aim: FloatArray,
        gain: FloatArray,
        width: Int,
        height: Int,
        allowed: IntArray,
        wanted: ByteArray,
        ink: Array<FloatArray>,
        keepGoing: () -> Boolean,
    ) {
        val n = width * height
        // The blur applied twice, which is what the squared, blurred error works out to.
        val sigmas = doubleArrayOf(SIGMA_LIGHT, SIGMA_COLOUR, SIGMA_COLOUR)
        val radius = IntArray(3) { min(16, ceil(sigmas[it] * sqrt(2.0) * 2.6).toInt()) }
        val kernel = Array(3) { c ->
            val line = gaussian(sigmas[c] * sqrt(2.0), radius[c])
            val size = 2 * radius[c] + 1
            FloatArray(size * size) { line[it / size] * line[it % size] }
        }
        val centre = FloatArray(3) { kernel[it][(2 * radius[it] + 1) * radius[it] + radius[it]] }
        val weight = floatArrayOf(1f, COLOUR_WEIGHT, COLOUR_WEIGHT)

        val seen = FloatArray(n * 3)
        val err = FloatArray(n)
        for (c in 0..2) {
            for (i in 0 until n) err[i] = gain[i] * (ink[out[i].toInt()][c] - aim[i * 3 + c])
            val rad = radius[c]
            val size = 2 * rad + 1
            val k = kernel[c]
            for (y in 0 until height) {
                val y0 = max(0, y - rad)
                val y1 = min(height - 1, y + rad)
                for (x in 0 until width) {
                    val x0 = max(0, x - rad)
                    val x1 = min(width - 1, x + rad)
                    var acc = 0f
                    for (yy in y0..y1) {
                        val row = (yy - y + rad) * size - x + rad
                        val base = yy * width
                        for (xx in x0..x1) acc += k[row + xx] * err[base + xx]
                    }
                    seen[(y * width + x) * 3 + c] = acc
                }
            }
            if (!keepGoing()) throw CancellationException()
        }

        fun apply(x: Int, y: Int, from: Int, to: Int) {
            val s = gain[y * width + x]
            for (c in 0..2) {
                val a = s * (ink[to][c] - ink[from][c])
                if (a == 0f) continue
                val rad = radius[c]
                val size = 2 * rad + 1
                val k = kernel[c]
                val y0 = max(0, y - rad)
                val y1 = min(height - 1, y + rad)
                val x0 = max(0, x - rad)
                val x1 = min(width - 1, x + rad)
                for (yy in y0..y1) {
                    val row = (yy - y + rad) * size - x + rad
                    var j = (yy * width + x0) * 3 + c
                    for (xx in x0..x1) {
                        seen[j] += a * k[row + xx]
                        j += 3
                    }
                }
            }
            out[y * width + x] = to.toByte()
        }

        val stopBelow = n / 2000
        for (pass in 0 until PASSES) {
            var changed = 0
            for (y in 0 until height) {
                if (!keepGoing()) throw CancellationException()
                for (x in 0 until width) {
                    val i = y * width + x
                    val k0 = out[i].toInt()
                    val s0 = gain[i]
                    val bits = wanted[i].toInt()
                    var best = -1e-6f
                    var bestInk = -1
                    var bestX = 0
                    var bestY = 0
                    var swap = false
                    // Would another ink here be better?
                    for (k1 in allowed) {
                        if (k1 == k0 || bits and (1 shl k1) == 0) continue
                        var d = 0f
                        for (c in 0..2) {
                            val a = s0 * (ink[k1][c] - ink[k0][c])
                            d += weight[c] * (2f * a * seen[i * 3 + c] + a * a * centre[c])
                        }
                        if (d < best) { best = d; bestInk = k1; swap = false }
                    }
                    // Or trading places with a neighbour?
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= height) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if ((dx == 0 && dy == 0) || xx < 0 || xx >= width) continue
                            val j = yy * width + xx
                            val k1 = out[j].toInt()
                            if (k1 == k0 || bits and (1 shl k1) == 0 || wanted[j].toInt() and (1 shl k0) == 0) continue
                            val s1 = gain[j]
                            var d = 0f
                            for (c in 0..2) {
                                val dv = ink[k1][c] - ink[k0][c]
                                val a0 = s0 * dv
                                val a1 = -s1 * dv
                                val size = 2 * radius[c] + 1
                                val cross = kernel[c][(dy + radius[c]) * size + dx + radius[c]]
                                d += weight[c] * (2f * a0 * seen[i * 3 + c] + 2f * a1 * seen[j * 3 + c] +
                                    (a0 * a0 + a1 * a1) * centre[c] + 2f * a0 * a1 * cross)
                            }
                            if (d < best) { best = d; bestInk = k1; bestX = xx; bestY = yy; swap = true }
                        }
                    }
                    if (bestInk < 0) continue
                    changed++
                    apply(x, y, k0, bestInk)
                    if (swap) apply(bestX, bestY, bestInk, k0)
                }
            }
            if (changed < stopBelow) break
        }
    }
}
