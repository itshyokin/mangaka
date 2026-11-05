package com.mangaka.draw

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves pages to app storage and exports them as PNG images. */
object PageStore {
    private const val MAGIC = 0x4D4E474B // "MNGK"
    private const val VERSION = 1

    fun save(file: File, spec: PageSpec, strokes: List<Stroke>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(spec.preset.id)
            out.writeInt(spec.dpi)
            out.writeInt(spec.widthPx)
            out.writeInt(spec.heightPx)
            out.writeInt(strokes.size)
            for (s in strokes) {
                out.writeInt(s.color)
                out.writeInt(s.size)
                for (i in 0 until s.size) {
                    out.writeFloat(s.xs[i])
                    out.writeFloat(s.ys[i])
                    out.writeFloat(s.ws[i])
                }
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(file: File): Pair<PageSpec, List<Stroke>>? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() > VERSION) return null
                val preset = Preset.byId(input.readUTF())
                val dpi = input.readInt()
                val w = input.readInt()
                val h = input.readInt()
                val spec = PageSpec(preset, dpi, w, h)
                val count = input.readInt()
                val strokes = ArrayList<Stroke>(count)
                repeat(count) {
                    val color = input.readInt()
                    val n = input.readInt()
                    val xs = FloatArray(n)
                    val ys = FloatArray(n)
                    val ws = FloatArray(n)
                    for (i in 0 until n) {
                        xs[i] = input.readFloat()
                        ys[i] = input.readFloat()
                        ws[i] = input.readFloat()
                    }
                    if (n > 0) strokes.add(Stroke(xs, ys, ws, color))
                }
                spec to strokes
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Renders the page at full resolution and saves it to Pictures/Mangaka.
     * Falls back to half resolution if the device can't hold a full-size image.
     * Returns the saved image's Uri, or null on failure.
     */
    fun exportPng(context: Context, spec: PageSpec, strokes: List<Stroke>, transparent: Boolean): Uri? {
        var factor = 1f
        var bmp: Bitmap? = null
        while (bmp == null && factor >= 0.25f) {
            try {
                bmp = Bitmap.createBitmap(
                    (spec.widthPx * factor).toInt().coerceAtLeast(1),
                    (spec.heightPx * factor).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
            } catch (e: OutOfMemoryError) {
                factor /= 2f
            }
        }
        val image = bmp ?: return null
        try {
            val c = Canvas(image)
            if (!transparent) c.drawColor(Color.WHITE)
            c.scale(factor, factor)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (s in strokes) StrokeRenderer.draw(c, s, paint, 0.5f)

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mangaka-$stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mangaka")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
            val ok = resolver.openOutputStream(uri)?.use { out ->
                image.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } finally {
            image.recycle()
        }
    }
}
