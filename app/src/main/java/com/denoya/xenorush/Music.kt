package com.denoya.xenorush

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

class MusicPlayer(private val context: Context) {
    private val songs: List<String> = try {
        context.assets.list(MusicConfig.FOLDER)?.filter { MusicConfig.isAudio(it) }?.sorted() ?: emptyList()
    } catch (_: Exception) { emptyList() }
    private val shuffle = ShuffleQueue(songs)
    private var mp: MediaPlayer? = null
    private var vol = 0f
    private var fadingOut = false
    private var userPaused = false
    var nowPlaying: String? = null
        private set
    var onSongStart: ((String) -> Unit)? = null

    val songCount: Int get() = songs.size

    /** Call when a new race starts so it gets a fresh random song. */
    fun newRace() { release() }

    /** Called every frame from the game loop. */
    fun update(want: MusicWant, enabled: Boolean, dt: Float) {
        if (!enabled || songs.isEmpty()) { if (mp != null) release(); return }
        when (want) {
            MusicWant.PLAY -> {
                fadingOut = false
                val p = mp
                if (p == null) { startNext(); return }
                if (userPaused) { safe { p.start() }; userPaused = false }
                else if (!safeBool { p.isPlaying }) { startNext(); return }   // song finished -> next one
                if (vol < MusicConfig.VOLUME) setVol(vol + dt * MusicConfig.VOLUME / MusicConfig.FADE_IN_S)
            }
            MusicWant.PAUSE -> pause()
            MusicWant.STOP -> {
                val p = mp ?: return
                if (userPaused) { release(); return }
                fadingOut = true
                setVol(vol - dt * MusicConfig.VOLUME / MusicConfig.FADE_OUT_S)
                if (vol <= 0f || !safeBool { p.isPlaying }) release()
            }
        }
    }

    /** App went to background (game loop stops, so do this directly). */
    fun pause() {
        val p = mp ?: return
        if (!userPaused && safeBool { p.isPlaying }) { safe { p.pause() }; userPaused = true }
        if (fadingOut) release()
    }

    fun release() {
        mp?.let { p -> safe { p.stop() }; safe { p.release() } }
        mp = null; vol = 0f; userPaused = false; fadingOut = false; nowPlaying = null
    }

    private fun startNext() {
        release()
        // try each song at most once in case a file is unreadable
        repeat(songs.size) {
            val file = shuffle.next() ?: return
            try {
                val afd = context.assets.openFd("${MusicConfig.FOLDER}/$file")
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                p.prepare()
                p.setVolume(0f, 0f)
                p.start()
                mp = p; vol = 0f
                nowPlaying = MusicConfig.titleOf(file)
                onSongStart?.invoke(nowPlaying!!)
                return
            } catch (_: Exception) { /* skip bad file */ }
        }
    }

    private fun setVol(v: Float) {
        vol = v.coerceIn(0f, MusicConfig.VOLUME)
        mp?.let { p -> safe { p.setVolume(vol, vol) } }
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }
    private inline fun safeBool(f: () -> Boolean): Boolean = try { f() } catch (_: Exception) { false }
}
