package com.denoya.xenorush

/**
 * Every vehicle carries the full loadout:
 *  BULLET  machine gun, 100 rounds max, 5-15 hp per hit (scales with the Weapon upgrade)
 *  ROCKET  unguided, 4 max, 20-40 hp (Rocket upgrade)
 *  SEEKER  guided missile, locks the nearest racer ahead          } 2 guided
 *  HUNTER  guided missile, locks the racer one position ahead     } missiles max
 *  LEADER  guided missile, locks the race leader                  } (55-75 hp, Missile upgrade)
 *  MINE    dropped behind, 3 max, 40-60 hp (Mine upgrade)
 */
enum class WeaponType { BULLET, ROCKET, SEEKER, HUNTER, LEADER, MINE;
    val guided: Boolean get() = this == SEEKER || this == HUNTER || this == LEADER
}

object Arms {
    const val MAX_HULL = 100
    const val MAX_SHIELD = 100
    const val MAX_BULLETS = 100
    const val MAX_ROCKETS = 4
    const val MAX_MISSILES = 2
    const val MAX_MINES = 3
    const val GUN_INTERVAL = 0.12f     // ~8 rounds per second while held
    const val SHIELD_PICKUP = 50
    const val REPAIR_PICKUP = 35
    /** Weapon upgrade 1..10 -> 5..15 hp per bullet. */
    fun bulletDamage(weaponLevel: Int): Float = 5f + (weaponLevel.coerceIn(1, 10) - 1) * 10f / 9f
    /** Rocket upgrade 1..10 -> 20..40 hp. */
    fun rocketDamage(level: Int): Float = scale(20f, 40f, level)
    /** Missile upgrade 1..10 -> 55..75 hp (all three guided missiles). */
    fun missileDamage(level: Int): Float = scale(55f, 75f, level)
    /** Mine upgrade 1..10 -> 40..60 hp. */
    fun mineDamage(level: Int): Float = scale(40f, 60f, level)
    private fun scale(lo: Float, hi: Float, level: Int): Float = lo + (level.coerceIn(1, 10) - 1) * (hi - lo) / 9f

    fun name(w: WeaponType): String = when (w) {
        WeaponType.BULLET -> "Machine Gun"
        WeaponType.ROCKET -> "Rocket"
        WeaponType.SEEKER -> "Seeker Missile"
        WeaponType.HUNTER -> "Hunter Missile"
        WeaponType.LEADER -> "Leader Missile"
        WeaponType.MINE -> "Mine"
    }
    fun short(w: WeaponType): String = when (w) {
        WeaponType.BULLET -> "GUN"; WeaponType.ROCKET -> "RKT"; WeaponType.SEEKER -> "SEEK"
        WeaponType.HUNTER -> "HUNT"; WeaponType.LEADER -> "LEAD"; WeaponType.MINE -> "MINE"
    }
}

data class VehicleDef(
    val id: Int,
    val name: String,
    val topSpeed: Float,      // world units / second
    val accel: Float,         // units / s^2
    val grip: Float,          // lateral stability multiplier
    val priceDrops: Long,     // 0 = free
    val color: Int
)

data class TrackDef(
    val id: Int,
    val name: String,
    val seed: Long,
    val difficulty: Int,       // 1..5
    val priceDrops: Long,      // 0 = free
    val skyTop: Int,
    val skyBottom: Int,
    val roadLight: Int,
    val roadDark: Int,
    val neon: Int
)

object Catalog {
    const val FREE_VEHICLES = 5
    const val FREE_TRACKS = 5
    /** Credits (earned by winning races) can raise a stat to this level; 6-10 are paid unlocks. */
    const val FREE_UPGRADE_MAX = 5
    const val MAX_UPGRADE = 10
    /** Racers per single-player race: you + 4 AI. */
    const val RACERS_PER_RACE = 5
    /** Upgrade stat keys: engine, thrusters, stabilizers, weapon (gun), rockets, missiles, mines. */
    val STATS = listOf("e", "a", "g", "w", "r", "m", "n")
    /** True if the next level of a stat currently at [level] can be bought with race credits. */
    fun creditUpgradeAllowed(level: Int): Boolean = level < FREE_UPGRADE_MAX

    const val XRP_DROPS = 1_000_000L

    // Bundle prices
    const val BUNDLE_ALL_VEHICLES_DROPS = 250L * XRP_DROPS
    const val BUNDLE_ALL_TRACKS_DROPS = 120L * XRP_DROPS
    const val BUNDLE_EVERYTHING_DROPS = 300L * XRP_DROPS

    private val vehicleNames = listOf(
        "Sprint MK-I", "Vega Runner", "Kestrel D2", "Pulsar GT", "Ion Drifter",
        "Falcon V", "Nova Sting", "Crimson Dart", "Zephyr R9", "Hellion X",
        "Tempest MK-III", "Quasar Blade", "Specter Wraith", "Vortex Prime", "Ember Fang",
        "Onyx Phantom", "Solaris Apex", "Hydra Dominator", "Nemesis Zero", "Aurora Titan",
        "Eclipse Omega", "Xenon Deity"
    )

