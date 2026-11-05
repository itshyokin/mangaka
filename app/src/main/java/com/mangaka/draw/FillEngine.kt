package com.mangaka.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Paint bucket.
 *
 * 1. Rasterize the line strokes onto a working grid (up to ~4 million cells).
 * 2. Thicken the lines by half the gap size, so small gaps between lines are sealed.
 * 3. Flood fill from the tapped cell.
 * 4. Grow the region back out to the true lines (undoing step 2), then a little
 *    further so the color tucks under the ink with no white halo.
 * 5. Trace the region's outlines into vector loops.
 *
 * Runs off the main thread; it touches no views.
 */
object FillEngine {

    sealed interface Result {
        class Filled(val fill: Fill) : Result
        object OnLine : Result
        object Empty : Result
    }

    private const val MAX_CELLS = 4_000_000
    private const val UNDER_LINES_CELLS = 2

    fun compute(
        spec: PageSpec,
        lines: List<Stroke>,
        seedX: Float,
        seedY: Float,
        gapPx: Float,
        color: Int,
    ): Result {
        val pageW = spec.widthPx
        val pageH = spec.heightPx
        val k = min(1f, sqrt(MAX_CELLS.toFloat() / (pageW.toFloat() * pageH)))
        val w = max(1, (pageW * k).roundToInt())
        val h = max(1, (pageH * k).roundToInt())
        val sx = pageW.toFloat() / w
        val sy = pageH.toFloat() / h

        val seedCx = (seedX / sx).toInt()
        val seedCy = (seedY / sy).toInt()
        if (seedCx !in 0 until w || seedCy !in 0 until h) return Result.Empty

        // 1. Lines -> barrier mask.
        val barrier = rasterize(lines, w, h, k)

        // 2. Seal gaps.
        val r = ceil(gapPx / 2f * k).toInt()
        val sealed = if (r > 0) dilate(barrier, w, h, r) else barrier

        var seed = seedCy * w + seedCx
        if (barrier[seed] != 0.toByte()) return Result.OnLine
        if (sealed[seed] != 0.toByte()) {
            // Tapped inside the thickened zone next to a line: start from the nearest open cell.
            seed = nearestOpen(sealed, w, h, seedCx, seedCy, r + 1)
            if (seed < 0) return Result.OnLine
        }

        // 3. Flood fill.
        val region = ByteArray(w * h)
        flood(sealed, region, w, h, seed)

        // 4. Grow back to the lines, then under them.
        grow(region, barrier, w, h, r + 1)
        val under = dilate(region, w, h, UNDER_LINES_CELLS)

        // 5. Outlines.
        val loops = trace(under, w, h, sx, sy)
        if (loops.isEmpty()) return Result.Empty
        return Result.Filled(Fill(loops, color))
    }

