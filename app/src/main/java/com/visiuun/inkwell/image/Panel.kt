package com.visiuun.inkwell.image

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow

/**
 * The panel as its datasheet describes it.
 *
 * These cases use a 3.7 inch, 416 x 240, 130 dpi four-pigment panel. Its specification gives the
 * colour of each pigment in CIELAB (20 to 29 degrees C): white L* 63, black L* 9, red L* >= 24
 * with a* 40, yellow L* >= 54 with b* 66. The pigments are a long way from ideal: red reflects
 * about an eighth of what the white does, and the yellow leans towards orange. Where the sheet
 * gives a limit instead of a typical value, a value a little inside the limit is used here.
 */
internal object Panel {
    /** CIELAB of each pigment, indexed by ink code (black, white, yellow, red). */
    private val LAB = arrayOf(
        doubleArrayOf(9.0, 3.0, -2.0),
        doubleArrayOf(63.0, -1.0, 1.5),
        doubleArrayOf(57.0, 9.0, 66.0),
        doubleArrayOf(26.0, 40.0, 28.0),
    )

    /**
     * Neighbouring dots darken each other, so a half-and-half pattern looks darker than half the
     * light (Yule-Nielsen). Shares of ink add up for colour^(1/N) instead of for light itself.
     * MIX is the matching exponent from an sRGB value: 2.2 / N.
     */
    const val MIX = 1.5
    const val N = 2.2 / MIX

    /** Linear RGB of each pigment with the panel's white as (1,1,1) and its black as (0,0,0). */
    val seen: Array<DoubleArray>

    /** The same in the space where shares add up. */
    val mixed: Array<DoubleArray>

    /** Where each pigment sits for the eye (Oklab), for judging how loud a mix of dots is. */
    private val looks: Array<DoubleArray>

    init {
        val d50 = doubleArrayOf(0.96422, 1.0, 0.82521)
        val d65 = doubleArrayOf(0.95047, 1.0, 1.08883)
        fun xyz(lab: DoubleArray): DoubleArray {
            val fy = (lab[0] + 16) / 116
            val f = doubleArrayOf(fy + lab[1] / 500, fy, fy - lab[2] / 200)
            return DoubleArray(3) { (if (f[it] > 6.0 / 29) f[it].pow(3) else 3 * (6.0 / 29).pow(2) * (f[it] - 4.0 / 29)) * d50[it] }
        }
        val white = xyz(LAB[1])
        fun rgb(lab: DoubleArray): DoubleArray {
            val v = xyz(lab)
            val x = v[0] / white[0] * d65[0]
            val y = v[1] / white[1] * d65[1]
            val z = v[2] / white[2] * d65[2]
            return doubleArrayOf(
                3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
            )
        }
        val black = rgb(LAB[0])
        val paper = rgb(LAB[1])
        seen = Array(4) { k ->
            val c = rgb(LAB[k])
            DoubleArray(3) { if (k == 0) 0.0 else ((c[it] - black[it]) / (paper[it] - black[it])).coerceIn(0.004, 1.0) }
        }
        mixed = Array(4) { k -> DoubleArray(3) { seen[k][it].pow(1.0 / N) } }
        looks = Array(4) { k ->
            val c = seen[k]
            val l = cbrt(0.4122214708 * c[0] + 0.5363325363 * c[1] + 0.0514459929 * c[2])
            val m = cbrt(0.2119034982 * c[0] + 0.6806995451 * c[1] + 0.1073969566 * c[2])
            val s = cbrt(0.0883024619 * c[0] + 0.2817188376 * c[1] + 0.6299787005 * c[2])
            doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }
    }

    /** Brightness, red against green, yellow against blue: the axes on which a colour is judged. */
    private fun judged(c: DoubleArray) = doubleArrayOf(
        0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2],
        c[0] - c[1],
        0.5 * (c[0] + c[1]) - c[2],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Everything a set of inks can mix, and for any wanted colour the mix that serves it best.
     *
     * First the closest mix is found. Then a little accuracy is given up where that buys a
     * quieter pattern: a mix is loud when its dots look very different from each other (black
     * next to white) and nearly silent when they do not (yellow next to white); [CALM] is how
     * much that counts. Calming may thin an ink out or drop it but never adds one, so greys stay
     * grey. The answer is worked out once on a grid and looked up from there.
     */
    class Gamut(private val codes: IntArray) {
        private val table = FloatArray(GRID * GRID * GRID * 4)

