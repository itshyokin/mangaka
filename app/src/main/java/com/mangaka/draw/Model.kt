package com.mangaka.draw

import android.graphics.BlendMode
import android.graphics.RectF

enum class LayerBlend(val id: String, val label: String, val mode: BlendMode) {
    NORMAL("normal", "Normal", BlendMode.SRC_OVER),
    MULTIPLY("multiply", "Multiply", BlendMode.MULTIPLY),
    SCREEN("screen", "Screen", BlendMode.SCREEN),
    OVERLAY("overlay", "Overlay", BlendMode.OVERLAY);

    companion object {
        fun byId(id: String): LayerBlend = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * One layer. Within a layer, color fills always render underneath strokes,
 * so filling on a line layer never covers the lines.
 */
class Layer(var name: String) {
    val strokes = ArrayList<Stroke>()
    val fills = ArrayList<Fill>()
    var visible = true
    var locked = false
    /** Reference layers supply the lines the paint bucket fills between. */
    var reference = false
    var opacity = 1f
    var blend = LayerBlend.NORMAL

    /** True when the layer renders exactly like plain drawing (no group compositing needed). */
    val isPlain: Boolean get() = opacity >= 0.999f && blend == LayerBlend.NORMAL

    /** Union of everything on the layer, in page px. Returns false if the layer is empty. */
    fun contentBounds(out: RectF): Boolean {
        var any = false
        out.setEmpty()
        for (f in fills) { if (any) out.union(f.bounds) else out.set(f.bounds); any = true }
        for (s in strokes) { if (any) out.union(s.bounds) else out.set(s.bounds); any = true }
        return any
    }
}

/** A page's layers, bottom (index 0) to top. */
class Document {
    val layers = ArrayList<Layer>()
    var activeIndex = 0

    val active: Layer get() = layers[activeIndex.coerceIn(0, layers.size - 1)]

    fun setActive(layer: Layer) {
        val i = layers.indexOf(layer)
        if (i >= 0) activeIndex = i
    }

    /** A copy safe to read on another thread (export) while drawing continues. */
    fun snapshot(): Document = Document().also { d ->
        for (l in layers) {
            d.layers.add(Layer(l.name).apply {
                strokes.addAll(l.strokes)
                fills.addAll(l.fills)
                visible = l.visible
                locked = l.locked
                reference = l.reference
                opacity = l.opacity
                blend = l.blend
            })
        }
        d.activeIndex = activeIndex
    }

    fun nextLayerName(): String {
        var n = layers.size + 1
        while (layers.any { it.name == "Layer $n" }) n++
        return "Layer $n"
    }

    companion object {
        /** A new page: a flats layer for color under an ink layer for lines. */
        fun standard(): Document = Document().apply {
            layers.add(Layer("Flats"))
            layers.add(Layer("Ink").apply { reference = true })
            activeIndex = 1
        }
    }
}
