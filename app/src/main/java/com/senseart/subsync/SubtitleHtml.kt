package com.senseart.subsync

/**
 * Превръща текста на реплика в HTML за показване. Всеки ред от .srt файла остава отделен ред
 * (<br>), познатите тагове <i> <b> <u> <font> се запазват, всичко друго се екранира.
 * Без зависимости от Android – за да може да се тества.
 */
object SubtitleHtml {
    private val ASS_TAGS = Regex("""\{\\[^}]*\}""")
    private val KNOWN_TAG = Regex("""</?(i|b|u|font)(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeKeepingTags(line: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in KNOWN_TAG.findAll(line)) {
            sb.append(escape(line.substring(last, m.range.first)))
            sb.append(m.value)
            last = m.range.last + 1
        }
        sb.append(escape(line.substring(last)))
        return sb.toString()
    }

    fun build(raw: String): String {
        val clean = ASS_TAGS.replace(raw, "")
        return clean.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .joinToString("<br>") { escapeKeepingTags(it.trim()) }
    }
}
