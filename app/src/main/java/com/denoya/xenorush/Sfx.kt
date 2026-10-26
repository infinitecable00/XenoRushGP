package com.denoya.xenorush

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Low-latency effects via [SoundPool]. Every candidate in [SfxMap.allFiles]
 * is loaded; [SfxMap.DEFAULT] and [ShipGuns.TABLE] choose which one plays.
 * Missing files stay silent.
 *
 * Constructed on the main thread (load callbacks need a looper). Played from
 * the game thread. [onAppPause] / [release] run after that thread has joined.
 */
class SfxPlayer(context: Context) {
    private val pool: SoundPool?
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private val heldFds = ArrayList<AssetFileDescriptor>()
    private val live = HashMap<RaceSfx, MutableList<Float>>()
    private val delayed = ArrayList<Delayed>()
    private var engineId = 0
    private var engineStream = 0
    private var pitId = 0
    private var pitFullId = 0
    private var pitStream = 0
    private var released = false

    private class Delayed(var left: Float, val file: String, val volume: Float, val rate: Float, val slot: RaceSfx)

    init {
        val built = try {
            SoundPool.Builder()
                .setMaxStreams(16)
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
            for (name in SfxMap.allFiles()) {
                val id = load(context, built, name)
                if (id != 0) ids[name] = id
            }
            engineId = ids[SfxMap.engineFile()] ?: 0
            pitId = ids[SfxMap.PIT_LOOP] ?: 0
            pitFullId = ids[SfxMap.PIT_FULL] ?: 0
        }
    }

    fun play(cue: SfxCue, userVolume: Float) {
        if (released || userVolume <= 0f) return
        val variant = if (cue.slot == RaceSfx.GUN) ShipGuns.voice(cue.vehicleId).variant else SfxMap.variant(cue.slot)
        val rate = if (cue.slot == RaceSfx.GUN) ShipGuns.voice(cue.vehicleId).rate else 1f
        val played = playFile(SfxMap.file(cue.slot, variant), cue.slot, cue.fromPlayer, cue.distance, userVolume, rate)
        if (played && cue.slot == RaceSfx.BULLET_HIT) {
            val echo = userVolume * SfxMix.slotGain(cue.slot) * SfxMix.audibility(cue.fromPlayer, cue.distance) * SfxMix.BULLET_TINK_GAIN
            delayed.add(Delayed(
                SfxMix.BULLET_TINK_GAP_S,
                SfxMap.file(RaceSfx.BULLET_HIT, SfxMap.BULLET_TINK_SECOND),
                echo.coerceIn(0f, 1f),
                1.04f,
                RaceSfx.BULLET_HIT
            ))
        }
    }

