package com.mangaka.draw

import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Canvas presets. Sizes are in millimetres for print presets and pixels for screen presets.
 *
 * Manga manuscript (B4) follows the traditional Japanese manuscript layout:
 * 257 x 364 mm paper, 220 x 310 mm trim (finished size, printed reduced to B5),
 * 180 x 270 mm inner "safe" frame, and 5 mm bleed beyond the trim.
 */
enum class Preset(
    val id: String,
    val label: String,
    val usesMm: Boolean,
    val widthMm: Float = 0f,
    val heightMm: Float = 0f,
    val trimWMm: Float = 0f,
    val trimHMm: Float = 0f,
    val safeWMm: Float = 0f,
    val safeHMm: Float = 0f,
    val bleedMm: Float = 0f,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    MANGA_B4("manga_b4", "Manga manuscript (B4)", true, 257f, 364f, 220f, 310f, 180f, 270f, 5f),
    TANKOBON_B5("tankobon_b5", "Tankōbon (B5, print size)", true, 188f, 263f, 182f, 257f, 150f, 220f, 3f),
    A4("a4", "A4", true, 210f, 297f, 0f, 0f, 190f, 277f, 0f),
    SQUARE("square", "Square (3000 × 3000 px)", false, widthPx = 3000, heightPx = 3000),
    WEBTOON("webtoon", "Webtoon strip (800 × 12800 px)", false, widthPx = 800, heightPx = 12800),
    CUSTOM("custom", "Custom size", false);

    companion object {
        fun byId(id: String): Preset = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

class PageSpec(val preset: Preset, val dpi: Int, val widthPx: Int, val heightPx: Int) {

    /** Finished (trimmed) page edge, in page pixels. */
    val trim: RectF?

    /** Bleed edge: art that should print to the paper edge must reach this line. */
    val bleed: RectF?

    /** Inner frame: keep text and important art inside it. */
    val safe: RectF?

    init {
        if (preset.usesMm && preset.trimWMm > 0f) {
            trim = centered(preset.trimWMm, preset.trimHMm)
            bleed = centered(preset.trimWMm + 2f * preset.bleedMm, preset.trimHMm + 2f * preset.bleedMm)
        } else {
            trim = null
            bleed = null
        }
        safe = if (preset.usesMm && preset.safeWMm > 0f) centered(preset.safeWMm, preset.safeHMm) else null
    }

    private fun mmToPx(mm: Float): Float = mm / 25.4f * dpi

    private fun centered(wMm: Float, hMm: Float): RectF {
        val w = mmToPx(wMm)
        val h = mmToPx(hMm)
        val l = (widthPx - w) / 2f
        val t = (heightPx - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    fun pxToMm(px: Float): Float = px / dpi * 25.4f

    fun describe(): String {
        val mb = widthPx.toLong() * heightPx * 4 / (1024 * 1024)
        return "$widthPx × $heightPx px · $dpi DPI · about $mb MB per full-page image"
    }

    companion object {
        fun fromPreset(p: Preset, dpi: Int): PageSpec {
            return if (p.usesMm) {
                PageSpec(p, dpi, (p.widthMm / 25.4f * dpi).roundToInt(), (p.heightMm / 25.4f * dpi).roundToInt())
            } else {
                PageSpec(p, 72, p.widthPx, p.heightPx)
            }
        }

        fun custom(widthPx: Int, heightPx: Int, dpi: Int): PageSpec =
            PageSpec(Preset.CUSTOM, dpi, widthPx.coerceIn(16, 20000), heightPx.coerceIn(16, 30000))
    }
}
