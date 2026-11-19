package com.visiuun.inkwell.image

/** The order the pixels travel to the case in. */
enum class Scan(val label: String) {
    /** Line by line. Confirmed on a real four-colour case. */
    ROWS("Rows"),
    /** Column by column: the vendor app's other layout, kept for cases that turn out to need it. */
    COLUMNS("Columns"),
}

/**
 * Lays an upright [InkImage] out the way the case's controller scans its panel: four pixels per
 * byte, first pixel in the top two bits.
 *
 * The vendor app gets there by mirroring and rotating a bitmap and then reading it column by
 * column. Worked through, its row layout is simply the picture read backwards from the
 * bottom-right pixel to the top-left one; its column layout reads the picture column by column
 * from the bottom-right corner.
 *
 * The vendor app picks between the two from the size and layout byte the case reports. That
 * choice is wrong for at least one real case (it picks columns where the panel wants rows), so
 * the layout is a setting here, with rows as the default.
 */
object Packer {
    fun pack(image: InkImage, scan: Scan = Scan.ROWS): ByteArray {
        val w = image.width
        val h = image.height
        val rows = scan == Scan.ROWS
        val mirror = rows || w == 200
        val src = if (mirror) image.mirrored() else image
        return if (rows) {
            // Bitmap rotated a quarter turn clockwise: h columns of w pixels each.
            columns(h, w) { x, y -> src.pixels[(h - 1 - x) * w + y] }
        } else {
            // Bitmap rotated a half turn: w columns of h pixels each.
            columns(w, h) { x, y -> src.pixels[(h - 1 - y) * w + (w - 1 - x)] }
        }
    }

    private inline fun columns(columns: Int, rows: Int, pixel: (x: Int, y: Int) -> Byte): ByteArray {
        val perColumn = (rows + 3) / 4
        val out = ByteArray(columns * perColumn)
        for (x in 0 until columns) {
            for (y in 0 until rows) {
                val at = x * perColumn + y / 4
                val shift = 6 - 2 * (y and 3)
                out[at] = (out[at].toInt() or ((pixel(x, y).toInt() and 3) shl shift)).toByte()
            }
        }
        return out
    }
}
