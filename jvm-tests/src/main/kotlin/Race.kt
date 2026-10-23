package com.denoya.xenorush

import kotlin.math.abs
import kotlin.random.Random

class Racer(
    val vehicle: VehicleDef,
    val vs: VehicleState,
    val isPlayer: Boolean,
    val aiSkill: Float,
    val isRemote: Boolean = false,   // multiplayer: another human, driven by network state (not simulated)
    val uid: String = "",
    val displayName: String = ""
) {
    var z = 0f
    var x = 0f
    var speed = 0f
    var lap = 0
    val totalDist: Float get() = lap * 1_000_000f + z
    var energy: Int = Arms.MAX_HULL      // hull health, 0 = destroyed
    var shield: Int = 0                  // shield points, absorb damage before the hull (max 100)
    var alive = true
    var finished = false
    var finishTimeMs = -1L
    var reflectT = 0f
    var boostT = 0f
    var bullets = Arms.MAX_BULLETS
    var rockets = Arms.MAX_ROCKETS
    var mines = 2
    val missiles = ArrayList<WeaponType>()  // guided missiles held (max 2)
    var secondary = WeaponType.ROCKET       // selected special weapon
    var heldPowerUp: PowerUpType? = null
    var gunCooldown = 0f
    var fireCooldown = 0f                   // special weapon cooldown
    var aiBurst = 0
    var lastLapStart = 0L
    val lapTimes = ArrayList<Long>()

    fun maxEnergy(): Int = Arms.MAX_HULL
    fun maxSpeed(): Float = vehicle.topSpeed * (1f + (vs.e - 1) * 0.035f)
    fun accel(): Float = vehicle.accel * (1f + (vs.a - 1) * 0.035f)
    fun grip(): Float = vehicle.grip * (1f + (vs.g - 1) * 0.05f)
    fun bulletDamage(): Float = Arms.bulletDamage(vs.w)
    fun rocketDamage(): Float = Arms.rocketDamage(vs.r)
    fun missileDamage(): Float = Arms.missileDamage(vs.m)
    fun mineDamage(): Float = Arms.mineDamage(vs.n)

    fun count(w: WeaponType): Int = when (w) {
        WeaponType.BULLET -> bullets
        WeaponType.ROCKET -> rockets
        WeaponType.MINE -> mines
        else -> missiles.count { it == w }
    }

    /** Special weapons the player can cycle through (rocket and mine always listed, missiles when held). */
    fun secondaryChoices(): List<WeaponType> =
        listOf(WeaponType.ROCKET) + listOf(WeaponType.SEEKER, WeaponType.HUNTER, WeaponType.LEADER).filter { it in missiles } + WeaponType.MINE

    fun cycleSecondary() {
        val ch = secondaryChoices()
        val i = ch.indexOf(secondary)
        secondary = ch[(i + 1).mod(ch.size)]
    }
}

class Projectile(
    val type: WeaponType,
    var z: Float,
    var x: Float,
    var speed: Float,
    var owner: Racer,
    val damage: Float,
    var target: Racer? = null,
    var alive: Boolean = true,
    var reflected: Boolean = false,
    var age: Float = 0f
)

class MineObj(var z: Float, var x: Float, val owner: Racer, val damage: Float, var alive: Boolean = true)

class PlayerInput {
    var left = false
    var right = false
    var gas = false
    var brake = false
}

enum class HitOutcome { DAMAGED, ABSORBED, REFLECTED, NONE }

/** Combat event sent to other players in multiplayer. Each client only damages its own racer. */
data class NetEvent(
    val type: String,          // "proj" or "mine"
    val from: String,          // uid of the racer that owns the shot
    val weapon: Int,           // WeaponType ordinal
    val z: Float, val x: Float, val speed: Float,
    val damage: Float,
    val target: String = "",   // guided missile target uid
    val reflected: Boolean = false
)

