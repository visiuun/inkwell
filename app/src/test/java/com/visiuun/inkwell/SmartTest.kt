package com.visiuun.inkwell

import com.visiuun.inkwell.image.Dither
import com.visiuun.inkwell.image.Ink
import com.visiuun.inkwell.image.Look
import com.visiuun.inkwell.image.Raster
import com.visiuun.inkwell.image.Quantizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class SmartTest {
    private val w = 48
    private val h = 40
    private val all = Ink.entries.toList()

    private fun picture(colour: (x: Int, y: Int) -> Triple<Int, Int, Int>) = FloatArray(w * h * 3).also { a ->
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = colour(x, y)
            val i = (y * w + x) * 3
            a[i] = r.toFloat(); a[i + 1] = g.toFloat(); a[i + 2] = b.toFloat()
        }
    }

    private fun share(pixels: ByteArray, ink: Ink) = pixels.count { it == ink.code.toByte() }.toFloat() / pixels.size

    @Test
    fun greysUseOnlyBlackAndWhiteAndGetLighterWithTheSource() {
        var last = -1f
        for (level in listOf(40, 90, 140, 190, 235)) {
            // meanLuma pinned so the lift for dark pictures does not move the goalposts
            val px = Quantizer.quantize(picture { _, _ -> Triple(level, level, level) }, w, h, all, Dither.SMART, meanLuma = 0.8f).pixels
            assertTrue(px.all { it == Ink.BLACK.code.toByte() || it == Ink.WHITE.code.toByte() })
            val white = share(px, Ink.WHITE)
            assertTrue("grey $level gave $white white after $last", white > last)
            last = white
        }
    }

    @Test
    fun coloursTheInksCannotMakeTurnGreyInsteadOfSmearing() {
        val blue = Quantizer.quantize(picture { _, _ -> Triple(40, 90, 230) }, w, h, all, Dither.SMART, meanLuma = 0.8f).pixels
        assertTrue(blue.all { it == Ink.BLACK.code.toByte() || it == Ink.WHITE.code.toByte() })
        assertTrue(share(blue, Ink.WHITE) in 0.05f..0.6f)
    }

    @Test
    fun aDarkPictureIsLiftedButItsBlackWhiteAndStrongColoursStay() {
        fun shares(level: Int, mean: Float) =
            share(Quantizer.quantize(picture { _, _ -> Triple(level, level, level) }, w, h, all, Dither.SMART, meanLuma = mean).pixels, Ink.WHITE)
        assertTrue("a dark picture's greys should come out lighter", shares(90, 0.25f) > shares(90, 0.8f) + 0.04f)
        assertEquals(0f, shares(0, 0.25f), 0f)
        assertEquals(1f, shares(255, 0.25f), 0f)
        val red = Quantizer.quantize(picture { _, _ -> Triple(230, 20, 20) }, w, h, all, Dither.SMART, meanLuma = 0.25f).pixels
        assertTrue("lifting must not wash red out, white share was ${share(red, Ink.WHITE)}", share(red, Ink.WHITE) < 0.05f && share(red, Ink.RED) > 0.9f)
    }

    @Test
    fun onlyTheChosenInksAppear() {
        val rainbow = picture { x, y -> Triple(x * 255 / (w - 1), y * 255 / (h - 1), (x + y) * 255 / (w + h - 2)) }
        val noYellow = Quantizer.quantize(rainbow, w, h, listOf(Ink.BLACK, Ink.WHITE, Ink.RED), Dither.SMART).pixels
        assertTrue(noYellow.none { it == Ink.YELLOW.code.toByte() })
        val two = Quantizer.quantize(rainbow, w, h, listOf(Ink.BLACK, Ink.WHITE), Dither.SMART).pixels
        assertTrue(two.all { it == Ink.BLACK.code.toByte() || it == Ink.WHITE.code.toByte() })
    }

    @Test
    fun aHardEdgeStaysExactlyWhereItIs() {
        val px = Quantizer.quantize(picture { x, _ -> if (x < w / 2) Triple(0, 0, 0) else Triple(255, 255, 255) }, w, h, all, Dither.SMART).pixels
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(if (x < w / 2) Ink.BLACK.code.toByte() else Ink.WHITE.code.toByte(), px[y * w + x])
        }
    }

    @Test
    fun theSamePictureAlwaysGivesTheSamePixelsAndLeavesItsInputAlone() {
        val source = picture { x, y -> Triple(255 - x * 3, 120 + y * 2, 60 + x) }
        val copy = source.copyOf()
        val a = Quantizer.quantize(source, w, h, all, Dither.SMART).pixels
        val b = Quantizer.quantize(source, w, h, all, Dither.SMART).pixels
        assertArrayEquals(a, b)
        assertArrayEquals(copy, source, 0f)
    }

    @Test
    fun itCanBeAbandonedHalfway() {
        var polls = 0
        try {
            Quantizer.quantize(picture { x, y -> Triple(x * 5, y * 6, 128) }, w, h, all, Dither.SMART, keepGoing = { ++polls < 4 })
            fail("should have been cancelled")
        } catch (_: CancellationException) {
        }
    }

    /**
     * Not a test of correctness: runs the app's own pipeline (downscale, sliders at their
     * defaults, every texture) over the pictures in INKWELL_BENCH_DIR so they can be scored, and
     * times it. Each picture is a raw little-endian ARGB file of the photo drawn at k times the
     * panel's size, with a .size file holding "width height k".
     */
    @Test
    fun benchmark() {
        val dir = System.getenv("INKWELL_BENCH_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val log = StringBuilder()
        for (file in dir!!.listFiles { f -> f.extension == "sup" }!!.sortedBy { it.name }) {
            val name = file.nameWithoutExtension
            val (bw, bh, k) = File(dir, "$name.size").readText().trim().split(" ").map { it.toInt() }
            val raw = IntArray(bw * k * bh * k)
            ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(raw)
            for (dither in Dither.entries) {
                val look = Look(dither = dither)
                var reduceTime = Long.MAX_VALUE
                var best = Long.MAX_VALUE
                var pixels = ByteArray(0)
                repeat(if (dither == Dither.SMART) 7 else 3) {
                    val t0 = System.nanoTime()
                    val reduced = Raster.reduce(raw, bw, bh, k, look)
                    val t1 = System.nanoTime()
                    pixels = Quantizer.quantize(reduced.rgb, bw, bh, all, dither, meanLuma = reduced.meanLuma).pixels
                    reduceTime = minOf(reduceTime, t1 - t0)
                    best = minOf(best, System.nanoTime() - t1)
                }
                File(dir, "$name.${dither.name.lowercase()}.ink").writeBytes(pixels)
                log.append("$name ${dither.name.lowercase()} ${"%.1f".format(best / 1e6)} downscale ${"%.1f".format(reduceTime / 1e6)}\n")
            }
            var quick = Long.MAX_VALUE
            val reduced = Raster.reduce(raw, bw, bh, k, Look(dither = Dither.SMART))
            repeat(5) {
                val t0 = System.nanoTime()
                Quantizer.quantize(reduced.rgb, bw, bh, all, Dither.SMART, meanLuma = reduced.meanLuma, refine = false)
                quick = minOf(quick, System.nanoTime() - t0)
            }
            log.append("$name smart-quick ${"%.1f".format(quick / 1e6)}\n")
        }
        File(dir, "timing.txt").writeText(log.toString())
    }
}
