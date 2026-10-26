package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

internal enum class MenuPage { ROOT, NAVIGATION, BOOKMARKS, LAYOUT, SCREEN, PRESETS, SETTINGS, IMPORT }
internal enum class MenuAction {
    BACK, NAVIGATION, BOOKMARKS, LAYOUT, SCREEN, FONT_DOWN, FONT_UP,
    SPACING, COLUMNS, OPTICAL, REFRESH, DRAFT_DOWN, DRAFT_UP, PERCENT, GO,
    CHAPTER, BOOKMARK_TOGGLE, BOOKMARK_GO, BOOKMARK_REMOVE, BOOKMARK_PREVIOUS, BOOKMARK_NEXT,
    FILES, IMPORT, IMPORT_FILES, IMPORT_FOLDER, FONT_FACE, FONT_IMPORT, PRESETS, PRESET,
    SETTINGS, LANGUAGE, CHECK_UPDATE, DOWNLOAD_UPDATE, FEEDBACK, NIGHT, DARK, LIGHT
}
internal data class MenuCommand(val action: MenuAction, val value: Int = 0)
internal data class MenuControl(val text: String, val command: MenuCommand?, val selected: Boolean = false)
internal data class MenuRow(val label: String, val command: MenuCommand? = null, val controls: List<MenuControl> = emptyList(), val controlsWidth: Float = 228f)
internal data class MenuModel(val title: String, val back: Boolean, val rows: List<MenuRow>, val minRows: Int = 0)

/** Geometry is shared by drawing and hit testing; every visible command has a real action. */
internal class ReadingMenu {
    val bounds = RectF()
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 155, 155); strokeWidth = .7f; style = Paint.Style.STROKE }
    private val header = 58f
    private val rowHeight = 64f

    fun place(viewportWidth: Float, model: MenuModel) {
        val width = minOf(416f, viewportWidth - 120f)
        bounds.set(viewportWidth - 84f - width, 110f, viewportWidth - 84f, 110f + header + maxOf(model.rows.size, model.minRows) * rowHeight)
    }
    private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float, gray: Int = 45, align: Paint.Align = Paint.Align.LEFT, max: Float = bounds.width() - 44f) {
        text.color = Color.rgb(gray, gray, gray); text.textSize = size; text.textAlign = align
        c.drawText(android.text.TextUtils.ellipsize(value, text, max, android.text.TextUtils.TruncateAt.END).toString(), x, y, text)
    }
    private fun controlsStart(row: MenuRow) = if (row.label.isEmpty()) bounds.left + 16 else bounds.right - row.controlsWidth

    fun draw(c: Canvas, model: MenuModel) {
        text.color = Color.WHITE
        c.drawRect(bounds, text); c.drawRect(bounds, rule)
        label(c, if (model.back) "‹" else "", bounds.left + 24, bounds.top + 36, 27f, 90)
        label(c, model.title, bounds.left + if (model.back) 52 else 22, bounds.top + 36, 18f, 90)
        c.drawLine(bounds.left + 20, bounds.top + header, bounds.right - 20, bounds.top + header, rule)
        model.rows.forEachIndexed { index, row ->
            val top = bounds.top + header + index * rowHeight
            label(c, row.label, bounds.left + 22, top + 40, if (row.command == null && row.controls.isEmpty()) 17f else 21f, if (row.command == null && row.controls.isEmpty()) 110 else 45, max = if (row.controls.isEmpty()) bounds.width()-70 else controlsStart(row)-bounds.left-30)
            if (row.controls.isNotEmpty()) {
                val start = controlsStart(row)
                val step = (bounds.right - 16 - start) / row.controls.size
                row.controls.forEachIndexed { i, control ->
                    val x = start + (i + .5f) * step
                    label(c, control.text, x, top + 40, 19f, if (control.command == null) 120 else 45, Paint.Align.CENTER)
                    if (control.selected) c.drawLine(x - 15, top + 49, x + 15, top + 49, rule)
                }
            } else if (row.command != null) label(c, "›", bounds.right - 26, top + 40, 23f, 105, Paint.Align.CENTER)
            if (index < model.rows.lastIndex) c.drawLine(bounds.left + 20, top + rowHeight, bounds.right - 20, top + rowHeight, rule)
        }
    }

    fun hit(x: Float, y: Float, model: MenuModel): MenuCommand? {
        if (!bounds.contains(x, y)) return null
        if (y < bounds.top + header) return if (model.back) MenuCommand(MenuAction.BACK) else null
        val row = model.rows.getOrNull(((y - bounds.top - header) / rowHeight).toInt()) ?: return null
        if (row.controls.isNotEmpty() && x >= controlsStart(row)) {
            val step = (bounds.right - 16 - controlsStart(row)) / row.controls.size
            val index = ((x - controlsStart(row)) / step).toInt().coerceIn(row.controls.indices)
            return row.controls[index].command
        }
        return row.command
    }
}
