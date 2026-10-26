package com.denoya.xenorush

/** Which vibration to play. Intensity grows from a pickup tick to a nearby explosion. */
enum class HapticKind { PICKUP, BOOST, HIT, EXPLOSION }

object HapticMap {
    /** An explosion this close to the player still rumbles, even when it hits someone else. */
    const val NEAR = 2500f

    fun kind(c: SfxCue): HapticKind? = when (c.slot) {
        RaceSfx.BOOST -> if (c.fromPlayer) HapticKind.BOOST else null
        RaceSfx.PICKUP -> if (c.fromPlayer) HapticKind.PICKUP else null
        RaceSfx.BULLET_HIT -> if (c.fromPlayer) HapticKind.HIT else null
        RaceSfx.SCRAPE -> if (c.fromPlayer || c.distance < NEAR) HapticKind.HIT else null
        RaceSfx.CRASH, RaceSfx.EXPLODE -> if (c.fromPlayer || c.distance < NEAR) HapticKind.EXPLOSION else null
        RaceSfx.ROCKET_HIT, RaceSfx.MISSILE_HIT, RaceSfx.MINE_HIT -> when {
            c.fromPlayer -> HapticKind.EXPLOSION
            c.distance < NEAR -> HapticKind.HIT
            else -> null
        }
        else -> null
    }
}

/**
 * Standard Android gamepad. Face buttons follow the Xbox layout (A bottom, B right, X left, Y top).
 * In a race: A / R2 gas, B / L2 brake, R1 gun, Y special, X cycles weapons, L1 boost, Start pauses.
 * In menus: D-pad or left stick moves, A activates, B goes back.
 */
object PadMap {
    const val DEAD = 0.28f
    const val TRIGGER = 0.40f

    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_START = 108

    enum class Btn { LEFT, RIGHT, UP, DOWN, GAS, BRAKE, GUN, SPECIAL, CYCLE, BOOST, PAUSE, OK, BACK }

    fun key(code: Int): Btn? = when (code) {
        DPAD_LEFT -> Btn.LEFT
        DPAD_RIGHT -> Btn.RIGHT
        DPAD_UP -> Btn.UP
        DPAD_DOWN -> Btn.DOWN
        BUTTON_A -> Btn.OK
        BUTTON_B -> Btn.BACK
        BUTTON_X -> Btn.CYCLE
        BUTTON_Y -> Btn.SPECIAL
        BUTTON_L1 -> Btn.BOOST
        BUTTON_R1 -> Btn.GUN
        BUTTON_L2 -> Btn.BRAKE
        BUTTON_R2 -> Btn.GAS
        BUTTON_START -> Btn.PAUSE
        else -> null
    }

    /** Held race control for this button, or null when the button is menu-only / edge-only. */
    fun raceHold(btn: Btn): String? = when (btn) {
        Btn.LEFT -> "left"
        Btn.RIGHT -> "right"
        Btn.GAS, Btn.OK -> "gas"
        Btn.BRAKE, Btn.BACK -> "brake"
        Btn.GUN -> "gun"
        Btn.SPECIAL -> "special"
        Btn.CYCLE -> "cycle"
        Btn.BOOST -> "boost"
        else -> null
    }

    fun steer(axisX: Float, hatX: Float): Float {
        val x = if (kotlin.math.abs(hatX) > 0.5f) hatX else axisX
        return if (kotlin.math.abs(x) < DEAD) 0f else x
    }

    fun pressed(axis: Float): Boolean = axis > TRIGGER
}

/** Leaving the app pauses a single-player race. A multiplayer race keeps running. */
object BackgroundPolicy {
    fun pauseSinglePlayer(inRace: Boolean, multiplayer: Boolean): Boolean = inRace && !multiplayer
}
