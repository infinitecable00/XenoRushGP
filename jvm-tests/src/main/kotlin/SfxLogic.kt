package com.denoya.xenorush

/**
 * Sound-effect slots and the one table that picks which file plays.
 *
 * Every candidate is bundled under app/src/main/assets/sfx/ (a, b, and c).
 * To swap a slot, change [SfxMap.DEFAULT] or [SfxMap.ENGINE]. Guns are
 * per ship in [ShipGuns.TABLE]. A missing file stays silent.
 */
enum class SfxVariant(val suffix: String) { A("a"), B("b"), C("c") }

enum class RaceSfx {
    BOOST, PICKUP, GUN, MISSILE, ROCKET, BULLET_HIT, MISSILE_HIT, ROCKET_HIT, MINE_HIT,
    SCRAPE, CRASH, EXPLODE
}

/** One race event, plus who made the sound so mixing can scale it. */
data class SfxCue(
    val slot: RaceSfx,
    val vehicleId: Int,
    val fromPlayer: Boolean,
    val distance: Float
)

object SfxMap {
    const val FOLDER = "sfx"

    /**
     * Default variant for every one-shot slot. Variant A is the shipped choice.
     * Change a single entry to try that slot's b or c file. Guns use [ShipGuns] instead.
     */
    val DEFAULT: Map<RaceSfx, SfxVariant> = mapOf(
        RaceSfx.BOOST to SfxVariant.A,
        RaceSfx.PICKUP to SfxVariant.A,
        RaceSfx.GUN to SfxVariant.A,
        RaceSfx.MISSILE to SfxVariant.A,
        RaceSfx.ROCKET to SfxVariant.A,
        RaceSfx.BULLET_HIT to SfxVariant.A,
        RaceSfx.MISSILE_HIT to SfxVariant.A,
        RaceSfx.ROCKET_HIT to SfxVariant.A,
        RaceSfx.MINE_HIT to SfxVariant.A
    )

    /** Engine loop. b and c are in the APK; change this to swap the loop. */
    val ENGINE = SfxVariant.A

    /**
     * Energy hum while the shield is filling on the recharge strip.
     * `sfx_booster_c` is the layered phaser-plus-thruster candidate, played
     * slower so it reads as a hum. Point [PIT_LOOP] at another [allFiles] name to swap it.
     * The loop stops when the ship leaves the lane or the shield hits full.
     */
    const val PIT_LOOP = "sfx_booster_c"
    const val PIT_GAIN = 0.40f
    const val PIT_RATE = 0.70f

    /**
     * Short chime when the strip tops the shield off.
     * `sfx_powerup_pickup_b` pitched up, so it is not the same blip as a pickup.
     */
    const val PIT_FULL = "sfx_powerup_pickup_b"
    const val PIT_FULL_GAIN = 0.88f
    const val PIT_FULL_RATE = 1.28f

    /**
     * Lights. Replace the files in assets/sfx to change the countdown.
     * Tick is the short gun sample, pitched up. GO is the rocket impact, pitched up and a little longer.
     */
    const val COUNTDOWN_TICK = "sfx_countdown_tick"
    const val COUNTDOWN_GO = "sfx_countdown_go"
    const val COUNTDOWN_TICK_GAIN = 0.90f
    const val COUNTDOWN_TICK_RATE = 1.45f
    const val COUNTDOWN_GO_GAIN = 0.95f
    const val COUNTDOWN_GO_RATE = 1.20f

    /** Second ping of a bullet hit, played [SfxMix.BULLET_TINK_GAP_S] later. */
    val BULLET_TINK_SECOND = SfxVariant.B

    fun variant(slot: RaceSfx): SfxVariant = DEFAULT[slot] ?: SfxVariant.A

    fun file(slot: RaceSfx, variant: SfxVariant): String = when (slot) {
        RaceSfx.BOOST -> "sfx_booster_${variant.suffix}"
        RaceSfx.PICKUP -> "sfx_powerup_pickup_${variant.suffix}"
        RaceSfx.GUN -> "sfx_gun_fire_${variant.suffix}"
        RaceSfx.MISSILE -> "sfx_missile_fire_${variant.suffix}"
        RaceSfx.ROCKET -> "sfx_rocket_fire_${variant.suffix}"
        RaceSfx.BULLET_HIT -> "sfx_gun_bullet_hit_${variant.suffix}"
        RaceSfx.MISSILE_HIT -> "sfx_missile_hit_${variant.suffix}"
        RaceSfx.ROCKET_HIT -> "sfx_rocket_hit_${variant.suffix}"
        RaceSfx.MINE_HIT -> "sfx_prox_mine_hit_${variant.suffix}"
        RaceSfx.SCRAPE -> "sfx_gun_bullet_hit_${variant.suffix}"
        RaceSfx.CRASH -> "sfx_rocket_hit_${variant.suffix}"
        RaceSfx.EXPLODE -> "sfx_prox_mine_hit_${variant.suffix}"
    }

    fun engineFile(variant: SfxVariant = ENGINE): String = "sfx_ship_accel_loop_${variant.suffix}"