class Race(val track: Track, val racers: List<Racer>, val raceStartTime: Long, gridOrder: List<Racer>? = null,
           dropRng: Random = Random.Default) {
    /** Multiplayer hook: called when the LOCAL player creates something other players must see. */
    var onLocalEvent: ((NetEvent) -> Unit)? = null
    val multiplayer: Boolean get() = racers.any { it.isRemote }
    val player: Racer = racers.first { it.isPlayer }
    val projectiles = ArrayList<Projectile>()
    val mines = ArrayList<MineObj>()
    /** Random Shield/Reflect drops for THIS race only (the shared track data is never changed). */
    val randomDrops = HashMap<Int, ArrayList<TrackSprite>>()   // segment index -> drops
    /** Fixed track power-ups plus this race's random drops. */
    fun spritesAt(seg: Segment): List<TrackSprite> = randomDrops[seg.index]?.let { seg.sprites + it } ?: seg.sprites
    val collected = HashSet<Long>() // (lap, segment, type) already picked up - power-ups come back every lap
    var raceTimeMs = 0L
    var countdown = 3.99f
    var finished = false
    var playerDestroyed = false
    var playerFinished = false
    val events = ArrayList<String>() // toast-like messages
    var shakeT = 0f
    val rng = Random(track.def.seed + 555)

    init {
        // starting grid: AI staggered ahead, player at the back
        val grid = gridOrder ?: racers.sortedBy { it.isPlayer } // player last
        grid.forEachIndexed { i, r ->
            r.z = (grid.size - 1 - i) * C.SEG_LEN * 1.8f
            r.x = if (i % 2 == 0) -0.4f else 0.4f
            r.speed = 0f
        }
        placeRandomDrops(dropRng)
    }

    companion object { const val DROP_SECTORS = 8 }

    private fun pickupKey(segIndex: Int, t: PowerUpType): Long = player.lap.toLong() * 10_000_000L + segIndex * 31L + t.ordinal
    fun isCollected(segIndex: Int, t: PowerUpType): Boolean = collected.contains(pickupKey(segIndex, t))

    /**
     * Random Shield and Reflect drops, different every race: the lap is split into
     * [DROP_SECTORS] sectors and each sector gets one drop (half shields, half reflects,
     * shuffled) at a random spot and lane. Like all power-ups they come back every lap.
     */
    private fun placeRandomDrops(rnd: Random) {
        val n = track.segments.size
        val types = (List(DROP_SECTORS / 2) { PowerUpType.SHIELD } + List(DROP_SECTORS - DROP_SECTORS / 2) { PowerUpType.REFLECT }).shuffled(rnd)
        val first = 40; val usable = n - 30 - first
        if (usable < DROP_SECTORS) return
        val sector = usable / DROP_SECTORS
        for (k in 0 until DROP_SECTORS) {
            var idx = first + k * sector + rnd.nextInt(sector)
            // keep off segments that already hold a power-up
            var tries = 0
            while (track.segments[idx].sprites.isNotEmpty() && tries++ < sector) idx = first + k * sector + rnd.nextInt(sector)
            if (track.segments[idx].sprites.isNotEmpty()) continue
            randomDrops.getOrPut(idx) { ArrayList() }.add(TrackSprite(types[k], -0.75f + rnd.nextInt(4) * 0.5f))
        }
    }

    fun rank(r: Racer): Int = racers.count { it.totalDist > r.totalDist } + 1

    fun update(dt: Float, input: PlayerInput, now: Long) {
        if (countdown > 0f) {
            countdown -= dt
            // hold everyone at the line
            racers.forEach { it.speed = 0f }
            return
        }
        raceTimeMs = now - raceStartTime

        for (r in racers) if (!r.isRemote) updateRacer(r, dt, input, now)

        // pickups
        val pSeg = (player.z / C.SEG_LEN).toInt()
        val seg = track.segAt(player.z)
        for (s in spritesAt(seg)) {
            val key = pickupKey(seg.index, s.type)
            if (!collected.contains(key) && abs(player.x - s.x) < 0.35f && player.speed > 0f) {
                collected.add(key)
                applyPickup(player, s.type)
            }
        }

        // projectiles
        val lenF = track.trackLength.toFloat()
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            val life = if (p.type.guided) 12f else if (p.type == WeaponType.BULLET) 1.6f else 5f
            if (p.age > life) { p.alive = false; it.remove(); continue }
            val tgt = p.target
            if (p.type.guided && tgt != null && tgt.alive) {
                p.x = lerp(p.x, tgt.x, clamp(3.5f * dt, 0f, 1f))
                // guided missiles always out-run their target
                if (!p.reflected) p.speed = maxOf(p.speed, tgt.speed + 7000f)
            }
            p.z += p.speed * dt
            // keep positions normalized to [0, trackLength) so modular distance checks
            // stay valid for shots crossing the start/finish line (either direction)
            if (p.z >= lenF) p.z -= lenF
            if (p.z < 0f) p.z += lenF
            val hitLen = if (p.type == WeaponType.BULLET) 200f else 260f
            val hitW = if (p.type == WeaponType.BULLET) 0.3f else 0.38f
            for (r in racers) {
                if (!r.alive || r === p.owner) continue
                // guided missiles fly over everyone except their locked target
                if (p.type.guided && tgt != null && tgt.alive && r !== tgt) continue
                // modular distance so combat works across the start/finish line
                if (abs(wrapDist(p.z - r.z, track.trackLength)) < hitLen && abs(p.x - r.x) < hitW) {
                    // remote racers are damaged on their own device; here the shot just disappears
                    if (r.isRemote) { p.alive = false; break }
                    val outcome = hitRacer(r, p.damage, p)
                    if (outcome == HitOutcome.REFLECTED && r.isPlayer) emit(p.toEvent(r.uid))
                    if (outcome != HitOutcome.REFLECTED) p.alive = false
                    break
                }
            }
            if (!p.alive) it.remove()
        }

        // mines
        for (m in mines) {
            if (!m.alive) continue
            for (r in racers) {
                if (!r.alive || r === m.owner) continue
                if (abs(wrapDist(m.z - r.z, track.trackLength)) < 180f && abs(m.x - r.x) < 0.3f) {
                    if (!r.isRemote) hitRacer(r, m.damage, null)
                    m.alive = false
                    break
                }
            }
        }

        if (!finished) {
            if (player.lap >= C.LAPS && !player.finished) {
                player.finished = true
                player.finishTimeMs = raceTimeMs
                playerFinished = true
                finished = true
            }
            if (!player.alive) {
                playerDestroyed = true
                finished = true
            }
        }
    }

    private fun updateRacer(r: Racer, dt: Float, input: PlayerInput, now: Long) {
        if (!r.alive) return
        if (r.finished) {
            // cruise to a stop after finish
            r.speed = (r.speed - 4000f * dt).coerceAtLeast(0f)
            r.z += r.speed * dt
            return
        }

        val seg = track.segAt(r.z)

        // steering input
        var steer = 0f
        if (r.isPlayer) {
            if (input.left) steer -= 1f
            if (input.right) steer += 1f
        } else {
            steer = aiSteer(r, seg)
        }

        val speedRatio = r.speed / r.maxSpeed()
        val dx = dt * 2.4f * speedRatio
        r.x += steer * dx
        // centrifugal force pushes outward in curves (grip counters)
        r.x -= dx * speedRatio * seg.curve * C.CENTRIFUGAL / r.grip()

        // throttle
        val maxSpd = r.maxSpeed() * (if (r.boostT > 0f) 1.35f else 1f)
        if (r.isPlayer) {
            if (input.gas) r.speed += r.accel() * dt
            else if (input.brake) r.speed -= 9000f * dt
            else r.speed -= 1400f * dt
        } else {
            val target = maxSpd * r.aiSkill * (if (abs(seg.curve) > 2f) 0.86f else 1f)
            r.speed += r.accel() * dt * (if (r.speed < target) 1f else -0.4f)
        }
        if (r.boostT > 0f) r.boostT -= dt
        if (r.reflectT > 0f) r.reflectT -= dt
        if (r.fireCooldown > 0f) r.fireCooldown -= dt
        if (r.gunCooldown > 0f) r.gunCooldown -= dt

        // off-road
        if (abs(r.x) > 1f) {
            if (r.speed > 3600f) r.speed -= 8000f * dt
            if (r.isPlayer) shakeT = 0.25f
        }
        r.x = clamp(r.x, -C.MAX_LATERAL, C.MAX_LATERAL)
        r.speed = clamp(r.speed, 0f, maxSpd * 1.2f)

        // move + lap
        r.z += r.speed * dt
        if (r.z >= track.trackLength) {
            r.z -= track.trackLength
            r.lap++
            if (r.isPlayer) {
                val lapMs = now - r.lastLapStart
                if (lapMs > 5000) r.lapTimes.add(lapMs)
                r.lastLapStart = now
            }
        }

        if (!r.isPlayer) aiCombat(r)
    }

    /** AI: short gun bursts at whoever is just ahead, rockets/missiles now and then, mines when chased. */
    private fun aiCombat(r: Racer) {
        if (raceTimeMs < 8000L) return   // no shooting off the start line
        val len = track.trackLength
        val ahead = racers.filter { it !== r && it.alive }.map { it to wrapDist(it.z - r.z, len) }
            .filter { it.second in 200f..4500f }.minByOrNull { it.second }?.first
        if (ahead != null && abs(ahead.x - r.x) < 0.35f) {
            if (r.aiBurst > 0) {
                if (fireGun(r)) { r.aiBurst--; if (r.aiBurst == 0) r.fireCooldown = 2.5f + rng.nextFloat() * 2f }  // rest between bursts
            }
            else if (r.fireCooldown <= 0f && rng.nextInt(100) < 2) r.aiBurst = 3 + rng.nextInt(3)
        }
        if (r.fireCooldown > 0f) return
        if (rng.nextInt(1000) >= 2) return   // ~1 special every 8 s per AI, on average
        val behind = racers.any { it !== r && it.alive && wrapDist(r.z - it.z, len) in 200f..2500f && abs(it.x - r.x) < 0.4f }
        r.secondary = when {
            r.missiles.isNotEmpty() && rng.nextBoolean() -> r.missiles.first()
            behind && r.mines > 0 && rng.nextInt(3) == 0 -> WeaponType.MINE
            ahead != null && r.rockets > 0 -> WeaponType.ROCKET
            else -> return
        }
        fireSecondary(r)
    }

    private fun aiSteer(r: Racer, seg: Segment): Float {
        // counter centrifugal drift: physics pushes x by -curve, so steer +curve
        var steer = seg.curve * 0.14f * r.grip()
        // avoid nearby cars ahead
        for (o in racers) {
            if (o === r) continue
            val d = o.z - r.z
            val wrapped = if (abs(d) > track.trackLength / 2f) d - Math.signum(d) * track.trackLength else d
            if (wrapped in 40f..1500f && abs(o.x - r.x) < 0.42f) {
                steer += if (o.x > r.x) -0.9f else 0.9f
            }
        }
        // drift back toward the racing line when pushed wide (avoidance, combat)
        if (abs(r.x) > 0.7f) steer -= Math.signum(r.x) * (abs(r.x) - 0.7f) * 2.5f
        return clamp(steer, -1f, 1f)
    }

    fun applyPickup(r: Racer, t: PowerUpType) {
        when (t) {
            PowerUpType.SHIELD -> { r.shield = (r.shield + Arms.SHIELD_PICKUP).coerceAtMost(Arms.MAX_SHIELD); events.add("SHIELD ${r.shield}") }
            PowerUpType.REFLECT -> { r.reflectT = 8f; events.add("REFLECT ON") }
            PowerUpType.SPEED -> { r.boostT = 3f; r.speed += 1500f; events.add("BOOST") }
            PowerUpType.REPAIR -> { r.energy = (r.energy + Arms.REPAIR_PICKUP).coerceAtMost(Arms.MAX_HULL); events.add("+${Arms.REPAIR_PICKUP} HULL") }
            PowerUpType.AMMO -> {
                r.bullets = (r.bullets + 30).coerceAtMost(Arms.MAX_BULLETS)
                r.rockets = (r.rockets + 1).coerceAtMost(Arms.MAX_ROCKETS)
                events.add("+30 BULLETS +1 ROCKET")
            }
            PowerUpType.MISSILE -> {
                if (r.missiles.size < Arms.MAX_MISSILES) {
                    val m = listOf(WeaponType.SEEKER, WeaponType.HUNTER, WeaponType.LEADER)[rng.nextInt(3)]
                    r.missiles.add(m)
                    if (r.isPlayer) r.secondary = m
                    events.add(Arms.name(m).uppercase())
                } else events.add("MISSILES FULL")
            }
            PowerUpType.MINES -> { r.mines = (r.mines + 1).coerceAtMost(Arms.MAX_MINES); events.add("+1 MINE") }
        }
    }

    private fun emit(e: NetEvent) { onLocalEvent?.invoke(e) }

    private fun Projectile.toEvent(from: String) = NetEvent("proj", from, type.ordinal,
        z, x, speed, damage, target?.uid ?: "", reflected)

    private fun wrapZ(z: Float): Float = z.mod(track.trackLength.toFloat())

    /** Position in the race (1 = leader) using the same ordering as rank(). */
    private fun standings(): List<Racer> = racers.filter { it.alive }.sortedByDescending { it.totalDist }

    /** Picks the lock-on target for a guided missile. */
    fun missileTarget(r: Racer, type: WeaponType): Racer? {
        val others = racers.filter { it !== r && it.alive }
        if (others.isEmpty()) return null
        val nearestAhead = others.minByOrNull { (it.z - r.z).mod(track.trackLength.toFloat()) }
        val order = standings()
        val myPos = order.indexOf(r)
        return when (type) {
            WeaponType.SEEKER -> nearestAhead
            WeaponType.HUNTER -> if (myPos > 0) order[myPos - 1] else nearestAhead      // one position ahead
            WeaponType.LEADER -> if (myPos != 0) order.first() else order.getOrNull(1)  // the leader (or 2nd if you lead)
            else -> null
        }
    }

    /** Machine gun: one bullet per call, rate-limited. Returns true if a round was fired. */
    fun fireGun(r: Racer): Boolean {
        if (!r.alive || r.finished || r.bullets <= 0 || r.gunCooldown > 0f) return false
        r.bullets--
        r.gunCooldown = Arms.GUN_INTERVAL
        val p = Projectile(WeaponType.BULLET, wrapZ(r.z + 250f), r.x, r.speed + 14000f, r, r.bulletDamage())
        projectiles.add(p)
        if (r.isPlayer) emit(p.toEvent(r.uid))
        return true
    }

    /** Fires the racer's selected special weapon (rocket, guided missile or mine). */
    fun fireSecondary(r: Racer): Boolean {
        if (!r.alive || r.finished || r.fireCooldown > 0f) return false
        val w = r.secondary
        when (w) {
            WeaponType.ROCKET -> {
                if (r.rockets <= 0) return false
                r.rockets--
                val p = Projectile(WeaponType.ROCKET, wrapZ(r.z + 300f), r.x, r.speed + 8000f, r, r.rocketDamage())
                projectiles.add(p)
                if (r.isPlayer) emit(p.toEvent(r.uid))
            }
            WeaponType.SEEKER, WeaponType.HUNTER, WeaponType.LEADER -> {
                if (!r.missiles.remove(w)) return false
                val target = missileTarget(r, w)
                val p = Projectile(w, wrapZ(r.z + 300f), r.x, r.speed + 7000f, r, r.missileDamage(), target)
                projectiles.add(p)
                if (r.isPlayer) {
                    emit(p.toEvent(r.uid))
                    events.add("${Arms.short(w)} LOCKED: ${target?.let { if (it.isRemote) it.displayName else it.vehicle.name } ?: "none"}")
                }
                if (w !in r.missiles) r.secondary = r.missiles.firstOrNull() ?: WeaponType.ROCKET
            }
            WeaponType.MINE -> {
                if (r.mines <= 0) return false
                r.mines--
                val m = MineObj(wrapZ(r.z - 400f), r.x, r, r.mineDamage())
                mines.add(m)
                if (r.isPlayer) emit(NetEvent("mine", r.uid, WeaponType.MINE.ordinal, m.z, m.x, 0f, m.damage))
            }
            WeaponType.BULLET -> return fireGun(r)
        }
        r.fireCooldown = 0.8f
        return true
    }

    /** Apply a combat event received from another player. */
    fun applyRemoteEvent(e: NetEvent) {
        val owner = racers.firstOrNull { it.isRemote && it.uid == e.from } ?: return
        val type = WeaponType.entries.getOrNull(e.weapon) ?: return
        when (e.type) {
            "proj" -> {
                val target = racers.firstOrNull { it.uid == e.target && e.target.isNotEmpty() }
                projectiles.add(Projectile(type, wrapZ(e.z), e.x, e.speed, owner, e.damage, target, reflected = e.reflected))
            }
            "mine" -> mines.add(MineObj(wrapZ(e.z), e.x, owner, e.damage))
        }
    }

    private fun wrapDist(d: Float, len: Int): Float {
        val l = len.toFloat()
        return if (abs(d) > l / 2f) d - Math.signum(d) * l else d
    }

    fun hitRacer(r: Racer, dmg: Float, proj: Projectile?): HitOutcome {
        if (!r.alive) return HitOutcome.NONE
        if (r.reflectT > 0f && proj != null && !proj.reflected) {
            // reflect: projectile reverses toward its owner (guided missiles re-lock on the shooter)
            proj.reflected = true
            val shooter = proj.owner
            proj.owner = r
            proj.target = if (proj.type.guided) shooter else null
            proj.speed = -abs(proj.speed) * 1.6f
            if (r.isPlayer) events.add("REFLECTED")
            return HitOutcome.REFLECTED
        }
        var d = dmg.toInt().coerceAtLeast(1)
        if (r.shield > 0) {
            val absorbed = minOf(r.shield, d)
            r.shield -= absorbed
            d -= absorbed
            if (d == 0) {
                if (r.isPlayer) events.add("SHIELD -$absorbed")
                return HitOutcome.ABSORBED
            }
        }
        r.energy -= d
        if (r.isPlayer) {
            shakeT = 0.3f
            events.add("HIT -$d")
        }
        if (r.energy <= 0) {
            r.energy = 0
            r.alive = false
            r.speed = 0f
            if (r.isPlayer) events.add("DESTROYED")
        }
        return HitOutcome.DAMAGED
    }

    fun usePowerUp(r: Racer) {
        val t = r.heldPowerUp ?: return
        applyPickup(r, t)
        r.heldPowerUp = null
    }
}
