package com.senseart.subsync

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Субтитри с черен контур: два TextView един върху друг –
 * отдолу дебел черен контур, отгоре бял текст със сянка.
 */
class SubtitleView(context: Context) : FrameLayout(context) {

    private val outline = TextView(context)
    private val fill = TextView(context)
    private var lastRaw: String? = null

    init {
        for (t in listOf(outline, fill)) {
            t.gravity = Gravity.CENTER_HORIZONTAL
            t.includeFontPadding = false
            addView(t, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        outline.setTextColor(Color.BLACK)
        outline.paint.style = Paint.Style.STROKE
        outline.paint.strokeJoin = Paint.Join.ROUND
        fill.setTextColor(Color.WHITE)
        fill.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        setTextSizeSp(30)
    }

    fun setTextSizeSp(sp: Int) {
        for (t in listOf(outline, fill)) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        outline.paint.strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp / 7f, resources.displayMetrics
        )
        outline.invalidate()
    }

    /** Задава текста само ако се е променил (вика се много пъти в секунда). */
    fun setSubtitle(raw: String) {
        if (raw == lastRaw) return
        lastRaw = raw
        if (raw.isEmpty()) {
            outline.text = ""; fill.text = ""; return
        }
        val spanned = toSpanned(raw)
        fill.text = spanned
        val noColor = SpannableString(spanned)
        for (s in noColor.getSpans(0, noColor.length, ForegroundColorSpan::class.java)) noColor.removeSpan(s)
        outline.text = noColor
    }

    companion object {
        private val ASS_TAGS = Regex("""\{\\[^}]*\}""")
        private val KNOWN_TAG = Regex("""</?(i|b|u|font)(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

        fun toSpanned(raw: String): Spanned {
            val clean = ASS_TAGS.replace(raw, "")
            // Екранираме всичко, освен познатите тагове <i> <b> <u> <font>.
            val sb = StringBuilder()
            var last = 0
            for (m in KNOWN_TAG.findAll(clean)) {
                sb.append(Html.escapeHtml(clean.substring(last, m.range.first)))
                sb.append(m.value)
                last = m.range.last + 1
            }
            sb.append(Html.escapeHtml(clean.substring(last)))
            val html = sb.toString().replace("\n", "<br>")
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        }
    }
}
