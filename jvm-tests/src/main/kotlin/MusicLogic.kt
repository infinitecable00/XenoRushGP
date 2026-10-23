package com.denoya.xenorush

import kotlin.random.Random

/**
 * Race music.
 *
 * Songs are bundled in the APK under app/src/main/assets/music/ (mp3, ogg, m4a, wav, flac).
 * Drop files in that folder and rebuild - no code changes needed. Works fully offline.
 *
 * Behaviour:
 *  - a random song starts right after the countdown ("GO"), never before
 *  - shuffle without repeats: every song plays once before any song repeats,
 *    and the next cycle never starts with the song that just played
 *  - if a song ends mid-race the next random song starts
 *  - pause menu / app in background -> music pauses; resume -> continues
 *  - race over / back to menus -> music fades out
 */
object MusicConfig {
    const val ARTIST = "InfiniteCable"
    const val FOLDER = "music"
    val EXTENSIONS = setOf("mp3", "ogg", "m4a", "aac", "wav", "flac")
    const val VOLUME = 0.8f
    const val FADE_IN_S = 1.0f
    const val FADE_OUT_S = 0.8f

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** "03_Neon_Overdrive.mp3" -> "Neon Overdrive" */
    fun titleOf(file: String): String {
        var t = file.substringAfterLast('/').substringBeforeLast('.')
        t = t.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^\\d{1,3}\\s*[-.]?\\s*"), "")   // leading track numbers
        t = t.replace(Regex("(?i)^${Regex.escape(ARTIST)}\\s*-\\s*"), "") // "InfiniteCable - Song"
        return t.ifBlank { file }
    }
}

/** Pure shuffle queue (unit tested on the JVM). */
class ShuffleQueue<T>(private val items: List<T>, private val rng: Random = Random.Default) {
    private val queue = ArrayDeque<T>()
    var last: T? = null
        private set

    fun next(): T? {
        if (items.isEmpty()) return null
        if (queue.isEmpty()) {
            val bag = items.shuffled(rng).toMutableList()
            // don't play the same song twice in a row across cycles
            if (bag.size > 1 && bag.first() == last) { val s = bag.removeAt(0); bag.add(1 + rng.nextInt(bag.size), s) }
            queue.addAll(bag)
        }
        return queue.removeFirst().also { last = it }
    }
}

/** What the game wants from the music this frame. */
enum class MusicWant { PLAY, PAUSE, STOP }