        /** A corner, edge, side or the inside of the shape the inks span. */
        private class Face(
            val idx: IntArray,
            val base: DoubleArray,
            val edges: Array<DoubleArray>,
            val exact: Array<DoubleArray>,
            val calmed: Array<DoubleArray>,
            val looksEdges: Array<DoubleArray>,
            val pull: DoubleArray,
        )

        init {
            val corner = Array(codes.size) { judged(mixed[codes[it]]) }
            val look = Array(codes.size) { looks[codes[it]] }
            val faces = ArrayList<Face>()
            for (size in 1..codes.size) for (idx in combinations(codes.size, size)) {
                val base = corner[idx[0]]
                val e = Array(size - 1) { j -> DoubleArray(3) { corner[idx[j + 1]][it] - base[it] } }
                val q0 = look[idx[0]]
                val qa = Array(size - 1) { j -> DoubleArray(3) { look[idx[j + 1]][it] - q0[it] } }
                val pull = DoubleArray(size - 1) { j -> dot(look[idx[j + 1]], look[idx[j + 1]]) - dot(q0, q0) - 2 * dot(q0, qa[j]) }
                val exact = invert(Array(size - 1) { a -> DoubleArray(size - 1) { b -> dot(e[a], e[b]) } }) ?: continue
                val calmed = invert(Array(size - 1) { a -> DoubleArray(size - 1) { b -> dot(e[a], e[b]) - CALM * dot(qa[a], qa[b]) } }) ?: continue
                faces += Face(idx, base, e, exact, calmed, qa, pull)
            }
            val shares = DoubleArray(codes.size)
            val used = BooleanArray(codes.size)
            val u = DoubleArray(3)
            val rhs = DoubleArray(3)
            val d = DoubleArray(3)

            /** Fills [shares] with the cheapest mix; with [calm], only from inks marked in [used]. */
            fun solve(p: DoubleArray, calm: Boolean) {
                var best = Double.MAX_VALUE
                for (f in faces) {
                    if (calm && f.idx.any { !used[it] }) continue
                    val k = f.edges.size
                    for (c in 0..2) d[c] = p[c] - f.base[c]
                    for (j in 0 until k) rhs[j] = dot(d, f.edges[j]) - if (calm) CALM * f.pull[j] / 2 else 0.0
                    val inverse = if (calm) f.calmed else f.exact
                    var rest = 0.0
                    var ok = true
                    for (j in 0 until k) {
                        var acc = 0.0
                        for (i in 0 until k) acc += inverse[j][i] * rhs[i]
                        u[j] = acc
                        rest += acc
                        if (acc < -1e-6) ok = false
                    }
                    if (!ok || 1 - rest < -1e-6) continue
                    var cost = 0.0
                    for (c in 0..2) {
                        var r = d[c]
                        for (j in 0 until k) r -= u[j] * f.edges[j][c]
                        cost += r * r
                    }
                    if (calm) {
                        var spread = 0.0
                        val centre = DoubleArray(3)
                        for (j in 0 until k) {
                            spread += u[j] * f.pull[j]
                            for (c in 0..2) centre[c] += u[j] * f.looksEdges[j][c]
                        }
                        cost += CALM * (spread - dot(centre, centre))
                    }
                    if (cost < best - 1e-12) {
                        best = cost
                        shares.fill(0.0)
                        shares[f.idx[0]] = 1 - rest
                        for (j in 0 until k) shares[f.idx[j + 1]] = u[j]
                    }
                }
            }

            for (ir in 0 until GRID) for (ig in 0 until GRID) for (ib in 0 until GRID) {
                val p = judged(doubleArrayOf(ir / (GRID - 1.0), ig / (GRID - 1.0), ib / (GRID - 1.0)))
                solve(p, false)
                for (i in codes.indices) used[i] = shares[i] > 1e-6
                solve(p, true)
                val at = ((ir * GRID + ig) * GRID + ib) * 4
                for (i in codes.indices) table[at + codes[i]] = shares[i].coerceIn(0.0, 1.0).toFloat()
            }
        }

