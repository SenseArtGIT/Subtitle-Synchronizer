package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SyncModelTest {
    @Test
    fun onePointAnchorSetsOffset() {
        val m = SyncModel()
        val r = m.anchor(cueMs = 10_000, videoMs = 14_700)
        assertEquals(SyncModel.Result.ONE_POINT, r)
        assertEquals(4_700L, m.offsetMs)
        assertEquals(10_000L, m.cueTimeAt(14_700))
    }

    @Test
    fun twoPointAnchorFindsFrameRateDrift() {
        // Субтитри за 25 fps върху видео 23.976 fps, плюс 3 с закъснение.
        val s = SyncModel.PAL_TO_FILM
        val m = SyncModel()
        fun video(cue: Long) = Math.round(cue * s) + 3_000
        m.anchor(60_000, video(60_000))
        val r = m.anchor(5_400_000, video(5_400_000))
        assertEquals(SyncModel.Result.TWO_POINT, r)
        assertEquals(s, m.scale, 1e-6)
        assertEquals(3_000.0, m.offsetMs.toDouble(), 2.0)
        assertEquals(video(3_000_000).toDouble(), m.videoTimeOf(3_000_000).toDouble(), 2.0)
    }

    @Test
    fun nudgeThenSecondAnchorStaysConsistent() {
        val m = SyncModel()
        m.anchor(100_000, 105_000)
        m.nudge(300)
        assertEquals(5_300L, m.offsetMs)
        assertEquals(105_300L, m.anchors.single().videoMs)
    }

    @Test
    fun impossibleScaleFallsBackToOnePoint() {
        val m = SyncModel()
        m.anchor(100_000, 100_000)
        // Втората точка дава скорост 3.0 – явно грешна (напр. превъртане при ръчен часовник).
        val r = m.anchor(200_000, 400_000)
        assertEquals(SyncModel.Result.ONE_POINT, r)
        assertEquals(1.0, m.scale, 0.0)
        assertEquals(200_000L, m.offsetMs)
        assertEquals(1, m.anchors.size)
    }

    @Test
    fun setScaleKeepsCurrentMomentInPlace() {
        val m = SyncModel(offsetMs = 2_000)
        val now = 1_000_000L
        val before = m.cueTimeAt(now)
        m.setScaleKeeping(SyncModel.FILM_TO_PAL, now)
        assertEquals(before.toDouble(), m.cueTimeAt(now).toDouble(), 1.0)
    }

    @Test
    fun formatting() {
        assertEquals("+4.7 с", SyncModel.formatOffset(4_700))
        assertEquals("−0.3 с", SyncModel.formatOffset(-300))
        assertEquals("+5:00.0 мин", SyncModel.formatOffset(300_000))
        assertEquals("1:02:03", SyncModel.formatClock(3_723_000))
    }

    @Test
    fun filenameMatching() {
        assertEquals(true, FileBrowser.matches(File("The.Movie.2020.1080p.srt"), "The.Movie.2020.1080p.mkv"))
        assertEquals(true, FileBrowser.matches(File("The.Movie.2020.1080p.bg.srt"), "The.Movie.2020.1080p.mkv"))
        assertEquals(false, FileBrowser.matches(File("Other.Film.srt"), "The.Movie.2020.1080p.mkv"))
    }
}
