package com.denoya.xenorush

/**
 * Named sound-effect slots. Files live in app/src/main/assets/sfx/.
 * The loader tries each [SfxConfig.EXTENSIONS] in order, so dropping
 * sfx_gun.ogg next to the placeholder sfx_gun.wav replaces it.
 * A missing file is silent — it never crashes the race.
 */
enum class RaceSfx(val fileBase: String) {
    BOOST("sfx_boost"),
    PICKUP("sfx_pickup"),
    GUN("sfx_gun"),
    MISSILE("sfx_missile"),
    ROCKET("sfx_rocket"),
    BULLET_HIT("sfx_bullet_hit"),
    MISSILE_HIT("sfx_missile_hit"),
    ROCKET_HIT("sfx_rocket_hit"),
    MINE_HIT("sfx_mine_hit")
}

object SfxConfig {
    const val FOLDER = "sfx"
    const val ENGINE = "sfx_engine"
    val EXTENSIONS = listOf("ogg", "wav", "mp3")
}
