package com.mangaka.draw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The page canvas.
 *
 * Input rules: the S Pen draws; fingers pan and pinch-zoom; a quick two-finger tap undoes
 * and a three-finger tap redoes. While the pen touches the screen, fingers (and palms)
 * are ignored. Holding the S Pen side button while drawing erases.
 */
@SuppressLint("ViewConstructor")
class DrawingView(context: Context) : View(context) {

    enum class Tool { PEN, ERASER }

    /** Called whenever the stroke list or undo/redo availability changes. */
    var onChanged: (() -> Unit)? = null

    var spec: PageSpec = PageSpec.fromPreset(Preset.MANGA_B4, 600)
        private set
    val strokes = ArrayList<Stroke>()
    val history = History(strokes)

    // ---- Settings (set by the toolbar) ----
    var tool = Tool.PEN
        set(v) { field = v; invalidate() }
    /** Pen width in page pixels. */
    var brushSize = 12f
    /** Eraser diameter in dp (screen-relative, so it feels the same at any zoom). */
    var eraserSizeDp = 32f
    /** 0..100: length of the pulled string. */
    var stabilizer = 40
    /** 0..100: how strongly a finished stroke settles into a smooth curve. */
    var smoothing = 40
    var taper = true
    var snapLines = true
    var fingerDraws = false
    var showPaper = true
        set(v) { field = v; invalidate() }
    var showGuides = true
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    /** Current pen color (opaque). */
    var color = Color.BLACK

    // ---- View transform: screen = page * scale + (tx, ty) ----
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var needsFit = true

    // ---- Cached render of finished strokes, in screen space ----
    private var cache: Bitmap? = null
    private var cacheScale = 1f
    private var cacheTx = 0f
    private var cacheTy = 0f
    private var cacheValid = false

