package com.denoya.xenorush

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Low-latency effects via [SoundPool]. One-shots are the [RaceSfx] slots.
 * The engine is a looping slot ([SfxConfig.ENGINE]) whose pitch and volume
 * follow the player's speed. Missing or unreadable files stay silent.
 *
 * Constructed on the main thread (so load callbacks have a looper). Played
 * from the game thread. [onAppPause] / [release] run after that thread joins.
 */
class SfxPlayer(context: Context) {
    private val pool: SoundPool?
    private val soundIds = HashMap<RaceSfx, Int>()
    private val ready = HashSet<Int>()
    private val heldFds = ArrayList<AssetFileDescriptor>()
    private var engineId = 0
    private var engineStream = 0
    private var released = false

    init {
        val built = try {
            SoundPool.Builder()
                .setMaxStreams(12)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
        } catch (_: Exception) { null }
        pool = built
        if (built != null) {
            built.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0 && sampleId != 0) synchronized(ready) { ready.add(sampleId) }
            }
            for (slot in RaceSfx.entries) {
                val id = load(context, built, slot.fileBase)
                if (id != 0) soundIds[slot] = id
            }
            engineId = load(context, built, SfxConfig.ENGINE)
        }
    }

    fun play(cue: RaceSfx, volume: Float) {
        if (released) return
        val p = pool ?: return
        val id = soundIds[cue] ?: return
        if (volume <= 0f || !isReady(id)) return
        val v = volume.coerceIn(0f, 1f)
        try { p.play(id, v, v, if (cue == RaceSfx.GUN || cue == RaceSfx.PICKUP) 1 else 2, 0, 1f) } catch (_: Exception) {}
    }

    /**
     * Loop the engine while [active]. [speedRatio] is speed / top speed (0..~1.2).
     * [volume] is the user's SFX level, 0..1. Zero or inactive stops the loop.
     */
    fun updateEngine(active: Boolean, speedRatio: Float, boosting: Boolean, volume: Float) {
        if (released) return
        val p = pool ?: return
        val id = engineId
        if (!active || volume <= 0f || id == 0 || !isReady(id)) {
            stopEngine()
            return
        }
        val ratio = speedRatio.coerceIn(0f, 1.2f)
        val vol = ((0.22f + 0.65f * (ratio / 1.2f)) * volume * (if (boosting) 1.15f else 1f)).coerceIn(0f, 1f)
        val rate = (0.70f + 0.55f * ratio + if (boosting) 0.18f else 0f).coerceIn(0.5f, 2f)
        val stream = engineStream
        if (stream == 0) {
            engineStream = try { p.play(id, vol, vol, 0, -1, rate) } catch (_: Exception) { 0 }
            return
        }
        try {
            p.setVolume(stream, vol, vol)
            p.setRate(stream, rate)
        } catch (_: Exception) {
            engineStream = 0
        }
    }

    fun onAppPause() {
        if (released) return
        try { pool?.autoPause() } catch (_: Exception) {}
    }

    fun onAppResume() {
        if (released) return
        try { pool?.autoResume() } catch (_: Exception) {}
    }

    fun release() {
        if (released) return
        released = true
        stopEngine()
        try { pool?.release() } catch (_: Exception) {}
        for (fd in heldFds) { try { fd.close() } catch (_: Exception) {} }
        heldFds.clear()
        soundIds.clear()
        synchronized(ready) { ready.clear() }
        engineId = 0
    }

    private fun stopEngine() {
        val stream = engineStream
        if (stream != 0) {
            try { pool?.stop(stream) } catch (_: Exception) {}
            engineStream = 0
        }
    }

    private fun isReady(id: Int): Boolean = synchronized(ready) { id in ready }

    private fun load(context: Context, pool: SoundPool, base: String): Int {
        val afd = open(context, base) ?: return 0
        return try {
            val id = pool.load(afd, 1)
            // SoundPool reads the fd asynchronously; keep it open until release().
            heldFds.add(afd)
            id
        } catch (_: Exception) {
            try { afd.close() } catch (_: Exception) {}
            0
        }
    }

    private fun open(context: Context, base: String): AssetFileDescriptor? {
        for (ext in SfxConfig.EXTENSIONS) {
            try {
                return context.assets.openFd("${SfxConfig.FOLDER}/$base.$ext")
            } catch (_: Exception) { /* try the next extension */ }
        }
        return null
    }
}
