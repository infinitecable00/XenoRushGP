import com.denoya.xenorush.*
import org.json.JSONObject

fun fireRocket(r: Race, s: Racer): Boolean {
    s.rockets = Arms.MAX_ROCKETS; s.fireCooldown = 0f; s.secondary = WeaponType.ROCKET
    return r.fireSecondary(s)
}

fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { pass++; println("PASS  $name") } else { fail++; println("FAIL  $name") }
    }

    // --- catalog sanity ---
    check("22 vehicles", Catalog.VEHICLES.size == 22)
    check("30 tracks", Catalog.TRACKS.size == 30)
    check("5 free vehicles", Catalog.VEHICLES.take(5).all { it.priceDrops == 0L })
    check("5 free tracks", Catalog.TRACKS.take(5).all { it.priceDrops == 0L })
    check("17 paid vehicles", Catalog.VEHICLES.drop(5).all { it.priceDrops > 0 })
    check("25 paid tracks", Catalog.TRACKS.drop(5).all { it.priceDrops > 0 })
    check("vehicles progressively better", Catalog.VEHICLES.zipWithNext().all { (a, b) -> b.topSpeed > a.topSpeed && b.accel > a.accel && b.grip > a.grip })
    check("6 weapon types (gun, rocket, 3 missiles, mine)", WeaponType.entries.size == 6 && WeaponType.entries.count { it.guided } == 3)
    check("all 30 tracks distinct names", Catalog.TRACKS.map { it.name }.toSet().size == 30)
    check("all 22 vehicles distinct names", Catalog.VEHICLES.map { it.name }.toSet().size == 22)

    // --- track generation ---
    val tracks = Catalog.TRACKS.map { TrackGen.build(it) }
    check("all tracks non-empty", tracks.all { it.size > 100 })
    val lens = tracks.map { it.size }
    println("      track segment counts: min=${lens.min()} max=${lens.max()}")
    check("tracks vary in size", lens.max() - lens.min() > 20)
    check("track lengths consistent", tracks.all { it.trackLength == it.size * 200 })
    check("power-ups on all tracks", tracks.all { t -> t.segments.any { it.sprites.isNotEmpty() } })
    val puCounts = tracks.map { t -> t.segments.sumOf { s -> s.sprites.size } }
    println("      power-ups per track: min=${puCounts.min()} max=${puCounts.max()}")
    check("deterministic generation", TrackGen.build(Catalog.TRACKS[7]).let { b -> b.segments.map { it.curve } == tracks[7].segments.map { it.curve } })
    check("pit strip on every track", tracks.all { t ->
        val pits = t.segments.filter { it.pit }
        pits.size == PitStrip.LENGTH_SEGS && pits.first().index == PitStrip.START_SEG && pits.all { it.curve == 0f }
    })
    check("pit approach is a straight", tracks.all { t ->
        (1..PitStrip.APPROACH_SEGS).all { d -> t.segments[(PitStrip.START_SEG - d + t.size) % t.size].curve == 0f }
    })
    val pitFrac = tracks.map { PitStrip.LENGTH_SEGS.toFloat() / it.size }
    println("      pit strip: segs ${PitStrip.START_SEG}..${PitStrip.START_SEG + PitStrip.LENGTH_SEGS - 1}, ${"%.1f".format(pitFrac.min() * 100)}–${"%.1f".format(pitFrac.max() * 100)}% of a lap")

    // --- full race simulation ---
    val track = tracks[0]
    val player = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
    check("races are 5 racers", Catalog.RACERS_PER_RACE == 5)
    val ais = (0 until Catalog.RACERS_PER_RACE - 1).map { Racer(Catalog.VEHICLES[(it * 3) % 22], VehicleState(true, 1, 1, 1, 1), false, 0.9f) }
    val race = Race(track, ais + player, 0)
    val input = PlayerInput()
    var simTime = 0f
    val dt = 1f / 60f
    input.gas = true
    var sawPickup = false
    var sawFire = false
    while (!race.finished && simTime < 600f) {
        val seg = track.segAt(race.player.z)
        input.left = race.player.x > -0.1f && seg.curve > 1.5f
        input.right = race.player.x < 0.1f && seg.curve < -1.5f
        race.update(dt, input, (simTime * 1000).toLong())
        simTime += dt
        if (race.player.shield > 0 || race.player.boostT > 0f || race.player.reflectT > 0f) sawPickup = true
        if (race.projectiles.isNotEmpty() || race.mines.isNotEmpty()) sawFire = true
    }
    println("      player hull at finish: ${race.player.energy} shield ${race.player.shield}; AI alive ${ais.count { it.alive }}/${ais.size}; AI off-road (alive only): ${ais.count { it.alive && kotlin.math.abs(it.x) > 1.3f }}")
    println("      race finished=${race.finished} simTime=${"%.1f".format(simTime)}s lap=${race.player.lap} rank=${race.rank(player)}")
    check("race completes", race.finished)
    check("player completes 3 laps", race.player.lap >= 3)
    check("power-ups were collected", sawPickup)
    check("weapons were fired", sawFire)
    check("ranks are a permutation", race.racers.map { race.rank(it) }.sorted() == (1..5).toList())
    check("race time positive", race.raceTimeMs > 0)
    // AI should stay mostly on the road (steering compensation works)
    val aiOff = ais.count { kotlin.math.abs(it.x) > 1.3f && !PitStrip.onStrip(track, it.x, it.z) }
    println("      AI off-road count at end: $aiOff/${ais.size} (avg |x| = ${"%.2f".format(ais.map { kotlin.math.abs(it.x) }.average())})")
    check("AI stays on road", aiOff <= 2)

    // recharge strip: the whole lane refills shield, and never hull
    run {
        val t = tracks[0]
        check("full strip distance grants a full shield",
            PitStrip.pointsFor(PitStrip.lengthUnits()) == PitStrip.FULL_PASS_SHIELD.toFloat() &&
                PitStrip.FULL_PASS_SHIELD == Arms.MAX_SHIELD &&
                PitStrip.distanceOnStrip(PitStrip.zStart(), PitStrip.zEnd(), t.trackLength) == PitStrip.lengthUnits())
        val p = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val r = Race(t, listOf(p), 0)
        r.countdown = 0f
        p.z = PitStrip.zStart() - 80f
        p.x = PitStrip.CENTER_X
        p.speed = 10000f
        p.shield = 0
        p.energy = 40
        val gas = PlayerInput().apply { gas = true }
        val cap = PitStrip.speedLimit(p.maxSpeed())
        var n = 0
        var framesOnStrip = 0
        var overCap = false
        while (n < 900 && p.z < PitStrip.zEnd() + 400f) {
            val wasOn = PitStrip.onStrip(t, p.x, p.z)
            r.update(1f / 60f, gas, n * 16L)
            if (wasOn) {
                framesOnStrip++
                if (p.speed > cap + 1f) overCap = true
            }
            n++
        }
        println("      pit pass shield=${p.shield} hull=${p.energy} frames=$n cap=$cap")
        check("full pit pass fills an empty shield", p.shield == Arms.MAX_SHIELD && r.events.contains("RECHARGE") && r.events.contains("SHIELD FULL") && p.shieldJustFull)
        check("pit pass does not heal hull", p.energy == 40)
        check("pit holds racing speed to the cap", PitStrip.SPEED_CAP in 0.70f..0.80f && framesOnStrip > 10 && !overCap)
        val ai = Racer(Catalog.VEHICLES[4], VehicleState(true, 1, 1, 1, 1), false, 1f)
        val ra = Race(t, listOf(ai, Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)), 0)
        ra.countdown = 0f
        ai.z = PitStrip.zStart() + 200f
        ai.x = PitStrip.CENTER_X
        ai.speed = ai.maxSpeed()
        ra.update(1f / 60f, PlayerInput(), 0L)
        check("pit cap applies to AI", PitStrip.onStrip(t, ai.x, ai.z) && ai.speed <= PitStrip.speedLimit(ai.maxSpeed()) + 1f)
        val half = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val rh = Race(t, listOf(half), 0)
        rh.countdown = 0f
        half.z = PitStrip.zStart()
        half.x = PitStrip.CENTER_X
        half.speed = 10000f
        half.shield = 0
        half.energy = 70
        var hn = 0
        while (hn < 600 && half.z < PitStrip.zStart() + PitStrip.lengthUnits() / 2f) {
            rh.update(1f / 60f, gas, 0L)
            hn++
        }
        check("half the strip is a partial shield", half.shield in 40..60 && half.energy == 70 && !half.shieldJustFull)
        val off = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val r2 = Race(t, listOf(off), 0)
        r2.countdown = 0f
        off.z = PitStrip.zStart() + 30f
        off.x = 0f
        off.speed = 10000f
        off.energy = 55
        repeat(40) { r2.update(1f / 60f, gas, 0L) }
        check("center of the road does not recharge", off.shield == 0 && !off.onPit && off.energy == 55)
        val full = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val rf = Race(t, listOf(full), 0)
        rf.countdown = 0f
        full.z = PitStrip.zStart() - 40f
        full.x = PitStrip.CENTER_X
        full.speed = 10000f
        full.shield = Arms.MAX_SHIELD
        full.energy = 15
        var fn = 0
        while (fn < 600 && full.z < PitStrip.zEnd() + 200f) {
            rf.update(1f / 60f, gas, 0L)
            fn++
        }
        check("a full shield does not chime again", full.shield == Arms.MAX_SHIELD && full.energy == 15 && !full.shieldJustFull && !rf.events.contains("SHIELD FULL") && !full.pitCharging)
    }
    run {
        var took = 0
        repeat(12) { n ->
            val t = TrackGen.build(Catalog.TRACKS[n])
            val ai = Racer(Catalog.VEHICLES[2], VehicleState(true, 1, 1, 1, 1), false, 1f)
            val p = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
            val r = Race(t, listOf(ai, p), 0)
            r.countdown = 0f
            val approach = (PitStrip.START_SEG - PitStrip.APPROACH_SEGS + t.size) % t.size
            ai.z = approach * C.SEG_LEN + 40f
            ai.x = 0f
            ai.speed = 9000f
            ai.shield = 0
            p.z = t.trackLength * 0.5f
            var steps = 0
            var best = 0
            while (steps < 60 * 6) {
                r.update(1f / 60f, PlayerInput(), steps * 16L)
                p.speed = 0f
                if (ai.shield > best) best = ai.shield
                val idx = PitStrip.indexAt(ai.z, t.size)
                if (steps > 20 && idx > PitStrip.START_SEG + PitStrip.LENGTH_SEGS + 15 && idx < t.size / 2) break
                steps++
            }
            if (best > 0) took++
        }
        println("      low-shield AI who recharged: $took/12")
        check("low-shield AI sometimes use the pit", took in 2..10)
    }

    // --- reflection: incoming projectile bounces back and damages the shooter ---
    run {
        val p = Racer(Catalog.VEHICLES[1], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val shooter = Racer(Catalog.VEHICLES[4], VehicleState(true, 1, 1, 1, 1), false, 1f) // PLASMA
        val r = Race(track, listOf(p, shooter), 0)
        r.countdown = 0f
        p.reflectT = 30f
        shooter.z = p.z - 2500f // shooter BEHIND the player, fires forward
        shooter.x = p.x
        shooter.energy = 200
        val energyBefore = shooter.energy
        var reflected = false
        var damagedShooter = false
        var t = 0f
        while (t < 10f) {
            r.update(1 / 60f, PlayerInput(), (t * 1000).toLong())
            // manually fire a plasma bolt at the player
            if (r.projectiles.isEmpty() && t < 1f) {
                fireRocket(r, shooter)
                // plasma bolt flying forward from the shooter toward the player
                r.projectiles.lastOrNull()?.let { it.x = p.x; it.z = shooter.z + 300f; it.speed = 9000f }
            }
            r.projectiles.forEach { if (it.reflected && it.owner === p) reflected = true }
            if (shooter.energy < energyBefore) { damagedShooter = true; break }
            t += 1 / 60f
        }
        check("projectile reflected back", reflected)
        check("reflected projectile damages original shooter", damagedShooter)
    }

    // --- every weapon type has an effect ---
    for (wt in WeaponType.entries) {
        val p2 = Racer(Catalog.VEHICLES[0], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val victim2 = Racer(Catalog.VEHICLES[1], VehicleState(true, 1, 1, 1, 1), false, 0.9f)
        val r2 = Race(tracks[1], listOf(p2, victim2), 0)
        r2.countdown = 0f
        victim2.z = p2.z + 1500f; victim2.x = p2.x
        if (wt.guided) p2.missiles.add(wt)
        p2.secondary = wt
        val before = r2.projectiles.size + r2.mines.size
        val ok = r2.fireSecondary(p2)
        check("fire $wt produces effect", ok && r2.projectiles.size + r2.mines.size == before + 1)
    }

    // --- upgrade math ---
    run {
        val vs2 = VehicleState(true, 1, 1, 1, 1)
        val r3 = Racer(Catalog.VEHICLES[5], vs2, true, 1f)
        var cost = 0
        for (lvl in 1 until 10) { cost += Catalog.upgradeCost(5, lvl) }
        vs2.e = 10; vs2.w = 10
        val dmg1 = Racer(Catalog.VEHICLES[5], VehicleState(true, 1, 1, 1, 1), true, 1f).bulletDamage()
        val dmg10 = Racer(Catalog.VEHICLES[5], VehicleState(true, 1, 1, 1, 10), true, 1f).bulletDamage()
        val spd1 = Racer(Catalog.VEHICLES[5], VehicleState(true, 1, 1, 1, 1), true, 1f).maxSpeed()
        val spd10 = Racer(Catalog.VEHICLES[5], VehicleState(true, 10, 1, 1, 1), true, 1f).maxSpeed()
        check("upgrade costs scale and are positive", cost > 0 && Catalog.upgradeCost(5, 2) > Catalog.upgradeCost(5, 1))
        check("bullet damage 5 at lvl1, 15 at lvl10", dmg1 == 5f && dmg10 == 15f)
        check("engine lvl10 boosts speed", spd10 > spd1 * 1.3f)
        println("      full engine upgrade cost: $cost CR; dmg ${"%.1f".format(dmg1)} -> ${"%.1f".format(dmg10)}")
    }

    // --- XRP client (live network) ---
    val li = Xrp.ledgerIndex(Xrp.MAINNET_RPC)
    println("      mainnet ledger index: $li")
    check("XRP ledger index reachable", li > 0)
    // real account with real envelope-format history: parser must normalize {"tx":..., "meta":..., "validated":...}
    // pick an account that just transacted (a fixed address may have no recent history)
    val realAddr = run {
        val c = java.net.URL(Xrp.MAINNET_RPC).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"method":"ledger","params":[{"ledger_index":"validated","transactions":true,"expand":true}]}""".toByteArray()) }
        val txsL = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("result").getJSONObject("ledger").getJSONArray("transactions")
        val o = txsL.getJSONObject(0)
        (o.optJSONObject("tx_json") ?: o).getString("Account")
    }
    println("      sample account: $realAddr")
    val txs = Xrp.accountTxs(Xrp.MAINNET_RPC, realAddr, li - 2000)
    check("account_tx parses real envelopes", txs.isNotEmpty())
    if (txs.isNotEmpty()) {
        val t = txs.first()
        check("normalized tx has flat fields + meta", t.has("meta") && t.optString("TransactionType").isNotEmpty() && t.has("date"))
        println("      sample tx: type=${t.optString("TransactionType")} date=${t.optLong("date")} result=${t.optJSONObject("meta")?.optString("TransactionResult")}")
    }

    // --- invoice verification unit tests (envelope-style fixtures) ---
    val now = System.currentTimeMillis()
    val inv = Invoice("v5", 10_000_000L, 42L, now - 60_000L, now + 29 * 60_000L)
    val merchant = "rMerchantAddressXXXXXXXXXXXXXXXXXXXXX"
    val rippleNow = (now - 946684800000L) / 1000
    fun envelope(dest: String, tag: Long, delivered: Any, result: String = "tesSUCCESS", dateSec: Long = rippleNow, hash: String = "H${(0..999999).random()}") = JSONObject()
        .put("tx", JSONObject()
            .put("hash", hash).put("TransactionType", "Payment").put("Destination", dest)
            .put("DestinationTag", tag).put("date", dateSec))
        .put("meta", JSONObject().put("TransactionResult", result).put("delivered_amount", delivered))
        .put("validated", true)
    fun flat(envelope: JSONObject): JSONObject = envelope // verifyPayment receives the normalized form
    fun norm(e: JSONObject): JSONObject {
        val inner = e.getJSONObject("tx")
        inner.put("meta", e.get("meta"))
        inner.put("validated", e.optBoolean("validated"))
        return inner
    }
    check("valid envelope payment verified", Xrp.verifyPayment(norm(envelope(merchant, 42, "10000000")), inv, merchant, emptySet()).ok)
    check("wrong tag rejected", !Xrp.verifyPayment(norm(envelope(merchant, 43, "10000000")), inv, merchant, emptySet()).ok)
    check("wrong destination rejected", !Xrp.verifyPayment(norm(envelope("rOtherXXXXXXXXXXXXXXXXXXXXXXXXXXX", 42, "10000000")), inv, merchant, emptySet()).ok)
    check("underpay rejected", !Xrp.verifyPayment(norm(envelope(merchant, 42, "9000000")), inv, merchant, emptySet()).ok)
    check("IOU rejected", !Xrp.verifyPayment(norm(envelope(merchant, 42, JSONObject(mapOf("currency" to "USD")))), inv, merchant, emptySet()).ok)
    check("failed tx (tec) rejected", !Xrp.verifyPayment(norm(envelope(merchant, 42, "10000000", result = "tecPATH_DRY")), inv, merchant, emptySet()).ok)
    check("reuse rejected", !Xrp.verifyPayment(norm(envelope(merchant, 42, "10000000", hash = "USED1")), inv, merchant, setOf("USED1")).ok)
    check("non-payment rejected", run {
        val e = envelope(merchant, 42, "10000000")
        e.getJSONObject("tx").put("TransactionType", "TrustSet")
        !Xrp.verifyPayment(norm(e), inv, merchant, emptySet()).ok
    })
    check("stale tx rejected (before invoice)", !Xrp.verifyPayment(norm(envelope(merchant, 42, "10000000", dateSec = (now - 86_400_000L - 946684800000L) / 1000)), inv, merchant, emptySet()).ok)
    check("network error handled without throwing", run {
        try { Xrp.ledgerIndex("https://invalid.example.invalid/") >= -1 } catch (_: Exception) { false }
    })

    // --- combat across the start/finish line (natural shot crossing the wrap) ---
    run {
        val shooter = Racer(Catalog.VEHICLES[4], VehicleState(true, 1, 1, 1, 1), false, 1f) // PLASMA
        val victim = Racer(Catalog.VEHICLES[1], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val tr = tracks[2]
        val r = Race(tr, listOf(shooter, victim), 0)
        r.countdown = 0f
        // shooter just BEFORE the finish line fires forward; victim just AFTER it
        shooter.z = tr.trackLength - 1000f
        victim.z = 1500f
        shooter.x = 0.1f
        victim.x = 0.1f
        victim.energy = 200
        shooter.speed = 0f
        victim.speed = 0f
        fireRocket(r, shooter) // natural plasma bolt from trackLength-1000+300, moving forward
        check("shot fired across the line exists", r.projectiles.isNotEmpty())
        var t = 0f
        var hit = false
        while (t < 3f && !hit) {
            r.update(1 / 60f, PlayerInput(), (t * 1000).toLong())
            if (victim.energy < 200) hit = true
            t += 1 / 60f
        }
        check("shot fired before the line damages racer after the line", hit)
    }

    // --- reflection across the wrap boundary ---
    run {
        val shooter = Racer(Catalog.VEHICLES[4], VehicleState(true, 1, 1, 1, 1), false, 1f)
        val victim = Racer(Catalog.VEHICLES[1], VehicleState(true, 1, 1, 1, 1), true, 1f)
        val tr = tracks[3]
        val r = Race(tr, listOf(shooter, victim), 0)
        r.countdown = 0f
        victim.reflectT = 30f
        shooter.z = tr.trackLength - 1000f
        victim.z = 1500f
        shooter.x = 0f
        victim.x = 0f
        shooter.energy = 200
        shooter.speed = 0f
        victim.speed = 0f
        fireRocket(r, shooter)
        var t = 0f
        var damaged = false
        while (t < 6f && !damaged) {
            r.update(1 / 60f, PlayerInput(), (t * 1000).toLong())
            if (shooter.energy < 200) damaged = true
            t += 1 / 60f
        }
        check("reflected shot crosses the line back to the shooter", damaged)
    }

    // --- unvalidated payment rejected ---
    run {
        val now2 = System.currentTimeMillis()
        val inv2 = Invoice("v6", 10_000_000L, 7L, now2 - 60_000L, now2 + 29 * 60_000L)
        val merchant2 = "rMerchantAddressXXXXXXXXXXXXXXXXXXXXX"
        val rippleNow2 = (now2 - 946684800000L) / 1000
        val unvalidated = JSONObject()
            .put("tx", JSONObject().put("hash", "UV1").put("TransactionType", "Payment")
                .put("Destination", merchant2).put("DestinationTag", 7L).put("date", rippleNow2))
            .put("meta", JSONObject().put("TransactionResult", "tesSUCCESS").put("delivered_amount", "10000000"))
            .put("validated", false)
        val inner = unvalidated.getJSONObject("tx")
        inner.put("meta", unvalidated.get("meta")); inner.put("validated", false)
        check("unvalidated payment rejected", !Xrp.verifyPayment(inner, inv2, merchant2, emptySet()).ok)
    }

    runV11Tests(::check)
    runWeaponTests(::check)
    runMusicTests(::check)
    runMirrorTests(::check)

    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
