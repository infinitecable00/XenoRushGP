package com.denoya.xenorush

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Multiplayer core (pure Kotlin, no Android/Firebase types). MpFirebase.kt implements MpBackend.
// ---------------------------------------------------------------------------------------------

data class MpPlayer(
    val uid: String,
    val name: String,
    val vehicleId: Int,
    val e: Int, val a: Int, val g: Int, val w: Int,
    val ownedTracks: Set<Int>,
    val preferredTrack: Int,
    val joinedAt: Long
)

data class MpRoom(
    val id: String,
    val status: String,                 // "waiting" or "racing"
    val players: Map<String, MpPlayer>,
    val trackId: Int,
    val startAt: Long,                  // server time (ms) when the green light goes
    val secondJoinAt: Long              // server time when the 2nd player joined
)

/** Snapshot of one racer, published ~10 times a second. */
data class NetState(
    val z: Float, val lap: Int, val x: Float, val speed: Float,
    val energy: Int, val alive: Boolean, val finished: Boolean, val finishMs: Long,
    val shield: Boolean, val reflect: Boolean, val boost: Boolean,
    val t: Long                         // sender's server-time estimate
)

/** Network transport. All callbacks may arrive on any thread; MpSession queues them. */
interface MpBackend {
    fun uid(): String
    fun serverNow(): Long
    fun joinOrCreate(me: MpPlayer, onJoined: (roomId: String) -> Unit, onError: (String) -> Unit)
    fun watchRoom(roomId: String, onChange: (MpRoom?) -> Unit)
    /** Atomically flips the room from waiting to racing (no-op if already started). */
    fun startRoom(roomId: String, trackId: Int, startAt: Long)
    fun publishState(roomId: String, s: NetState)
    fun watchStates(roomId: String, onState: (uid: String, s: NetState?) -> Unit)
    fun sendEvent(roomId: String, e: NetEvent)
    fun watchEvents(roomId: String, onEvent: (NetEvent) -> Unit)
    fun leave(roomId: String)
}

object MpRules {
    const val MAX_PLAYERS = 4
    const val FILL_WAIT_MS = 15_000L      // after the 2nd player joins, wait this long for more
    const val START_DELAY_MS = 5_000L     // countdown lead time once the race is locked in
    const val STATE_INTERVAL_MS = 100L    // 10 Hz

    /** The host is the earliest joined player still in the room (ties broken by uid). */
    fun host(room: MpRoom): String? = room.players.values.minWithOrNull(compareBy<MpPlayer>({ it.joinedAt }, { it.uid }))?.uid

    fun shouldStart(room: MpRoom, serverNow: Long): Boolean {
        if (room.status != "waiting") return false
        val n = room.players.size
        if (n >= MAX_PLAYERS) return true
        return n >= 2 && room.secondJoinAt > 0 && serverNow - room.secondJoinAt >= FILL_WAIT_MS
    }

    /** Host's chosen track if every player owns it, otherwise a free track picked from the room id. */
    fun chooseTrack(room: MpRoom, hostUid: String): Int {
        val host = room.players[hostUid]
        val pref = host?.preferredTrack ?: 0
        if (pref in 0 until 30 && room.players.values.all { pref in it.ownedTracks || pref < Catalog.FREE_TRACKS }) return pref
        return abs(room.id.hashCode()) % Catalog.FREE_TRACKS
    }
}

class MpSession(private val backend: MpBackend) {
    enum class Phase { IDLE, SEARCHING, LOBBY, STARTING, RACING, ERROR, LEFT }

    @Volatile var phase = Phase.IDLE
        private set
    var status = ""
        private set
    var roomId: String? = null
        private set
    var room: MpRoom? = null
        private set
    val myUid: String get() = backend.uid()

    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private var startRequested = false
    var link: MpRaceLink? = null
        private set

    fun find(me: MpPlayer) {
        if (phase != Phase.IDLE && phase != Phase.ERROR && phase != Phase.LEFT) return
        phase = Phase.SEARCHING; status = "Searching for a match..."; startRequested = false
        backend.joinOrCreate(me, { id -> inbox.add { onJoined(id) } }, { err -> inbox.add { phase = Phase.ERROR; status = err } })
    }

    private fun onJoined(id: String) {
        if (phase != Phase.SEARCHING) { backend.leave(id); return }
        roomId = id; phase = Phase.LOBBY; status = "Waiting for opponents..."
        backend.watchRoom(id) { r -> inbox.add { onRoom(r) } }
    }

    private fun onRoom(r: MpRoom?) {
        if (phase == Phase.LEFT) return
        if (r == null) { phase = Phase.ERROR; status = "Room closed"; return }
        room = r
        if (r.status == "racing" && r.startAt > 0 && (phase == Phase.LOBBY || phase == Phase.SEARCHING)) {
            phase = Phase.STARTING
            status = "Race locked: ${Catalog.TRACKS[r.trackId.coerceIn(0, 29)].name}"
        } else if (phase == Phase.LOBBY) {
            status = if (r.players.size < 2) "Waiting for opponents..." else "${r.players.size}/4 racers - starting soon"
        }
    }

