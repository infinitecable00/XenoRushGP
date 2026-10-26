package com.denoya.xenorush

import kotlin.math.atan2

/**
 * Barrier hits at the edge of the track (|x| = [C.MAX_LATERAL]).
 * A shallow angle is a scrape. A steep angle at speed is a crash.
 * Hull zero waits [RESPAWN_DELAY_S], then the ship returns to z = 0 of the same lap.
 */
object Impact {
    const val SCRAPE_ANGLE_DEG = 28f
    const val SCRAPE_MIN_SPEED = 1800f
    const val CRASH_MIN_SPEED = 4500f
    const val SCRAPE_COOLDOWN_S = 0.22f
    /** Fraction of speed kept on a light scrape. Steeper and faster scrapes keep less, down by [SCRAPE_HARSH]. */
    const val SCRAPE_KEEP = 0.90f
    /** Most extra speed a scrape can take off [SCRAPE_KEEP]. */
    const val SCRAPE_HARSH = 0.12f
    const val SCRAPE_SHIELD = 3
    /** Fraction of speed kept on a crash. The rest is gone. */
    const val CRASH_KEEP = 0.15f
    const val CRASH_STUN_S = 1.0f
    const val CRASH_SHIELD = 28
    const val CRASH_HULL = 16
    const val RESPAWN_DELAY_S = 1.75f
    const val INVULN_S = 2.0f

    enum class Kind { NONE, SCRAPE, CRASH }

    /**
     * [penetration] is how far past the wall this frame, in lateral units (1 = half a road).
     * [forward] is distance along the track this frame, in world units.
     */
    fun kind(speed: Float, penetration: Float, forward: Float): Kind {
        if (penetration <= 0.002f || speed < SCRAPE_MIN_SPEED) return Kind.NONE
        val angle = angleDeg(penetration, forward)
        return if (angle >= SCRAPE_ANGLE_DEG && speed >= CRASH_MIN_SPEED) Kind.CRASH else Kind.SCRAPE
    }

    /** Fraction of speed kept. A shallow slow glance stays near [SCRAPE_KEEP]. */
    fun scrapeKeep(speed: Float, penetration: Float, forward: Float): Float {
        val angleT = (angleDeg(penetration, forward) / SCRAPE_ANGLE_DEG).coerceIn(0f, 1f)
        val speedT = ((speed - SCRAPE_MIN_SPEED) / (CRASH_MIN_SPEED - SCRAPE_MIN_SPEED)).coerceIn(0f, 1f)
        val harsh = SCRAPE_HARSH * angleT * (0.35f + 0.65f * speedT)
        return (SCRAPE_KEEP - harsh).coerceIn(SCRAPE_KEEP - SCRAPE_HARSH, SCRAPE_KEEP)
    }

    private fun angleDeg(penetration: Float, forward: Float): Float {
        val lateral = penetration * (C.ROAD_WIDTH / 2f)
        return Math.toDegrees(atan2(lateral.toDouble(), forward.toDouble().coerceAtLeast(1.0))).toFloat()
    }
}

/** The digit on the lights. 3, 2, 1, then 0 for GO, then -1 once the race has started. */
object Countdown {
    fun mark(countdown: Float): Int = when {
        countdown > 3f -> 3
        countdown > 2f -> 2
        countdown > 1f -> 1
        countdown > 0f -> 0
        else -> -1
    }
}
