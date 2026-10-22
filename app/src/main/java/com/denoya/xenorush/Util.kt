package com.denoya.xenorush

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.tan

object C {
    const val SEG_LEN = 200f
    const val ROAD_WIDTH = 2200f
    const val RUMBLE = 3
    const val LANES = 3
    const val DRAW_DIST = 240
    const val CAM_HEIGHT = 1150f
    const val FOV = 100f
    val CAM_DEPTH: Float = (1.0 / tan(PI / 180.0 * FOV / 2.0)).toFloat()
    const val LAPS = 3
    val PLAYER_Z: Float = CAM_HEIGHT * CAM_DEPTH
    const val CENTRIFUGAL = 0.31f
    const val MAX_LATERAL = 2.4f
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clampD(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

fun easeIn(a: Float, b: Float, p: Float): Float = a + (b - a) * (p * p)
fun easeOut(a: Float, b: Float, p: Float): Float = a + (b - a) * (1f - (1f - p).pow(2))
fun easeInOut(a: Float, b: Float, p: Float): Float = a + (b - a) * ((-cos(p * PI).toFloat() / 2f) + 0.5f)

fun fmtTime(ms: Long): String {
    if (ms < 0) return "0:00.00"
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    val hundredths = (ms % 1000) / 10
    return String.format("%d:%02d.%02d", m, sec, hundredths)
}

fun fmtXrp(drops: Long): String {
    val xrp = drops / 1_000_000.0
    return if (xrp % 1.0 == 0.0) String.format("%.0f XRP", xrp) else String.format("%.2f XRP", xrp)
}
