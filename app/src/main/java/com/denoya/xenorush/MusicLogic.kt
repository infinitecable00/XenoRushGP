package com.denoya.xenorush

import kotlin.random.Random

/**
 * Menu and race music.
 *
 * Tracks are bundled under app/src/main/assets/music/ and streamed from the APK
 * (ogg/mp3/m4a/aac/wav/flac are stored uncompressed). Works fully offline.
 *
 * Behaviour:
 *  - the home / menu screens loop [MENU_TRACK]
 *  - a random race track starts only after the countdown ("GO"), never during it
 *  - race tracks are shuffled so each one plays before any repeats, and the same
 *    track never plays twice in a row (including across the next cycle)
 *  - if a race track ends mid-race the next random track starts
 *  - pause menu / app in the background pauses music; resume continues
 *  - race over / quit to the menus fades the race track out, then menu music returns
 */
object MusicConfig {
    const val ARTIST = "InfiniteCable"
    const val FOLDER = "music"
    const val MENU_TRACK = "music_menu_black_hoodie_thoughts.ogg"
    val RACE_TRACKS = listOf(
        "music_race_black_spiral.ogg",
        "music_race_shadows.ogg",
        "music_race_night_loop.ogg",
        "music_race_midnight_spiral.ogg",
        "music_race_in_the_darkness.ogg",
        "music_race_hypnotic.ogg",
        "music_race_grin_in_the_dark.ogg",
        "music_race_fall_back.ogg",
        "music_race_black_hoodie.ogg",
        "music_race_graveyard.ogg"
    )
    val EXTENSIONS = setOf("mp3", "ogg", "m4a", "aac", "wav", "flac")
    const val VOLUME = 0.8f
    const val FADE_IN_S = 1.0f
    const val FADE_OUT_S = 0.8f

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun isMenuTrack(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base.startsWith("music_menu_") && isAudio(base)
    }

    /** Any bundled audio that is not menu music can play during a race. */
    fun isRaceTrack(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return isAudio(base) && !isMenuTrack(base)
    }

    /** "03_Neon_Overdrive.mp3" -> "Neon Overdrive"; "music_race_black_spiral.ogg" -> "Black Spiral". */
    fun titleOf(file: String): String {
        var t = file.substringAfterLast('/').substringBeforeLast('.')
        t = t.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^\\d{1,3}\\s*[-.]?\\s*"), "")   // leading track numbers
        t = t.replace(Regex("(?i)^${Regex.escape(ARTIST)}\\s*-\\s*"), "") // "InfiniteCable - Song"
        t = t.replace(Regex("(?i)^music (?:race|menu) "), "")
        t = t.split(' ').joinToString(" ") { w ->
            if (w.isEmpty() || w.any { it.isUpperCase() }) w else w.replaceFirstChar { it.uppercase() }
        }
        return t.ifBlank { file }
    }
}

/** Pure shuffle queue (unit tested on the JVM). */
class ShuffleQueue<T>(private val items: List<T>, private val rng: Random = Random.Default) {
    private val queue = ArrayDeque<T>()
    var last: T? = null
        private set

    /** Forget the upcoming order. [last] is kept so the next pick is not a repeat. */
    fun reset() { queue.clear() }

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

/** Which bed the game wants this frame. NONE fades out to silence (countdown, results). */
enum class MusicCue { MENU, RACE, NONE }
