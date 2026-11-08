package com.senseart.subsync

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/** Откъде знаем в коя секунда е филмът. */
interface PlaybackClock {
    /** Текущата позиция на филма в ms или null, ако не е известна. */
    fun positionMs(): Long?
    fun isPlaying(): Boolean
    val label: String
    fun release() {}
}

/**
 * Позицията идва директно от плейъра (Android MediaSession).
 * Пауза, връщане и превъртане във ViMu се отразяват автоматично.
 */
class SessionClock(
    val controller: MediaController,
    handler: Handler,
    private val calibrationMs: () -> Long,
) : PlaybackClock {

    @Volatile
    private var state: PlaybackState? = controller.playbackState

    @Volatile
    var metadata: MediaMetadata? = controller.metadata
        private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            state = s
        }

        override fun onMetadataChanged(m: MediaMetadata?) {
            metadata = m
        }
    }

    init {
        controller.registerCallback(callback, handler)
    }

    override fun positionMs(): Long? {
        val st = state ?: return null
        if (st.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN || st.position < 0) return null
        if (st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_ERROR) return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos + calibrationMs()
    }

    override fun isPlaying() = state?.state == PlaybackState.STATE_PLAYING

    override val label = "плейъра (автоматично)"

    fun togglePlay() {
        if (isPlaying()) controller.transportControls.pause() else controller.transportControls.play()
    }

    override fun release() {
        controller.unregisterCallback(callback)
    }
}

/**
 * Собствен часовник, когато плейърът не съобщава позицията си.
 * Синхронизира се с „Тази реплика е сега“. Паузата се следи по бутоните
 * на дистанционното (Play/Pause), а при нужда и ръчно от менюто.
 */
class ManualClock : PlaybackClock {
    private var baseMs = 0L
    private var startedAt = SystemClock.elapsedRealtime()
    private var playing = true

    override fun positionMs(): Long =
        if (playing) baseMs + (SystemClock.elapsedRealtime() - startedAt) else baseMs

    override fun isPlaying() = playing

    override val label = "ръчен часовник"

    fun restart() {
        baseMs = 0; startedAt = SystemClock.elapsedRealtime(); playing = true
    }

    fun setPosition(ms: Long, play: Boolean) {
        baseMs = ms; startedAt = SystemClock.elapsedRealtime(); playing = play
    }

    fun pause() {
        if (playing) {
            baseMs = positionMs(); playing = false
        }
    }

    fun resume() {
        if (!playing) {
            startedAt = SystemClock.elapsedRealtime(); playing = true
        }
    }

    fun toggle() = if (playing) pause() else resume()
}
