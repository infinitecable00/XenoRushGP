package com.denoya.xenorush

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool

/**
 * One announcer voice at a time, above the effect pool. Missing clips stay silent.
 * Constructed on the main thread. [tick] and [offer] run on the game thread.
 */
class AnnouncerPlayer(context: Context) {
    private val pool: SoundPool?
    private val ids = HashMap<String, Int>()
    private val seconds = HashMap<String, Float>()
    private val ready = HashSet<Int>()
    private val heldFds = ArrayList<AssetFileDescriptor>()
    private val index: Announcer.Index
    private val queue = LineQueue()
    private var stream = 0
    private var left = 0f
    private var released = false

    val speaking: Boolean get() = left > 0f

    init {
        val names = try { context.assets.list(Announcer.FOLDER)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        index = Announcer.index(names)
        val built = try {
            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
        } catch (_: Exception) { null }
        pool = built
        if (built != null) {
            built.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0 && sampleId != 0) synchronized(ready) { ready.add(sampleId) }
            }
            for (base in index.files) {
                val sec = duration(context, base)
                seconds[base] = sec
                val id = load(context, built, base)
                if (id != 0) ids[base] = id
            }
        }
    }

    fun tick(dt: Float, voice: VoiceChoice, volume: Float) {
        if (released) return
        queue.tick(dt)
        if (left > 0f) {
            left -= dt
            if (left <= 0f) {
                left = 0f
                stream = 0
            }
        }
        if (left > 0f || volume <= 0f || voice == VoiceChoice.OFF) return
        val next = queue.poll(false) ?: return
        start(Announcer.resolve(index, voice, next), volume, false)
    }

    /** Lights. Stops whatever is speaking so 3, 2, 1, GO land on the numbers. */
    fun countdown(call: Call, voice: VoiceChoice, volume: Float) {
        if (released) return
        queue.pushCountdown()
        stop()
        if (volume <= 0f || voice == VoiceChoice.OFF) return
        start(Announcer.resolve(index, voice, call), volume, true)
    }

    /** Race call for the local player. Queues one line, or drops a boost that would overlap. */
    fun offer(call: Call, voice: VoiceChoice, volume: Float) {
        if (released || volume <= 0f || voice == VoiceChoice.OFF) return
        val now = queue.submit(call, speaking) ?: return
        start(Announcer.resolve(index, voice, now), volume, false)
    }

    fun stop() {
        if (stream != 0) {
            try { pool?.stop(stream) } catch (_: Exception) {}
            stream = 0
        }
        left = 0f
    }

    fun release() {
        if (released) return
        released = true
        stop()
        try { pool?.release() } catch (_: Exception) {}
        for (fd in heldFds) { try { fd.close() } catch (_: Exception) {} }
        heldFds.clear()
        ids.clear()
        synchronized(ready) { ready.clear() }
    }

    private fun start(file: String?, volume: Float, interrupt: Boolean) {
        if (file == null) return
        val p = pool ?: return
        val id = ids[file] ?: return
        if (!isReady(id)) return
        if (interrupt && stream != 0) {
            try { p.stop(stream) } catch (_: Exception) {}
            stream = 0
        }
        val vol = volume.coerceIn(0f, 1f)
        val played = try { p.play(id, vol, vol, 10, 0, 1f) } catch (_: Exception) { 0 }
        if (played == 0) return
        stream = played
        left = seconds[file] ?: 1.2f
    }

    private fun isReady(id: Int): Boolean = synchronized(ready) { id in ready }

    private fun load(context: Context, pool: SoundPool, base: String): Int {
        val afd = try { context.assets.openFd("${Announcer.FOLDER}/$base.ogg") } catch (_: Exception) { null } ?: return 0
        return try {
            val id = pool.load(afd, 1)
            heldFds.add(afd)
            id
        } catch (_: Exception) {
            try { afd.close() } catch (_: Exception) {}
            0
        }
    }

    private fun duration(context: Context, base: String): Float {
        val mmr = MediaMetadataRetriever()
        var afd: AssetFileDescriptor? = null
        return try {
            afd = context.assets.openFd("${Announcer.FOLDER}/$base.ogg")
            mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1200L
            (ms / 1000f).coerceIn(0.35f, 6f)
        } catch (_: Exception) {
            1.2f
        } finally {
            try { afd?.close() } catch (_: Exception) {}
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
