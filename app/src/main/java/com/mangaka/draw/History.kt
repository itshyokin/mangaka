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

sealed interface Edit
class AddEdit(val stroke: Stroke) : Edit
/** Strokes removed by the eraser, in removal order, each with the index it was removed from. */
class RemoveEdit(val removed: List<Pair<Int, Stroke>>) : Edit
class ClearEdit(val strokes: List<Stroke>) : Edit

class History(private val strokes: MutableList<Stroke>) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun add(stroke: Stroke) {
        strokes.add(stroke)
        push(AddEdit(stroke))
    }

    fun recordRemoval(removed: List<Pair<Int, Stroke>>) {
        if (removed.isNotEmpty()) push(RemoveEdit(removed))
    }

    fun clearAll() {
        if (strokes.isEmpty()) return
        val copy = ArrayList(strokes)
        strokes.clear()
        push(ClearEdit(copy))
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        when (e) {
            is AddEdit -> strokes.remove(e.stroke)
            is RemoveEdit -> for ((idx, s) in e.removed.asReversed()) strokes.add(idx.coerceAtMost(strokes.size), s)
            is ClearEdit -> strokes.addAll(e.strokes)
        }
        redoStack.addLast(e)
        return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        when (e) {
            is AddEdit -> strokes.add(e.stroke)
            is RemoveEdit -> for ((_, s) in e.removed) strokes.remove(s)
            is ClearEdit -> strokes.clear()
        }
        undoStack.addLast(e)
        return true
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun push(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > MAX_STEPS) undoStack.removeFirst()
        redoStack.clear()
    }

    private companion object {
        const val MAX_STEPS = 300
    }
}
