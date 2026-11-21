package com.visiuun.inkwell.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Where the photo sits inside the panel. Offsets are fractions of the panel size. */
data class Placement(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val quarterTurns: Int = 0,
    val fill: Boolean = true,
) {
    val isDefault get() = zoom == 1f && offsetX == 0f && offsetY == 0f && quarterTurns == 0
}

/** How the photo is translated to ink. Sliders run from -1 to 1 with 0 meaning untouched. */
data class Look(
    val brightness: Float = 0f,
    val contrast: Float = 0.15f,
    val saturation: Float = 0.3f,
    val dither: Dither = Dither.SMOOTH,
    val red: Boolean = true,
    val yellow: Boolean = true,
    val darkBackdrop: Boolean = false,
) {
    val inks: List<Ink>
        get() = buildList {
            add(Ink.BLACK); add(Ink.WHITE)
            if (yellow) add(Ink.YELLOW)
            if (red) add(Ink.RED)
        }
}

object Composer {
    private const val SOURCE_MAX = 1400
    private const val SUPERSAMPLE = 2

    /** Decodes a picked photo at a size that is comfortable to re-render on every gesture. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > SOURCE_MAX) {
                    val k = SOURCE_MAX.toFloat() / longest
                    decoder.setTargetSize(max(1, (info.size.width * k).toInt()), max(1, (info.size.height * k).toInt()))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > SOURCE_MAX * 2) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    fun load(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.path)
    } catch (_: Exception) {
        null
    }

    /** The scale at which the photo exactly fits or fills a [width] x [height] panel. */
    fun baseScale(source: Bitmap, width: Int, height: Int, placement: Placement): Float {
        val turned = placement.quarterTurns % 2 != 0
        val sw = (if (turned) source.height else source.width).toFloat()
        val sh = (if (turned) source.width else source.height).toFloat()
        return if (placement.fill) max(width / sw, height / sh) else min(width / sw, height / sh)
    }

    /**
     * Renders the photo into the panel's pixels and reduces it to ink.
     *
     * [refine] and [keepGoing] only matter to [Dither.SMART], which takes a moment: with [refine]
     * off it returns its quick first version, and [keepGoing] turning false abandons the work
     * with a CancellationException.
     */
    fun compose(
        source: Bitmap?,
        width: Int,
        height: Int,
        placement: Placement,
        look: Look,
        refine: Boolean = true,
        keepGoing: () -> Boolean = { true },
    ): InkImage {
        val backdrop = if (look.darkBackdrop) Ink.BLACK else Ink.WHITE
        if (source == null) return InkImage.solid(width, height, backdrop)
        val k = SUPERSAMPLE
        val canvasBitmap = Bitmap.createBitmap(width * k, height * k, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(0xFF000000.toInt() or backdrop.rgb)
        val scale = baseScale(source, width, height, placement) * placement.zoom * k
        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postRotate(90f * placement.quarterTurns)
            postScale(scale, scale)
            postTranslate((0.5f + placement.offsetX) * width * k, (0.5f + placement.offsetY) * height * k)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val raw = IntArray(width * k * height * k)
        canvasBitmap.getPixels(raw, 0, width * k, 0, 0, width * k, height * k)
        canvasBitmap.recycle()

        val rgb = FloatArray(width * height * 3)
        val contrast = if (look.contrast >= 0f) 1f + look.contrast * 1.5f else 1f + look.contrast * 0.8f
        val saturation = if (look.saturation >= 0f) 1f + look.saturation * 1.5f else 1f + look.saturation
        val lift = look.brightness * 110f
        val samples = (k * k).toFloat()
        var lumaSum = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (dy in 0 until k) {
                    val row = (y * k + dy) * width * k
                    for (dx in 0 until k) {
                        val c = raw[row + x * k + dx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                r /= samples; g /= samples; b /= samples
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                lumaSum += luma
                r = luma + (r - luma) * saturation
                g = luma + (g - luma) * saturation
                b = luma + (b - luma) * saturation
                val i = (y * width + x) * 3
                rgb[i] = ((r - 128f) * contrast + 128f + lift).coerceIn(0f, 255f)
                rgb[i + 1] = ((g - 128f) * contrast + 128f + lift).coerceIn(0f, 255f)
                rgb[i + 2] = ((b - 128f) * contrast + 128f + lift).coerceIn(0f, 255f)
            }
        }
        val meanLuma = (lumaSum / (width * height) / 255.0).toFloat()
        return Quantizer.quantize(rgb, width, height, look.inks, look.dither, meanLuma, refine, keepGoing)
    }

    fun toBitmap(image: InkImage, paper: Boolean): Bitmap =
        Bitmap.createBitmap(image.argb(paper), image.width, image.height, Bitmap.Config.ARGB_8888)

    fun fromBitmap(bitmap: Bitmap): InkImage {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return InkImage.fromArgb(bitmap.width, bitmap.height, px)
    }
}
