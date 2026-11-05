package com.mangaka.draw

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    private const val VERSION = 2

    fun save(file: File, spec: PageSpec, doc: Document) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(spec.preset.id)
            out.writeInt(spec.dpi)
            out.writeInt(spec.widthPx)
            out.writeInt(spec.heightPx)
            out.writeInt(doc.layers.size)
            out.writeInt(doc.activeIndex)
            for (layer in doc.layers) {
                out.writeUTF(layer.name)
                out.writeBoolean(layer.visible)
                out.writeBoolean(layer.locked)
                out.writeBoolean(layer.reference)
                out.writeFloat(layer.opacity)
                out.writeUTF(layer.blend.id)
                out.writeInt(layer.fills.size)
                for (f in layer.fills) {
                    out.writeInt(f.color)
                    val loops = f.loops
                    out.writeInt(loops.size)
                    for (loop in loops) {
                        out.writeInt(loop.size)
                        for (v in loop) out.writeFloat(v)
                    }
                }
                out.writeInt(layer.strokes.size)
                for (s in layer.strokes) writeStroke(out, s)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(file: File): Pair<PageSpec, Document>? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC) return null
                val version = input.readInt()
                if (version > VERSION) return null
                val preset = Preset.byId(input.readUTF())
                val dpi = input.readInt()
                val w = input.readInt()
                val h = input.readInt()
                val spec = PageSpec(preset, dpi, w, h)
                val doc = if (version == 1) readV1(input) else readV2(input)
                spec to doc
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Version 1 pages had one stroke list: it becomes the Ink layer over an empty Flats layer. */
    private fun readV1(input: DataInputStream): Document {
        val doc = Document.standard()
        val count = input.readInt()
        repeat(count) { readStroke(input)?.let { doc.layers[1].strokes.add(it) } }
        return doc
    }

    private fun readV2(input: DataInputStream): Document {
        val doc = Document()
        val layerCount = input.readInt()
        val active = input.readInt()
        repeat(layerCount) {
            val layer = Layer(input.readUTF())
            layer.visible = input.readBoolean()
            layer.locked = input.readBoolean()
            layer.reference = input.readBoolean()
            layer.opacity = input.readFloat()
            layer.blend = LayerBlend.byId(input.readUTF())
            val fillCount = input.readInt()
            repeat(fillCount) {
                val color = input.readInt()
                val loopCount = input.readInt()
                val loops = ArrayList<FloatArray>(loopCount)
                repeat(loopCount) {
                    val n = input.readInt()
                    loops.add(FloatArray(n) { input.readFloat() })
                }
                if (loops.isNotEmpty()) layer.fills.add(Fill(loops, color))
            }
            val strokeCount = input.readInt()
            repeat(strokeCount) { readStroke(input)?.let { layer.strokes.add(it) } }
            doc.layers.add(layer)
        }
        if (doc.layers.isEmpty()) return Document.standard()
        doc.activeIndex = active.coerceIn(0, doc.layers.size - 1)
        return doc
    }

    private fun writeStroke(out: DataOutputStream, s: Stroke) {
        out.writeInt(s.color)
        out.writeInt(s.size)
        for (i in 0 until s.size) {
            out.writeFloat(s.xs[i])
            out.writeFloat(s.ys[i])
            out.writeFloat(s.ws[i])
        }
    }

    private fun readStroke(input: DataInputStream): Stroke? {
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
        return if (n > 0) Stroke(xs, ys, ws, color) else null
    }

    /**
     * Renders every visible layer at full resolution and saves it to Pictures/Mangaka.
     * Falls back to a smaller size if the device can't hold a full-size image.
     * Returns the saved image's Uri, or null on failure.
     */
    fun exportPng(context: Context, spec: PageSpec, doc: Document, transparent: Boolean): Uri? {
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
            DocRenderer().draw(c, doc, null, 0.5f / factor)

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
