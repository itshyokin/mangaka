package com.mangaka.draw

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Pen settings kept separately for each mode. */
class BrushSettings(
    var size: Float,
    var stabilizer: Int,
    var smoothing: Int,
    var taper: Boolean,
    var snap: Boolean,
    var color: Int,
    var joinEnds: Boolean,
)

enum class Mode(val key: String, val label: String) {
    /** Clean final lines: stabilizer, smoothing, taper and snap on by default. */
    INK("ink", "Ink"),

    /** Free drawing and coloring: everything that changes your line is off by default. */
    SKETCH("sketch", "Sketch / Color"),
}

class MainActivity : Activity() {

    private lateinit var page: DrawingView
    private lateinit var layersPanel: LayersPanel
    private lateinit var prefs: SharedPreferences
    private val pageFile by lazy { File(filesDir, "current.mpage") }

    private var mode = Mode.INK
    private val settings = mutableMapOf(
        Mode.INK to BrushSettings(12f, 40, 40, taper = true, snap = true, color = Color.BLACK, joinEnds = true),
        Mode.SKETCH to BrushSettings(8f, 0, 0, taper = false, snap = false, color = Color.BLACK, joinEnds = false),
    )
    private val current: BrushSettings get() = settings.getValue(mode)

    // Controls that change with the mode or tool.
    private val modeButtons = mutableMapOf<Mode, Button>()
    private lateinit var sizeSlider: SliderHandle
    private lateinit var stabilizerSlider: SliderHandle
    private lateinit var smoothingSlider: SliderHandle
    private lateinit var taperToggle: ToggleHandle
    private lateinit var snapToggle: ToggleHandle
    private lateinit var joinToggle: ToggleHandle
    private lateinit var strokeEraseButton: Button
    private lateinit var pixelEraseButton: Button
    private lateinit var colorSwatch: View
    private lateinit var penGroup: View
    private lateinit var eraserGroup: View
    private lateinit var fillGroup: View
    private lateinit var endsGroup: View

