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

class MainActivity : Activity() {

    private lateinit var page: DrawingView
    private lateinit var prefs: SharedPreferences
    private val pageFile by lazy { File(filesDir, "current.mpage") }

    private lateinit var penButton: Button
    private lateinit var eraserButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private var sizeLabel: TextView? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        page = DrawingView(this)
        loadSettings()
        PageStore.load(pageFile)?.let { (spec, strokes) -> page.loadPage(spec, strokes) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TOOLBAR_BG)
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buildToolbar())
        }
        root.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Android 15 draws apps edge to edge; keep the toolbar clear of the status bar.
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
        page.onChanged = { updateButtons() }
        updateButtons()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        try {
            PageStore.save(pageFile, page.spec, ArrayList(page.strokes))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save the page: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------------
    // Toolbar
    // ---------------------------------------------------------------------

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        penButton = button("Pen") { page.tool = DrawingView.Tool.PEN; updateButtons() }
        eraserButton = button("Eraser") { page.tool = DrawingView.Tool.ERASER; updateButtons() }
        bar.addView(penButton)
        bar.addView(eraserButton)
        bar.addView(divider())

        bar.addView(slider("Size", 1, 120, page.brushSize.toInt(), { sizeText(it) }) {
            page.brushSize = it.toFloat()
        })
        bar.addView(slider("Eraser", 8, 120, page.eraserSizeDp.toInt(), { "$it dp" }) {
            page.eraserSizeDp = it.toFloat()
        })
        bar.addView(slider("Stabilizer", 0, 100, page.stabilizer, { if (it == 0) "off" else "$it" }) {
            page.stabilizer = it
        })
        bar.addView(slider("Smoothing", 0, 100, page.smoothing, { if (it == 0) "off" else "$it" }) {
            page.smoothing = it
        })
        bar.addView(divider())

        bar.addView(toggle("Taper", page.taper) { page.taper = it })
        bar.addView(toggle("Snap lines", page.snapLines) { page.snapLines = it })
        bar.addView(toggle("Paper", page.showPaper) { page.showPaper = it })
        bar.addView(toggle("Guides", page.showGuides) { page.showGuides = it })
        bar.addView(toggle("Finger draws", page.fingerDraws) { page.fingerDraws = it })
        bar.addView(divider())

        undoButton = button("Undo") { page.undo() }
        redoButton = button("Redo") { page.redo() }
        bar.addView(undoButton)
        bar.addView(redoButton)
        bar.addView(divider())

        bar.addView(button("Fit") { page.fitToScreen() })
        bar.addView(button("New page") { showNewPage() })
        bar.addView(button("Clear") { confirmClear() })
        bar.addView(button("Export") { showExport() })
        return bar
    }

    private fun sizeText(px: Int): String {
        val mm = page.spec.pxToMm(px.toFloat())
        return String.format(Locale.US, "%d px · %.2f mm", px, mm)
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

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): Button {
        var on = initial
        val b = button(text) {}
        fun paint() { b.background = pill(if (on) ACTIVE_BG else BUTTON_BG) }
        paint()
        b.setOnClickListener {
            on = !on
            paint()
            onChange(on)
        }
        return b
    }

    private fun slider(
        name: String,
        min: Int,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(10), 0)
        }
        val label = TextView(this).apply {
            textSize = 12f
            setTextColor(LABEL_COLOR)
            text = "$name: ${format(initial)}"
        }
        if (name == "Size") sizeLabel = label
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
        box.addView(bar, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(32)).apply {
            marginStart = dp(6)
            marginEnd = dp(12)
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun updateButtons() {
        penButton.background = pill(if (page.tool == DrawingView.Tool.PEN) ACTIVE_BG else BUTTON_BG)
        eraserButton.background = pill(if (page.tool == DrawingView.Tool.ERASER) ACTIVE_BG else BUTTON_BG)
        undoButton.isEnabled = page.history.canUndo
        redoButton.isEnabled = page.history.canRedo
        undoButton.alpha = if (page.history.canUndo) 1f else 0.4f
        redoButton.alpha = if (page.history.canRedo) 1f else 0.4f
        sizeLabel?.text = "Size: ${sizeText(page.brushSize.toInt())}"
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun showNewPage() {
        NewPageDialog.show(this, page.spec) { spec ->
            page.newPage(spec)
            PageStore.save(pageFile, page.spec, emptyList())
            updateButtons()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear page?")
            .setMessage("Removes every stroke. You can undo this.")
            .setPositiveButton("Clear") { _, _ -> page.clearPage() }
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
        val snapshot = ArrayList(page.strokes)
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
        page.brushSize = prefs.getFloat("brushSize", 12f)
        page.eraserSizeDp = prefs.getFloat("eraserSizeDp", 32f)
        page.stabilizer = prefs.getInt("stabilizer", 40)
        page.smoothing = prefs.getInt("smoothing", 40)
        page.taper = prefs.getBoolean("taper", true)
        page.snapLines = prefs.getBoolean("snapLines", true)
        page.showPaper = prefs.getBoolean("showPaper", true)
        page.showGuides = prefs.getBoolean("showGuides", true)
        page.fingerDraws = prefs.getBoolean("fingerDraws", false)
    }

    private fun saveSettings() {
        prefs.edit()
            .putFloat("brushSize", page.brushSize)
            .putFloat("eraserSizeDp", page.eraserSizeDp)
            .putInt("stabilizer", page.stabilizer)
            .putInt("smoothing", page.smoothing)
            .putBoolean("taper", page.taper)
            .putBoolean("snapLines", page.snapLines)
            .putBoolean("showPaper", page.showPaper)
            .putBoolean("showGuides", page.showGuides)
            .putBoolean("fingerDraws", page.fingerDraws)
            .apply()
    }

    private companion object {
        const val TOOLBAR_BG = 0xFF1F1F24.toInt()
        const val BUTTON_BG = 0xFF33333B.toInt()
        const val ACTIVE_BG = 0xFF2563EB.toInt()
        const val LABEL_COLOR = 0xFFB8B8C2.toInt()
    }
}