    // ---- Paints ----
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val checkerPaint = Paint().apply { shader = makeChecker() }
    private val shadowPaint = Paint().apply { color = 0x40000000 }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCC3B82F6.toInt()
    }
    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x993B82F6.toInt()
    }
    private val stringDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x993B82F6.toInt() }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        color = 0xFF2563EB.toInt()
    }
    private val workspaceColor = 0xFF3A3A40.toInt()

    // ---- Pen stroke state ----
    private val string = PulledString()
    private val liveX = FloatList()
    private val liveY = FloatList()
    private val liveP = FloatList()
    private var penPointerId = -1
    private var drawing = false
    private var erasing = false
    private var pressureEma = 0.5f
    private var rawX = 0f
    private var rawY = 0f
    private val removedThisStroke = ArrayList<Pair<Int, Stroke>>()

    // Hold-to-snap: pausing at the end of a stroke turns it into a straight line.
    private var snapActive = false
    private var snapEndX = 0f
    private var snapEndY = 0f
    private var stillX = 0f
    private var stillY = 0f
    private val snapRunnable = Runnable { trySnap() }

    // ---- Hover (S Pen held above the screen) ----
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverButton = false

    // ---- Finger gestures ----
    private var gestureActive = false
    private var fingerStroke = false
    private var gestureStartTime = 0L
    private var maxPointers = 0
    private var gestureMoved = false
    private var startScale = 1f
    private var startTx = 0f
    private var startTy = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var startDist = 0f
    private var capturedCount = 0
    private val touchSlop = 10f * density

    // =====================================================================
    // Public API
    // =====================================================================

    fun loadPage(newSpec: PageSpec, loaded: List<Stroke>) {
        spec = newSpec
        strokes.clear()
        strokes.addAll(loaded)
        history.reset()
        needsFit = true
        if (width > 0) fitToScreen()
        cacheValid = false
        invalidate()
        onChanged?.invoke()
    }

    fun newPage(newSpec: PageSpec) = loadPage(newSpec, emptyList())

    fun clearPage() {
        history.clearAll()
        cacheValid = false
        invalidate()
        onChanged?.invoke()
    }

    fun undo() {
        if (drawing) return
        if (history.undo()) {
            cacheValid = false
            invalidate()
            onChanged?.invoke()
        }
    }

    fun redo() {
        if (drawing) return
        if (history.redo()) {
            cacheValid = false
            invalidate()
            onChanged?.invoke()
        }
    }

    fun fitToScreen() {
        if (width == 0 || height == 0) { needsFit = true; return }
        val m = 24f * density
        val pw = spec.widthPx.toFloat()
        val ph = spec.heightPx.toFloat()
        val tall = ph / pw > 4f
        fitScale = if (tall) (width - 2 * m) / pw else min((width - 2 * m) / pw, (height - 2 * m) / ph)
        scale = fitScale
        tx = (width - pw * scale) / 2f
        ty = if (tall) m else (height - ph * scale) / 2f
        needsFit = false
        cacheValid = false
        invalidate()
    }

    // =====================================================================
    // Layout and drawing
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache?.recycle()
        cache = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        if (needsFit || oldw == 0) {
            fitToScreen()
        } else {
            // Keep the same page point centred after a rotation or resize.
            tx += (w - oldw) / 2f
            ty += (h - oldh) / 2f
        }
        cacheValid = false
    }

    private fun rebuildCache() {
        val bmp = cache ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.translate(tx, ty)
        c.scale(scale, scale)
        val visible = RectF(-tx / scale, -ty / scale, (width - tx) / scale, (height - ty) / scale)
        val step = 1f / scale
        for (s in strokes) {
            if (RectF.intersects(s.bounds, visible)) StrokeRenderer.draw(c, s, inkPaint, step)
        }
        cacheScale = scale
        cacheTx = tx
        cacheTy = ty
        cacheValid = true
    }

    /** Draws one new stroke into the cache without a full rebuild, when the view hasn't moved. */
    private fun appendToCache(s: Stroke) {
        val bmp = cache
        if (bmp == null || !cacheValid || cacheScale != scale || cacheTx != tx || cacheTy != ty) {
            cacheValid = false
            return
        }
        val c = Canvas(bmp)
        c.translate(tx, ty)
        c.scale(scale, scale)
        StrokeRenderer.draw(c, s, inkPaint, 1f / scale)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(workspaceColor)

        val pageRect = RectF(tx, ty, tx + spec.widthPx * scale, ty + spec.heightPx * scale)
        val sh = 4f * density
        canvas.drawRect(pageRect.left + sh, pageRect.top + sh, pageRect.right + sh, pageRect.bottom + sh, shadowPaint)
        canvas.drawRect(pageRect, if (showPaper) paperPaint else checkerPaint)

        // Finished strokes, from the cache. While pinching we stretch the old cache
        // and rebuild it sharp when the fingers lift.
        if (!cacheValid && !gestureActive) rebuildCache()
        val bmp = cache
        if (bmp != null && cacheValid) {
            val k = scale / cacheScale
            canvas.save()
            canvas.clipRect(pageRect)
            canvas.translate(tx - cacheTx * k, ty - cacheTy * k)
            canvas.scale(k, k)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()
        }

        // Page space: guides and the stroke being drawn.
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        if (showGuides) drawGuides(canvas)
        if (drawing && !erasing) drawLiveStroke(canvas)
        canvas.restore()

        // Screen space overlays: stabilizer string and cursors.
        if (drawing && !erasing && !snapActive && string.length > 0f) {
            canvas.drawLine(string.tip.x, string.tip.y, rawX, rawY, stringPaint)
            canvas.drawCircle(rawX, rawY, 3f * density, stringDotPaint)
        }
        if (drawing && erasing) {
            canvas.drawCircle(rawX, rawY, eraserSizeDp * density / 2f, cursorPaint)
        } else if (hovering && !drawing) {
            val eraseHover = tool == Tool.ERASER || hoverButton
            val r = if (eraseHover) eraserSizeDp * density / 2f else max(2f * density, brushSize * scale / 2f)
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
            if (!eraseHover && stabilizer > 0) {
                canvas.drawCircle(hoverX, hoverY, stringLength(), stringPaint)
            }
        }
    }

    private fun drawGuides(canvas: Canvas) {
        val hair = 1.25f * density / scale
        spec.bleed?.let {
            guidePaint.pathEffect = null
            guidePaint.strokeWidth = hair
            guidePaint.color = 0x99E11D48.toInt()
            canvas.drawRect(it, guidePaint)
        }
        spec.trim?.let {
            guidePaint.pathEffect = null
            guidePaint.strokeWidth = hair
            guidePaint.color = 0xCC2563EB.toInt()
            canvas.drawRect(it, guidePaint)
        }
        spec.safe?.let {
            guidePaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / scale, 4f * density / scale), 0f)
            guidePaint.strokeWidth = hair
            guidePaint.color = 0xAA0891B2.toInt()
            canvas.drawRect(it, guidePaint)
            guidePaint.pathEffect = null
        }
    }

    private fun drawLiveStroke(canvas: Canvas) {
        val n = liveX.size
        if (n == 0) return
        livePaint.color = color
        if (snapActive) {
            snapPaint.strokeWidth = StrokeMath.width(brushSize, averagePressure())
            canvas.drawLine(liveX[0], liveY[0], snapEndX, snapEndY, snapPaint)
            return
        }
        if (n == 1) {
            livePaint.style = Paint.Style.FILL
            canvas.drawCircle(liveX[0], liveY[0], StrokeMath.width(brushSize, liveP[0]) / 2f, livePaint)
            livePaint.style = Paint.Style.STROKE
            return
        }
        for (i in 1 until n) {
            livePaint.strokeWidth = StrokeMath.width(brushSize, (liveP[i - 1] + liveP[i]) * 0.5f)
            canvas.drawLine(liveX[i - 1], liveY[i - 1], liveX[i], liveY[i], livePaint)
        }
    }

    private fun makeChecker(): BitmapShader {
        val cell = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = 0xFFDADADA.toInt() }
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // =====================================================================
    // Input routing
    // =====================================================================

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        var penPresent = false
        for (i in 0 until e.pointerCount) {
            if (isPen(e, i)) { penPresent = true; break }
        }
        if (penPresent || penPointerId != -1) {
            handlePen(e)
        } else {
            handleFingers(e)
        }
        return true
    }

    private fun handlePen(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val ai = e.actionIndex
                if (penPointerId == -1 && isPen(e, ai)) {
                    // The pen wins: drop any finger gesture or finger stroke in progress.
                    if (fingerStroke) { cancelStroke(); fingerStroke = false }
                    if (gestureActive) { gestureActive = false; cacheValid = false }
                    requestUnbufferedDispatch(e)
                    penPointerId = e.getPointerId(ai)
                    val buttonErase = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    val erase = tool == Tool.ERASER || buttonErase ||
                        e.getToolType(ai) == MotionEvent.TOOL_TYPE_ERASER
                    hovering = false
                    beginStroke(e.getX(ai), e.getY(ai), e.getPressure(ai), erase)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penPointerId == -1) return
                val pi = e.findPointerIndex(penPointerId)
                if (pi < 0) return
                for (h in 0 until e.historySize) {
                    continueStroke(e.getHistoricalX(pi, h), e.getHistoricalY(pi, h), e.getHistoricalPressure(pi, h))
                }
                continueStroke(e.getX(pi), e.getY(pi), e.getPressure(pi))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (penPointerId != -1 && e.getPointerId(e.actionIndex) == penPointerId) {
                    if ((e.flags and MotionEvent.FLAG_CANCELED) != 0) cancelStroke() else endStroke()
                    penPointerId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelStroke()
                penPointerId = -1
            }
        }
    }

    private fun handleFingers(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = e.eventTime
                maxPointers = 1
                gestureMoved = false
                if (fingerDraws) {
                    fingerStroke = true
                    beginStroke(e.x, e.y, FINGER_PRESSURE, tool == Tool.ERASER)
                } else {
                    gestureActive = true
                    capture(e, -1)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                if (fingerStroke) {
                    cancelStroke()
                    fingerStroke = false
                }
                gestureActive = true
                capture(e, -1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingerStroke) {
                    for (h in 0 until e.historySize) continueStroke(e.getHistoricalX(0, h), e.getHistoricalY(0, h), FINGER_PRESSURE)
                    continueStroke(e.x, e.y, FINGER_PRESSURE)
                } else if (gestureActive) {
                    applyGesture(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureActive) capture(e, e.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                if (fingerStroke) {
                    endStroke()
                    fingerStroke = false
                } else if (gestureActive) {
                    gestureActive = false
                    val quick = e.eventTime - gestureStartTime < TAP_MS
                    if (quick && !gestureMoved) {
                        when (maxPointers) {
                            2 -> undo()
                            3 -> redo()
                        }
                    }
                    cacheValid = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (fingerStroke) cancelStroke()
                fingerStroke = false
                gestureActive = false
                cacheValid = false
                invalidate()
            }
        }
    }

    /** Records the start of a pan/pinch from the current fingers, ignoring [skip]. */
    private fun capture(e: MotionEvent, skip: Int) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n == 0) return
        val mx = sx / n
        val my = sy / n
        var d = 0f
        if (n >= 2) {
            for (i in 0 until e.pointerCount) {
                if (i == skip) continue
                d += hypot(e.getX(i) - mx, e.getY(i) - my)
            }
            d /= n
        }
        startMidX = mx; startMidY = my; startDist = d
        startScale = scale; startTx = tx; startTy = ty
        capturedCount = n
    }

    private fun applyGesture(e: MotionEvent) {
        val n = e.pointerCount
        if (n != capturedCount) { capture(e, -1); return }
        var sx = 0f
        var sy = 0f
        for (i in 0 until n) { sx += e.getX(i); sy += e.getY(i) }
        val mx = sx / n
        val my = sy / n
        var d = 0f
        if (n >= 2) {
            for (i in 0 until n) d += hypot(e.getX(i) - mx, e.getY(i) - my)
            d /= n
        }
        if (hypot(mx - startMidX, my - startMidY) > touchSlop || abs(d - startDist) > touchSlop) gestureMoved = true

        val newScale = if (n >= 2 && startDist > touchSlop && d > 0f) {
            (startScale * d / startDist).coerceIn(fitScale * 0.2f, MAX_SCALE)
        } else startScale
        // Keep the page point that was under the fingers under the fingers.
        val pageX = (startMidX - startTx) / startScale
        val pageY = (startMidY - startTy) / startScale
        scale = newScale
        tx = mx - pageX * newScale
        ty = my - pageY * newScale
        invalidate()
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!isPen(e, 0)) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                hoverX = e.x
                hoverY = e.y
                hoverButton = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
        }
        invalidate()
        return true
    }

    // =====================================================================
    // Strokes
    // =====================================================================

    private fun stringLength(): Float = stabilizer * 0.6f * density

    private fun toPageX(sx: Float) = (sx - tx) / scale
    private fun toPageY(sy: Float) = (sy - ty) / scale

    private fun beginStroke(x: Float, y: Float, p: Float, erase: Boolean) {
        drawing = true
        erasing = erase
        rawX = x
        rawY = y
        snapActive = false
        if (erase) {
            removedThisStroke.clear()
            eraseAt(x, y)
            invalidate()
            return
        }
        liveX.clear(); liveY.clear(); liveP.clear()
        pressureEma = clampPressure(p)
        string.length = stringLength()
        string.reset(x, y)
        emit(x, y)
        stillX = x
        stillY = y
        scheduleSnap()
        invalidate()
    }

    private fun continueStroke(x: Float, y: Float, p: Float) {
        if (!drawing) return
        rawX = x
        rawY = y
        if (erasing) {
            eraseAt(x, y)
            invalidate()
            return
        }
        pressureEma += (clampPressure(p) - pressureEma) * 0.35f
        if (snapActive) {
            snapEndX = toPageX(x)
            snapEndY = toPageY(y)
            invalidate()
            return
        }
        if (hypot(x - stillX, y - stillY) > SNAP_STILL_DP * density) {
            stillX = x
            stillY = y
            scheduleSnap()
        }
        if (string.length <= 0f) {
            emit(x, y)
        } else if (string.pull(x, y)) {
            emit(string.tip.x, string.tip.y)
        }
        invalidate()
    }

    /** Adds a stabilized screen point to the live stroke, in page coordinates. */
    private fun emit(sx: Float, sy: Float) {
        val px = toPageX(sx)
        val py = toPageY(sy)
        if (liveX.size > 0 && hypot(px - liveX.last(), py - liveY.last()) * scale < 0.75f) return
        liveX.add(px)
        liveY.add(py)
        liveP.add(pressureEma)
    }

    private fun scheduleSnap() {
        removeCallbacks(snapRunnable)
        if (snapLines && drawing && !erasing) postDelayed(snapRunnable, SNAP_HOLD_MS)
    }

    private fun trySnap() {
        if (!drawing || erasing || snapActive || liveX.size < 4) return
        val ex = toPageX(rawX)
        val ey = toPageY(rawY)
        if (hypot(ex - liveX[0], ey - liveY[0]) * scale < 24f * density) return
        snapActive = true
        snapEndX = ex
        snapEndY = ey
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private fun endStroke() {
        removeCallbacks(snapRunnable)
        if (!drawing) return
        drawing = false
        if (erasing) {
            history.recordRemoval(ArrayList(removedThisStroke))
            removedThisStroke.clear()
            erasing = false
            invalidate()
            onChanged?.invoke()
            return
        }
        val stroke = buildStroke()
        snapActive = false
        liveX.clear(); liveY.clear(); liveP.clear()
        if (stroke != null) {
            history.add(stroke)
            appendToCache(stroke)
            onChanged?.invoke()
        }
        invalidate()
    }

    private fun cancelStroke() {
        removeCallbacks(snapRunnable)
        if (drawing && erasing && removedThisStroke.isNotEmpty()) {
            // Strokes already erased stay erased, but remain undoable.
            history.recordRemoval(ArrayList(removedThisStroke))
            removedThisStroke.clear()
            onChanged?.invoke()
        }
        drawing = false
        erasing = false
        snapActive = false
        liveX.clear(); liveY.clear(); liveP.clear()
        invalidate()
    }

    private fun eraseAt(sx: Float, sy: Float) {
        val px = toPageX(sx)
        val py = toPageY(sy)
        val r = eraserSizeDp * density / 2f / scale
        var i = strokes.size - 1
        var any = false
        while (i >= 0) {
            val s = strokes[i]
            if (s.hits(px, py, r)) {
                strokes.removeAt(i)
                removedThisStroke.add(i to s)
                any = true
            }
            i--
        }
        if (any) cacheValid = false
    }

    private fun averagePressure(): Float {
        if (liveP.size == 0) return pressureEma
        var sum = 0f
        for (i in 0 until liveP.size) sum += liveP[i]
        return sum / liveP.size
    }

    private fun buildStroke(): Stroke? {
        val n = liveX.size
        if (n == 0) return null
        // Point spacing: about 1.5 screen px, but never coarser than a third of the brush.
        val spacing = (1.5f / scale).coerceIn(0.5f, max(2f, brushSize * 0.35f))

        if (snapActive) {
            val x0 = liveX[0]
            val y0 = liveY[0]
            val len = hypot(snapEndX - x0, snapEndY - y0)
            val count = max(2, (len / spacing).toInt() + 1)
            val p = averagePressure()
            val xs = FloatArray(count) { x0 + (snapEndX - x0) * it / (count - 1) }
            val ys = FloatArray(count) { y0 + (snapEndY - y0) * it / (count - 1) }
            val ps = FloatArray(count) { p }
            return Stroke(xs, ys, StrokeMath.widths(xs, ys, ps, brushSize, taper), color)
        }

        if (n == 1) {
            return Stroke(
                floatArrayOf(liveX[0]), floatArrayOf(liveY[0]),
                floatArrayOf(StrokeMath.width(brushSize, liveP[0])), color
            )
        }

        val r = StrokeMath.resample(liveX.toArray(), liveY.toArray(), liveP.toArray(), spacing)
        val sigmaScreenPx = smoothing * 0.15f * density
        val sigmaPts = sigmaScreenPx / (spacing * scale)
        val xs = StrokeMath.smooth(r.xs, sigmaPts)
        val ys = StrokeMath.smooth(r.ys, sigmaPts)
        val ps = StrokeMath.smooth(r.ps, sigmaPts)
        return Stroke(xs, ys, StrokeMath.widths(xs, ys, ps, brushSize, taper), color)
    }

    private fun clampPressure(p: Float): Float = if (p <= 0f) 0.5f else p.coerceIn(0.05f, 1f)

    private companion object {
        const val FINGER_PRESSURE = 0.6f
        const val TAP_MS = 300L
        const val MAX_SCALE = 12f
        const val SNAP_HOLD_MS = 550L
        const val SNAP_STILL_DP = 6f
    }
}