    private lateinit var penButton: Button
    private lateinit var eraserButton: Button
    private lateinit var fillButton: Button
    private lateinit var endsButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var layersToggle: ToggleHandle

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        page = DrawingView(this)
        loadSettings()
        PageStore.load(pageFile)?.let { (spec, doc) -> page.loadPage(spec, doc) }
        layersPanel = LayersPanel(this, page)
        layersPanel.visibility = if (prefs.getBoolean("layersOpen", true)) View.VISIBLE else View.GONE

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        middle.addView(page, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        middle.addView(layersPanel, LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.MATCH_PARENT))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TOOLBAR_BG)
        }
        root.addView(scrollable(buildTopBar()), matchWrap())
        root.addView(middle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(scrollable(buildBottomBar()), matchWrap())

        // Android 15 draws apps edge to edge; keep both bars clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        setContentView(root)
        page.onChanged = {
            updateButtons()
            layersPanel.refresh()
        }
        page.onMessage = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        applyMode()
        updateButtons()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        try {
            PageStore.save(pageFile, page.spec, page.doc)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save the page: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // Bars
    // ---------------------------------------------------------------------

    /** Top bar: mode, then the settings for whichever tool is selected. */
    private fun buildTopBar(): View {
        val bar = barLayout()

        for (m in Mode.entries) {
            val b = button(m.label) { setMode(m) }
            modeButtons[m] = b
            bar.addView(b)
        }
        bar.addView(divider())

        // Pen settings
        val pen = barLayout().apply { setPadding(0, 0, 0, 0) }
        sizeSlider = slider("Size", 1, 120, current.size.toInt(), { sizeText(it) }) {
            current.size = it.toFloat()
            page.brushSize = current.size
        }
        stabilizerSlider = slider("Stabilizer", 0, 100, current.stabilizer, { if (it == 0) "off" else "$it" }) {
            current.stabilizer = it
            page.stabilizer = it
        }
        smoothingSlider = slider("Smoothing", 0, 100, current.smoothing, { if (it == 0) "off" else "$it" }) {
            current.smoothing = it
            page.smoothing = it
        }
        for (s in listOf(sizeSlider, stabilizerSlider, smoothingSlider)) {
            pen.addView(s.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        taperToggle = toggle("Taper", current.taper) { current.taper = it; page.taper = it }
        snapToggle = toggle("Snap lines", current.snap) { current.snap = it; page.snapLines = it }
        joinToggle = toggle("Join ends", current.joinEnds) { current.joinEnds = it; page.joinEnds = it }
        pen.addView(taperToggle.button)
        pen.addView(snapToggle.button)
        pen.addView(joinToggle.button)
        penGroup = pen

        // Eraser settings
        val eraser = barLayout().apply { setPadding(0, 0, 0, 0) }
        val eraserSize = slider("Eraser size", 8, 120, page.eraserSizeDp.toInt(), { "$it dp" }) {
            page.eraserSizeDp = it.toFloat()
        }
        eraser.addView(eraserSize.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        strokeEraseButton = button("Whole strokes") { page.eraserMode = DrawingView.EraserMode.STROKE; updateButtons() }
        pixelEraseButton = button("Pixel") { page.eraserMode = DrawingView.EraserMode.PIXEL; updateButtons() }
        eraser.addView(strokeEraseButton)
        eraser.addView(pixelEraseButton)
        eraser.addView(hint("Whole strokes removes any line or color it touches. Pixel rubs out just what's under it."),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        eraserGroup = eraser

        // Fill settings
        val fill = barLayout().apply { setPadding(0, 0, 0, 0) }
        val gap = slider("Close gaps", 0, 10, page.fillGap, { if (it == 0) "off" else String.format(Locale.US, "%.1f mm", it * 0.5f) }) {
            page.fillGap = it
        }
        fill.addView(gap.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fill.addView(hint("Fills the area between lines on reference layers (or all visible layers if none are marked). Color goes on the selected layer, under its lines."),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        fillGroup = fill

        // Ends tool
        val ends = barLayout().apply { setPadding(0, 0, 0, 0) }
        ends.addView(hint("Drag a line's end to move it; the line bends to follow. Orange dots are open ends. Ends snap onto other ends and lines (green ring)."),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        endsGroup = ends

        for (g in listOf(penGroup, eraserGroup, fillGroup, endsGroup)) {
            bar.addView(g, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    /** Bottom bar: tools, history, view, and page actions. */
    private fun buildBottomBar(): View {
        val bar = barLayout()

        penButton = button("Pen") { page.tool = DrawingView.Tool.PEN; updateButtons() }
        eraserButton = button("Eraser") { page.tool = DrawingView.Tool.ERASER; updateButtons() }
        fillButton = button("Fill") { page.tool = DrawingView.Tool.FILL; updateButtons() }
        endsButton = button("Ends") { page.tool = DrawingView.Tool.ENDS; updateButtons() }
        colorSwatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) }
            contentDescription = "Color"
            setOnClickListener { showPalette() }
        }
        bar.addView(penButton)
        bar.addView(eraserButton)
        bar.addView(fillButton)
        bar.addView(endsButton)
        bar.addView(colorSwatch)
        bar.addView(spacer())

        undoButton = button("Undo") { page.undo() }
        redoButton = button("Redo") { page.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        bar.addView(spacer())

        layersToggle = toggle("Layers", layersPanel.visibility == View.VISIBLE) {
            layersPanel.visibility = if (it) View.VISIBLE else View.GONE
        }
        bar.addView(layersToggle.button)
        bar.addView(button("Fit") { page.fitToScreen() })
        bar.addView(toggle("Paper", page.showPaper) { page.showPaper = it }.button)
        bar.addView(toggle("Guides", page.showGuides) { page.showGuides = it }.button)
        bar.addView(toggle("Finger draws", page.fingerDraws) { page.fingerDraws = it }.button)
        bar.addView(spacer())

        bar.addView(button("New page") { showNewPage() })
        bar.addView(button("Clear layer") { confirmClear() })
        bar.addView(button("Export") { showExport() })
        return bar
    }

    // ---------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------

    private fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        applyMode()
    }

    /** Pushes the current mode's settings into the canvas and the controls. */
    private fun applyMode() {
        val s = current
        page.brushSize = s.size
        page.stabilizer = s.stabilizer
        page.smoothing = s.smoothing
        page.taper = s.taper
        page.snapLines = s.snap
        page.joinEnds = s.joinEnds
        page.color = s.color
        if (page.tool == DrawingView.Tool.ERASER) page.tool = DrawingView.Tool.PEN

        sizeSlider.set(s.size.toInt())
        stabilizerSlider.set(s.stabilizer)
        smoothingSlider.set(s.smoothing)
        taperToggle.set(s.taper)
        snapToggle.set(s.snap)
        joinToggle.set(s.joinEnds)
        for ((m, b) in modeButtons) b.background = pill(if (m == mode) ACTIVE_BG else BUTTON_BG)
        updateButtons()
    }

    // ---------------------------------------------------------------------
    // Color
    // ---------------------------------------------------------------------

    private fun showPalette() {
        val columns = 6
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        var dialog: AlertDialog? = null
        PALETTE.chunked(columns).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in rowColors) {
                val chip = View(this).apply {
                    background = swatch(c, selected = c == current.color)
                    setOnClickListener {
                        current.color = c
                        page.color = c
                        updateButtons()
                        dialog?.dismiss()
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            grid.addView(row)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Color (${mode.label})")
            .setView(grid)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun swatch(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(if (selected) 3 else 1), if (selected) ACTIVE_BG else 0x66FFFFFF)
    }

    // ---------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------

    private inner class SliderHandle(val view: View, private val bar: SeekBar) {
        fun set(value: Int) {
            bar.progress = value.coerceIn(bar.min, bar.max)
        }
    }

    private inner class ToggleHandle(val button: Button, var on: Boolean) {
        fun set(value: Boolean) {
            on = value
            button.background = pill(if (on) ACTIVE_BG else BUTTON_BG)
        }
    }

    private fun barLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    /** Stretches to fill the screen width, and scrolls only if the screen is too narrow. */
    private fun scrollable(content: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun sizeText(px: Int): String {
        val mm = page.spec.pxToMm(px.toFloat())
        return String.format(Locale.US, "%d px · %.2f mm", px, mm)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(LABEL_COLOR)
        setPadding(dp(8), 0, dp(8), 0)
        minimumWidth = dp(160)
        // Wrap onto a few lines instead of stretching the bar.
        maxWidth = dp(340)
        maxLines = 3
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        background = pill(BUTTON_BG)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            marginEnd = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): ToggleHandle {
        val b = button(text) {}
        val handle = ToggleHandle(b, initial)
        handle.set(initial)
        b.setOnClickListener {
            handle.set(!handle.on)
            onChange(handle.on)
        }
        return handle
    }

    private fun slider(
        name: String,
        min: Int,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): SliderHandle {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(10), 0)
            minimumWidth = dp(140)
        }
        val label = TextView(this).apply {
            textSize = 12f
            setTextColor(LABEL_COLOR)
            text = "$name: ${format(initial)}"
        }
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = initial.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = "$name: ${format(value)}"
                    onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        box.addView(label)
        box.addView(bar, matchWrap())
        return SliderHandle(box, bar)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(32)).apply {
            marginStart = dp(6)
            marginEnd = dp(12)
        }
    }

    /** Flexible gap between button groups; collapses to a small gap on narrow screens. */
    private fun spacer(): View = View(this).apply {
        minimumWidth = dp(12)
        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun updateButtons() {
        val tool = page.tool
        penButton.background = pill(if (tool == DrawingView.Tool.PEN) ACTIVE_BG else BUTTON_BG)
        eraserButton.background = pill(if (tool == DrawingView.Tool.ERASER) ACTIVE_BG else BUTTON_BG)
        fillButton.background = pill(if (tool == DrawingView.Tool.FILL) ACTIVE_BG else BUTTON_BG)
        endsButton.background = pill(if (tool == DrawingView.Tool.ENDS) ACTIVE_BG else BUTTON_BG)
        penGroup.visibility = if (tool == DrawingView.Tool.PEN) View.VISIBLE else View.GONE
        eraserGroup.visibility = if (tool == DrawingView.Tool.ERASER) View.VISIBLE else View.GONE
        fillGroup.visibility = if (tool == DrawingView.Tool.FILL) View.VISIBLE else View.GONE
        endsGroup.visibility = if (tool == DrawingView.Tool.ENDS) View.VISIBLE else View.GONE
        val pixel = page.eraserMode == DrawingView.EraserMode.PIXEL
        strokeEraseButton.background = pill(if (!pixel) ACTIVE_BG else BUTTON_BG)
        pixelEraseButton.background = pill(if (pixel) ACTIVE_BG else BUTTON_BG)
        colorSwatch.background = swatch(current.color, selected = false)
        undoButton.isEnabled = page.history.canUndo
        redoButton.isEnabled = page.history.canRedo
        undoButton.alpha = if (page.history.canUndo) 1f else 0.4f
        redoButton.alpha = if (page.history.canRedo) 1f else 0.4f
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun showNewPage() {
        NewPageDialog.show(this, page.spec) { spec ->
            page.newPage(spec)
            PageStore.save(pageFile, page.spec, page.doc)
            sizeSlider.set(current.size.toInt())
            updateButtons()
        }
    }

    private fun confirmClear() {
        val layer = page.doc.active
        AlertDialog.Builder(this)
            .setTitle("Clear “${layer.name}”?")
            .setMessage("Removes everything on this layer. You can undo this.")
            .setPositiveButton("Clear") { _, _ -> page.clearLayer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExport() {
        val options = arrayOf("PNG, transparent background", "PNG, white background")
        AlertDialog.Builder(this)
            .setTitle("Export page")
            .setItems(options) { _, which -> export(transparent = which == 0) }
            .show()
    }

    private fun export(transparent: Boolean) {
        val spec = page.spec
        val snapshot = page.doc.snapshot()
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show()
        Thread {
            val uri = try {
                PageStore.exportPng(applicationContext, spec, snapshot, transparent)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                val msg = if (uri != null) "Saved to Pictures/Mangaka" else "Export failed"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    private fun loadSettings() {
        mode = if (prefs.getString("mode", Mode.INK.key) == Mode.SKETCH.key) Mode.SKETCH else Mode.INK
        for (m in Mode.entries) {
            val s = settings.getValue(m)
            val k = m.key
            s.size = prefs.getFloat("$k.size", s.size)
            s.stabilizer = prefs.getInt("$k.stabilizer", s.stabilizer)
            s.smoothing = prefs.getInt("$k.smoothing", s.smoothing)
            s.taper = prefs.getBoolean("$k.taper", s.taper)
            s.snap = prefs.getBoolean("$k.snap", s.snap)
            s.color = prefs.getInt("$k.color", s.color)
            s.joinEnds = prefs.getBoolean("$k.joinEnds", s.joinEnds)
        }
        page.eraserSizeDp = prefs.getFloat("eraserSizeDp", 32f)
        page.eraserMode = if (prefs.getString("eraserMode", "stroke") == "pixel") DrawingView.EraserMode.PIXEL else DrawingView.EraserMode.STROKE
        page.fillGap = prefs.getInt("fillGap", 3)
        page.showPaper = prefs.getBoolean("showPaper", true)
        page.showGuides = prefs.getBoolean("showGuides", true)
        page.fingerDraws = prefs.getBoolean("fingerDraws", false)
    }

    private fun saveSettings() {
        val e = prefs.edit()
        e.putString("mode", mode.key)
        for (m in Mode.entries) {
            val s = settings.getValue(m)
            val k = m.key
            e.putFloat("$k.size", s.size)
            e.putInt("$k.stabilizer", s.stabilizer)
            e.putInt("$k.smoothing", s.smoothing)
            e.putBoolean("$k.taper", s.taper)
            e.putBoolean("$k.snap", s.snap)
            e.putInt("$k.color", s.color)
            e.putBoolean("$k.joinEnds", s.joinEnds)
        }
        e.putFloat("eraserSizeDp", page.eraserSizeDp)
        e.putString("eraserMode", if (page.eraserMode == DrawingView.EraserMode.PIXEL) "pixel" else "stroke")
        e.putInt("fillGap", page.fillGap)
        e.putBoolean("showPaper", page.showPaper)
        e.putBoolean("showGuides", page.showGuides)
        e.putBoolean("fingerDraws", page.fingerDraws)
        e.putBoolean("layersOpen", layersPanel.visibility == View.VISIBLE)
        e.apply()
    }

    private companion object {
        const val TOOLBAR_BG = 0xFF1F1F24.toInt()
        const val BUTTON_BG = 0xFF33333B.toInt()
        const val ACTIVE_BG = 0xFF2563EB.toInt()
        const val LABEL_COLOR = 0xFFB8B8C2.toInt()

        val PALETTE = listOf(
            // Grays, plus non-photo blue for sketch lines
            0xFF000000, 0xFF404040, 0xFF808080, 0xFFBFBFBF, 0xFFFFFFFF, 0xFF8FC7E8,
            // Warm
            0xFFB91C1C, 0xFFEF4444, 0xFFF97316, 0xFFFBBF24, 0xFFFDE68A, 0xFF92400E,
            // Cool
            0xFF15803D, 0xFF4ADE80, 0xFF0EA5E9, 0xFF1D4ED8, 0xFF7C3AED, 0xFFDB2777,
            // Skin and hair tones
            0xFFFDE2CF, 0xFFF5C6A5, 0xFFD9A07B, 0xFFA8714F, 0xFF6B4226, 0xFF3B2416,
        ).map { it.toInt() }
    }
}
