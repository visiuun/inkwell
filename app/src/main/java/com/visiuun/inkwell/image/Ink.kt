package com.visiuun.inkwell.image

/**
 * The four pigments of the panel. [code] is the 2-bit value the case expects for a pixel,
 * [rgb] the ideal colour used for matching, [paper] roughly how the pigment looks on the panel.
 */
enum class Ink(val code: Int, val rgb: Int, val paper: Int) {
    BLACK(0, 0x000000, 0xFF1C1B1A.toInt()),
    WHITE(1, 0xFFFFFF, 0xFFE8E5DA.toInt()),
    YELLOW(2, 0xFFFF00, 0xFFE9C22E.toInt()),
    RED(3, 0xFF0000, 0xFFB7392D.toInt());

    val r get() = (rgb shr 16) and 0xFF
    val g get() = (rgb shr 8) and 0xFF
    val b get() = rgb and 0xFF

    companion object {
        private val byCode = entries.sortedBy { it.code }
        fun of(code: Int): Ink = byCode[code and 3]
    }
}

enum class Dither(val label: String) {
    SMOOTH("Smooth"),
    CRISP("Crisp"),
    PATTERN("Pattern"),
    FLAT("Flat"),
}

/** A picture reduced to ink codes, one byte per pixel, row by row from the top left. */
class InkImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size == width * height)
    }

    fun rotated180(): InkImage = InkImage(width, height, pixels.reversedArray())

    fun mirrored(): InkImage {
        val out = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) out[y * width + x] = pixels[y * width + (width - 1 - x)]
        return InkImage(width, height, out)
    }

    /** Colours as ARGB ints, either ideal or as they look on the panel. */
    fun argb(paper: Boolean): IntArray {
        val lut = IntArray(4) { val ink = Ink.of(it); if (paper) ink.paper else (0xFF000000.toInt() or ink.rgb) }
        return IntArray(pixels.size) { lut[pixels[it].toInt() and 3] }
    }

    companion object {
        fun solid(width: Int, height: Int, ink: Ink) = InkImage(width, height, ByteArray(width * height) { ink.code.toByte() })

        /** Maps arbitrary ARGB pixels to the nearest ink, used when reloading saved designs. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): InkImage {
            val out = ByteArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                var best = Ink.WHITE
                var bestD = Int.MAX_VALUE
                for (ink in Ink.entries) {
                    val d = (r - ink.r) * (r - ink.r) + (g - ink.g) * (g - ink.g) + (b - ink.b) * (b - ink.b)
                    if (d < bestD) { bestD = d; best = ink }
                }
                out[i] = best.code.toByte()
            }
            return InkImage(width, height, out)
        }
    }
}