    /** Age the mix window and fire the second bullet ping. */
    fun tick(dt: Float, enabled: Boolean) {
        if (released) return
        for (q in live.values) {
            for (i in q.indices) q[i] -= dt
            q.removeAll { it <= 0f }
        }
        if (!enabled) { delayed.clear(); return }
        val due = ArrayList<Delayed>()
        val it = delayed.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.left -= dt
            if (d.left <= 0f) { due.add(d); it.remove() }
        }
        for (d in due) playRaw(d.file, d.slot, d.volume, d.rate, priority(d.slot))
    }

    /**
     * Loop the player's engine while [active]. [speedRatio] is speed / top speed.
     * Pitch rises with speed and again while boosting. Explosions duck it.
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
        val explosions = (count(RaceSfx.MISSILE_HIT) + count(RaceSfx.ROCKET_HIT) + count(RaceSfx.MINE_HIT))
        val duck = if (explosions > 0) 0.58f else 1f
        val vol = ((0.18f + 0.48f * (ratio / 1.2f)) * volume * (if (boosting) 1.12f else 1f) * duck).coerceIn(0f, 0.75f)
        val rate = (0.72f + 0.50f * ratio + if (boosting) 0.16f else 0f).coerceIn(0.5f, 2f)
        val stream = engineStream
        if (stream == 0) {
            engineStream = try { p.play(id, vol, vol, 1, -1, rate) } catch (_: Exception) { 0 }
            return
        }
        try {
            p.setVolume(stream, vol, vol)
            p.setRate(stream, rate)
        } catch (_: Exception) {
            engineStream = 0
        }
    }

    /** One-shot when the recharge strip fills the shield. */
    fun playPitFull(volume: Float) {
        if (released || volume <= 0f) return
        val p = pool ?: return
        val id = pitFullId
        if (id == 0 || !isReady(id)) return
        val vol = (volume * SfxMap.PIT_FULL_GAIN).coerceIn(0f, 1f)
        val rate = SfxMap.PIT_FULL_RATE.coerceIn(0.5f, 2f)
        try { p.play(id, vol, vol, 2, 0, rate) } catch (_: Exception) {}
    }

    /** Energy hum while the shield is still filling. [active] is false once it is full or the ship leaves. */
    fun updatePit(active: Boolean, volume: Float) {
        if (released) return
        val p = pool ?: return
        val id = pitId
        if (!active || volume <= 0f || id == 0 || !isReady(id)) {
            stopPit()
            return
        }
        val vol = (volume * SfxMap.PIT_GAIN).coerceIn(0f, 1f)
        val rate = SfxMap.PIT_RATE.coerceIn(0.5f, 2f)
        val stream = pitStream
        if (stream == 0) {
            pitStream = try { p.play(id, vol, vol, 1, -1, rate) } catch (_: Exception) { 0 }
            return
        }
        try {
            p.setVolume(stream, vol, vol)
            p.setRate(stream, rate)
        } catch (_: Exception) {
            pitStream = 0
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
        delayed.clear()
        live.clear()
        stopEngine()
        stopPit()
        try { pool?.release() } catch (_: Exception) {}
        for (fd in heldFds) { try { fd.close() } catch (_: Exception) {} }
        heldFds.clear()
        ids.clear()
        synchronized(ready) { ready.clear() }
        engineId = 0
    }

    private fun playFile(file: String, slot: RaceSfx, fromPlayer: Boolean, distance: Float, userVolume: Float, rate: Float): Boolean {
        val active = count(slot)
        if (!SfxMix.allow(slot, fromPlayer, active)) return false
        val vol = (userVolume * SfxMix.slotGain(slot) * SfxMix.audibility(fromPlayer, distance) * SfxMix.stackGain(active)).coerceIn(0f, 1f)
        return playRaw(file, slot, vol, rate, priority(slot))
    }

    private fun playRaw(file: String, slot: RaceSfx, volume: Float, rate: Float, priority: Int): Boolean {
        val p = pool ?: return false
        val id = ids[file] ?: return false
        if (volume <= 0f || !isReady(id)) return false
        val stream = try {
            p.play(id, volume, volume, priority, 0, rate.coerceIn(0.5f, 2f))
        } catch (_: Exception) { 0 }
        if (stream == 0) return false
        live.getOrPut(slot) { ArrayList() }.add(SfxMix.windowS(slot))
        return true
    }

    private fun count(slot: RaceSfx): Int = live[slot]?.size ?: 0

    private fun priority(slot: RaceSfx): Int = when (slot) {
        RaceSfx.MISSILE_HIT, RaceSfx.ROCKET_HIT, RaceSfx.MINE_HIT -> 3
        RaceSfx.BULLET_HIT, RaceSfx.MISSILE, RaceSfx.ROCKET -> 2
        else -> 1
    }

    private fun stopEngine() {
        val stream = engineStream
        if (stream != 0) {
            try { pool?.stop(stream) } catch (_: Exception) {}
            engineStream = 0
        }
    }

    private fun stopPit() {
        val stream = pitStream
        if (stream != 0) {
            try { pool?.stop(stream) } catch (_: Exception) {}
            pitStream = 0
        }
    }

    private fun isReady(id: Int): Boolean = synchronized(ready) { id in ready }

    private fun load(context: Context, pool: SoundPool, base: String): Int {
        val afd = try {
            context.assets.openFd("${SfxMap.FOLDER}/$base.ogg")
        } catch (_: Exception) { null } ?: return 0
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
}
