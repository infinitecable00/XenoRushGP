package com.denoya.xenorush

/**
 * Frame cap and graphics tiers.
 *
 * [FrameCap.TARGET_FPS] is fixed. A 90 Hz or 120 Hz panel still presents at this rate.
 * Max Graphics (a settings toggle, not a tier picker) calls [Graphics.detect] and may
 * step down one tier when the smoothed rate stays under [Graphics.STEP_DOWN_FPS].
 * The toggle off uses [Graphics.Tier.SAFE].
 *
 * The race view is a Canvas software draw (SurfaceView.lockCanvas), so the knobs are
 * draw distance, resolution scale, decorative glow, star count, and mirror length.
 * GPU name is only a small bonus. A Mali-G57 does not outrank the CPU score.
 */
object FrameCap {
    const val TARGET_FPS = 60
    const val FRAME_NS = 1_000_000_000L / TARGET_FPS
}

object Graphics {
    /** Well under the cap. A brief hitch does not drop a tier. */
    const val STEP_DOWN_FPS = 50f
    const val STEP_DOWN_MS = 1500f

    enum class Tier(
        val label: String,
        val drawDist: Int,
        val resolutionScale: Float,
        val stars: Int,
        val glow: Boolean,
        val laneMarkers: Boolean,
        val mirrorSegments: Int,
        val antialias: Boolean
    ) {
        SAFE("Safe", 120, 0.62f, 0, false, true, 24, false),
        MEDIUM("Medium", 160, 0.75f, 16, false, true, 36, false),
        HIGH("High", 200, 0.85f, 28, true, true, 48, true),
        MAX("Max", C.DRAW_DIST, 1.0f, 40, true, true, 60, true);

        fun lower(): Tier = if (ordinal == 0) this else values()[ordinal - 1]
    }

    data class DeviceProfile(
        val ramMb: Int,
        val cores: Int,
        val maxFreqMhz: Int,
        val performanceClass: Int,
        val gpu: String = ""
    )

    /**
     * Highest tier this Canvas renderer should start at for a steady [FrameCap.TARGET_FPS].
     * RAM above 3 GB does not raise the score: the limit is CPU draw-call cost.
     * [DeviceProfile.maxFreqMhz] of 0 (clock unreadable) gets a small credit, not a penalty.
     */
    fun detect(p: DeviceProfile): Tier {
        var score = 0
        score += when {
            p.ramMb >= 3000 -> 2
            p.ramMb >= 2000 -> 1
            else -> 0
        }
        score += when {
            p.cores >= 8 -> 2
            p.cores >= 4 -> 1
            else -> 0
        }
        score += when {
            p.maxFreqMhz >= 3000 -> 3
            p.maxFreqMhz >= 2200 -> 2
            p.maxFreqMhz >= 1600 -> 1
            p.maxFreqMhz <= 0 -> 1
            else -> 0
        }
        score += when {
            p.performanceClass >= 34 -> 2
            p.performanceClass >= 31 -> 1
            else -> 0
        }
        val g = p.gpu.lowercase()
        if (g.contains("adreno 7") || g.contains("adreno 8") || g.contains("mali-g7") ||
            g.contains("immortalis") || g.contains("xclipse")) score += 1
        return when {
            score >= 9 -> Tier.MAX
            score >= 6 -> Tier.HIGH
            score >= 4 -> Tier.MEDIUM
            else -> Tier.SAFE
        }
    }

    /**
     * Samsung Galaxy A16 5G: MediaTek Dimensity 6300, Mali-G57, 8 cores at 2.4 GHz.
     * 4 GB, 6 GB, and 8 GB all land on the same tier.
     */
    fun galaxyA16(ramMb: Int = 6144, maxFreqMhz: Int = 2400): Tier =
        detect(DeviceProfile(ramMb, 8, maxFreqMhz, 0, "Mali-G57 MC2"))

    /** Snap a panel rate onto 60 / 90 / 120 / 240. Other rates fall to the nearest lower step. */
    fun normalizeHz(rate: Float): Int = when {
        rate >= 216f -> 240
        rate >= 110f -> 120
        rate >= 80f -> 90
        rate >= 50f -> 60
        else -> 0
    }

    fun supportedTargets(rates: List<Float>): Set<Int> {
        val out = HashSet<Int>()
        out.add(FrameCap.TARGET_FPS)
        for (r in rates) {
            val n = normalizeHz(r)
            if (n >= FrameCap.TARGET_FPS) out.add(n)
        }
        return out
    }

    /**
     * Highest rate to aim for while Max Graphics is on.
     * High-tier phones (the A16 class) may use 90. 120 and 240 are only for a Max-tier device
     * whose panel actually lists that rate. 60 is always available.
     */
    fun fpsTarget(profile: DeviceProfile, rates: List<Float>): Int {
        val ceiling = when (detect(profile)) {
            Tier.MAX -> 240
            Tier.HIGH -> 90
            else -> FrameCap.TARGET_FPS
        }
        val have = supportedTargets(rates)
        for (hz in intArrayOf(240, 120, 90, FrameCap.TARGET_FPS)) {
            if (hz <= ceiling && have.contains(hz)) return hz
        }
        return FrameCap.TARGET_FPS
    }

    /** Next lower rate the panel can do. Never goes under [FrameCap.TARGET_FPS]. */
    fun lowerFps(current: Int, rates: List<Float>): Int {
        val have = supportedTargets(rates)
        for (hz in intArrayOf(240, 120, 90, FrameCap.TARGET_FPS)) {
            if (hz < current && have.contains(hz)) return hz
        }
        return FrameCap.TARGET_FPS
    }

    /** Max Graphics off stays at 60. On uses [fpsTarget]. */
    fun presentHz(maxGraphics: Boolean, profile: DeviceProfile, rates: List<Float>): Int =
        if (!maxGraphics) FrameCap.TARGET_FPS else fpsTarget(profile, rates)
}

/** Smoothed frame rate. [sample] returns true once when a step-down is due, then starts a fresh window. */
class FpsMeter {
    private val window = FloatArray(30)
    private var count = 0
    private var index = 0
    private var lowMs = 0f
    var fps: Float = 0f
        private set
    var frameMs: Float = 0f
        private set

    fun reset() {
        count = 0
        index = 0
        lowMs = 0f
    }

    fun sample(frameNs: Long, targetFps: Int = FrameCap.TARGET_FPS): Boolean {
        val ms = (frameNs / 1_000_000f).coerceAtLeast(0f)
        frameMs = ms
        val inst = if (ms > 0.4f) 1000f / ms else targetFps.toFloat().coerceAtLeast(1f)
        window[index] = inst
        index = (index + 1) % window.size
        if (count < window.size) count++
        var sum = 0f
        for (i in 0 until count) sum += window[i]
        fps = sum / count
        if (count < window.size) return false
        val line = targetFps * 0.85f
        if (fps < line) lowMs += ms else lowMs = 0f
        if (lowMs >= Graphics.STEP_DOWN_MS) {
            lowMs = 0f
            count = 0
            index = 0
            return true
        }
        return false
    }

    fun label(): String {
        val shown = fps.toInt().coerceIn(0, 999)
        return "$shown FPS  ${"%.1f".format(frameMs)} ms"
    }
}
