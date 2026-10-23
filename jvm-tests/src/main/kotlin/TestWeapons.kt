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
        check("rocket hits for 40", v.energy == 60)
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
        check("LEADER missile hits leader for 75", ais[0].energy == 25)
    }

    // reflected guided missile goes back to the shooter
    run {
        val p = racer(true); val shooter = racer(false); val r = Race(track, listOf(p, shooter), 0); r.countdown = 0f
        shooter.z = 1000f; p.z = 4000f; shooter.x = 0.4f; p.x = -0.4f
        p.reflectT = 30f
        shooter.missiles.add(WeaponType.SEEKER); shooter.secondary = WeaponType.SEEKER
        r.fireSecondary(shooter)
        runFor(r, 8f, listOf(p, shooter))
        check("reflected SEEKER damages its shooter", shooter.energy == 25 && p.energy == 100)
    }

    // mines
    run {
        val p = racer(true); val chaser = racer(false); val r = Race(track, listOf(p, chaser), 0); r.countdown = 0f
        p.z = 5000f; chaser.z = 3000f; p.x = 0f; chaser.x = 0f
        p.secondary = WeaponType.MINE; val m0 = p.mines
        check("mine drops behind", r.fireSecondary(p) && p.mines == m0 - 1 && r.mines.first().z < p.z)
        var t = 0f; while (t < 2f) { r.update(1 / 60f, PlayerInput(), 0); p.speed = 0f; chaser.speed = 1500f; t += 1 / 60f }
        check("mine hits for 50", chaser.energy == 50)
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
        val ais = (0 until 7).map { Racer(Catalog.VEHICLES[it], VehicleState(true, 1, 1, 1, 1), false, 0.95f) }
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
        println("      AI weapons used: $used, AI alive after race: $alive/7, player hull ${p.energy} alive=${p.alive}")
        check("AI fires bullets", WeaponType.BULLET in used)
        check("AI fires rockets or missiles", WeaponType.ROCKET in used || used.any { it.guided })
        check("races are not a wipeout (>=3 AI survive)", alive >= 3)
    }
}
