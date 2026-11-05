package com.mangaka.draw

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Pulled-string ("lazy brush") stabilizer.
 *
 * The drawn tip hangs on an invisible string behind the pen. It only moves once the pen
 * pulls the string tight, so tremors shorter than the string never reach the canvas.
 * Works in screen pixels, so the feel stays the same at every zoom level.
 */
class PulledString {
    var length = 0f
    val tip = PointF()

    fun reset(x: Float, y: Float) {
        tip.set(x, y)
    }

    /** Moves the tip toward the pen. Returns true if the tip moved. */
    fun pull(x: Float, y: Float): Boolean {
        val dx = x - tip.x
        val dy = y - tip.y
        val d = hypot(dx, dy)
        if (d <= length) return false
        val k = (d - length) / d
        tip.offset(dx * k, dy * k)
        return true
    }
}

/** A layer's contents at one moment (cheap: copies references, not drawings). */
class Contents(val strokes: List<Stroke>, val fills: List<Fill>) {
    fun applyTo(layer: Layer) {
        layer.strokes.clear(); layer.strokes.addAll(strokes)
        layer.fills.clear(); layer.fills.addAll(fills)
    }

    companion object {
        fun of(layer: Layer) = Contents(ArrayList(layer.strokes), ArrayList(layer.fills))
    }
}

sealed interface Edit
class AddStrokeEdit(val layer: Layer, val stroke: Stroke) : Edit
class AddFillEdit(val layer: Layer, val fill: Fill) : Edit
/** Any other change to one layer's contents (erasing, clearing). */
class ContentEdit(val layer: Layer, val before: Contents, val after: Contents) : Edit
/** Layers added, deleted or reordered. */
class LayersEdit(
    val before: List<Layer>,
    val after: List<Layer>,
    val activeBefore: Layer,
    val activeAfter: Layer,
) : Edit

class History(private val doc: Document) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > MAX_STEPS) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        when (e) {
            is AddStrokeEdit -> e.layer.strokes.remove(e.stroke)
            is AddFillEdit -> e.layer.fills.remove(e.fill)
            is ContentEdit -> e.before.applyTo(e.layer)
            is LayersEdit -> setLayers(e.before, e.activeBefore)
        }
        redoStack.addLast(e)
        return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        when (e) {
            is AddStrokeEdit -> e.layer.strokes.add(e.stroke)
            is AddFillEdit -> e.layer.fills.add(e.fill)
            is ContentEdit -> e.after.applyTo(e.layer)
            is LayersEdit -> setLayers(e.after, e.activeAfter)
        }
        undoStack.addLast(e)
        return true
    }

    private fun setLayers(list: List<Layer>, active: Layer) {
        doc.layers.clear()
        doc.layers.addAll(list)
        doc.setActive(active)
    }

    private companion object {
        const val MAX_STEPS = 300
    }
}