    private val vehicleColors = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8A65.toInt(), 0xFFBA68C8.toInt(),
        0xFF4DD0E1.toInt(), 0xFFFF5370.toInt(), 0xFFFFB74D.toInt(), 0xFF7E57C2.toInt(), 0xFF26A69A.toInt(),
        0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFF29B6F6.toInt(), 0xFFAB47BC.toInt(), 0xFFFFCA28.toInt(),
        0xFF78909C.toInt(), 0xFF8BC34A.toInt(), 0xFFFF7043.toInt(), 0xFF5C6BC0.toInt(), 0xFFFFD740.toInt(),
        0xFFEC407A.toInt(), 0xFF00E5FF.toInt()
    )

    val VEHICLES: List<VehicleDef> = (0 until 22).map { i ->
        VehicleDef(
            id = i,
            name = vehicleNames[i],
            topSpeed = 8600f + i * 270f,
            accel = 3300f + i * 115f,
            grip = 0.72f + i * 0.031f,
            priceDrops = if (i < FREE_VEHICLES) 0L else 15_000_000L + i * 4_000_000L,
            color = vehicleColors[i]
        )
    }

    private val trackNames = listOf(
        "Neo Kyoto Loop", "Sunset Boulevard", "Cobalt Runway", "Emerald Straight", "Amber Canyon",
        "Crimson Skyway", "Frozen Circuit", "Toxic Refinery", "Midnight Highway", "Chrome Spires",
        "Volcano Pass", "Deep Blue Dome", "Scrapyard Sprint", "Aurora Fields", "Thunder Basin",
        "Obsidian Ridge", "Neon Undercity", "Crystal Mines", "Gravity Well", "Sahara Mirage",
        "Polar Vortex", "Magma Chamber", "Sky Gardens", "Quantum Lab", "Wasteland Rally",
        "Emerald Abyss", "Titan Docks", "Void Circuit", "Supernova Ring", "Xeno Grand Final"
    )

    private val skyPalettes = arrayOf(
        intArrayOf(0xFF1A237E.toInt(), 0xFF7C4DFF.toInt()),   // indigo/violet
        intArrayOf(0xFF37474F.toInt(), 0xFFFF8A65.toInt()),     // dusk orange
        intArrayOf(0xFF0D47A1.toInt(), 0xFF4FC3F7.toInt()),    // blue
        intArrayOf(0xFF1B5E20.toInt(), 0xFF9CCC65.toInt()),    // green
        intArrayOf(0xFF4E342E.toInt(), 0xFFFFB74D.toInt()),    // canyon
        intArrayOf(0xFFB71C1C.toInt(), 0xFFFF5370.toInt()),    // red
        intArrayOf(0xFF263238.toInt(), 0xFF80DEEA.toInt()),    // ice
        intArrayOf(0xFF33691E.toInt(), 0xFFAEEA00.toInt()),    // toxic
        intArrayOf(0xFF050510.toInt(), 0xFF7986CB.toInt()),    // night
        intArrayOf(0xFF37474F.toInt(), 0xFFCFD8DC.toInt()),    // chrome
        intArrayOf(0xFFBF360C.toInt(), 0xFFFF8A65.toInt()),    // volcano
        intArrayOf(0xFF01579B.toInt(), 0xFF00E5FF.toInt()),   // underwater
        intArrayOf(0xFF4E342E.toInt(), 0xFF8D6E63.toInt()),    // scrap
        intArrayOf(0xFF1A237E.toInt(), 0xFF80CBC4.toInt()),    // aurora
        intArrayOf(0xFF26C6DA.toInt(), 0xFF4FC3F7.toInt()),   // storm
        intArrayOf(0xFF212121.toInt(), 0xFF616161.toInt()),    // obsidian
        intArrayOf(0xFF311B92.toInt(), 0xFFE040FB.toInt()),    // neon
        intArrayOf(0xFF006064.toInt(), 0xFF84FFFF.toInt()),   // crystal
        intArrayOf(0xFF1A0033.toInt(), 0xFF7C4DFF.toInt()),    // gravity
        intArrayOf(0xFF8D6E63.toInt(), 0xFFFFEE58.toInt()),   // desert
        intArrayOf(0xFF263238.toInt(), 0xFFB3E5FC.toInt()),    // polar
        intArrayOf(0xFF3E2723.toInt(), 0xFFFF5722.toInt()),    // magma
        intArrayOf(0xFF1B5E20.toInt(), 0xFFAED581.toInt()),    // sky garden
        intArrayOf(0xFF0D47A1.toInt(), 0xFF00E5FF.toInt()),    // quantum
        intArrayOf(0xFF4E342E.toInt(), 0xFFD7CCC8.toInt()),    // wasteland
        intArrayOf(0xFF004D40.toInt(), 0xFF1DE9B6.toInt()),    // emerald abyss
        intArrayOf(0xFF455A64.toInt(), 0xFF90A4AE.toInt()),    // titan
        intArrayOf(0xFF12002E.toInt(), 0xFF651FFF.toInt()),    // void
        intArrayOf(0xFFE65100.toInt(), 0xFFFFD180.toInt()),    // supernova
        intArrayOf(0xFF000000.toInt(), 0xFF00E5FF.toInt())    // final
    )

    val TRACKS: List<TrackDef> = (0 until 30).map { i ->
        TrackDef(
            id = i,
            name = trackNames[i],
            seed = 1000L + i * 7777L,
            difficulty = 1 + i / 6,
            priceDrops = if (i < FREE_TRACKS) 0L else 5_000_000L + i * 500_000L,
            skyTop = skyPalettes[i][0],
            skyBottom = skyPalettes[i][1],
            roadLight = 0xFF3A3F5C.toInt(),
            roadDark = 0xFF333846.toInt(),
            neon = skyPalettes[i][1]
        )
    }

    fun rewardForRank(rank: Int, difficulty: Int): Int {
        val base = intArrayOf(500, 350, 250, 180, 120, 80, 50, 30)
        val v = base[(rank - 1).coerceIn(0, 7)] * (1.0 + difficulty * 0.15)
        return v.toInt()
    }

    // Upgrade costs in credits: level N -> N+1 costs 300 * (N) * (1 + tier*0.4)
    fun upgradeCost(vehicleId: Int, currentLevel: Int): Int {
        val tier = vehicleId / 5
        return (300 * currentLevel * (1 + tier * 0.4)).toInt()
    }
}
