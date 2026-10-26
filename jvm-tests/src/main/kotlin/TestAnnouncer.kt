import com.denoya.xenorush.*

fun runAnnouncerTests(check: (String, Boolean) -> Unit) {
    check("countdown marks match the lights",
        Countdown.mark(3.99f) == 3 && Countdown.mark(3.01f) == 3 && Countdown.mark(2.5f) == 2 &&
            Countdown.mark(1.01f) == 1 && Countdown.mark(0.4f) == 0 && Countdown.mark(0f) == -1 &&
            Countdown.mark(-1f) == -1)
    check("countdown calls follow the marks",
        Announcer.countdown(3) == Call.THREE && Announcer.countdown(0) == Call.GO && Announcer.countdown(-1) == null)
    check("final lap starts when the lap index reaches the last lap",
        !Announcer.isFinalLapStart(0, 3) && !Announcer.isFinalLapStart(1, 3) && Announcer.isFinalLapStart(2, 3))

    val files = listOf(
        "vo_robot_one.ogg", "vo_robot_two.ogg", "vo_robot_three.ogg", "vo_robot_go.ogg",
        "vo_kenney_male_three.ogg", "vo_kenney_male_two.ogg", "vo_kenney_male_one.ogg", "vo_kenney_male_go.ogg",
        "vo_kenney_male_you_win.ogg", "vo_kenney_male_you_lose.ogg", "vo_kenney_male_final_round.ogg",
        "vo_kenney_male_new_highscore.ogg", "vo_kenney_male_power_up.ogg", "vo_kenney_male_hurry_up.ogg",
        "vo_kenney_female_you_win.ogg", "vo_kenney_female_game_over.ogg", "vo_kenney_female_three.ogg",
        "vo_rockk_countdown.ogg", "vo_rockk_victory.ogg", "vo_rockk_turbo.ogg"
    )
    val index = Announcer.index(files)
    check("robot countdown uses the robot clip",
        Announcer.resolve(index, VoiceChoice.ROBOT, Call.THREE) == "vo_robot_three" &&
            Announcer.resolve(index, VoiceChoice.ROBOT, Call.GO) == "vo_robot_go")
    check("robot race calls fall back to the male voice",
        Announcer.resolve(index, VoiceChoice.ROBOT, Call.WIN) == "vo_kenney_male_you_win" &&
            Announcer.resolve(index, VoiceChoice.ROBOT, Call.FINAL_LAP) == "vo_kenney_male_final_round" &&
            Announcer.resolve(index, VoiceChoice.ROBOT, Call.HURRY) == "vo_kenney_male_hurry_up")
    check("female uses her own win line",
        Announcer.resolve(index, VoiceChoice.FEMALE, Call.WIN) == "vo_kenney_female_you_win")
    check("a missing lose line uses game over",
        Announcer.resolve(index, VoiceChoice.FEMALE, Call.LOSE) == "vo_kenney_female_game_over")
    check("announcer off is silent", Announcer.resolve(index, VoiceChoice.OFF, Call.GO) == null)
    check("rockk countdown is not the lights",
        Announcer.resolve(index, VoiceChoice.MALE, Call.THREE) == "vo_kenney_male_three")
    check("boost uses the rockk turbo clip",
        Announcer.resolve(index, VoiceChoice.ROBOT, Call.BOOST) == "vo_rockk_turbo" &&
            Announcer.resolve(index, VoiceChoice.OFF, Call.BOOST) == null)
    check("voice setting defaults to robot and cycles",
        Announcer.voiceOf(null) == VoiceChoice.ROBOT && Announcer.voiceOf("nope") == VoiceChoice.ROBOT &&
            Announcer.next(VoiceChoice.ROBOT) == VoiceChoice.FEMALE && Announcer.next(VoiceChoice.OFF) == VoiceChoice.ROBOT &&
            Announcer.wire(VoiceChoice.MALE) == "male")

    val q = LineQueue()
    check("power-up throttles", q.submit(Call.POWER_UP, false) == Call.POWER_UP && q.submit(Call.POWER_UP, false) == null)
    q.tick(Announcer.POWER_GAP_S)
    check("power-up speaks again after the gap", q.submit(Call.POWER_UP, false) == Call.POWER_UP)
    check("a busy line queues one call and drops the next",
        q.submit(Call.WIN, true) == null && q.submit(Call.LOSE, true) == null && q.poll(false) == Call.WIN && q.poll(false) == null)
    check("boost is dropped while a line is waiting", q.submit(Call.BOOST, true) == null && q.poll(false) == null)
    q.tick(Announcer.BOOST_GAP_S)
    check("boost plays when nothing else is speaking", q.submit(Call.BOOST, false) == Call.BOOST)
    q.submit(Call.WIN, true)
    q.pushCountdown()
    check("countdown clears a queued line", q.poll(false) == null)

    val fresh = SaveData()
    check("announcer defaults to robot and stays on this device",
        Announcer.voiceOf(fresh.voice) == VoiceChoice.ROBOT)
    fresh.voice = "female"
    val back = SaveCodec.fromJson(SaveCodec.toJson(fresh))
    check("announcer voice round-trips", back.voice == "female")
    val remote = SaveData(); remote.voice = "off"
    check("announcer voice is not overwritten by a cloud save", SaveMerge.merge(fresh, remote).voice == "female")
    check("old save keeps the robot", SaveCodec.fromJson("{}").voice == "robot")

    check("countdown files are bundled names",
        SfxMap.COUNTDOWN_TICK == "sfx_countdown_tick" && SfxMap.COUNTDOWN_GO == "sfx_countdown_go" &&
            SfxMap.COUNTDOWN_TICK in SfxMap.allFiles() && SfxMap.COUNTDOWN_GO in SfxMap.allFiles() &&
            SfxMap.allFiles().size == 32)

    check("a shallow hit is a scrape", Impact.kind(5000f, 0.02f, 80f) == Impact.Kind.SCRAPE)
    check("a steep hit at speed is a crash", Impact.kind(6000f, 0.4f, 200f) == Impact.Kind.CRASH)
    check("a steep slow hit stays a scrape", Impact.kind(2000f, 0.4f, 40f) == Impact.Kind.SCRAPE)
    check("a slow touch is nothing", Impact.kind(1000f, 0.4f, 20f) == Impact.Kind.NONE)
    check("a steeper scrape keeps less speed",
        Impact.scrapeKeep(4000f, 0.01f, 400f) > Impact.scrapeKeep(4400f, 0.25f, 30f))

    val track = TrackGen.build(Catalog.TRACKS[0])
    fun ship(player: Boolean) = Racer(Catalog.VEHICLES[if (player) 0 else 3], VehicleState(true, 1, 1, 1, 1), player, 1f)
    val player = ship(true)
    val ai = ship(false)
    val race = Race(track, listOf(player, ai), 0)
    race.countdown = 0f
    player.x = 3.2f
    player.z = 5000f
    player.speed = 7000f
    player.shield = 100
    player.energy = 100
    player.lap = 1
    race.update(0.016f, PlayerInput(), 0L)
    check("a head-on wall hit crashes",
        player.stunT > 0.5f && player.shield == 100 - Impact.CRASH_SHIELD &&
            player.energy == 100 - Impact.CRASH_HULL && player.speed < 7000f * Impact.CRASH_KEEP + 50f &&
            player.lap == 1 && race.drainSfx().any { it.slot == RaceSfx.CRASH && it.fromPlayer })

    player.invulnT = 0f
    player.stunT = 0f
    player.wallCd = 0f
    player.x = 2.42f
    player.speed = 3000f
    player.shield = 40
    player.energy = 90
    race.update(0.016f, PlayerInput(), 0L)
    check("a glancing wall hit scrapes the shield",
        player.stunT == 0f && player.shield == 37 && player.energy == 90 && player.speed < 3000f &&
            race.drainSfx().any { it.slot == RaceSfx.SCRAPE })

    ai.x = 3.2f
    ai.speed = 7000f
    ai.shield = 100
    ai.energy = 100
    ai.z = 4000f
    race.update(0.016f, PlayerInput(), 0L)
    check("ai ships crash into the wall too", ai.stunT > 0.5f && race.drainSfx().any { it.slot == RaceSfx.CRASH && !it.fromPlayer })

    player.x = 0f
    player.z = 5000f
    player.lap = 1
    player.speed = 4000f
    player.energy = 20
    player.invulnT = 0f
    player.alive = true
    val before = player.lap
    race.hitRacer(player, 75f, null)
    check("hull zero wrecks immediately without moving the lap",
        !player.alive && player.energy == 0 && player.z == 5000f && player.lap == before &&
            player.respawnT > 1.5f && race.drainSfx().any { it.slot == RaceSfx.EXPLODE })
    var steps = 0
    while (steps < 130 && !player.alive) {
        race.update(0.016f, PlayerInput(), 0L)
        steps++
    }
    check("the ship respawns at the start of the same lap",
        player.alive && player.energy == Arms.MAX_HULL && player.shield == Arms.MAX_SHIELD &&
            player.z == 0f && player.lap == 1 && player.invulnT > 0f)
    race.hitRacer(player, 40f, null)
    check("respawn invulnerability blocks the next hit", player.energy == Arms.MAX_HULL && player.alive)

    val near = ship(true)
    val hold = Race(track, listOf(near), 0)
    hold.countdown = 0f
    near.z = track.trackLength - 50f
    near.lap = 0
    near.speed = 9000f
    near.energy = 5
    hold.hitRacer(near, 30f, null)
    val gas = PlayerInput(); gas.gas = true
    hold.update(0.05f, gas, 0L)
    check("a wreck on the line does not advance the lap", near.lap == 0 && !near.alive)
}
