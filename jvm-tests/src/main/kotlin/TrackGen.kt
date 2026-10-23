package com.denoya.xenorush

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
