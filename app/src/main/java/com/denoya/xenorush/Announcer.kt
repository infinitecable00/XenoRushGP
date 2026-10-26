package com.denoya.xenorush

/**
 * Race announcer. Clips live in assets/sfx/announcer as vo_<voice>_<line>.ogg.
 * Robot speaks the lights. Race calls the robot does not have fall back to the male Kenney clips.
 * Rockk's countdown clip is never used. Turbo is the Rockk clip, used sparingly on a boost.
 */
enum class VoiceChoice { ROBOT, FEMALE, MALE, OFF }

enum class Call {
    THREE, TWO, ONE, GO,
    FINAL_LAP, WIN, LOSE, HIGH_SCORE, POWER_UP, HURRY, BOOST
}

object Announcer {
    const val FOLDER = "sfx/announcer"
    /** Music level while a line is speaking. */
    const val DUCK = 0.55f
    const val POWER_GAP_S = 8f
    const val BOOST_GAP_S = 18f

    fun voiceOf(raw: String?): VoiceChoice = when (raw) {
        "female" -> VoiceChoice.FEMALE
        "male" -> VoiceChoice.MALE
        "off" -> VoiceChoice.OFF
        else -> VoiceChoice.ROBOT
    }

    fun wire(voice: VoiceChoice): String = when (voice) {
        VoiceChoice.FEMALE -> "female"
        VoiceChoice.MALE -> "male"
        VoiceChoice.OFF -> "off"
        VoiceChoice.ROBOT -> "robot"
    }

    fun label(voice: VoiceChoice): String = when (voice) {
        VoiceChoice.ROBOT -> "ROBOT"
        VoiceChoice.FEMALE -> "FEMALE"
        VoiceChoice.MALE -> "MALE"
        VoiceChoice.OFF -> "OFF"
    }

    fun next(voice: VoiceChoice): VoiceChoice = when (voice) {
        VoiceChoice.ROBOT -> VoiceChoice.FEMALE
        VoiceChoice.FEMALE -> VoiceChoice.MALE
        VoiceChoice.MALE -> VoiceChoice.OFF
        VoiceChoice.OFF -> VoiceChoice.ROBOT
    }

    /** The digit on the lights. 0 is GO. */
    fun countdown(mark: Int): Call? = when (mark) {
        3 -> Call.THREE
        2 -> Call.TWO
        1 -> Call.ONE
        0 -> Call.GO
        else -> null
    }

    /** [lap] just became this value. Lap 0 is the opening lap, so the last lap starts at laps - 1. */
    fun isFinalLapStart(lap: Int, laps: Int): Boolean = laps > 1 && lap == laps - 1

    class Index(private val byVoiceLine: Map<Pair<String, String>, String>) {
        fun get(voice: String, line: String): String? = byVoiceLine[voice to line]
        val files: Set<String> get() = byVoiceLine.values.toSet()
    }

    fun index(files: Collection<String>): Index {
        val map = HashMap<Pair<String, String>, String>()
        for (raw in files) {
            val base = raw.substringAfterLast('/').substringBeforeLast('.')
            if (!base.startsWith("vo_")) continue
            val body = base.removePrefix("vo_")
            val voice: String
            val line: String
            when {
                body.startsWith("kenney_male_") -> { voice = "male"; line = body.removePrefix("kenney_male_") }
                body.startsWith("kenney_female_") -> { voice = "female"; line = body.removePrefix("kenney_female_") }
                body.startsWith("robot_") -> { voice = "robot"; line = body.removePrefix("robot_") }
                body.startsWith("rockk_") -> { voice = "rockk"; line = body.removePrefix("rockk_") }
                else -> continue
            }
            val rawLine = line.lowercase().replace('-', '_').replace(' ', '_')
            val key = normalize(line)
            val slot = voice to key
            if (rawLine == key) {
                map[slot] = base
            } else {
                map.putIfAbsent(slot, base)
            }
        }
        return Index(map)
    }

