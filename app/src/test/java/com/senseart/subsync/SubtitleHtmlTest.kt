package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleHtmlTest {
    @Test
    fun eachSrtLineStaysOnItsOwnLine() {
        // Двата реда не бива да се слеят („край.Начало“) – между тях трябва <br>.
        assertEquals("Това е краят.<br>Начало на втория ред", SubtitleHtml.build("Това е краят.\nНачало на втория ред"))
        assertEquals("а<br>б", SubtitleHtml.build("а \r\n б"))
    }

    @Test
    fun keepsKnownTagsAndEscapesTheRest() {
        assertEquals("<i>Шепот</i><br>a &lt; b &amp; c", SubtitleHtml.build("<i>Шепот</i>\na < b & c"))
        assertEquals("<i>ред 1<br>ред 2</i>", SubtitleHtml.build("<i>ред 1\nред 2</i>"))
        assertEquals("Текст", SubtitleHtml.build("{\\an8}Текст"))
    }
}
