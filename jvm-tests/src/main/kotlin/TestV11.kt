import com.denoya.xenorush.*
import org.json.JSONObject

/** In-memory multiplayer transport shared by several simulated devices. */
class LoopHub {
    var clock = 1_000_000L
    val rooms = LinkedHashMap<String, MpRoom>()
    val roomWatchers = HashMap<String, MutableList<(MpRoom?) -> Unit>>()
    val stateWatchers = HashMap<String, MutableList<(String, NetState?) -> Unit>>()
    val eventWatchers = HashMap<String, MutableList<(NetEvent) -> Unit>>()
    var nextId = 1
    fun notifyRoom(id: String) { roomWatchers[id]?.toList()?.forEach { it(rooms[id]) } }
}

class LoopBackend(val hub: LoopHub, val me: String) : MpBackend {
    override fun uid() = me
    override fun serverNow() = hub.clock
    override fun joinOrCreate(me: MpPlayer, onJoined: (String) -> Unit, onError: (String) -> Unit) {
        val open = hub.rooms.values.firstOrNull { it.status == "waiting" && it.players.size < MpRules.MAX_PLAYERS }
        if (open != null) {
            val players = open.players + (me.uid to me.copy(joinedAt = hub.clock))
            hub.rooms[open.id] = open.copy(players = players, secondJoinAt = if (players.size == 2) hub.clock else open.secondJoinAt)
            onJoined(open.id); hub.notifyRoom(open.id)
        } else {
            val id = "room${hub.nextId++}"
            hub.rooms[id] = MpRoom(id, "waiting", mapOf(me.uid to me.copy(joinedAt = hub.clock)), 0, 0L, 0L)
            onJoined(id)
        }
    }
    override fun watchRoom(roomId: String, onChange: (MpRoom?) -> Unit) {
        hub.roomWatchers.getOrPut(roomId) { mutableListOf() }.add(onChange); onChange(hub.rooms[roomId])
    }
    var startCalls = 0
    override fun startRoom(roomId: String, trackId: Int, startAt: Long) {
        startCalls++
        val r = hub.rooms[roomId] ?: return
        if (r.status != "waiting") return
        hub.rooms[roomId] = r.copy(status = "racing", trackId = trackId, startAt = startAt); hub.notifyRoom(roomId)
    }
    override fun publishState(roomId: String, s: NetState) { hub.stateWatchers[roomId]?.toList()?.forEach { it(me, s) } }
    override fun watchStates(roomId: String, onState: (String, NetState?) -> Unit) { hub.stateWatchers.getOrPut(roomId) { mutableListOf() }.add(onState) }
    override fun sendEvent(roomId: String, e: NetEvent) { hub.eventWatchers[roomId]?.toList()?.forEach { it(e) } }
    override fun watchEvents(roomId: String, onEvent: (NetEvent) -> Unit) { hub.eventWatchers.getOrPut(roomId) { mutableListOf() }.add(onEvent) }
    override fun leave(roomId: String) {
        val r = hub.rooms[roomId] ?: return
        hub.rooms[roomId] = r.copy(players = r.players - me); hub.notifyRoom(roomId)
        hub.stateWatchers[roomId]?.toList()?.forEach { it(me, null) }
    }
}

