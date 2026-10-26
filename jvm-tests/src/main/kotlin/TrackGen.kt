package com.denoya.xenorush

import kotlin.math.abs
import kotlin.random.Random

class Point {
    var worldX: Float = 0f; var worldY: Float = 0f; var worldZ: Float = 0f
    var cameraX: Float = 0f; var cameraY: Float = 0f; var cameraZ: Float = 0f
    var screenX: Float = 0f; var screenY: Float = 0f; var screenW: Float = 0f; var screenScale: Float = 0f
}

enum class PowerUpType { SHIELD, REFLECT, SPEED, REPAIR, AMMO, MISSILE, MINES }

class TrackSprite(val type: PowerUpType, val x: Float)

class Segment(val index: Int) {
    val p1 = Point()
    val p2 = Point()
    var curve = 0f
    var clip = 0f
    val sprites = ArrayList<TrackSprite>()
    var light = false
    /** Right-shoulder recharge strip on the run past the start line. */
    var pit = false
}

class Track(val def: TrackDef) {
    val segments = ArrayList<Segment>()
    var trackLength: Int = 0

    fun segAt(pos: Float): Segment {
        val idx = ((pos / C.SEG_LEN).toInt() % segments.size + segments.size) % segments.size
        return segments[idx]
    }

    val size: Int get() = segments.size
}

object TrackGen {

    private fun addSegment(track: Track, curve: Float, y1: Float, y2: Float) {
        val n = track.segments.size
        track.segments.add(Segment(n).apply {
            p1.worldY = y1
            p1.worldZ = n * C.SEG_LEN
            p2.worldY = y2
            p2.worldZ = (n + 1) * C.SEG_LEN
            this.curve = curve
            this.light = (n / C.RUMBLE) % 2 == 0
        })
    }

    private fun lastY(track: Track): Float =
        if (track.segments.isEmpty()) 0f else track.segments.last().p2.worldY

    private fun addRoad(track: Track, enter: Int, hold: Int, leave: Int, curve: Float, dY: Float) {
        val startY = lastY(track)
        val endY = startY + dY
        val total = enter + hold + leave
        for (i in 0 until enter) addSegment(track, easeIn(0f, curve, i.toFloat() / enter), easeInOut(startY, endY, i.toFloat() / total), easeInOut(startY, endY, (i + 1).toFloat() / total))
        for (i in 0 until hold) addSegment(track, curve, easeInOut(startY, endY, (enter + i).toFloat() / total), easeInOut(startY, endY, (enter + i + 1).toFloat() / total))
        for (i in 0 until leave) addSegment(track, easeInOut(curve, 0f, i.toFloat() / leave), easeInOut(startY, endY, (enter + hold + i).toFloat() / total), easeInOut(startY, endY, (enter + hold + i + 1).toFloat() / total))
    }

    fun build(def: TrackDef): Track {
        val track = Track(def)
        val rng = Random(def.seed)
        val difficulty = def.difficulty

        addRoad(track, 20, 40, 20, 0f, 0f) // start/finish straight

        val pieces = 8 + difficulty * 2
        for (i in 0 until pieces) {
            val roll = rng.nextInt(100)
            val len = rng.nextInt(2) + 1
            val hill = (rng.nextInt(3) - 1) * (12f + difficulty * 6f)
            when {
                roll < 25 -> addRoad(track, 20, 30 * len, 20, 0f, hill)
                roll < 55 -> { // curve
                    val dir = if (rng.nextBoolean()) 1 else -1
                    val sharp = (2 + rng.nextInt(3) + difficulty) * 0.6f
                    addRoad(track, 15 + difficulty * 5, 20 * len + difficulty * 5, 15 + difficulty * 5, dir * sharp, hill)
                }
                roll < 75 -> { // s-curve
                    val dir = if (rng.nextBoolean()) 1 else -1
                    val sharp = (2 + rng.nextInt(2) + difficulty) * 0.6f
                    addRoad(track, 12, 20, 12, dir * sharp, 0f)
                    addRoad(track, 12, 20, 12, -dir * sharp, 0f)
                }
                else -> { // rolling hills
                    addRoad(track, 15, 30 * len, 15, 0f, 30f)
                    addRoad(track, 15, 30 * len, 15, 0f, -30f)
                }
            }
        }
        // close the loop elevation back to 0, then a flat straight to the line
        val curY = lastY(track)
        addRoad(track, 25, 40, 25, 0f, -curY)
        addRoad(track, 15, 30, 15, 0f, 0f)

        track.trackLength = (track.segments.size * C.SEG_LEN).toInt()
        PitStrip.mark(track)

        // scatter power-ups deterministically
        val pRng = Random(def.seed + 99)
        var i = 40
        while (i < track.segments.size - 30) {
            val type = PowerUpType.entries[pRng.nextInt(PowerUpType.entries.size)]
            val lane = -0.75f + pRng.nextInt(4) * 0.5f
            track.segments[i].sprites.add(TrackSprite(type, lane))
            i += 18 + pRng.nextInt(22)
        }
        return track
    }
}

