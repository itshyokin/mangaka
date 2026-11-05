package com.mangaka.draw

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Draws a document's layers, bottom to top, with each layer's opacity and blend mode. */
class DocRenderer {
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val layerPaint = Paint()
    private val layerBounds = RectF()

    /**
     * [c] must already be in page space. [visible] (page px) skips off-screen items;
     * pass null to draw everything. [minStep] is about one output pixel, in page px.
     */
    fun draw(c: Canvas, doc: Document, visible: RectF?, minStep: Float, onlyBelow: Layer? = null) {
        for (layer in doc.layers) {
            if (layer === onlyBelow) return
            drawLayer(c, layer, visible, minStep)
        }
    }

    fun drawLayer(c: Canvas, layer: Layer, visible: RectF?, minStep: Float) {
        if (!layer.visible || !layer.contentBounds(layerBounds)) return
        if (visible != null && !RectF.intersects(layerBounds, visible)) return
        val grouped = !layer.isPlain
        if (grouped) {
            layerPaint.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
            layerPaint.blendMode = layer.blend.mode
            // Only allocate an offscreen buffer as large as the layer's content.
            if (visible != null && !layerBounds.intersect(visible)) return
            c.saveLayer(layerBounds, layerPaint)
        }
        for (f in layer.fills) {
            if (visible != null && !RectF.intersects(f.bounds, visible)) continue
            fillPaint.color = f.color
            c.drawPath(f.path, fillPaint)
        }
        for (s in layer.strokes) {
            if (visible != null && !RectF.intersects(s.bounds, visible)) continue
            StrokeRenderer.draw(c, s, inkPaint, minStep)
        }
        if (grouped) c.restore()
    }
}