    /** Every bundled candidate, so the loader can keep them all resident. */
    fun allFiles(): List<String> {
        val bundled = listOf(
            RaceSfx.BOOST, RaceSfx.PICKUP, RaceSfx.GUN, RaceSfx.MISSILE, RaceSfx.ROCKET,
            RaceSfx.BULLET_HIT, RaceSfx.MISSILE_HIT, RaceSfx.ROCKET_HIT, RaceSfx.MINE_HIT
        )
        val shots = bundled.flatMap { slot -> SfxVariant.entries.map { file(slot, it) } }
        val loops = SfxVariant.entries.map { engineFile(it) }
        return shots + loops + listOf(COUNTDOWN_TICK, COUNTDOWN_GO)
    }
}

/**
 * One gun voice per ship. Three recordings cover 22 ships by pairing each
 * file with its own playback rate (1.0 is the recording, higher is sharper).
 * `sfx_gun_fire_c` is the short sample, so it is used on the ships that
 * should stay crisp under rapid fire.
 */
object ShipGuns {
    data class Voice(val variant: SfxVariant, val rate: Float)

    val TABLE: List<Pair<String, Voice>> = listOf(
        "Sprint MK-I" to Voice(SfxVariant.C, 1.00f),
        "Vega Runner" to Voice(SfxVariant.A, 0.90f),
        "Kestrel D2" to Voice(SfxVariant.B, 1.08f),
        "Pulsar GT" to Voice(SfxVariant.C, 0.86f),
        "Ion Drifter" to Voice(SfxVariant.A, 1.12f),
        "Falcon V" to Voice(SfxVariant.B, 0.84f),
        "Nova Sting" to Voice(SfxVariant.C, 1.14f),
        "Crimson Dart" to Voice(SfxVariant.A, 0.78f),
        "Zephyr R9" to Voice(SfxVariant.B, 1.22f),
        "Hellion X" to Voice(SfxVariant.C, 1.28f),
        "Tempest MK-III" to Voice(SfxVariant.A, 1.26f),
        "Quasar Blade" to Voice(SfxVariant.B, 0.96f),
        "Specter Wraith" to Voice(SfxVariant.C, 0.74f),
        "Vortex Prime" to Voice(SfxVariant.A, 1.40f),
        "Ember Fang" to Voice(SfxVariant.B, 1.36f),
        "Onyx Phantom" to Voice(SfxVariant.C, 1.20f),
        "Solaris Apex" to Voice(SfxVariant.A, 1.00f),
        "Hydra Dominator" to Voice(SfxVariant.B, 1.14f),
        "Nemesis Zero" to Voice(SfxVariant.C, 0.94f),
        "Aurora Titan" to Voice(SfxVariant.A, 0.84f),
        "Eclipse Omega" to Voice(SfxVariant.B, 0.78f),
        "Xenon Deity" to Voice(SfxVariant.C, 1.36f)
    )

    fun voiceFor(name: String): Voice =
        TABLE.firstOrNull { it.first == name }?.second ?: Voice(SfxMap.variant(RaceSfx.GUN), 1f)

    fun voice(vehicleId: Int): Voice = voiceFor(Catalog.VEHICLES.getOrNull(vehicleId)?.name ?: "")
}

/** Loudness rules shared by the player and the tests. */
object SfxMix {
    const val BULLET_TINK_GAP_S = 0.06f
    const val BULLET_TINK_GAIN = 0.78f

    fun maxConcurrent(slot: RaceSfx): Int = when (slot) {
        RaceSfx.GUN -> 4
        RaceSfx.BULLET_HIT -> 4
        RaceSfx.MISSILE, RaceSfx.ROCKET -> 2
        RaceSfx.MISSILE_HIT, RaceSfx.ROCKET_HIT, RaceSfx.MINE_HIT -> 2
        RaceSfx.BOOST, RaceSfx.PICKUP -> 2
        RaceSfx.SCRAPE -> 2
        RaceSfx.CRASH, RaceSfx.EXPLODE -> 2
    }

    /** How long a play still counts as "sounding" for the cap above. */
    fun windowS(slot: RaceSfx): Float = when (slot) {
        RaceSfx.GUN, RaceSfx.BULLET_HIT, RaceSfx.SCRAPE -> 0.14f
        RaceSfx.PICKUP, RaceSfx.BOOST -> 0.35f
        else -> 0.90f
    }

    /** Player always gets through. A few extra AI copies are dropped. */
    fun allow(slot: RaceSfx, fromPlayer: Boolean, active: Int): Boolean {
        val cap = maxConcurrent(slot)
        if (active < cap) return true
        return fromPlayer && active < cap + 2
    }

    /** 1.0 for the player. AI starts a little quieter and falls off with distance. */
    fun audibility(fromPlayer: Boolean, distance: Float): Float {
        if (fromPlayer) return 1f
        val t = ((distance - 400f) / 5000f).coerceIn(0f, 1f)
        return 0.82f * (1f - t * 0.70f)
    }

    fun stackGain(alreadyPlaying: Int): Float = when {
        alreadyPlaying <= 0 -> 1f
        alreadyPlaying == 1 -> 0.70f
        else -> 0.48f
    }

    fun slotGain(slot: RaceSfx): Float = when (slot) {
        RaceSfx.GUN -> 0.48f
        RaceSfx.BULLET_HIT -> 0.55f
        RaceSfx.MISSILE, RaceSfx.ROCKET -> 0.62f
        RaceSfx.MISSILE_HIT, RaceSfx.ROCKET_HIT, RaceSfx.MINE_HIT, RaceSfx.CRASH, RaceSfx.EXPLODE -> 0.58f
        RaceSfx.BOOST, RaceSfx.PICKUP -> 0.80f
        RaceSfx.SCRAPE -> 0.50f
    }
}