/**
 * F-Zero-style recharge strip: a narrow lane on the right shoulder of the
 * opening straight, the stretch just after the start/finish line.
 *
 * Every course begins with the same 80-segment straight, so the strip sits
 * on the same segments of every track. Recharge follows distance along the
 * lane, not time, and it never touches hull. [FULL_PASS_SHIELD] is the knob:
 * driving the whole strip (9,200 units) grants that many shield points, at
 * racing speed or any other speed. 100 matches [Arms.MAX_SHIELD], so an
 * empty shield is full at the end of the lane.
 */
object PitStrip {
    const val START_SEG = 10
    const val LENGTH_SEGS = 46
    /** Road edge is 1.0. This sits just outside the rumble, still inside [C.MAX_LATERAL]. */
    const val CENTER_X = 1.58f
    const val HALF_WIDTH = 0.36f
    /** Shield points for covering the entire strip. Does not heal hull. */
    const val FULL_PASS_SHIELD = 100
    const val AI_SEEK_BELOW = 40
    const val AI_SEEK_CHANCE = 0.5f
    /** Segments before the strip where a seeking AI slides to the right lane. */
    const val APPROACH_SEGS = 50
    const val SHOULDER = 0.22f

    fun lengthUnits(): Float = LENGTH_SEGS * C.SEG_LEN
    fun zStart(): Float = START_SEG * C.SEG_LEN
    fun zEnd(): Float = (START_SEG + LENGTH_SEGS) * C.SEG_LEN

    /** World-units of [prevZ, newZ] that lie on the strip. Handles the lap wrap. */
    fun distanceOnStrip(prevZ: Float, newZ: Float, trackLength: Int): Float {
        val start = zStart()
        val end = zEnd()
        val len = trackLength.toFloat()
        return if (newZ >= prevZ) {
            spanOverlap(prevZ, newZ, start, end)
        } else {
            spanOverlap(prevZ, len, start, end) + spanOverlap(0f, newZ, start, end)
        }
    }

    fun pointsFor(distance: Float): Float =
        if (lengthUnits() <= 0f) 0f else distance / lengthUnits() * FULL_PASS_SHIELD

    private fun spanOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val lo = maxOf(a0, b0)
        val hi = minOf(a1, b1)
        return if (hi > lo) hi - lo else 0f
    }

    fun mark(track: Track) {
        val end = (START_SEG + LENGTH_SEGS).coerceAtMost(track.segments.size)
        for (i in START_SEG until end) {
            val seg = track.segments[i]
            if (seg.curve != 0f) break
            seg.pit = true
        }
    }

    fun indexAt(z: Float, size: Int): Int {
        val n = size.coerceAtLeast(1)
        var i = (z / C.SEG_LEN).toInt() % n
        if (i < 0) i += n
        return i
    }

    fun onStrip(track: Track, x: Float, z: Float): Boolean {
        if (track.segments.isEmpty()) return false
        val seg = track.segments[indexAt(z, track.size)]
        return seg.pit && abs(x - CENTER_X) <= HALF_WIDTH
    }

    /** The shoulder under the strip is paved, so entering it is not a dirt slowdown. */
    fun paved(track: Track, x: Float, z: Float): Boolean {
        if (track.segments.isEmpty() || x <= 1f) return false
        val seg = track.segments[indexAt(z, track.size)]
        return seg.pit && x <= CENTER_X + HALF_WIDTH + SHOULDER
    }

    /** True on the strip and on the straight run into it. */
    fun approaching(track: Track, z: Float): Boolean {
        val n = track.size
        if (n <= 0) return false
        val idx = indexAt(z, n)
        if (track.segments[idx].pit) return true
        val dist = (START_SEG - idx + n) % n
        return dist in 1..APPROACH_SEGS
    }

    fun wantsPit(shield: Int, roll: Float): Boolean =
        shield < AI_SEEK_BELOW && roll < AI_SEEK_CHANCE
}
