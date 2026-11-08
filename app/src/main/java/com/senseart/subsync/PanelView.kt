package com.senseart.subsync

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

/** Полупрозрачният панел, който се показва над филма. */
class PanelView(context: Context) : LinearLayout(context) {

    data class Row(val text: String, val selected: Boolean = false, val active: Boolean = false)

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false, single: Boolean = false) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (single) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }

    private val header = label(19f, Color.WHITE, bold = true)
    private val info = label(14f, 0xFFB0B8C0.toInt(), single = true)
    private val rows = List(MAX_ROWS) { label(17f, Color.LTGRAY, single = true) }
    private val hint = label(14f, 0xFF9FD3FF.toInt())
    private val status = label(15f, 0xFFFFD76A.toInt())

    private val selectedBg = GradientDrawable().apply {
        setColor(0xFF2E6BD8.toInt()); cornerRadius = dp(6).toFloat()
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xE6101418.toInt())
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(header)
        addView(info)
        rows.forEach { addView(it) }
        addView(hint)
        addView(status)
    }

    fun show(headerText: String, infoText: String, lines: List<Row>, hintText: String, statusText: String) {
        header.text = headerText
        info.text = infoText
        info.visibility = if (infoText.isEmpty()) GONE else VISIBLE
        for (i in rows.indices) {
            val t = rows[i]
            val r = lines.getOrNull(i)
            if (r == null) {
                t.visibility = GONE; continue
            }
            t.visibility = VISIBLE
            t.text = r.text
            t.background = if (r.selected) selectedBg else null
            t.setTextColor(
                when {
                    r.selected -> Color.WHITE
                    r.active -> 0xFFFFFFFF.toInt()
                    else -> 0xFFB8B8B8.toInt()
                }
            )
            t.setTypeface(null, if (r.selected || r.active) Typeface.BOLD else Typeface.NORMAL)
        }
        hint.text = hintText
        status.text = statusText
        status.visibility = if (statusText.isEmpty()) GONE else VISIBLE
    }

    companion object {
        const val MAX_ROWS = 9
    }
}