    private fun rasterize(lines: List<Stroke>, w: Int, h: Int, k: Float): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val c = Canvas(bmp)
            c.scale(k, k)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            // Every line is at least 1.5 cells wide, so thin lines can't leak.
            val minWidth = 1.5f / k
            for (s in lines) StrokeRenderer.draw(c, s, paint, 0.5f / k, minWidth)
            val rowBytes = bmp.rowBytes
            val buf = ByteBuffer.allocate(rowBytes * h)
            bmp.copyPixelsToBuffer(buf)
            val raw = buf.array()
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                val row = y * rowBytes
                val o = y * w
                for (x in 0 until w) {
                    if ((raw[row + x].toInt() and 0xFF) > 96) out[o + x] = 1
                }
            }
            return out
        } finally {
            bmp.recycle()
        }
    }

    /** Square dilation by [r] cells, separable: rows then columns. */
    private fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var count = 0
            for (x in 0..min(r, w - 1)) if (src[o + x] != 0.toByte()) count++
            for (x in 0 until w) {
                if (count > 0) tmp[o + x] = 1
                val add = x + r + 1
                val drop = x - r
                if (add < w && src[o + add] != 0.toByte()) count++
                if (drop >= 0 && src[o + drop] != 0.toByte()) count--
            }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            var count = 0
            for (y in 0..min(r, h - 1)) if (tmp[y * w + x] != 0.toByte()) count++
            for (y in 0 until h) {
                if (count > 0) out[y * w + x] = 1
                val add = y + r + 1
                val drop = y - r
                if (add < h && tmp[add * w + x] != 0.toByte()) count++
                if (drop >= 0 && tmp[drop * w + x] != 0.toByte()) count--
            }
        }
        return out
    }

    private fun nearestOpen(mask: ByteArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Int {
        var best = -1
        var bestD = Int.MAX_VALUE
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y !in 0 until h) continue
            for (dx in -radius..radius) {
                val x = cx + dx
                if (x !in 0 until w) continue
                val d = dx * dx + dy * dy
                if (d < bestD && mask[y * w + x] == 0.toByte()) { bestD = d; best = y * w + x }
            }
        }
        return best
    }

    /** Scanline flood fill of open (0) cells in [blocked], marking [region]. */
    private fun flood(blocked: ByteArray, region: ByteArray, w: Int, h: Int, seed: Int) {
        var stack = IntArray(4096)
        var sp = 0
        fun push(v: Int) {
            if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = v
        }
        fun open(i: Int) = blocked[i] == 0.toByte() && region[i] == 0.toByte()
        push(seed)
        while (sp > 0) {
            val i = stack[--sp]
            if (!open(i)) continue
            val y = i / w
            val rowStart = y * w
            var l = i
            while (l > rowStart && open(l - 1)) l--
            var rr = i
            while (rr < rowStart + w - 1 && open(rr + 1)) rr++
            var aboveOpen = false
            var belowOpen = false
            for (j in l..rr) {
                region[j] = 1
                if (y > 0) {
                    val a = j - w
                    val o = open(a)
                    if (o && !aboveOpen) push(a)
                    aboveOpen = o
                }
                if (y < h - 1) {
                    val b = j + w
                    val o = open(b)
                    if (o && !belowOpen) push(b)
                    belowOpen = o
                }
            }
        }
    }

    /** Grows [region] by up to [steps] cells into cells that aren't lines (4-connected). */
    private fun grow(region: ByteArray, barrier: ByteArray, w: Int, h: Int, steps: Int) {
        var frontier = IntArray(1024)
        var fn = 0
        // Seed the frontier with region cells on the region's edge.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (region[i] == 0.toByte()) continue
                val edge = (x > 0 && region[i - 1] == 0.toByte()) || (x < w - 1 && region[i + 1] == 0.toByte()) ||
                    (y > 0 && region[i - w] == 0.toByte()) || (y < h - 1 && region[i + w] == 0.toByte())
                if (edge) {
                    if (fn == frontier.size) frontier = frontier.copyOf(fn * 2)
                    frontier[fn++] = i
                }
            }
        }
        repeat(steps) {
            var next = IntArray(max(16, fn * 2))
            var nn = 0
            for (f in 0 until fn) {
                val i = frontier[f]
                val x = i % w
                val y = i / w
                for (d in 0 until 4) {
                    val j = when (d) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x < w - 1) i + 1 else -1
                        2 -> if (y > 0) i - w else -1
                        else -> if (y < h - 1) i + w else -1
                    }
                    if (j < 0 || region[j] != 0.toByte() || barrier[j] != 0.toByte()) continue
                    region[j] = 1
                    if (nn == next.size) next = next.copyOf(nn * 2)
                    next[nn++] = j
                }
            }
            frontier = next
            fn = nn
            if (fn == 0) return
        }
    }

    /**
     * Traces the boundaries of [mask] into closed loops in page px. Boundary edges run
     * with the region on their right; following them gives one loop per outline or hole.
     * Corners are then replaced by edge midpoints, which turns pixel stairs into smooth
     * diagonals, and points on straight runs are dropped.
     */
    private fun trace(mask: ByteArray, w: Int, h: Int, sx: Float, sy: Float): List<FloatArray> {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x] != 0.toByte()) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return emptyList()

        val cw = x1 - x0 + 2
        val ch = y1 - y0 + 2
        val nextA = IntArray(cw * ch) { -1 }
        val nextB = IntArray(cw * ch) { -1 }
        fun corner(x: Int, y: Int) = (y - y0) * cw + (x - x0)
        fun addEdge(from: Int, to: Int) {
            if (nextA[from] == -1) nextA[from] = to else nextB[from] = to
        }
        fun filled(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x] != 0.toByte()

        for (y in y0..y1) for (x in x0..x1) {
            if (!filled(x, y)) continue
            if (!filled(x, y - 1)) addEdge(corner(x, y), corner(x + 1, y))
            if (!filled(x + 1, y)) addEdge(corner(x + 1, y), corner(x + 1, y + 1))
            if (!filled(x, y + 1)) addEdge(corner(x + 1, y + 1), corner(x, y + 1))
            if (!filled(x - 1, y)) addEdge(corner(x, y + 1), corner(x, y))
        }

        val loops = ArrayList<FloatArray>()
        var px = IntArray(1024)
        var py = IntArray(1024)
        for (start in 0 until cw * ch) {
            // A corner where two outlines touch has two outgoing edges: trace both.
            while (nextA[start] != -1) {
                var n = 0
                var cur = start
                while (true) {
                    if (n == px.size) { px = px.copyOf(n * 2); py = py.copyOf(n * 2) }
                    px[n] = cur % cw + x0
                    py[n] = cur / cw + y0
                    n++
                    if (nextA[cur] == -1) break
                    val nxt = nextA[cur]
                    nextA[cur] = nextB[cur]
                    nextB[cur] = -1
                    cur = nxt
                    if (cur == start) break
                }
                if (n >= 4) loops.add(simplify(px, py, n, sx, sy))
            }
        }
        return loops.filter { it.size >= 6 }
    }

    /** Edge midpoints of a corner loop, with collinear points removed, in page px. */
    private fun simplify(px: IntArray, py: IntArray, n: Int, sx: Float, sy: Float): FloatArray {
        // Midpoints in doubled integer units so collinearity tests are exact.
        val mx = IntArray(n) { px[it] + px[(it + 1) % n] }
        val my = IntArray(n) { py[it] + py[(it + 1) % n] }
        val out = FloatList(n)
        for (i in 0 until n) {
            val a = (i - 1 + n) % n
            val b = (i + 1) % n
            val cross = (mx[i] - mx[a]).toLong() * (my[b] - my[i]) - (my[i] - my[a]).toLong() * (mx[b] - mx[i])
            if (cross == 0L) continue
            out.add(mx[i] * 0.5f * sx)
            out.add(my[i] * 0.5f * sy)
        }
        return out.toArray()
    }
}