fun runV11Tests(check: (String, Boolean) -> Unit) {
    // ---------------- pricing ----------------
    check("price: upgrade 0.99, vehicle 2.50, track pack 9.99",
        Pricing.UPGRADE_USD_CENTS == 99 && Pricing.VEHICLE_USD_CENTS == 250 && Pricing.TRACK_PACK_USD_CENTS == 999)
    check("dropsFor rounds up to 0.01 XRP", Pricing.dropsFor(250, 1.306) == 1_920_000L) // 2.50/1.306=1.9142 -> 1.92
    check("dropsFor exact", Pricing.dropsFor(999, 1.0) == 9_990_000L)
    check("dropsFor never underpays", listOf(0.37, 1.306, 2.71, 9.9).all { p -> listOf(99, 250, 999).all { c -> Pricing.dropsFor(c, p) / 1e6 * p >= c / 100.0 - 1e-9 } })
    check("fmtUsd", Pricing.fmtUsd(99) == "$0.99" && Pricing.fmtUsd(250) == "$2.50" && Pricing.fmtUsd(999) == "$9.99")
    check("label offline -> N/A", Pricing.label(250, null).contains("N/A"))
    check("label online shows XRP", Pricing.label(250, 1.306) == "$2.50 (~1.92 XRP)")
    check("parse vehicle", Pricing.parse("v7") == Pricing.Item.Vehicle(7))
    check("parse free vehicle rejected", Pricing.parse("v2") == null && Pricing.parse("v22") == null)
    check("parse upgrade", Pricing.parse("u0_e") == Pricing.Item.Upgrade(0, "e") && Pricing.parse("u21_w") == Pricing.Item.Upgrade(21, "w"))
    check("parse bad upgrade rejected", Pricing.parse("u0_x") == null && Pricing.parse("u99_e") == null && Pricing.parse("u_e") == null)
    check("parse track pack", Pricing.parse("trackpack") == Pricing.Item.TrackPack)
    check("old per-track ids not sold", Pricing.parse("t12") == null)
    val amm = JSONObject("""{"result":{"amm":{"amount":"10000000000","amount2":{"currency":"RLUSD","value":"13060"}}}}""")
    check("AMM price parse", Math.abs((Pricing.parseAmmPrice(amm) ?: 0.0) - 1.306) < 1e-9)
    check("AMM bad parse -> null", Pricing.parseAmmPrice(JSONObject("{}")) == null)
    check("CoinGecko parse", Pricing.parseCoinGecko(JSONObject("""{"ripple":{"usd":1.31}}""")) == 1.31)
    check("Kraken parse", Pricing.parseKraken(JSONObject("""{"error":[],"result":{"XXRPZUSD":{"c":["1.30680","5"]}}}""")) == 1.3068)
    check("insane price rejected", Pricing.parseCoinGecko(JSONObject("""{"ripple":{"usd":0}}""")) == null)
    val live = PriceOracle.fetch(force = true)
    println("      live XRP/USD = $live")
    check("live price fetch (XRPL AMM / fallback)", live != null && Pricing.sane(live))

    // ---------------- save codec + merge ----------------
    val a = SaveData()
    a.credits = 1200; a.updatedAt = 100
    a.vehicleState(7).owned = true; a.vehicleState(0).e = 6; a.trackState(0).bestLapMs = 50_000; a.trackState(0).wins = 2
    a.usedTxHashes.add("H1"); a.sound = false
    val rt = SaveCodec.fromJson(SaveCodec.toJson(a))
    check("codec round-trip", rt.credits == 1200 && rt.vehicleState(7).owned && rt.vehicleState(0).e == 6 && "H1" in rt.usedTxHashes && !rt.sound && rt.updatedAt == 100L)
    check("codec corrupt -> fresh", SaveCodec.fromJson("{not json").credits == 500)
    check("codec clamps levels", SaveCodec.fromJson("""{"vehicles":{"3":{"owned":true,"e":99,"a":0,"g":5,"w":5}}}""").vehicleState(3).let { it.e == 10 && it.a == 1 })
    check("cloud json excludes settings/invoice", !SaveCodec.toJson(a, includeLocal = false).contains("settings"))
    val b = SaveData()
    b.credits = 300; b.updatedAt = 200
    b.vehicleState(9).owned = true; b.vehicleState(0).e = 3; b.vehicleState(0).w = 8
    b.trackState(12).owned = true; b.trackState(0).bestLapMs = 48_000; b.trackState(0).wins = 1
    b.usedTxHashes.add("H2"); b.sound = true
    val m = SaveMerge.merge(a, b)
    check("merge: owned vehicles union", m.vehicleState(7).owned && m.vehicleState(9).owned)
    check("merge: owned tracks union", m.trackState(12).owned)
    check("merge: max upgrade levels", m.vehicleState(0).e == 6 && m.vehicleState(0).w == 8)
    check("merge: tx hashes union", m.usedTxHashes.containsAll(listOf("H1", "H2")))
    check("merge: credits from newer save", m.credits == 300)
    check("merge: best lap fastest, wins max", m.trackState(0).bestLapMs == 48_000L && m.trackState(0).wins == 2)
    check("merge: settings stay local", !m.sound)
    check("merge is idempotent", SaveCodec.toJson(SaveMerge.merge(m, m)) == SaveCodec.toJson(m))

    // ---------------- multiplayer: matchmaking + host start ----------------
    val hub = LoopHub()
    val ba = LoopBackend(hub, "alice"); val bb = LoopBackend(hub, "bob")
    val sa = MpSession(ba); val sb = MpSession(bb)
    fun player(uid: String, veh: Int, tracks: Set<Int>, pref: Int) = MpPlayer(uid, uid.uppercase(), veh, 5, 5, 5, 5, tracks, pref, 0L)
    sa.find(player("alice", 3, (0..29).toSet(), 17)); sa.tick()
    check("mp: first player creates room, waits", sa.phase == MpSession.Phase.LOBBY && hub.rooms.size == 1)
    sb.find(player("bob", 6, (0..4).toSet(), 2)); sb.tick(); sa.tick()
    check("mp: second player joins same room", sb.roomId == sa.roomId && sa.room?.players?.size == 2)
    hub.clock += 10_000; sa.tick(); sb.tick()
    check("mp: no start before 15s fill window", hub.rooms[sa.roomId]!!.status == "waiting")
    hub.clock += 5_100; sa.tick(); sb.tick(); sa.tick(); sb.tick()
    check("mp: host starts after 15s", hub.rooms[sa.roomId]!!.status == "racing")
    check("mp: only host issued start", ba.startCalls == 1 && bb.startCalls == 0)
    check("mp: both clients STARTING", sa.phase == MpSession.Phase.STARTING && sb.phase == MpSession.Phase.STARTING)
    val room = hub.rooms[sa.roomId]!!
    check("mp: track falls back to a free track when not all own host's pick", room.trackId < Catalog.FREE_TRACKS)
    check("mp: host track used when all own it", MpRules.chooseTrack(room.copy(players = room.players.mapValues { it.value.copy(ownedTracks = (0..29).toSet()) }), "alice") == 17)
    check("mp: start is ~5s in future", room.startAt - hub.clock in 4_000..5_000)
    val fourRoom = MpRoom("r", "waiting", (1..4).associate { "p$it" to player("p$it", 0, setOf(0), 0) }, 0, 0, 0)
    check("mp: full room starts immediately", MpRules.shouldStart(fourRoom, 0))
    check("mp: lone player never starts", !MpRules.shouldStart(fourRoom.copy(players = mapOf("p1" to fourRoom.players["p1"]!!)), Long.MAX_VALUE))

    // ---------------- multiplayer: race link ----------------
    val track = TrackGen.build(Catalog.TRACKS[room.trackId])
    val la = sa.createLink(track, Catalog.VEHICLES[0], VehicleState(true, 5, 5, 5, 5))!!
    val lb = sb.createLink(track, Catalog.VEHICLES[6], VehicleState(true, 5, 5, 5, 5))!!
    check("mp: each race has local + remote proxy", la.race.racers.size == 2 && la.race.racers.count { it.isRemote } == 1 && lb.race.racers.count { it.isRemote } == 1)
    check("mp: same grid on both devices",
        la.race.racers.first { it.uid == "alice" }.z == lb.race.racers.first { it.uid == "alice" }.z &&
        la.race.racers.first { it.uid == "bob" }.z == lb.race.racers.first { it.uid == "bob" }.z)
    // run both to the green light and drive a bit
    hub.clock = room.startAt + 1
    val inA = PlayerInput().apply { gas = true }; val inB = PlayerInput().apply { gas = true }
    var t = 0L
    repeat(300) {
        hub.clock += 16; t += 16
        for ((s, l, inp) in listOf(Triple(sa, la, inA), Triple(sb, lb, inB))) {
            s.tick(); l.race.countdown = 0f; l.race.update(0.016f, inp, t); l.publish()
        }
    }
    sa.tick(); sb.tick()
    val bobOnA = la.race.racers.first { it.uid == "bob" }
    val bobReal = lb.race.player
    check("mp: remote proxy tracks real position", Math.abs((bobOnA.lap * track.trackLength + bobOnA.z) - (bobReal.lap * track.trackLength + bobReal.z)) < 1500f)
    check("mp: remote proxy not simulated locally (no ammo use / pickups)", bobOnA.isRemote)

    // combat: alice fires plasma at bob from right behind him
    val aliceL = la.race.player; val bobL = lb.race.player
    aliceL.z = 3000f; aliceL.x = 0f; aliceL.lap = 0; aliceL.speed = 0f
    bobL.z = 4200f; bobL.x = 0f; bobL.lap = 0; bobL.speed = 0f; bobL.shieldT = 0f; bobL.reflectT = 0f
    for (l in listOf(la, lb)) l.publish(force = true)
    sa.tick(); sb.tick(); repeat(30) { sa.tick(); sb.tick() } // let proxies settle
    val bobEnergy0 = bobL.energy
    la.race.projectiles.clear(); lb.race.projectiles.clear()
    aliceL.ammo = 5; aliceL.fireCooldown = 0f
    val proxyBobOnA = la.race.racers.first { it.uid == "bob" }
    val proxyEnergyBefore = proxyBobOnA.energy
    la.race.fireWeapon(aliceL)
    sb.tick()
    check("mp: alice has a projectile weapon", Catalog.VEHICLES[0].weapon == WeaponType.PLASMA)
    check("mp: shot event reaches other device", lb.race.projectiles.size == 1 && lb.race.projectiles[0].owner.uid == "alice")
    repeat(40) {
        la.race.update(0.016f, PlayerInput(), t); lb.race.update(0.016f, PlayerInput(), t)
        aliceL.speed = 0f; bobL.speed = 0f
    }
    check("mp: victim device applies damage", bobL.energy < bobEnergy0)
    check("mp: shooter device does not damage proxy", proxyBobOnA.energy == proxyEnergyBefore || proxyBobOnA.energy == bobL.energy)
    check("mp: shot removed on shooter device after hitting proxy", la.race.projectiles.isEmpty())

    // reflect: bob has reflect, alice fires again -> reflected shot comes back and hurts alice
    lb.race.projectiles.clear(); la.race.projectiles.clear()
    bobL.reflectT = 5f; aliceL.fireCooldown = 0f
    val aliceEnergy0 = aliceL.energy; val bobEnergy1 = bobL.energy
    la.race.fireWeapon(aliceL); sb.tick()
    repeat(60) {
        la.race.update(0.016f, PlayerInput(), t); lb.race.update(0.016f, PlayerInput(), t)
        sa.tick(); sb.tick(); aliceL.speed = 0f; bobL.speed = 0f; bobL.reflectT = 5f
    }
    check("mp: reflector not damaged", bobL.energy == bobEnergy1)
    check("mp: reflected shot damages original shooter", aliceL.energy < aliceEnergy0)

    // laser: simulate a laser event from alice
    val laserEv = NetEvent("laser", "alice", WeaponType.LASER.ordinal, 0f, 0f, 0f, 18f, "bob")
    bobL.reflectT = 0f; bobL.shieldT = 0f
    val e2 = bobL.energy
    lb.race.applyRemoteEvent(laserEv)
    check("mp: laser event damages target only on its device", bobL.energy == e2 - 18)
    bobL.reflectT = 5f
    val aliceE = aliceL.energy
    lb.race.applyRemoteEvent(laserEv); sa.tick()
    check("mp: reflected laser bounces back to shooter", aliceL.energy == aliceE - 18)
    check("mp: own events ignored", run { val before = bobL.energy; lb.onEvent(NetEvent("laser", "bob", 3, 0f, 0f, 0f, 18f, "bob")); bobL.energy == before })

    // disconnect + standings
    sb.leave(); sa.tick()
    check("mp: leaver marked out on other device", "bob" in la.left && !la.race.racers.first { it.uid == "bob" }.alive)
    aliceL.finished = true; aliceL.finishTimeMs = 90_000
    check("mp: standings put finishers first", la.standings().first() === aliceL)
    check("mp: race done when everyone finished or left", la.allDone())
}
