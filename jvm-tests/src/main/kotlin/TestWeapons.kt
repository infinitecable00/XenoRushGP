import com.denoya.xenorush.*

fun runWeaponTests(check: (String, Boolean) -> Unit) {
    val track = TrackGen.build(Catalog.TRACKS[2])
    fun racer(player: Boolean, w: Int = 1) = Racer(Catalog.VEHICLES[if (player) 0 else 6], VehicleState(true, 1, 1, 1, w), player, 1f)
    fun disarm(rs: List<Racer>) = rs.forEach { it.bullets = 0; it.rockets = 0; it.mines = 0; it.missiles.clear() }
    fun runFor(r: Race, secs: Float, freeze: List<Racer>) { var t = 0f; while (t < secs) { r.update(1 / 60f, PlayerInput(), 0); freeze.forEach { it.speed = 0f }; t += 1 / 60f } }

    // stats
    val a = racer(true)
    check("hull 100", a.energy == 100 && Arms.MAX_HULL == 100)
    check("shield max 100, starts 0", Arms.MAX_SHIELD == 100 && a.shield == 0)
    check("bullets start/max 100", a.bullets == 100 && Arms.MAX_BULLETS == 100)
    check("rockets max 4", a.rockets == 4 && Arms.MAX_ROCKETS == 4)
    check("missiles max 2", Arms.MAX_MISSILES == 2)
    check("bullet dmg 5..15 by weapon level", (1..10).map { Arms.bulletDamage(it) }.let { it.first() == 5f && it.last() == 15f && it.zipWithNext().all { (x, y) -> y > x } })
    check("hull is 100 on every vehicle", Catalog.VEHICLES.all { Racer(it, VehicleState(true, 10, 10, 10, 10), true, 1f).maxEnergy() == 100 })

    // pickups / caps
    run {
        val p = racer(true); val r = Race(track, listOf(p, racer(false)), 0)
        repeat(5) { r.applyPickup(p, PowerUpType.SHIELD) }
        check("shield pickups cap at 100", p.shield == 100)
        p.bullets = 90; r.applyPickup(p, PowerUpType.AMMO)
        check("ammo pickup caps bullets at 100", p.bullets == 100)
        p.rockets = 4; r.applyPickup(p, PowerUpType.AMMO)
        check("rockets cap at 4", p.rockets == 4)
        repeat(5) { r.applyPickup(p, PowerUpType.MISSILE) }
        check("missiles cap at 2", p.missiles.size == 2 && p.missiles.all { it.guided })
        repeat(5) { r.applyPickup(p, PowerUpType.MINES) }
        check("mines cap at 3", p.mines == 3)
        p.energy = 90; r.applyPickup(p, PowerUpType.REPAIR)
        check("repair caps hull at 100", p.energy == 100)
    }

    // damage values through shields
    run {
        val p = racer(true); val v = racer(false); val r = Race(track, listOf(p, v), 0)
        v.shield = 30
        r.hitRacer(v, 40f, null)
        check("rocket 40: shield absorbs 30, hull takes 10", v.shield == 0 && v.energy == 90)
        v.shield = 100; r.hitRacer(v, 75f, null)
        check("missile 75 fully absorbed by 100 shield", v.shield == 25 && v.energy == 90)
        v.shield = 0; v.energy = 75; r.hitRacer(v, 75f, null)
        check("missile 75 destroys a 75-hull ship", !v.alive && v.energy == 0)
    }

    // bullets actually hit, with level-scaled damage
    for (lvl in listOf(1, 10)) {
        val p = racer(true, lvl); val v = racer(false); val r = Race(track, listOf(p, v), 0); r.countdown = 0f
        p.z = 2000f; v.z = 3200f; p.x = 0f; v.x = 0f
        p.gunCooldown = 0f
        check("gun fires (lvl $lvl)", r.fireGun(p))
        check("gun uses one bullet", p.bullets == 99)
        runFor(r, 0.5f, listOf(p, v))
        check("bullet hits for ${Arms.bulletDamage(lvl).toInt()} (lvl $lvl)", v.energy == 100 - Arms.bulletDamage(lvl).toInt())
    }
    run {
        val p = racer(true); val r = Race(track, listOf(p, racer(false)), 0); r.countdown = 0f
        p.gunCooldown = 0f; r.fireGun(p)
        check("gun is rate limited", !r.fireGun(p))
        p.bullets = 0; p.gunCooldown = 0f
        check("empty gun does not fire", !r.fireGun(p))
    }

    // rockets: unguided, 40 dmg
    run {
        val p = racer(true); val v = racer(false); val r = Race(track, listOf(p, v), 0); r.countdown = 0f
        p.z = 2000f; v.z = 4000f; p.x = 0f; v.x = 0f
        p.secondary = WeaponType.ROCKET
        check("rocket fires", r.fireSecondary(p) && p.rockets == 3)
        runFor(r, 1f, listOf(p, v))
        check("rocket lvl1 hits for 20", v.energy == 80)
        // unguided: a target off to the side is missed
        val v2 = racer(false); val r2 = Race(track, listOf(p, v2), 0); r2.countdown = 0f
        p.z = 2000f; v2.z = 4000f; p.x = -0.6f; v2.x = 0.6f; p.fireCooldown = 0f
        r2.fireSecondary(p); runFor(r2, 1.5f, listOf(p, v2))
        check("rocket is unguided (misses other lane)", v2.energy == 100)
    }

    // guided missile targeting
    run {
        val p = racer(true)
        val ais = (0 until 5).map { racer(false) }
        val r = Race(track, ais + p, 0); r.countdown = 0f
        disarm(ais)
        // positions: ai0 leads, then ai1, ai2, PLAYER, ai3, ai4
        val zs = listOf(9000f, 7000f, 5000f, 1000f, 500f)
        ais.forEachIndexed { i, ai -> ai.z = zs[i]; ai.x = 0.5f }
        p.z = 3000f; p.x = -0.5f
        check("SEEKER locks nearest racer ahead", r.missileTarget(p, WeaponType.SEEKER) === ais[2])
        ais[2].z = 2900f // still ahead in race? no: now behind the player
        check("HUNTER locks the racer one position ahead", r.missileTarget(p, WeaponType.HUNTER) === ais[1])
        check("LEADER locks the race leader", r.missileTarget(p, WeaponType.LEADER) === ais[0])
        // when the player leads, LEADER goes for 2nd
        p.z = 9500f
        check("LEADER from 1st targets 2nd place", r.missileTarget(p, WeaponType.LEADER) === ais[0])
        p.z = 3000f; ais[2].z = 5000f

        // missile flies past others and hits the leader across lanes for 75
        p.missiles.add(WeaponType.LEADER); p.secondary = WeaponType.LEADER
        check("LEADER missile fires", r.fireSecondary(p))
        check("missile removed from inventory", p.missiles.isEmpty())
        runFor(r, 6f, ais + p)
        check("LEADER missile skips racers in between", ais[1].energy == 100 && ais[2].energy == 100)
        check("LEADER missile lvl1 hits leader for 55", ais[0].energy == 45)
    }

    // reflected guided missile goes back to the shooter
    run {
        val p = racer(true); val shooter = racer(false); val r = Race(track, listOf(p, shooter), 0); r.countdown = 0f
        shooter.z = 1000f; p.z = 4000f; shooter.x = 0.4f; p.x = -0.4f
        p.reflectT = 30f
        shooter.missiles.add(WeaponType.SEEKER); shooter.secondary = WeaponType.SEEKER
        r.fireSecondary(shooter)
        runFor(r, 8f, listOf(p, shooter))
        check("reflected SEEKER damages its shooter", shooter.energy == 45 && p.energy == 100)
    }

    // mines
    run {
        val p = racer(true); val chaser = racer(false); val r = Race(track, listOf(p, chaser), 0); r.countdown = 0f
        p.z = 5000f; chaser.z = 3000f; p.x = 0f; chaser.x = 0f
        p.secondary = WeaponType.MINE; val m0 = p.mines
        check("mine drops behind", r.fireSecondary(p) && p.mines == m0 - 1 && r.mines.first().z < p.z)
        var t = 0f; while (t < 2f) { r.update(1 / 60f, PlayerInput(), 0); p.speed = 0f; chaser.speed = 1500f; t += 1 / 60f }
        check("mine lvl1 hits for 40", chaser.energy == 60)
    }

    // v1.3: rockets / missiles / mines upgrade 1-10
    check("rocket dmg 20 -> 40", Arms.rocketDamage(1) == 20f && Arms.rocketDamage(10) == 40f)
    check("missile dmg 55 -> 75", Arms.missileDamage(1) == 55f && Arms.missileDamage(10) == 75f)
    check("mine dmg 40 -> 60", Arms.mineDamage(1) == 40f && Arms.mineDamage(10) == 60f)
    check("special dmg rises every level", (1..9).all { Arms.rocketDamage(it + 1) > Arms.rocketDamage(it) && Arms.missileDamage(it + 1) > Arms.missileDamage(it) && Arms.mineDamage(it + 1) > Arms.mineDamage(it) })
    run {
        // level-10 weapons actually deal level-10 damage in flight
        val p = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1, 10, 10, 10), true, 1f); val v = racer(false)
        val r = Race(track, listOf(p, v), 0); r.countdown = 0f
        p.z = 2000f; v.z = 4000f; p.x = 0f; v.x = 0f; p.secondary = WeaponType.ROCKET
        r.fireSecondary(p); runFor(r, 1f, listOf(p, v))
        check("rocket lvl10 hits for 40", v.energy == 60)
        v.energy = 100; v.z = 6000f; p.fireCooldown = 0f; p.missiles.add(WeaponType.SEEKER); p.secondary = WeaponType.SEEKER
        r.fireSecondary(p); runFor(r, 4f, listOf(p, v))
        check("missile lvl10 hits for 75", v.energy == 25)
        val c = racer(false); val r2 = Race(track, listOf(p, c), 0); r2.countdown = 0f
        p.z = 5000f; c.z = 3000f; c.x = 0f; p.x = 0f; p.fireCooldown = 0f; p.secondary = WeaponType.MINE
        r2.fireSecondary(p); var t = 0f; while (t < 2f) { r2.update(1 / 60f, PlayerInput(), 0); p.speed = 0f; c.speed = 1500f; t += 1 / 60f }
        check("mine lvl10 hits for 60", c.energy == 40)
    }
    check("credits upgrade only to level 5", (1..4).all { Catalog.creditUpgradeAllowed(it) } && (5..10).none { Catalog.creditUpgradeAllowed(it) })
    check("7 upgrade stats incl. rockets/missiles/mines", Catalog.STATS == listOf("e", "a", "g", "w", "r", "m", "n"))
    check("paid upgrade ids for new stats parse", listOf("r", "m", "n").all { Pricing.parse("u3_$it") == Pricing.Item.Upgrade(3, it) })
    check("paid upgrade names", Pricing.displayName(Pricing.Item.Upgrade(0, "m")).endsWith("Missiles +1"))
    run {
        val sd = SaveData(); val v = sd.vehicleState(2); v.r = 7; v.m = 4; v.n = 10
        val back = SaveCodec.fromJson(SaveCodec.toJson(sd)).vehicleState(2)
        check("save keeps rocket/missile/mine levels", back.r == 7 && back.m == 4 && back.n == 10)
        val old = SaveCodec.fromJson("""{"vehicles":{"1":{"owned":true,"e":3,"a":2,"g":1,"w":4}}}""").vehicleState(1)
        check("old saves load new stats at level 1", old.e == 3 && old.w == 4 && old.r == 1 && old.m == 1 && old.n == 1)
        val other = SaveData(); other.vehicleState(2).m = 9
        val mg = SaveMerge.merge(sd, other).vehicleState(2)
        check("cloud merge takes max of new stats", mg.r == 7 && mg.m == 9 && mg.n == 10)
    }

    // v1.3: random Shield / Reflect drops on every course
    run {
        val counts = Catalog.TRACKS.map { td ->
            val t = TrackGen.build(td); val r = Race(t, listOf(racer(true), racer(false)), 0, null, kotlin.random.Random(td.id))
            val all = r.randomDrops.values.flatten()
            all.count { it.type == PowerUpType.SHIELD } to all.count { it.type == PowerUpType.REFLECT }
        }
        check("every course has random shield drops", counts.all { it.first >= 3 })
        check("every course has random reflect drops", counts.all { it.second >= 3 })
        val t = TrackGen.build(Catalog.TRACKS[4]); val before = t.segments.sumOf { it.sprites.size }
        val ra = Race(t, listOf(racer(true)), 0, null, kotlin.random.Random(1))
        val rb = Race(t, listOf(racer(true)), 0, null, kotlin.random.Random(2))
        check("drops are random per race", ra.randomDrops.keys != rb.randomDrops.keys)
        check("drops don't change the shared track", t.segments.sumOf { it.sprites.size } == before)
        // a drop can be picked up, and comes back next lap
        val p = racer(true); val r = Race(t, listOf(p), 0, null, kotlin.random.Random(3)); r.countdown = 0f
        val (idx, sp) = r.randomDrops.entries.first { it.value.first().type == PowerUpType.SHIELD }.let { it.key to it.value.first() }
        p.z = idx * C.SEG_LEN + 1f; p.x = sp.x; p.speed = 100f
        r.update(1 / 60f, PlayerInput(), 0)
        check("random shield drop collected", p.shield == Arms.SHIELD_PICKUP && r.isCollected(idx, sp.type))
        p.lap += 1
        check("power-ups return next lap", !r.isCollected(idx, sp.type))
    }

    // weapon cycling
    run {
        val p = racer(true)
        p.missiles.add(WeaponType.HUNTER)
        val seen = (0 until 3).map { p.cycleSecondary(); p.secondary }
        check("WPN cycles rocket -> missile -> mine", seen == listOf(WeaponType.HUNTER, WeaponType.MINE, WeaponType.ROCKET))
    }

    // AI uses the new arsenal during a full race
    run {
        val p = racer(true)
        val ais = (0 until Catalog.RACERS_PER_RACE - 1).map { Racer(Catalog.VEHICLES[it], VehicleState(true, 1, 1, 1, 1), false, 0.95f) }
        val r = Race(TrackGen.build(Catalog.TRACKS[0]), ais + p, 0); r.countdown = 0f
        val used = HashSet<WeaponType>()
        val inp = PlayerInput().apply { gas = true }
        var t = 0f
        while (t < 120f && !r.finished) {
            r.update(1 / 60f, inp, (t * 1000).toLong())
            r.projectiles.forEach { used.add(it.type) }
            if (r.mines.isNotEmpty()) used.add(WeaponType.MINE)
            t += 1 / 60f
        }
        val alive = ais.count { it.alive }
        println("      AI weapons used: $used, AI alive after race: $alive/${Catalog.RACERS_PER_RACE - 1}, player hull ${p.energy} alive=${p.alive}")
        check("AI fires bullets", WeaponType.BULLET in used)
        check("AI fires rockets or missiles", WeaponType.ROCKET in used || used.any { it.guided })
        check("races are not a wipeout (>=3 AI survive)", alive >= 3)
    }
}
