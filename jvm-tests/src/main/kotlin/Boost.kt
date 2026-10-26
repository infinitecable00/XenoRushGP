package com.denoya.xenorush

/**
 * Manual boost and the shared speed cap.
 *
 * A press lasts [DURATION_S], then the button recharges for [COOLDOWN_S].
 * While any boost is active (button or speed pad), top speed is
 * `1 + bonus` and never higher. Level 1 is [BONUS_L1] (+30%). A future
 * upgrade can raise the per-ship level; [bonusFor] climbs toward
 * [HARD_MAX_BONUS] (+50%) and will not pass it. Upgrades are not sold yet.
 * The speed pad uses the same cap, so pad + button do not stack past it.
 */
object Boost {
    const val DURATION_S = 3f
    const val COOLDOWN_S = 10f
    const val BONUS_L1 = 0.30f
    const val HARD_MAX_BONUS = 0.50f
    const val MAX_LEVEL = 10

    /** Bonus over base top speed for a booster level. Level 1 is all that ships today. */
    fun bonusFor(level: Int): Float {
        val lv = level.coerceIn(1, MAX_LEVEL)
        if (lv <= 1) return BONUS_L1
        val t = (lv - 1).toFloat() / (MAX_LEVEL - 1)
        return (BONUS_L1 + (HARD_MAX_BONUS - BONUS_L1) * t).coerceAtMost(HARD_MAX_BONUS)
    }

    fun multiplier(level: Int): Float = 1f + bonusFor(level)
}
