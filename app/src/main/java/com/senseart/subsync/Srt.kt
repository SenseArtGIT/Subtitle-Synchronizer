package com.senseart.subsync

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.max
import kotlin.math.roundToLong

/** Един ред (реплика) от .srt файла. Времената са в милисекунди спрямо самия файл. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Зареден .srt файл. Пазим кодировката, за да го запишем обратно в същата. */
class SubtitleFile(
    val file: File?,
    val charset: Charset,
    val hadBom: Boolean,
    val cues: List<Cue>,
) {
    /** Индекс на последната реплика, започваща в или преди [t]; -1 ако няма такава. */
    fun indexAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= t) {
                idx = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return idx
    }

    /** Всички реплики, които трябва да се виждат в момент [t] (може да се застъпват). */
    fun activeAt(t: Long): List<Cue> {
        val idx = indexAtOrBefore(t)
        if (idx < 0) return emptyList()
        val res = ArrayList<Cue>(2)
        var j = idx
        var n = 0
        while (j >= 0 && n < 20) {
            val c = cues[j]
            if (c.endMs > t) res.add(c)
            j--; n++
        }
        res.reverse()
        return res
    }
}

object Srt {
    private val TIME = Regex(
        """(\d{1,2}):(\d{1,2}):(\d{1,2})[,.:](\d{1,3})\s*-->\s*(\d{1,2}):(\d{1,2}):(\d{1,2})[,.:](\d{1,3})"""
    )

    /** Резервна кодировка за файлове без BOM, които не са валиден UTF-8 (типично за български .srt). */
    val FALLBACK: Charset = Charset.forName("windows-1251")

    fun load(file: File): SubtitleFile {
        val d = decode(file.readBytes())
        return SubtitleFile(file, d.charset, d.bom, parse(d.text))
    }

    data class Decoded(val text: String, val charset: Charset, val bom: Boolean)

    fun decode(b: ByteArray): Decoded {
        fun at(i: Int) = b[i].toInt() and 0xFF
        if (b.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF)
            return Decoded(String(b, 3, b.size - 3, Charsets.UTF_8), Charsets.UTF_8, true)
        if (b.size >= 2 && at(0) == 0xFF && at(1) == 0xFE)
            return Decoded(String(b, 2, b.size - 2, Charsets.UTF_16LE), Charsets.UTF_16LE, true)
        if (b.size >= 2 && at(0) == 0xFE && at(1) == 0xFF)
            return Decoded(String(b, 2, b.size - 2, Charsets.UTF_16BE), Charsets.UTF_16BE, true)
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Decoded(dec.decode(ByteBuffer.wrap(b)).toString(), Charsets.UTF_8, false)
        } catch (e: CharacterCodingException) {
            Decoded(String(b, FALLBACK), FALLBACK, false)
        }
    }

    fun parse(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) {
                i++; continue
            }
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            i++
            val sb = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // Защита от файлове без празен ред между репликите.
                if (TIME.containsMatchIn(lines[i])) break
                val t = lines[i].trim()
                if (i + 1 < lines.size && t.isNotEmpty() && t.all { it.isDigit() } &&
                    TIME.containsMatchIn(lines[i + 1])
                ) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i].trim())
                i++
            }
            cues.add(Cue(start, max(end, start), sb.toString()))
        }
        cues.sortBy { it.startMs } // стабилно сортиране
        return cues
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong()

    fun formatTime(ms: Long): String {
        val v = max(0L, ms)
        val h = v / 3_600_000
        val m = v / 60_000 % 60
        val s = v / 1000 % 60
        val r = v % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, r)
    }

    /** Текстът на файла с приложени отместване и скорост. */
    fun render(cues: List<Cue>, scale: Double, offsetMs: Long): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            val s = max(0L, (c.startMs * scale).roundToLong() + offsetMs)
            val e = max(s, (c.endMs * scale).roundToLong() + offsetMs)
            sb.append(i + 1).append("\r\n")
            sb.append(formatTime(s)).append(" --> ").append(formatTime(e)).append("\r\n")
            sb.append(c.text.replace("\n", "\r\n")).append("\r\n\r\n")
        }
        return sb.toString()
    }

    /**
     * Записва синхронизирания файл на мястото на оригинала.
     * Оригиналът се копира веднъж като „име.srt.bak“ (ако такъв още няма).
     */
    fun saveShifted(sub: SubtitleFile, scale: Double, offsetMs: Long) {
        val f = sub.file ?: error("Няма файл")
        val bak = File(f.path + ".bak")
        if (!bak.exists()) f.copyTo(bak)
        val body = render(sub.cues, scale, offsetMs).toByteArray(sub.charset)
        val bom = when {
            !sub.hadBom -> ByteArray(0)
            sub.charset == Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            sub.charset == Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            sub.charset == Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bom + body)
        if (!tmp.renameTo(f)) {
            f.writeBytes(bom + body)
            tmp.delete()
        }
    }
}
