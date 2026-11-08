package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class SrtTest {
    private val sample = "1\r\n00:00:01,500 --> 00:00:03,000\r\nЗдравей!\r\n\r\n" +
        "2\r\n00:00:04,000 --> 00:00:06,250\r\n<i>Втори ред</i>\r\nи още\r\n\r\n" +
        "3\r\n00:01:00,000 --> 00:01:02,000\r\nТрети\r\n"

    @Test
    fun parsesCues() {
        val c = Srt.parse(sample)
        assertEquals(3, c.size)
        assertEquals(1500L, c[0].startMs)
        assertEquals(6250L, c[1].endMs)
        assertEquals("<i>Втори ред</i>\nи още", c[1].text)
        assertEquals(60_000L, c[2].startMs)
    }

    @Test
    fun parsesMissingBlankLinesAndShortMillis() {
        val c = Srt.parse("1\n00:00:01,5 --> 00:00:02,0\nA\n2\n00:00:03.250 --> 00:00:04.000\nB\n")
        assertEquals(2, c.size)
        assertEquals(1500L, c[0].startMs)
        assertEquals("A", c[0].text)
        assertEquals(3250L, c[1].startMs)
    }

    @Test
    fun detectsCp1251() {
        val bytes = sample.toByteArray(Charset.forName("windows-1251"))
        val d = Srt.decode(bytes)
        assertEquals("windows-1251", d.charset.name())
        assertTrue(d.text.contains("Здравей"))
    }

    @Test
    fun detectsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray()
        val d = Srt.decode(bytes)
        assertEquals(Charsets.UTF_8, d.charset)
        assertTrue(d.bom)
        assertTrue(d.text.startsWith("1"))
    }

    @Test
    fun activeAtHandlesOverlap() {
        val f = SubtitleFile(null, Charsets.UTF_8, false, Srt.parse(
            "1\n00:00:01,000 --> 00:00:05,000\nA\n\n2\n00:00:02,000 --> 00:00:03,000\nB\n"
        ))
        assertEquals(listOf("A", "B"), f.activeAt(2500).map { it.text })
        assertEquals(listOf("A"), f.activeAt(4000).map { it.text })
        assertEquals(emptyList<Cue>(), f.activeAt(500))
        assertEquals(emptyList<Cue>(), f.activeAt(6000))
    }

    @Test
    fun saveShiftedKeepsEncodingAndMakesBackup() {
        val dir = createTempDir()
        try {
            val f = File(dir, "film.srt")
            f.writeBytes(sample.toByteArray(Charset.forName("windows-1251")))
            val sub = Srt.load(f)
            Srt.saveShifted(sub, 1.0, 2_000)
            assertTrue(File(dir, "film.srt.bak").exists())
            val again = Srt.load(f)
            assertEquals("windows-1251", again.charset.name())
            assertEquals(3500L, again.cues[0].startMs)
            assertEquals("Здравей!", again.cues[0].text)
            // Отрицателни времена се режат до 0.
            Srt.saveShifted(again, 1.0, -10_000)
            assertEquals(0L, Srt.load(f).cues[0].startMs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
