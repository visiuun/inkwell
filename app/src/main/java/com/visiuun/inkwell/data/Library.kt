package com.visiuun.inkwell.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.visiuun.inkwell.image.Composer
import com.visiuun.inkwell.image.InkImage
import java.io.File
import java.util.zip.CRC32

/** A design that was sent to the case, kept as a lossless PNG of its ink pixels. */
data class Design(val file: File, val width: Int, val height: Int, val modified: Long) {
    val id: String get() = file.name
}

class Library(context: Context) {
    private val dir = File(context.filesDir, "designs").apply { mkdirs() }

    /** The working copy of the photo being edited, so the editor survives a restart. */
    val draft = File(context.filesDir, "draft")

    fun list(): List<Design> = dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) Design(f, bounds.outWidth, bounds.outHeight, f.lastModified()) else null
        }

    /** Saves [image]; sending the same design again just moves it back to the top. */
    fun save(image: InkImage) {
        val crc = CRC32().apply { update(image.pixels) }.value
        val file = File(dir, "%dx%d_%08x.png".format(image.width, image.height, crc))
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return
        }
        val bitmap = Composer.toBitmap(image, paper = false)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            file.delete()
        } finally {
            bitmap.recycle()
        }
    }

    fun load(design: Design): InkImage? = Composer.load(design.file)?.let { b -> Composer.fromBitmap(b).also { b.recycle() } }

    fun delete(design: Design) {
        design.file.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
