package com.denoya.xenorush

/**
 * Rear-view mirror camera (pure math, no Android).
 *
 * Looks backwards from just in front of the player's car. Results are in normalised mirror
 * space: x/w are in units of half the mirror width, y in units of half the mirror height,
 * with (0,0) at the mirror's horizon centre. Like a real mirror, something behind you on
 * your left shows up on the left side.
 */
class RearView(val track: Track, val player: Racer) {
    companion object {
        const val SEGMENTS = 60
        const val CAM_AHEAD = 400f          // camera sits this far in front of the car
        const val MIN_DIST = 520f           // closer than this is "on your bumper": not drawn
        val RANGE: Float = (SEGMENTS - 2) * C.SEG_LEN
        val CAM_HEIGHT: Float = C.CAM_HEIGHT * 0.6f
    }

    class Pt { var x = 0f; var y = 0f; var w = 0f; var scale = 0f; var dist = 0f }

    val camX: Float = player.x * C.ROAD_WIDTH
    val camZ: Float = player.z + CAM_AHEAD
    private val base: Segment = track.segAt(camZ)
    /** distance from the camera back to the start (low-z edge) of the base segment */
    private val off: Float = ((camZ % C.SEG_LEN) + C.SEG_LEN) % C.SEG_LEN
    val camY: Float
    val xNear = FloatArray(SEGMENTS)
    val xFar = FloatArray(SEGMENTS)

    init {
        val pct = off / C.SEG_LEN
        camY = CAM_HEIGHT + lerp(base.p1.worldY, base.p2.worldY, pct)
        var x = 0f
        var dx = 0f
        for (k in 0 until SEGMENTS) {
            xNear[k] = x
            x += dx
            xFar[k] = x
            dx += seg(k).curve
        }
    }

    /** k-th segment behind the camera (0 = the one the camera is on). */
    fun seg(k: Int): Segment = track.segments[((base.index - k) % track.size + track.size) % track.size]

    /** distance behind the camera of the segment's high-z (near) edge; negative for k = 0 */
    fun nearDist(k: Int): Float = off + (k - 1) * C.SEG_LEN
    /** distance behind the camera of the segment's low-z (far) edge */
    fun farDist(k: Int): Float = off + k * C.SEG_LEN

    /** How far behind the camera world position z is, wrapping around the lap. */
    fun behind(z: Float): Float {
        val len = track.trackLength.toFloat()
        val d = camZ - z
        return ((d % len) + len) % len
    }

    fun project(worldX: Float, worldY: Float, dist: Float, xOff: Float, out: Pt): Boolean {
        if (dist <= C.CAM_DEPTH) return false
        val s = C.CAM_DEPTH / dist
        out.scale = s
        out.dist = dist
        out.x = s * (worldX + xOff - camX)
        out.y = s * (worldY - camY)
        out.w = s * C.ROAD_WIDTH
        return true
    }

    /** Places an object at world z / lane x (road units, -1..1) in the mirror, or false if out of view. */
    fun place(z: Float, laneX: Float, out: Pt): Boolean {
        val d = behind(z)
        if (d < MIN_DIST || d > RANGE) return false
        var k = kotlin.math.ceil(((d - off) / C.SEG_LEN).toDouble()).toInt()
        k = k.coerceIn(0, SEGMENTS - 1)
        val t = ((d - nearDist(k)) / C.SEG_LEN).coerceIn(0f, 1f)
        val s = seg(k)
        val xo = lerp(xNear[k], xFar[k], t)
        val wy = lerp(s.p2.worldY, s.p1.worldY, t)
        return project(laneX * C.ROAD_WIDTH, wy, d, xo, out)
    }

    /** Racers behind the player that the mirror can show, farthest first (draw order). */
    fun racersBehind(racers: List<Racer>): List<Pair<Racer, Pt>> {
        val out = ArrayList<Pair<Racer, Pt>>()
        for (r in racers) {
            if (r === player || !r.alive) continue
            val p = Pt()
            if (place(r.z, r.x, p)) out.add(r to p)
        }
        out.sortByDescending { it.second.dist }
        return out
    }
}
