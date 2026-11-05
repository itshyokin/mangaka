package com.mangaka.draw

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.roundToInt

/** "New page" picker: preset, resolution, and custom size. */
object NewPageDialog {

    fun show(activity: Activity, current: PageSpec, onCreate: (PageSpec) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val presets = Preset.entries
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(4))
        }

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }

        root.addView(label("Page size"))
        val presetSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, presets.map { it.label })
            setSelection(presets.indexOf(current.preset).coerceAtLeast(0))
        }
        root.addView(presetSpinner)

        // Custom size row
        val customRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val widthField = EditText(activity).apply {
            hint = "Width"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val heightField = EditText(activity).apply {
            hint = "Height"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val unitSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("mm", "px"))
        }
        customRow.addView(widthField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        customRow.addView(heightField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        customRow.addView(unitSpinner)
        val customLabel = label("Custom size")
        root.addView(customLabel)
        root.addView(customRow)

        // Resolution
        val dpiLabel = label("Resolution")
        root.addView(dpiLabel)
        val dpiGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val dpi600 = RadioButton(activity).apply { text = "600 DPI (black & white)"; id = View.generateViewId() }
        val dpi350 = RadioButton(activity).apply { text = "350 DPI (color)"; id = View.generateViewId() }
        dpiGroup.addView(dpi600)
        dpiGroup.addView(dpi350)
        dpiGroup.check(if (current.dpi in 300..400) dpi350.id else dpi600.id)
        root.addView(dpiGroup)

        val info = TextView(activity).apply {
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(info)

        fun selectedDpi() = if (dpiGroup.checkedRadioButtonId == dpi350.id) 350 else 600

        fun build(): PageSpec? {
            val preset = presets[presetSpinner.selectedItemPosition]
            if (preset != Preset.CUSTOM) return PageSpec.fromPreset(preset, selectedDpi())
            val w = widthField.text.toString().toFloatOrNull() ?: return null
            val h = heightField.text.toString().toFloatOrNull() ?: return null
            if (w <= 0f || h <= 0f) return null
            return if (unitSpinner.selectedItemPosition == 0) {
                val dpi = selectedDpi()
                PageSpec.custom((w / 25.4f * dpi).roundToInt(), (h / 25.4f * dpi).roundToInt(), dpi)
            } else {
                PageSpec.custom(w.roundToInt(), h.roundToInt(), 72)
            }
        }

        fun refresh() {
            val preset = presets[presetSpinner.selectedItemPosition]
            val custom = preset == Preset.CUSTOM
            customLabel.visibility = if (custom) View.VISIBLE else View.GONE
            customRow.visibility = if (custom) View.VISIBLE else View.GONE
            val needsDpi = preset.usesMm || (custom && unitSpinner.selectedItemPosition == 0)
            dpiLabel.visibility = if (needsDpi) View.VISIBLE else View.GONE
            dpiGroup.visibility = if (needsDpi) View.VISIBLE else View.GONE
            val spec = build()
            val guides = if (spec?.trim != null) "\nShows bleed (red), trim (blue) and safe-area (dashed) guides." else ""
            info.text = (spec?.describe() ?: "Enter a width and height.") + guides +
                "\n\nThis replaces the current page and clears undo history."
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        presetSpinner.onItemSelectedListener = listener
        unitSpinner.onItemSelectedListener = listener
        dpiGroup.setOnCheckedChangeListener { _, _ -> refresh() }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        widthField.addTextChangedListener(watcher)
        heightField.addTextChangedListener(watcher)
        refresh()

        AlertDialog.Builder(activity)
            .setTitle("New page")
            .setView(root)
            .setPositiveButton("Create") { _, _ -> build()?.let(onCreate) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
