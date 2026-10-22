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
    var energy: Int = maxEnergy()
    var alive = true
    var finished = false
    var finishTimeMs = -1L
    var shieldT = 0f
    var reflectT = 0f
    var boostT = 0f
    var ammo = 0
    var heldPowerUp: PowerUpType? = null
    var fireCooldown = 0f
    var lastLapStart = 0L
    val lapTimes = ArrayList<Long>()

    fun maxEnergy(): Int = (vehicle.energy * (1f + (vs.g - 1) * 0.05f)).toInt()
    fun maxSpeed(): Float = vehicle.topSpeed * (1f + (vs.e - 1) * 0.035f)
    fun accel(): Float = vehicle.accel * (1f + (vs.a - 1) * 0.035f)
    fun grip(): Float = vehicle.grip * (1f + (vs.g - 1) * 0.05f)
    fun weaponDamage(): Float {
        val base = when (vehicle.weapon) {
            WeaponType.PLASMA -> 22f
            WeaponType.HOMING -> 30f
            WeaponType.MINE -> 34f
            WeaponType.LASER -> 18f
        }
        return base * (1f + (vs.w - 1) * 0.08f)
    }

    fun startAmmo(): Int = vehicle.ammo + (vs.w - 1)
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
    val type: String,          // "proj", "mine", "laser", "laserback"
    val from: String,          // uid of the racer that owns the shot
    val weapon: Int,           // WeaponType ordinal
    val z: Float, val x: Float, val speed: Float,
    val damage: Float,
    val target: String = "",   // homing target / laser victim uid
    val reflected: Boolean = false
)

class Race(val track: Track, val racers: List<Racer>, val raceStartTime: Long, gridOrder: List<Racer>? = null) {
    /** Multiplayer hook: called when the LOCAL player creates something other players must see. */
    var onLocalEvent: ((NetEvent) -> Unit)? = null
    val multiplayer: Boolean get() = racers.any { it.isRemote }
    val player: Racer = racers.first { it.isPlayer }
    val projectiles = ArrayList<Projectile>()
    val mines = ArrayList<MineObj>()
    val collected = HashSet<Int>() // segment indices already picked up this race
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
            r.ammo = r.startAmmo()
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
        for (s in seg.sprites) {
            val key = seg.index * 31 + s.type.ordinal
            if (!collected.contains(key) && abs(player.x - s.x) < 0.35f && player.speed > 0f) {
                collected.add(key)
                applyPickup(player, s.type)
            }
        }

