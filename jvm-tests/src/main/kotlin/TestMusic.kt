import com.denoya.xenorush.*
import kotlin.random.Random

fun runMusicTests(check: (String, Boolean) -> Unit) {
    val songs = (1..7).map { "s$it.mp3" }
    val q = ShuffleQueue(songs, Random(42))
    val plays = (0 until 70).map { q.next()!! }
    check("music: every song plays once per cycle", plays.chunked(7).all { it.toSet().size == 7 })
    check("music: never the same song twice in a row", plays.zipWithNext().none { (a, b) -> a == b })
    check("music: order is random", plays.chunked(7).map { it }.toSet().size > 1)
    check("music: different races get different songs", ShuffleQueue(songs, Random(1)).next() != ShuffleQueue(songs, Random(2)).next() || ShuffleQueue(songs, Random(3)).next() != ShuffleQueue(songs, Random(4)).next())
    val one = ShuffleQueue(listOf("only.mp3"))
    check("music: single song loops", one.next() == "only.mp3" && one.next() == "only.mp3")
    check("music: no songs -> silence", ShuffleQueue(emptyList<String>()).next() == null)
    check("music: audio file types", listOf("a.mp3", "b.OGG", "c.m4a", "d.wav", "e.flac", "f.aac").all { MusicConfig.isAudio(it) } && !MusicConfig.isAudio("README.txt"))
    check("music: titles from file names", MusicConfig.titleOf("03_Neon_Overdrive.mp3") == "Neon Overdrive" &&
        MusicConfig.titleOf("InfiniteCable - Star Road.ogg") == "Star Road" && MusicConfig.titleOf("12. Hyper Drift.m4a") == "Hyper Drift")
    val sd = SaveData(); sd.music = false; sd.musicVolume = 25; sd.sfxVolume = 50; sd.sound = false
    val back = SaveCodec.fromJson(SaveCodec.toJson(sd))
    check("music: on/off setting saved", !back.music && SaveCodec.fromJson("{}").music)
    check("audio: volumes round-trip and default on", back.musicVolume == 25 && back.sfxVolume == 50 && !back.sound &&
        SaveCodec.fromJson("{}").let { it.musicVolume == 100 && it.sfxVolume == 100 && it.sound })
    check("music: shipped menu and race tracks", MusicConfig.RACE_TRACKS.size == 10 && MusicConfig.RACE_TRACKS.all { MusicConfig.isRaceTrack(it) } &&
        MusicConfig.RACE_TRACKS.toSet().size == 10 && !MusicConfig.isRaceTrack(MusicConfig.MENU_TRACK) && MusicConfig.isMenuTrack(MusicConfig.MENU_TRACK))
    check("music: race titles", MusicConfig.titleOf("music_race_black_spiral.ogg") == "Black Spiral" &&
        MusicConfig.titleOf("music_menu_black_hoodie_thoughts.ogg") == "Black Hoodie Thoughts")
    val resetQ = ShuffleQueue(songs, Random(7))
    val first = resetQ.next()
    resetQ.reset()
    check("music: new race avoids repeating the last track", resetQ.next() != first)

    val track = TrackGen.build(Catalog.TRACKS[0])
    fun ship(player: Boolean) = Racer(Catalog.VEHICLES[if (player) 0 else 6], VehicleState(true, 1, 1, 1, 1), player, 1f)
    val player = ship(true)
    val rival = ship(false)
    val race = Race(track, listOf(player, rival), 0)
    race.countdown = 0f
    race.drainSfx()
    fun slots(cues: List<SfxCue>) = cues.map { it.slot }
    race.applyPickup(player, PowerUpType.SPEED)
    check("sfx: speed boost cues boost and pickup", slots(race.drainSfx()).let { it.contains(RaceSfx.BOOST) && it.contains(RaceSfx.PICKUP) && it.size == 2 })
    race.applyPickup(player, PowerUpType.SHIELD)
    check("sfx: other pickups cue pickup only", slots(race.drainSfx()) == listOf(RaceSfx.PICKUP))
    player.bullets = 0
    check("sfx: empty gun is silent", !race.fireGun(player) && race.drainSfx().isEmpty())
    player.bullets = 5
    player.gunCooldown = 0f
    check("sfx: gun fire", race.fireGun(player) && race.drainSfx().let { it.size == 1 && it[0].slot == RaceSfx.GUN && it[0].fromPlayer && it[0].vehicleId == 0 })
    player.z = 0f
    rival.z = 3000f
    rival.bullets = 5
    rival.gunCooldown = 0f
    check("sfx: opponent gun fire", race.fireGun(rival) && race.drainSfx().let { it.size == 1 && it[0].slot == RaceSfx.GUN && !it[0].fromPlayer && it[0].vehicleId == 6 && it[0].distance > 2000f })
    player.rockets = 1
    player.fireCooldown = 0f
    player.secondary = WeaponType.ROCKET
    check("sfx: rocket fire", race.fireSecondary(player) && slots(race.drainSfx()) == listOf(RaceSfx.ROCKET))
    player.missiles.add(WeaponType.SEEKER)
    player.secondary = WeaponType.SEEKER
    player.fireCooldown = 0f
    check("sfx: missile fire", race.fireSecondary(player) && slots(race.drainSfx()) == listOf(RaceSfx.MISSILE))
    player.mines = 1
    player.secondary = WeaponType.MINE
    player.fireCooldown = 0f
    check("sfx: mine deploy has no fire slot", race.fireSecondary(player) && race.drainSfx().isEmpty())

    player.x = 3f; player.z = 100f; player.speed = 0f
    rival.x = 0f; rival.z = 8000f; rival.speed = 0f; rival.alive = true; rival.energy = 100
    race.projectiles.add(Projectile(WeaponType.BULLET, rival.z, rival.x, 0f, player, 8f))
    race.update(1f / 60f, PlayerInput(), 0L)
    check("sfx: bullet hit on an opponent", slots(race.drainSfx()).contains(RaceSfx.BULLET_HIT))

    player.x = 0f; player.z = 2000f; player.speed = 0f; player.energy = 100; player.shield = 0
    rival.x = 3f; rival.z = 9000f
    race.projectiles.add(Projectile(WeaponType.ROCKET, player.z, player.x, 0f, rival, 30f))
    race.update(1f / 60f, PlayerInput(), 0L)
    check("sfx: rocket hit on the player", race.drainSfx().any { it.slot == RaceSfx.ROCKET_HIT && it.fromPlayer })

    player.shield = 0; player.energy = 100
    race.projectiles.add(Projectile(WeaponType.SEEKER, player.z, player.x, 0f, rival, 60f, player))
    race.update(1f / 60f, PlayerInput(), 0L)
    check("sfx: missile hit on the player", slots(race.drainSfx()).contains(RaceSfx.MISSILE_HIT))

    rival.x = 0f; rival.z = player.z + 400f; rival.alive = true; rival.energy = 100
    race.mines.add(MineObj(rival.z, rival.x, player, 40f))
    race.update(1f / 60f, PlayerInput(), 0L)
    check("sfx: mine hit on an opponent", slots(race.drainSfx()).contains(RaceSfx.MINE_HIT))
    check("sfx: variant a is the default", RaceSfx.entries.all { SfxMap.variant(it) == SfxVariant.A } && SfxMap.ENGINE == SfxVariant.A)
    check("sfx: default files", SfxMap.file(RaceSfx.BOOST, SfxVariant.A) == "sfx_booster_a" &&
        SfxMap.file(RaceSfx.MINE_HIT, SfxVariant.A) == "sfx_prox_mine_hit_a" && SfxMap.engineFile() == "sfx_ship_accel_loop_a")
    check("sfx: all 30 candidates", SfxMap.allFiles().size == 30 && SfxMap.allFiles().toSet().size == 30)
    check("sfx: bullet tink is a second variant", SfxMap.BULLET_TINK_SECOND == SfxVariant.B && SfxMix.BULLET_TINK_GAP_S == 0.06f)
    check("sfx: one gun voice per ship", ShipGuns.TABLE.map { it.first } == Catalog.VEHICLES.map { it.name })
    check("sfx: gun voices are distinct", ShipGuns.TABLE.map { it.second.variant to it.second.rate }.toSet().size == ShipGuns.TABLE.size)
    check("sfx: gun rates stay in range", ShipGuns.TABLE.all { it.second.rate in 0.5f..2f })
    check("sfx: ai is quieter far away", SfxMix.audibility(true, 8000f) == 1f && SfxMix.audibility(false, 0f) > SfxMix.audibility(false, 5000f))
    check("sfx: stack and cap", SfxMix.stackGain(2) < SfxMix.stackGain(0) && !SfxMix.allow(RaceSfx.GUN, false, 4) && SfxMix.allow(RaceSfx.GUN, true, 4))
    check("sfx: pit loop reuses a bundled file", SfxMap.PIT_LOOP in SfxMap.allFiles() && SfxMap.PIT_GAIN < 0.5f)
}
