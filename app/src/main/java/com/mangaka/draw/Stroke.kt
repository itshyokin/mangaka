package com.mangaka.draw

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A finished vector stroke: a centre line with a width at every point, in page pixels.
 * Stored as curves rather than pixels, so it can be re-rendered sharply at any zoom.
 */
class Stroke(val xs: FloatArray, val ys: FloatArray, val ws: FloatArray, val color: Int) {

    val size: Int get() = xs.size
    val bounds = RectF()

    init {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in xs.indices) {
            val h = ws[i] / 2f
            l = min(l, xs[i] - h); t = min(t, ys[i] - h)
            r = max(r, xs[i] + h); b = max(b, ys[i] + h)
        }
        bounds.set(l, t, r, b)
    }

    /** True if a circle of radius [r] at (x, y) touches this stroke's inked area. */
    fun hits(x: Float, y: Float, r: Float): Boolean {
        if (x < bounds.left - r || x > bounds.right + r || y < bounds.top - r || y > bounds.bottom + r) return false
        if (size == 1) return hypot(x - xs[0], y - ys[0]) <= r + ws[0] / 2f
        for (i in 1 until size) {
            val reach = r + max(ws[i - 1], ws[i]) / 2f
            if (distToSegment(x, y, xs[i - 1], ys[i - 1], xs[i], ys[i]) <= reach) return true
        }
        return false
    }

    /** Length along the centre line, in page px. */
    val length: Float by lazy {
        var l = 0f
        for (i in 1 until size) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        l
    }

    fun endX(atStart: Boolean) = if (atStart) xs[0] else xs[size - 1]
    fun endY(atStart: Boolean) = if (atStart) ys[0] else ys[size - 1]

    /**
     * A copy with one end moved to (nx, ny). The line bends smoothly: points within
     * [falloff] page px of that end follow it, fading to no movement beyond that.
     */
    fun withEndMoved(atStart: Boolean, nx: Float, ny: Float, falloff: Float): Stroke {
        val n = size
        if (n == 1) return Stroke(floatArrayOf(nx), floatArrayOf(ny), ws.copyOf(), color)
        val i0 = if (atStart) 0 else n - 1
        val dx = nx - xs[i0]
        val dy = ny - ys[i0]
        val ox = xs.copyOf()
        val oy = ys.copyOf()
        val l = max(falloff, 1e-3f)
        var s = 0f
        for (step in 0 until n) {
            val i = if (atStart) step else n - 1 - step
            if (step > 0) {
                val prev = if (atStart) i - 1 else i + 1
                s += hypot(xs[i] - xs[prev], ys[i] - ys[prev])
            }
            if (s >= l) break
            val t = 1f - s / l
            val w = t * t * (3f - 2f * t)
            ox[i] += dx * w
            oy[i] += dy * w
        }
        return Stroke(ox, oy, ws.copyOf(), color)
    }

    /** Nearest point on the centre line to (x, y), written into [out]; returns its distance. */
    fun nearestPoint(x: Float, y: Float, out: FloatArray): Float {
        if (size == 1) {
            out[0] = xs[0]; out[1] = ys[0]
            return hypot(x - xs[0], y - ys[0])
        }
        var best = Float.MAX_VALUE
        for (i in 1 until size) {
            val ax = xs[i - 1]; val ay = ys[i - 1]
            val dx = xs[i] - ax; val dy = ys[i] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            val qx = ax + t * dx
            val qy = ay + t * dy
            val d = hypot(x - qx, y - qy)
            if (d < best) { best = d; out[0] = qx; out[1] = qy }
        }
        return best
    }

    /**
     * Pixel-style erase: removes the part of the line within [r] of (cx, cy), splitting
     * the stroke where the eraser passes through. Returns null if the stroke is untouched,
     * otherwise the remaining pieces (possibly none).
     */
    fun eraseCircle(cx: Float, cy: Float, r: Float): List<Stroke>? {
        if (!hits(cx, cy, r)) return null
        val nx = FloatList(size + 16)
        val ny = FloatList(size + 16)
        val nw = FloatList(size + 16)
        val step = max(0.5f, r * 0.4f)
        for (i in 0 until size) {
            if (i > 0 && distToSegment(cx, cy, xs[i - 1], ys[i - 1], xs[i], ys[i]) < r) {
                // Subdivide segments the eraser crosses so the cut lands where it touched.
                val len = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
                val parts = ceil(len / step).toInt()
                for (k in 1 until parts) {
                    val t = k.toFloat() / parts
                    nx.add(xs[i - 1] + (xs[i] - xs[i - 1]) * t)
                    ny.add(ys[i - 1] + (ys[i] - ys[i - 1]) * t)
                    nw.add(ws[i - 1] + (ws[i] - ws[i - 1]) * t)
                }
            }
            nx.add(xs[i]); ny.add(ys[i]); nw.add(ws[i])
        }
        val n = nx.size
        val inside = BooleanArray(n) { hypot(nx[it] - cx, ny[it] - cy) < r }
        if (inside.none { it }) return null
        val pieces = ArrayList<Stroke>()
        var start = -1
        for (i in 0..n) {
            val keep = i < n && !inside[i]
            if (keep && start < 0) start = i
            if (!keep && start >= 0) {
                val count = i - start
                if (count >= 2) {
                    pieces.add(
                        Stroke(
                            FloatArray(count) { nx[start + it] },
                            FloatArray(count) { ny[start + it] },
                            FloatArray(count) { nw[start + it] },
                            color
                        )
                    )
                }
                start = -1
            }
        }
        return pieces
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

object StrokeRenderer {
    /**
     * Draws [s] onto [c], which must already be transformed into page space.
     * [minStep] is the smallest segment worth drawing, in page px (about one screen pixel),
     * so zoomed-out redraws skip detail nobody can see.
     */
    fun draw(c: Canvas, s: Stroke, paint: Paint, minStep: Float, minWidth: Float = 0.5f) {
        paint.color = s.color
        if (s.size == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(s.xs[0], s.ys[0], max(minWidth, s.ws[0]) / 2f, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        var j = 0
        for (i in 1 until s.size) {
            val last = i == s.size - 1
            if (!last && hypot(s.xs[i] - s.xs[j], s.ys[i] - s.ys[j]) < minStep) continue
            paint.strokeWidth = max(minWidth, (s.ws[j] + s.ws[i]) * 0.5f)
            c.drawLine(s.xs[j], s.ys[j], s.xs[i], s.ys[i], paint)
            j = i
        }
    }
}

/** A growable float array, so live strokes don't box every coordinate. */
class FloatList(capacity: Int = 256) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Float = data[i]
    fun last(): Float = data[size - 1]
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
}

object StrokeMath {

    /** Line width for a pen pressure (0..1). Light pressure still leaves a visible line. */
    fun width(size: Float, pressure: Float): Float = size * (0.2f + 0.8f * pressure.coerceIn(0f, 1f).pow(0.75f))

    class Resampled(val xs: FloatArray, val ys: FloatArray, val ps: FloatArray)

    /** Re-spaces a polyline so points sit [spacing] apart, which makes smoothing even. */
    fun resample(xs: FloatArray, ys: FloatArray, ps: FloatArray, spacing: Float): Resampled {
        val n = xs.size
        val ox = FloatList(n * 2)
        val oy = FloatList(n * 2)
        val op = FloatList(n * 2)
        ox.add(xs[0]); oy.add(ys[0]); op.add(ps[0])
        var carry = 0f
        for (i in 1 until n) {
            val ax = xs[i - 1]; val ay = ys[i - 1]; val ap = ps[i - 1]
            val bx = xs[i]; val by = ys[i]; val bp = ps[i]
            val segLen = hypot(bx - ax, by - ay)
            if (segLen == 0f) continue
            var dist = spacing - carry
            while (dist <= segLen) {
                val t = dist / segLen
                ox.add(ax + (bx - ax) * t)
                oy.add(ay + (by - ay) * t)
                op.add(ap + (bp - ap) * t)
                dist += spacing
            }
            carry = segLen - (dist - spacing)
        }
        // Always end exactly on the final input point.
        val fx = xs[n - 1]; val fy = ys[n - 1]; val fp = ps[n - 1]
        if (ox.size == 1 || hypot(fx - ox.last(), fy - oy.last()) > spacing * 0.3f) {
            ox.add(fx); oy.add(fy); op.add(fp)
        } else {
            // Replace the near-duplicate last point with the true end.
            val ax = ox.toArray(); val ay = oy.toArray(); val ap = op.toArray()
            ax[ax.size - 1] = fx; ay[ay.size - 1] = fy; ap[ap.size - 1] = fp
            return Resampled(ax, ay, ap)
        }
        return Resampled(ox.toArray(), oy.toArray(), op.toArray())
    }

    /**
     * Gaussian smoothing with both endpoints pinned in place: the window shrinks near
     * the ends, so the line still starts and stops exactly where you put the pen.
     */
    fun smooth(a: FloatArray, sigmaPts: Float): FloatArray {
        if (sigmaPts < 0.5f || a.size < 3) return a
        val r = ceil(sigmaPts * 2.5f).toInt()
        val w = FloatArray(r + 1) { k -> exp(-(k * k) / (2f * sigmaPts * sigmaPts)) }
        val n = a.size
        return FloatArray(n) { i ->
            val rr = min(r, min(i, n - 1 - i))
            var sum = 0f
            var wsum = 0f
            for (k in -rr..rr) {
                val wk = w[if (k < 0) -k else k]
                sum += a[i + k] * wk
                wsum += wk
            }
            sum / wsum
        }
    }

    /** Pressure widths, optionally tapered to a point at both ends like a G-pen stroke. */
    fun widths(xs: FloatArray, ys: FloatArray, ps: FloatArray, size: Float, taper: Boolean): FloatArray {
        val n = xs.size
        val out = FloatArray(n) { width(size, ps[it]) }
        if (!taper || n < 3) return out
        val cum = FloatArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum[n - 1]
        val taperLen = min(size * 4f, total * 0.3f)
        if (taperLen <= 0f) return out
        for (i in 0 until n) {
            val fromStart = ease(cum[i] / taperLen)
            val fromEnd = ease((total - cum[i]) / taperLen)
            out[i] *= min(fromStart, fromEnd)
        }
        return out
    }

    private fun ease(t: Float): Float = 0.15f + 0.85f * sqrt(t.coerceIn(0f, 1f))
}