    /** Call every frame on the game thread. */
    fun tick() {
        while (true) { val f = inbox.poll() ?: break; f() }
        val r = room ?: return
        val id = roomId ?: return
        if (phase == Phase.LOBBY && !startRequested && MpRules.host(r) == myUid && MpRules.shouldStart(r, backend.serverNow())) {
            startRequested = true
            backend.startRoom(id, MpRules.chooseTrack(r, myUid), backend.serverNow() + MpRules.START_DELAY_MS)
        }
        link?.pump()
    }

    /** Once phase == STARTING, the game builds the race through this. */
    fun createLink(track: Track, localVehicle: VehicleDef, localState: VehicleState): MpRaceLink? {
        val r = room ?: return null
        val id = roomId ?: return null
        val l = MpRaceLink(backend, id, r, track, myUid, localVehicle, localState)
        link = l
        phase = Phase.RACING
        backend.watchStates(id) { uid, s -> l.inbox.add { l.onState(uid, s) } }
        backend.watchEvents(id) { e -> l.inbox.add { l.onEvent(e) } }
        return l
    }

    fun leave() {
        roomId?.let { backend.leave(it) }
        phase = Phase.LEFT; status = ""; room = null; roomId = null; link = null; inbox.clear()
    }
}

/** Connects one local Race to the network: publishes local state, shows remote racers as proxies. */
class MpRaceLink(
    private val backend: MpBackend,
    private val roomId: String,
    val room: MpRoom,
    val track: Track,
    private val myUid: String,
    localVehicle: VehicleDef,
    localState: VehicleState
) {
    val inbox = ConcurrentLinkedQueue<() -> Unit>()
    val race: Race
    private val latest = HashMap<String, NetState>()
    private val recvAt = HashMap<String, Long>()
    private var lastPublish = 0L
    val left = HashSet<String>()

    init {
        val me = Racer(localVehicle, localState, true, 1f, uid = myUid, displayName = "YOU")
        val others = room.players.values.filter { it.uid != myUid }.map { p ->
            Racer(Catalog.VEHICLES[p.vehicleId.coerceIn(0, 21)], VehicleState(true, p.e, p.a, p.g, p.w), false, 1f,
                isRemote = true, uid = p.uid, displayName = p.name.ifBlank { "Racer" })
        }
        val all = others + me
        // same grid on every device: order by uid
        val grid = all.sortedBy { it.uid }
        race = Race(track, all, 0L, grid)
        race.onLocalEvent = { e -> backend.sendEvent(roomId, e) }
    }

    /** Seconds until green light, from the shared server clock. */
    fun secondsToStart(): Float = ((room.startAt - backend.serverNow()) / 1000f)

    fun onState(uid: String, s: NetState?) {
        if (uid == myUid) return
        if (s == null) { left.add(uid); latest.remove(uid); race.racers.firstOrNull { it.uid == uid }?.let { it.alive = false }; return }
        latest[uid] = s
        recvAt[uid] = backend.serverNow()
    }

    fun onEvent(e: NetEvent) {
        if (e.from == myUid) return
        race.applyRemoteEvent(e)
    }

    /** Drain network input and smooth remote racers toward their latest reported position. */
    fun pump(dt: Float = 1f / 60f) {
        while (true) { val f = inbox.poll() ?: break; f() }
        val len = track.trackLength.toFloat()
        val now = backend.serverNow()
        for (r in race.racers) {
            if (!r.isRemote) continue
            if (r.uid in left) { r.alive = false; continue }
            val s = latest[r.uid] ?: continue
            val age = ((now - (recvAt[r.uid] ?: now)).coerceIn(0L, 500L)) / 1000f
            // extrapolate along the track, then ease toward it (distance-on-track handles lap wrap)
            val targetDist = s.lap * len + s.z + (if (s.finished || !s.alive) 0f else s.speed * age)
            val curDist = r.lap * len + r.z
            val diff = targetDist - curDist
            val newDist = if (abs(diff) > 4000f) targetDist else curDist + diff * (dt * 12f).coerceAtMost(1f)
            r.lap = (newDist / len).toInt().coerceAtLeast(0)
            r.z = newDist - r.lap * len
            r.x += (s.x - r.x) * (dt * 12f).coerceAtMost(1f)
            r.speed = s.speed
            r.energy = s.energy
            r.alive = s.alive
            r.finished = s.finished
            r.finishTimeMs = s.finishMs
            r.shieldT = if (s.shield) 1f else 0f
            r.reflectT = if (s.reflect) 1f else 0f
            r.boostT = if (s.boost) 1f else 0f
        }
    }

    /** Publish the local racer at 10 Hz (call every frame). */
    fun publish(force: Boolean = false) {
        val now = backend.serverNow()
        if (!force && now - lastPublish < MpRules.STATE_INTERVAL_MS) return
        lastPublish = now
        val p = race.player
        backend.publishState(roomId, NetState(p.z, p.lap, p.x, p.speed, p.energy, p.alive, p.finished,
            p.finishTimeMs, p.shieldT > 0f, p.reflectT > 0f, p.boostT > 0f, now))
    }

    /** Multiplayer standings: finishers by time, then by distance; destroyed racers last. */
    fun standings(): List<Racer> = race.racers.sortedWith(compareBy<Racer>(
        { if (it.finished) 0 else if (it.alive) 1 else 2 },
        { if (it.finished) it.finishTimeMs else 0L },
        { -(it.lap * track.trackLength + it.z) }
    ))

    fun allDone(): Boolean = race.racers.all { it.finished || !it.alive || it.uid in left }
}