        /**
         * Shares of each ink (by ink code) for a colour in mixing space, into [out]. Each grid
         * cube is cut into six tetrahedra around its grey diagonal, so a grey only ever blends
         * between greys and never picks up a tint from its neighbours.
         */
        fun shares(r: Float, g: Float, b: Float, out: FloatArray) {
            val fr = r.coerceIn(0f, 1f) * (GRID - 1)
            val fg = g.coerceIn(0f, 1f) * (GRID - 1)
            val fb = b.coerceIn(0f, 1f) * (GRID - 1)
            val ir = min(fr.toInt(), GRID - 2)
            val ig = min(fg.toInt(), GRID - 2)
            val ib = min(fb.toInt(), GRID - 2)
            val dr = fr - ir
            val dg = fg - ig
            val db = fb - ib
            // Walk from the cube's dark corner to its light one, along the largest fraction first.
            val stepR = GRID * GRID * 4
            val stepG = GRID * 4
            val stepB = 4
            val s1: Int
            val s2: Int
            val s3: Int
            val d1: Float
            val d2: Float
            val d3: Float
            if (dr >= dg) {
                if (dg >= db) { s1 = stepR; s2 = stepG; s3 = stepB; d1 = dr; d2 = dg; d3 = db }
                else if (dr >= db) { s1 = stepR; s2 = stepB; s3 = stepG; d1 = dr; d2 = db; d3 = dg }
                else { s1 = stepB; s2 = stepR; s3 = stepG; d1 = db; d2 = dr; d3 = dg }
            } else {
                if (dr >= db) { s1 = stepG; s2 = stepR; s3 = stepB; d1 = dg; d2 = dr; d3 = db }
                else if (dg >= db) { s1 = stepG; s2 = stepB; s3 = stepR; d1 = dg; d2 = db; d3 = dr }
                else { s1 = stepB; s2 = stepG; s3 = stepR; d1 = db; d2 = dg; d3 = dr }
            }
            val a0 = ((ir * GRID + ig) * GRID + ib) * 4
            val a1 = a0 + s1
            val a2 = a1 + s2
            val a3 = a2 + s3
            val w0 = 1 - d1
            val w1 = d1 - d2
            val w2 = d2 - d3
            for (k in 0..3) out[k] = w0 * table[a0 + k] + w1 * table[a1 + k] + w2 * table[a2 + k] + d3 * table[a3 + k]
        }
    }

    private const val GRID = 33
    private const val CALM = 0.1

    private val gamuts = arrayOfNulls<Gamut>(4)

    /** The gamut of black and white plus the chosen colours; built on first use. */
    fun gamut(yellow: Boolean, red: Boolean): Gamut = synchronized(gamuts) {
        val slot = (if (yellow) 1 else 0) or (if (red) 2 else 0)
        gamuts[slot] ?: Gamut(buildList {
            add(Ink.BLACK.code); add(Ink.WHITE.code)
            if (yellow) add(Ink.YELLOW.code)
            if (red) add(Ink.RED.code)
        }.toIntArray()).also { gamuts[slot] = it }
    }

    private fun combinations(n: Int, size: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun go(start: Int, picked: IntArray, depth: Int) {
            if (depth == size) { out += picked.copyOf(); return }
            for (i in start until n) { picked[depth] = i; go(i + 1, picked, depth + 1) }
        }
        go(0, IntArray(size), 0)
        return out
    }

    /** Inverse of a matrix of at most 3 x 3, or null if it has none. */
    private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = m.size
        if (n == 0) return emptyArray()
        val a = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) m[r][c] else if (c - n == r) 1.0 else 0.0 } }
        var det = 1.0
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (pivot != col) { val t = a[pivot]; a[pivot] = a[col]; a[col] = t }
            val p = a[col][col]
            det *= p
            if (abs(det) < 1e-9) return null
            for (c in 0 until 2 * n) a[col][c] /= p
            for (r in 0 until n) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (c in 0 until 2 * n) a[r][c] -= f * a[col][c]
            }
        }
        return Array(n) { r -> DoubleArray(n) { a[r][it + n] } }
    }
}
