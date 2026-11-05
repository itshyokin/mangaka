package com.mangaka.draw

import android.graphics.Path
import android.graphics.RectF

/**
 * A vector color region: one or more closed outlines filled with the even-odd rule,
 * so holes (an eye inside a face) stay empty. Coordinates are page px.
 */
class Fill private constructor(
    val color: Int,
    private var loopsCache: List<FloatArray>?,
    private var pathCache: Path?,
) {
    /** From outlines, each a closed loop of interleaved x, y. */
    constructor(loops: List<FloatArray>, color: Int) : this(color, loops, null)

    val path: Path
        get() {
            pathCache?.let { return it }
            val p = Path()
            p.fillType = Path.FillType.EVEN_ODD
            for (loop in loopsCache.orEmpty()) {
                if (loop.size < 6) continue
                p.moveTo(loop[0], loop[1])
                var i = 2
                while (i < loop.size) { p.lineTo(loop[i], loop[i + 1]); i += 2 }
                p.close()
            }
            pathCache = p
            return p
        }

    val loops: List<FloatArray>
        get() {
            loopsCache?.let { return it }
            val l = loopsOf(path)
            loopsCache = l
            return l
        }

    val bounds: RectF by lazy { RectF().also { path.computeBounds(it, true) } }

    /** Even-odd point-in-region test. */
    fun contains(x: Float, y: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        var inside = false
        for (loop in loops) {
            val n = loop.size / 2
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val xi = loop[2 * i]; val yi = loop[2 * i + 1]
                val xj = loop[2 * j]; val yj = loop[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /**
     * Cuts a circle out of this region. Returns this fill if the circle misses it,
     * null if nothing is left, or the remaining region.
     */
    fun erased(cx: Float, cy: Float, r: Float): Fill? {
        if (cx + r < bounds.left || cx - r > bounds.right || cy + r < bounds.top || cy - r > bounds.bottom) return this
        val hole = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val out = Path()
        if (!out.op(path, hole, Path.Op.DIFFERENCE)) return this
        if (out.isEmpty) return null
        out.fillType = Path.FillType.EVEN_ODD
        return Fill(color, null, out)
    }

    companion object {
        /** Flattens a path into closed loops (used to save fills that were cut by the eraser). */
        fun loopsOf(path: Path): List<FloatArray> {
            val a = path.approximate(0.5f)
            val loops = ArrayList<FloatArray>()
            val cur = FloatList()
            var lastF = -1f
            var i = 0
            while (i + 2 < a.size) {
                val f = a[i]
                // Two points with the same fraction mark a jump to a new contour.
                if (cur.size > 0 && f == lastF) {
                    if (cur.size >= 6) loops.add(cur.toArray())
                    cur.clear()
                }
                cur.add(a[i + 1]); cur.add(a[i + 2])
                lastF = f
                i += 3
            }
            if (cur.size >= 6) loops.add(cur.toArray())
            return loops
        }
    }
}