        // projectiles
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age > 6f) { p.alive = false; it.remove(); continue }
            if (p.type == WeaponType.HOMING && p.target != null && p.target!!.alive) {
                p.x = lerp(p.x, p.target!!.x, clamp(2.5f * dt, 0f, 1f))
            }
            p.z += p.speed * dt
            // keep positions normalized to [0, trackLength) so modular distance checks
            // stay valid for shots crossing the start/finish line (either direction)
            val lenF = track.trackLength.toFloat()
            if (p.z >= lenF) p.z -= lenF
            if (p.z < 0f) p.z += lenF
            for (r in racers) {
                if (!r.alive || r === p.owner) continue
                // modular distance so combat works across the start/finish line
                if (abs(wrapDist(p.z - r.z, track.trackLength)) < 260f && abs(p.x - r.x) < 0.38f) {
                    // remote racers are damaged on their own device; here the shot just disappears
                    if (r.isRemote) { p.alive = false; break }
                    val outcome = hitRacer(r, p.damage, p)
                    if (outcome == HitOutcome.REFLECTED && r.isPlayer) emit(p.toEvent("proj", r.uid))
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
        if (r.shieldT > 0f) r.shieldT -= dt
        if (r.reflectT > 0f) r.reflectT -= dt
        if (r.fireCooldown > 0f) r.fireCooldown -= dt

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

        // AI fires occasionally
        if (!r.isPlayer && r.fireCooldown <= 0f && r.ammo > 0) {
            val d = player.z - r.z
            val wrapped = if (abs(d) > track.trackLength / 2f) d - Math.signum(d) * track.trackLength else d
            if (abs(wrapped) < 4500f && rng.nextInt(100) < 45) fireWeapon(r)
        }
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
        return clamp(steer, -1f, 1f)
    }

    fun applyPickup(r: Racer, t: PowerUpType) {
        when (t) {
            PowerUpType.SHIELD -> { r.shieldT = 6f; events.add("SHIELD UP") }
            PowerUpType.REFLECT -> { r.reflectT = 8f; events.add("REFLECT ON") }
            PowerUpType.SPEED -> { r.boostT = 3f; r.speed += 1500f; events.add("BOOST") }
            PowerUpType.REPAIR -> { r.energy = (r.energy + 35).coerceAtMost(r.maxEnergy()); events.add("+35 ENERGY") }
            PowerUpType.AMMO -> { r.ammo += 3; events.add("+3 AMMO") }
        }
    }

    private fun emit(e: NetEvent) { onLocalEvent?.invoke(e) }

    private fun Projectile.toEvent(type: String, from: String) = NetEvent(type, from, this.type.ordinal,
        z, x, speed, damage, target?.uid ?: "", reflected)

    private fun wrapZ(z: Float): Float = z.mod(track.trackLength.toFloat())

    fun fireWeapon(r: Racer) {
        if (r.ammo <= 0 || !r.alive || r.finished) return
        r.ammo--
        r.fireCooldown = 1.6f
        when (r.vehicle.weapon) {
            WeaponType.PLASMA -> {
                val p = Projectile(WeaponType.PLASMA, wrapZ(r.z + 300f), r.x, r.speed + 9500f, r, r.weaponDamage())
                projectiles.add(p)
                if (r.isPlayer) emit(p.toEvent("proj", r.uid))
            }
            WeaponType.HOMING -> {
                val target = racers.filter { it !== r && it.alive }.minByOrNull {
                    abs(wrapDist(it.z - r.z, track.trackLength))
                }
                val p = Projectile(WeaponType.HOMING, wrapZ(r.z + 300f), r.x, r.speed + 6000f, r, r.weaponDamage(), target)
                projectiles.add(p)
                if (r.isPlayer) emit(p.toEvent("proj", r.uid))
            }
            WeaponType.MINE -> {
                val m = MineObj(wrapZ(r.z - 400f), r.x, r, r.weaponDamage())
                mines.add(m)
                if (r.isPlayer) emit(NetEvent("mine", r.uid, WeaponType.MINE.ordinal, m.z, m.x, 0f, m.damage))
            }
            WeaponType.LASER -> {
                // instant beam: hit nearest racer within 5200 units ahead
                val hit = racers.filter {
                    it !== r && it.alive && abs(wrapDist(it.z - r.z, track.trackLength)) in 300f..5200f && abs(it.x - r.x) < 0.4f
                }.minByOrNull { abs(wrapDist(it.z - r.z, track.trackLength)) }
                if (hit != null && hit.isRemote) {
                    // the victim's device decides (shield / reflect / damage)
                    if (r.isPlayer) emit(NetEvent("laser", r.uid, WeaponType.LASER.ordinal, r.z, r.x, 0f, r.weaponDamage(), hit.uid))
                } else if (hit != null) {
                    if (hit.reflectT > 0f) {
                        // beam bounces back at the shooter
                        hitRacer(r, r.weaponDamage(), null)
                        if (r.isPlayer) events.add("BEAM REFLECTED")
                    } else {
                        hitRacer(hit, r.weaponDamage(), null)
                    }
                }
            }
        }
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
            "laser" -> if (e.target == player.uid && player.alive) {
                if (player.reflectT > 0f) {
                    events.add("BEAM REFLECTED")
                    emit(NetEvent("laserback", player.uid, e.weapon, player.z, player.x, 0f, e.damage, e.from))
                } else hitRacer(player, e.damage, null)
            }
            "laserback" -> if (e.target == player.uid && player.alive) {
                hitRacer(player, e.damage, null)
                events.add("BEAM REFLECTED")
            }
        }
    }

    private fun wrapDist(d: Float, len: Int): Float {
        val l = len.toFloat()
        return if (abs(d) > l / 2f) d - Math.signum(d) * l else d
    }

    fun hitRacer(r: Racer, dmg: Float, proj: Projectile?): HitOutcome {
        if (!r.alive) return HitOutcome.NONE
        if (r.reflectT > 0f && proj != null && !proj.reflected) {
            // reflect: projectile reverses toward its owner
            proj.reflected = true
            proj.owner = r
            proj.target = null
            proj.speed = -abs(proj.speed) * 1.6f
            if (r.isPlayer) events.add("REFLECTED")
            return HitOutcome.REFLECTED
        }
        if (r.shieldT > 0f) {
            if (r.isPlayer) events.add("SHIELD ABSORBED")
            return HitOutcome.ABSORBED
        }
        r.energy -= dmg.toInt()
        if (r.isPlayer) {
            shakeT = 0.3f
            events.add("HIT -${dmg.toInt()}")
        }
        if (r.energy <= 0) {
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
