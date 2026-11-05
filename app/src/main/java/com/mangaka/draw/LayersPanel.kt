package com.mangaka.draw

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Side panel listing the page's layers, top layer first.
 * Tap a layer to draw on it; long-press to rename.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class LayersPanel(context: Context, private val page: DrawingView) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val opacityLabel = TextView(context)
    private val opacityBar = SeekBar(context)
    private val blendButton: Button
    private val referenceButton: Button
    private val lockButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL_BG)
        setPadding(dp(10), dp(10), dp(10), dp(10))

        addView(TextView(context).apply {
            text = "Layers"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(2), 0, 0, dp(8))
        })

        addView(row(
            small("+ New") { page.addLayer() },
            small("Copy") { page.duplicateLayer() },
            small("Merge ↓") { page.mergeDown() },
            small("Delete") { confirmDelete() },
        ))

        addView(ScrollView(context).apply {
            addView(list)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Settings for the selected layer
        addView(rule())
        addView(row(
            small("▲ Up") { page.moveActiveLayer(+1) },
            small("▼ Down") { page.moveActiveLayer(-1) },
            small("Rename") { rename(page.doc.active) },
        ))
        opacityLabel.apply {
            textSize = 12f
            setTextColor(LABEL_COLOR)
            setPadding(dp(2), dp(8), 0, 0)
        }
        addView(opacityLabel)
        opacityBar.apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    page.doc.active.opacity = value / 100f
                    opacityLabel.text = "Opacity: $value%"
                    page.layerPropertiesChanged()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        addView(opacityBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        blendButton = small("Blend") {
            val layer = page.doc.active
            val modes = LayerBlend.entries
            layer.blend = modes[(modes.indexOf(layer.blend) + 1) % modes.size]
            page.layerPropertiesChanged()
        }
        referenceButton = small("Reference") {
            val layer = page.doc.active
            layer.reference = !layer.reference
            page.layerPropertiesChanged()
        }
        lockButton = small("Lock") {
            val layer = page.doc.active
            layer.locked = !layer.locked
            page.layerPropertiesChanged()
        }
        addView(row(blendButton, referenceButton, lockButton))
        addView(TextView(context).apply {
            text = "Reference layers hold the lines the paint bucket fills between."
            textSize = 11f
            setTextColor(LABEL_COLOR)
            setPadding(dp(2), dp(6), 0, 0)
        })

        refresh()
    }

    /** Rebuilds the list and the selected layer's settings from the document. */
    fun refresh() {
        val doc = page.doc
        list.removeAllViews()
        for (i in doc.layers.indices.reversed()) list.addView(layerRow(doc.layers[i], i == doc.activeIndex))

        val active = doc.active
        val pct = (active.opacity * 100f).toInt()
        opacityLabel.text = "Opacity: $pct%"
        opacityBar.progress = pct
        blendButton.text = active.blend.label
        referenceButton.background = pill(if (active.reference) ACTIVE_BG else BUTTON_BG)
        lockButton.background = pill(if (active.locked) ACTIVE_BG else BUTTON_BG)
        lockButton.text = if (active.locked) "Locked" else "Lock"
    }

    private fun layerRow(layer: Layer, selected: Boolean): View {
        val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(if (selected) SELECTED_BG else ROW_BG)
            setPadding(dp(4), dp(4), dp(10), dp(4))
            setOnClickListener { page.selectLayer(layer) }
            setOnLongClickListener { rename(layer); true }
        }
        val eye = Button(context).apply {
            text = if (layer.visible) "◉" else "○"
            textSize = 16f
            setTextColor(if (layer.visible) Color.WHITE else LABEL_COLOR)
            background = null
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            contentDescription = if (layer.visible) "Hide layer" else "Show layer"
            setOnClickListener {
                layer.visible = !layer.visible
                page.layerPropertiesChanged()
            }
        }
        rowView.addView(eye, LayoutParams(dp(40), dp(40)))

        val tags = buildList {
            if (layer.reference) add("ref")
            if (layer.locked) add("locked")
            if (layer.blend != LayerBlend.NORMAL) add(layer.blend.label.lowercase())
            if (layer.opacity < 0.999f) add("${(layer.opacity * 100).toInt()}%")
        }
        val name = TextView(context).apply {
            text = layer.name
            textSize = 14f
            setTextColor(if (layer.visible) Color.WHITE else LABEL_COLOR)
            maxLines = 1
        }
        val tagView = TextView(context).apply {
            text = tags.joinToString(" · ")
            textSize = 11f
            setTextColor(LABEL_COLOR)
            maxLines = 1
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(name)
            if (tags.isNotEmpty()) addView(tagView)
        }
        rowView.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        return rowView.also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
    }

    private fun rename(layer: Layer) {
        val field = EditText(context).apply {
            setText(layer.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(context)
            .setTitle("Rename layer")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = field.text.toString().trim()
                if (n.isNotEmpty()) {
                    layer.name = n
                    page.layerPropertiesChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun confirmDelete() {
        val layer = page.doc.active
        AlertDialog.Builder(context)
            .setTitle("Delete “${layer.name}”?")
            .setMessage("You can undo this.")
            .setPositiveButton("Delete") { _, _ -> page.deleteActiveLayer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        for (v in views) addView(v, LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
    }

    private fun small(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        background = pill(BUTTON_BG)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun rule() = View(context).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = 8 * d
        setColor(color)
    }

    private companion object {
        const val PANEL_BG = 0xFF26262C.toInt()
        const val ROW_BG = 0xFF30303A.toInt()
        const val SELECTED_BG = 0xFF1E3A8A.toInt()
        const val BUTTON_BG = 0xFF3A3A44.toInt()
        const val ACTIVE_BG = 0xFF2563EB.toInt()
        const val LABEL_COLOR = 0xFFB8B8C2.toInt()
    }
}
