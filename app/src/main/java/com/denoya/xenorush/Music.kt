package com.denoya.xenorush

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * One streamed music player. Menu music loops; race music advances through [ShuffleQueue].
 * Updated from the game thread. [pause] and [release] run from the UI thread only after
 * that thread has been joined, and every entry point is synchronized.
 */
class MusicPlayer(private val context: Context) {
    private val files: List<String> = try {
        context.assets.list(MusicConfig.FOLDER)?.filter { MusicConfig.isAudio(it) }?.sorted() ?: emptyList()
    } catch (_: Exception) { emptyList() }
    private val raceSongs = files.filter { MusicConfig.isRaceTrack(it) }
    private val menuFile = files.firstOrNull { it == MusicConfig.MENU_TRACK }
        ?: files.firstOrNull { MusicConfig.isMenuTrack(it) }
    private val shuffle = ShuffleQueue(raceSongs)

    private val lock = Any()
    private var mp: MediaPlayer? = null
    private var phase = Phase.IDLE
    private var current = MusicCue.NONE
    private var pending = MusicCue.NONE
    private var level = 0f
    private var gain = 1f
    private var duck = 1f
    private var userPaused = false
    private var menuFailed = false
    private val badRace = HashSet<String>()

    var nowPlaying: String? = null
        private set
    var onSongStart: ((String) -> Unit)? = null

    val songCount: Int get() = raceSongs.size

    /** Call when a new race starts so the next race track is a fresh shuffle. */
    fun newRace() = synchronized(lock) { shuffle.reset() }

    /**
     * @param cue what should be heard if music is enabled
     * @param suspended pause in place (pause menu, or the app is in the background)
     * @param enabled master music toggle
     * @param volume 0..1 user music volume (100% still caps at [MusicConfig.VOLUME])
     */
    fun update(cue: MusicCue, suspended: Boolean, enabled: Boolean, volume: Float, dt: Float, duck: Float = 1f) = synchronized(lock) {
        gain = volume.coerceIn(0f, 1f)
        this.duck = duck.coerceIn(0f, 1f)
        val want = if (enabled) cue else MusicCue.NONE
        if (suspended) {
            if (phase == Phase.FADING) {
                releasePlayer()
                phase = Phase.IDLE
                current = MusicCue.NONE
                pending = MusicCue.NONE
            } else pausePlaying()
            return
        }
        if (userPaused) {
            if (want == current && phase == Phase.PLAYING && mp != null) {
                safe { mp?.start() }
                userPaused = false
            } else if (want != current) {
                releasePlayer()
                userPaused = false
                phase = Phase.IDLE
                current = MusicCue.NONE
            }
        }
        when (phase) {
            Phase.IDLE -> if (want != MusicCue.NONE) start(want)
            Phase.PLAYING -> {
                if (want != current) {
                    phase = Phase.FADING
                    pending = want
                } else if (current == MusicCue.RACE && mp != null && !safeBool { mp!!.isPlaying }) {
                    start(MusicCue.RACE)
                } else fadeIn(dt)
            }
            Phase.FADING -> {
                pending = want
                level -= dt / MusicConfig.FADE_OUT_S
                applyVol()
                if (level <= 0f || mp == null || !safeBool { mp!!.isPlaying }) {
                    val next = pending
                    releasePlayer()
                    phase = Phase.IDLE
                    pending = MusicCue.NONE
                    current = MusicCue.NONE
                    if (next != MusicCue.NONE) start(next)
                }
            }
        }
    }

    /** App went to the background (the game loop has already stopped). */
    fun pause() = synchronized(lock) {
        if (phase == Phase.FADING) {
            releasePlayer()
            phase = Phase.IDLE
            current = MusicCue.NONE
            pending = MusicCue.NONE
        } else pausePlaying()
    }

    fun release() = synchronized(lock) {
        releasePlayer()
        phase = Phase.IDLE
        pending = MusicCue.NONE
        current = MusicCue.NONE
    }

    private fun start(cue: MusicCue) {
        when (cue) {
            MusicCue.MENU -> {
                if (menuFailed) return
                val file = menuFile ?: return
                if (tryStart(file, loop = true)) current = MusicCue.MENU else menuFailed = true
            }
            MusicCue.RACE -> {
                if (raceSongs.isEmpty() || badRace.size >= raceSongs.size) return
                repeat(raceSongs.size) {
                    val file = shuffle.next() ?: return
                    if (file in badRace) return@repeat
                    if (tryStart(file, loop = false)) { current = MusicCue.RACE; return }
                    badRace.add(file)
                }
            }
            MusicCue.NONE -> {}
        }
    }

    private fun tryStart(file: String, loop: Boolean): Boolean {
        releasePlayer()
        var afd: AssetFileDescriptor? = null
        var created: MediaPlayer? = null
        return try {
            afd = context.assets.openFd("${MusicConfig.FOLDER}/$file")
            val p = MediaPlayer()
            created = p
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            afd = null
            p.isLooping = loop
            p.prepare()
            p.setVolume(0f, 0f)
            p.start()
            mp = p
            level = 0f
            userPaused = false
            phase = Phase.PLAYING
            val title = MusicConfig.titleOf(file)
            nowPlaying = title
            if (!loop) onSongStart?.invoke(title)
            true
        } catch (_: Exception) {
            try { afd?.close() } catch (_: Exception) {}
            if (mp == null) created?.let { safe { it.release() } }
            releasePlayer()
            phase = Phase.IDLE
            current = MusicCue.NONE
            false
        }
    }

    private fun fadeIn(dt: Float) {
        if (level < 1f) level = (level + dt / MusicConfig.FADE_IN_S).coerceAtMost(1f)
        applyVol()
    }

    private fun applyVol() {
        val v = (level.coerceIn(0f, 1f) * gain * duck * MusicConfig.VOLUME).coerceIn(0f, 1f)
        mp?.let { p -> safe { p.setVolume(v, v) } }
    }

    private fun pausePlaying() {
        val p = mp ?: return
        if (!userPaused && safeBool { p.isPlaying }) {
            safe { p.pause() }
            userPaused = true
        }
    }

    private fun releasePlayer() {
        mp?.let { p -> safe { p.stop() }; safe { p.release() } }
        mp = null
        level = 0f
        userPaused = false
        nowPlaying = null
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }
    private inline fun safeBool(f: () -> Boolean): Boolean = try { f() } catch (_: Exception) { false }

    private enum class Phase { IDLE, PLAYING, FADING }
}
