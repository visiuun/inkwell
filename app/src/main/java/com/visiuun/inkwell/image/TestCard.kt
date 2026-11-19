package com.visiuun.inkwell.image

/**
 * A flat, lopsided picture for checking that pixels land where they should: a red bar along the
 * top, yellow down the left, black along the right and bottom, a letter F, one swatch of every
 * ink, a slanted line and a ruler. Any flip, mirror or scrambled scan order shows at a glance.
 */
object TestCard {
    fun render(width: Int, height: Int): InkImage {
        val px = ByteArray(width * height) { Ink.WHITE.code.toByte() }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, ink: Ink) {
            for (y in y0.coerceIn(0, height) until y1.coerceIn(0, height)) {
                for (x in x0.coerceIn(0, width) until x1.coerceIn(0, width)) px[y * width + x] = ink.code.toByte()
            }
        }
        val u = width / 10
        val edge = u / 2
        rect(0, 0, width, edge, Ink.RED)
        rect(0, height - edge, width, height, Ink.BLACK)
        rect(0, edge, edge, height - edge, Ink.YELLOW)
        rect(width - edge, edge, width, height - edge, Ink.BLACK)

        // The letter F, which reads wrong under every flip and rotation.
        rect(2 * u, 2 * u, 3 * u, 8 * u, Ink.BLACK)
        rect(2 * u, 2 * u, 7 * u, 3 * u, Ink.BLACK)
        rect(2 * u, 9 * u / 2, 6 * u, 11 * u / 2, Ink.BLACK)

        // One swatch per ink; the white one gets an outline.
        val top = 10 * u
        val inks = listOf(Ink.BLACK, Ink.WHITE, Ink.YELLOW, Ink.RED)
        inks.forEachIndexed { i, ink ->
            val x = u + i * 2 * u
            rect(x, top, x + 3 * u / 2, top + 2 * u, Ink.BLACK)
            rect(x + 2, top + 2, x + 3 * u / 2 - 2, top + 2 * u - 2, ink)
        }

        // A slanted line: a wrong row length turns it into a staircase or scatters it.
        val x0 = 2 * u
        val x1 = 8 * u
        val y0 = 13 * u
        val y1 = minOf(16 * u, height - 2 * u)
        for (x in x0 until x1) {
            val y = y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            rect(x, y, x + 1, y + 3, Ink.RED)
        }

        // Ruler: a tick every tenth of the width, standing on the bottom bar.
        for (i in 1 until 10) rect(i * u, height - edge - u / 2, i * u + 2, height - edge, Ink.BLACK)
        return InkImage(width, height, px)
    }
}
