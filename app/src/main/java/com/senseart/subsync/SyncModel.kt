package com.senseart.subsync

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Връзката между времето във .srt файла и времето във филма:
 *
 *     времеВъвФилма = времеВъвФайла × scale + offsetMs
 *
 * offsetMs > 0 означава, че субтитрите се показват по-късно.
 * scale ≠ 1 оправя субтитри, правени за версия с друга честота на кадрите
 * (напр. 25 fps срещу 23.976 fps), при които разликата расте с времето.
 */
class SyncModel(var offsetMs: Long = 0, var scale: Double = 1.0) {

    data class Anchor(val cueMs: Long, val videoMs: Long)

    enum class Result { ONE_POINT, TWO_POINT }

    val anchors = mutableListOf<Anchor>()

    fun cueTimeAt(videoMs: Long): Long = ((videoMs - offsetMs) / scale).roundToLong()

    fun videoTimeOf(cueMs: Long): Long = (cueMs * scale).roundToLong() + offsetMs

    /** Ръчно отместване (стрелки ляво/дясно). */
    fun nudge(deltaMs: Long) {
        offsetMs += deltaMs
        // Запазваме точките на синхрон съвместими с новото отместване.
        for (i in anchors.indices) anchors[i] = anchors[i].copy(videoMs = anchors[i].videoMs + deltaMs)
    }

    /** Смяна на скоростта така, че това, което се вижда в момента, да не „скача“. */
    fun setScaleKeeping(newScale: Double, videoNowMs: Long) {
        val cue = (videoNowMs - offsetMs) / scale
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        offsetMs = (videoNowMs - cue * scale).roundToLong()
        anchors.clear()
    }

    /**
     * „Тази реплика започва точно сега.“
     * С една точка се изчислява отместването. Ако има две точки, отдалечени поне
     * [MIN_SPAN_MS] една от друга, се изчислява и скоростта.
     */
    fun anchor(cueMs: Long, videoMs: Long): Result {
        anchors.removeAll { abs(it.cueMs - cueMs) < 1000 }
        anchors.add(Anchor(cueMs, videoMs))
        if (anchors.size >= 2) {
            val a = anchors.minBy { it.cueMs }
            val b = anchors.maxBy { it.cueMs }
            if (b.cueMs - a.cueMs >= MIN_SPAN_MS) {
                val s = (b.videoMs - a.videoMs).toDouble() / (b.cueMs - a.cueMs)
                if (s in MIN_SCALE..MAX_SCALE) {
                    scale = s
                    offsetMs = (a.videoMs - a.cueMs * s).roundToLong()
                    return Result.TWO_POINT
                }
                // Невъзможна скорост: старите точки вероятно са грешни, пазим само новата.
                anchors.retainAll { it.cueMs == cueMs }
            }
        }
        offsetMs = videoMs - (cueMs * scale).roundToLong()
        return Result.ONE_POINT
    }

    fun reset(defaultOffsetMs: Long = 0) {
        offsetMs = defaultOffsetMs
        scale = 1.0
        anchors.clear()
    }

    companion object {
        const val MIN_SCALE = 0.8
        const val MAX_SCALE = 1.25
        const val MIN_SPAN_MS = 60_000L

        /** Субтитри за 25 fps върху видео 23.976 fps. */
        const val PAL_TO_FILM = 25.0 / 23.976

        /** Субтитри за 23.976 fps върху видео 25 fps. */
        const val FILM_TO_PAL = 23.976 / 25.0

        fun formatOffset(ms: Long): String {
            val sign = if (ms < 0) "−" else "+"
            val a = abs(ms)
            return if (a >= 60_000) {
                "%s%d:%02d.%d мин".format(sign, a / 60_000, a / 1000 % 60, a / 100 % 10)
            } else {
                "%s%d.%d с".format(sign, a / 1000, a / 100 % 10)
            }
        }

        fun formatClock(ms: Long): String {
            val v = if (ms < 0) 0 else ms
            return "%d:%02d:%02d".format(v / 3_600_000, v / 60_000 % 60, v / 1000 % 60)
        }
    }
}
