import com.denoya.xenorush.*

fun runGraphicsTests(check: (String, Boolean) -> Unit) {
    check("frame cap is 60", FrameCap.TARGET_FPS == 60 && FrameCap.FRAME_NS == 1_000_000_000L / 60)
    val order = Graphics.Tier.values().toList()
    check("tiers climb in quality", order.first() == Graphics.Tier.SAFE && order.last() == Graphics.Tier.MAX &&
        order.zipWithNext().all { (a, b) -> b.drawDist > a.drawDist && b.resolutionScale >= a.resolutionScale })
    check("max tier is the full picture", Graphics.Tier.MAX.drawDist == C.DRAW_DIST &&
        Graphics.Tier.MAX.resolutionScale == 1f && Graphics.Tier.MAX.glow && Graphics.Tier.MAX.mirrorSegments == 60)
    check("safe tier is lighter", Graphics.Tier.SAFE.resolutionScale < 1f && !Graphics.Tier.SAFE.glow &&
        Graphics.Tier.SAFE.laneMarkers && Graphics.Tier.SAFE.drawDist < Graphics.Tier.HIGH.drawDist)
    check("step down stops at safe", Graphics.Tier.MAX.lower() == Graphics.Tier.HIGH &&
        Graphics.Tier.HIGH.lower() == Graphics.Tier.MEDIUM &&
        Graphics.Tier.MEDIUM.lower() == Graphics.Tier.SAFE &&
        Graphics.Tier.SAFE.lower() == Graphics.Tier.SAFE)
    check("A16 4/6/8 GB starts on High", listOf(4096, 6144, 8192).all { Graphics.galaxyA16(it) == Graphics.Tier.HIGH })
    check("A16 with no clock reading starts on Medium", Graphics.galaxyA16(ramMb = 6144, maxFreqMhz = 0) == Graphics.Tier.MEDIUM)
    check("Mali-G57 does not raise the A16 tier",
        Graphics.galaxyA16() == Graphics.detect(Graphics.DeviceProfile(6144, 8, 2400, 0, "")))
    check("flagship starts on Max", Graphics.detect(Graphics.DeviceProfile(12288, 8, 3300, 34, "Adreno 750")) == Graphics.Tier.MAX)
    check("low-end phone starts on Safe", Graphics.detect(Graphics.DeviceProfile(1024, 2, 1200, 0, "")) == Graphics.Tier.SAFE)

    val steady = FpsMeter()
    var steps = 0
    repeat(180) { if (steady.sample(16_666_666L)) steps++ }
    check("60fps does not step down", steps == 0 && steady.fps > 55f)
    val slow = FpsMeter()
    steps = 0
    repeat(120) { if (slow.sample(40_000_000L)) steps++ }
    check("sustained 25fps steps down once", steps == 1)

    val fresh = SaveData()
    check("fps and max graphics default off", !fresh.showFps && !fresh.maxGraphics && !fresh.showFpsSet)
    fresh.showFps = true
    fresh.showFpsSet = true
    fresh.maxGraphics = true
    val back = SaveCodec.fromJson(SaveCodec.toJson(fresh))
    check("fps and max graphics round-trip", back.showFps && back.showFpsSet && back.maxGraphics)
    check("old save leaves the new toggles off", SaveCodec.fromJson("{}").let { !it.showFps && !it.maxGraphics && !it.showFpsSet })
    val local = SaveData()
    local.showFps = false
    local.maxGraphics = false
    local.showFpsSet = true
    val remote = SaveData()
    remote.showFps = true
    remote.maxGraphics = true
    val merged = SaveMerge.merge(local, remote)
    check("fps and max graphics stay on this device", !merged.showFps && !merged.maxGraphics && merged.showFpsSet)
    check("haptics default on, tips unseen", fresh.haptics && !fresh.tipsSeen && SaveCodec.fromJson("{}").haptics && !SaveCodec.fromJson("{}").tipsSeen)
    fresh.haptics = false
    fresh.tipsSeen = true
    val feel = SaveCodec.fromJson(SaveCodec.toJson(fresh))
    check("haptics and tips round-trip", !feel.haptics && feel.tipsSeen)
    val remoteFeel = SaveData()
    remoteFeel.haptics = true
    remoteFeel.tipsSeen = false
    val kept = SaveMerge.merge(fresh, remoteFeel)
    check("haptics and tips stay on this device", !kept.haptics && kept.tipsSeen)

    val a16 = Graphics.DeviceProfile(6144, 8, 2400, 0, "Mali-G57 MC2")
    val panel90 = listOf(60f, 90f)
    check("A16 with Max Graphics targets 90", Graphics.presentHz(true, a16, panel90) == 90)
    check("Max Graphics off stays at 60 on a 90 Hz panel", Graphics.presentHz(false, a16, panel90) == 60)
    check("90 steps down to 60", Graphics.lowerFps(90, panel90) == 60)
    check("high tier does not chase 120", Graphics.fpsTarget(a16, listOf(60f, 120f)) == 60)
    val flagship = Graphics.DeviceProfile(12288, 8, 3300, 34, "Adreno 750")
    check("flagship 120 panel targets 120", Graphics.fpsTarget(flagship, listOf(60f, 120f)) == 120)
    check("flagship 240 panel targets 240", Graphics.fpsTarget(flagship, listOf(60f, 120f, 240f)) == 240)
    check("120 steps to 60 when 90 is missing", Graphics.lowerFps(120, listOf(60f, 120f)) == 60)

    check("haptic boost and pickup are the player's", HapticMap.kind(SfxCue(RaceSfx.BOOST, 0, true, 0f)) == HapticKind.BOOST &&
        HapticMap.kind(SfxCue(RaceSfx.PICKUP, 0, true, 0f)) == HapticKind.PICKUP &&
        HapticMap.kind(SfxCue(RaceSfx.BOOST, 1, false, 10f)) == null)
    check("haptic hit and explosion", HapticMap.kind(SfxCue(RaceSfx.BULLET_HIT, 0, true, 0f)) == HapticKind.HIT &&
        HapticMap.kind(SfxCue(RaceSfx.MINE_HIT, 0, true, 0f)) == HapticKind.EXPLOSION &&
        HapticMap.kind(SfxCue(RaceSfx.ROCKET_HIT, 1, false, 400f)) == HapticKind.HIT &&
        HapticMap.kind(SfxCue(RaceSfx.ROCKET_HIT, 1, false, 9000f)) == null)
    check("pad map", PadMap.key(PadMap.BUTTON_A) == PadMap.Btn.OK && PadMap.raceHold(PadMap.Btn.OK) == "gas" &&
        PadMap.raceHold(PadMap.Btn.BOOST) == "boost" && PadMap.key(PadMap.BUTTON_START) == PadMap.Btn.PAUSE &&
        PadMap.raceHold(PadMap.Btn.PAUSE) == null)
    check("pad stick deadzone and hat", PadMap.steer(0.1f, 0f) == 0f && PadMap.steer(-0.8f, 0f) < 0f && PadMap.steer(0.9f, -1f) < 0f)
    check("background pauses single player only", BackgroundPolicy.pauseSinglePlayer(true, false) &&
        !BackgroundPolicy.pauseSinglePlayer(true, true) && !BackgroundPolicy.pauseSinglePlayer(false, false))
}
