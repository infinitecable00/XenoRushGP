import com.denoya.xenorush.*

fun runMirrorTests(check: (String, Boolean) -> Unit) {
    val track = TrackGen.build(Catalog.TRACKS[0])
    fun racer(player: Boolean) = Racer(Catalog.VEHICLES[if (player) 0 else 6], VehicleState(true, 1, 1, 1, 1), player, 1f)
    // flatten + straighten the track so screen x is easy to reason about
    for (sg in track.segments) { sg.curve = 0f; sg.p1.worldY = 0f; sg.p2.worldY = 0f }
    val startSeg = track.size / 2
    val p = racer(true); p.z = startSeg * C.SEG_LEN + 50f; p.x = 0f
    val rv = RearView(track, p)
    val pt = RearView.Pt()
    check("racer 2000 behind same lane is visible", rv.place(p.z - 2000f, 0f, pt))
    check("same lane on a straight shows centred", kotlin.math.abs(pt.x) < 1e-3f)
    check("below the mirror horizon (on the road)", pt.y < 0f)
    val near = RearView.Pt(); rv.place(p.z - 800f, 0f, near)
    check("closer racer draws bigger and lower", near.w > pt.w && near.y < pt.y)
    val left = RearView.Pt(); rv.place(p.z - 2000f, -0.6f, left)
    val right = RearView.Pt(); rv.place(p.z - 2000f, 0.6f, right)
    check("racer behind on your left appears on the left (mirror)", left.x < 0f && right.x > 0f)
    check("racer ahead is not in the mirror", !rv.place(p.z + 1500f, 0f, RearView.Pt()))
    check("racer on your bumper is not drawn", !rv.place(p.z - 50f, 0f, RearView.Pt()))
    check("racer beyond mirror range is not drawn", !rv.place(p.z - RearView.RANGE - 2000f, 0f, RearView.Pt()))
    // wrap-around at the start/finish line
    val p2 = racer(true); p2.z = 300f
    val rv2 = RearView(track, p2)
    check("racer behind across the finish line is visible", rv2.place(track.trackLength - 1500f, 0f, RearView.Pt()))
    // list ordering
    val a = racer(false).apply { z = p.z - 1200f }
    val b = racer(false).apply { z = p.z - 3000f }
    val dead = racer(false).apply { z = p.z - 1500f; alive = false }
    val list = rv.racersBehind(listOf(p, a, b, dead))
    check("racersBehind skips player and wrecked cars", list.size == 2)
    check("racersBehind is farthest-first", list.first().first === b && list.last().first === a)
    // a right-hand bend you just drove through stays on the right in the mirror
    for (sg in track.segments) sg.curve = 2f
    val rv3 = RearView(track, p)
    val bend = RearView.Pt(); rv3.place(p.z - 3000f, 0f, bend)
    check("bend behind you curves the right way in the mirror", bend.x > 0f)
}