    /** File to play, or null when this voice has no clip for the call (and no allowed fallback). */
    fun resolve(index: Index, voice: VoiceChoice, call: Call): String? {
        if (voice == VoiceChoice.OFF) return null
        if (call == Call.BOOST) return index.get("rockk", "turbo")
        val key = lineKey(call)
        val own = voiceKey(voice)
        index.get(own, key)?.let { return it }
        if (call == Call.WIN) index.get(own, "congratulations")?.let { return it }
        if (call == Call.LOSE) index.get(own, "game_over")?.let { return it }
        if (voice == VoiceChoice.ROBOT && !isLight(call)) {
            index.get("male", key)?.let { return it }
            if (call == Call.WIN) {
                index.get("male", "congratulations")?.let { return it }
                return index.get("rockk", "victory")
            }
            if (call == Call.LOSE) return index.get("male", "game_over")
        }
        if (call == Call.WIN && voice != VoiceChoice.ROBOT) return index.get("rockk", "victory")
        return null
    }

    private fun voiceKey(voice: VoiceChoice): String = when (voice) {
        VoiceChoice.FEMALE -> "female"
        VoiceChoice.MALE -> "male"
        else -> "robot"
    }

    private fun isLight(call: Call): Boolean =
        call == Call.THREE || call == Call.TWO || call == Call.ONE || call == Call.GO

    private fun lineKey(call: Call): String = when (call) {
        Call.THREE -> "three"
        Call.TWO -> "two"
        Call.ONE -> "one"
        Call.GO -> "go"
        Call.FINAL_LAP -> "final_round"
        Call.WIN -> "you_win"
        Call.LOSE -> "you_lose"
        Call.HIGH_SCORE -> "new_highscore"
        Call.POWER_UP -> "power_up"
        Call.HURRY -> "hurry_up"
        Call.BOOST -> "turbo"
    }

    private fun normalize(line: String): String {
        val n = line.lowercase().replace('-', '_').replace(' ', '_')
        return when (n) {
            "3", "countdown_3", "countdown3" -> "three"
            "2", "countdown_2", "countdown2" -> "two"
            "1", "countdown_1", "countdown1" -> "one"
            "4" -> "four"
            "5" -> "five"
            "finalround", "final_lap", "finallap" -> "final_round"
            "youwin", "win" -> "you_win"
            "youlose", "lose" -> "you_lose"
            "gameover" -> "game_over"
            "new_high_score", "highscore", "newhighscore" -> "new_highscore"
            "powerup" -> "power_up"
            "hurryup", "hurry" -> "hurry_up"
            "congrats" -> "congratulations"
            "nitro" -> "turbo"
            else -> n
        }
    }
}

/**
 * One line at a time. A countdown clears the queue so the lights stay on the beat.
 * A boost is dropped instead of queued. Power-ups inside [Announcer.POWER_GAP_S] are dropped.
 */
class LineQueue {
    private var powerCd = 0f
    private var boostCd = 0f
    private var queued: Call? = null

    fun tick(dt: Float) {
        if (powerCd > 0f) powerCd = (powerCd - dt).coerceAtLeast(0f)
        if (boostCd > 0f) boostCd = (boostCd - dt).coerceAtLeast(0f)
    }

    fun pushCountdown() { queued = null }

    /**
     * The call to start now. Null means it was dropped or is waiting behind [busy].
     */
    fun submit(call: Call, busy: Boolean): Call? {
        when (call) {
            Call.POWER_UP -> {
                if (powerCd > 0f) return null
                powerCd = Announcer.POWER_GAP_S
            }
            Call.BOOST -> {
                if (boostCd > 0f || busy || queued != null) return null
                boostCd = Announcer.BOOST_GAP_S
                return call
            }
            else -> {}
        }
        if (!busy) return call
        if (queued == null) queued = call
        return null
    }

    fun poll(busy: Boolean): Call? {
        if (busy) return null
        val next = queued
        queued = null
        return next
    }
}
