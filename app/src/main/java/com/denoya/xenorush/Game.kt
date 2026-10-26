package com.denoya.xenorush

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.MotionEvent
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.abs
import kotlin.random.Random

enum class Screen { TITLE, MENU, TRACKS, GARAGE, VEHICLE, SHOP, PAY, SETTINGS, RACE, RESULTS, PAUSED, ACCOUNT, MP_LOBBY, UPDATES, CREDITS_INFO }

/** In-game "Updates" screen: newest first. */
object Changelog {
    const val VERSION = "1.6.0"
    val ENTRIES = listOf(
        "v1.6.0" to listOf(
            "Menu music loops on the home screen",
            "Race music starts after the lights and shuffles ten tracks",
            "Music and sound-effect volume in Settings",
            "Engine, boost, weapons, hits and pickups play sound effects",
            "Each ship has its own gun sound",
            "Recharge strip on the start straight fills the shield"
        ),
        "v1.5.0" to listOf(
            "The game now runs in landscape (turn the phone either way)",
            "Rearview mirror at the top middle of the race screen",
            "The mirror shows cars, missiles, rockets and mines behind you",
            "The mirror frame flashes red with MISSILE LOCK when a missile is chasing you",
            "Race controls resized for landscape: steering on the left, gas and weapons on the right",
            "Every menu redesigned for the wide screen",
            "Results screen now shows the correct number of racers"
        ),
        "v1.4.0" to listOf(
            "Race music by InfiniteCable",
            "A random song starts after GO, shuffled every race",
            "Music pauses with the game and fades out at the finish",
            "Race music on/off in Settings"
        ),
        "v1.3.0" to listOf(
            "Rockets, missiles and mines upgradable 1-10",
            "Rockets 20-40, missiles 55-75, mines 40-60 hp",
            "Race credits upgrade to level 5",
            "Levels 6-10 are paid unlocks",
            "Random Shield and Reflect drops on every course",
            "Power-ups come back every lap",
            "Races are now 5 racers",
            "New Updates and Credits screens",
            "Multiplayer coming soon"
        ),
        "v1.2.0" to listOf(
            "Guns, rockets, 3 guided missiles, mines",
            "100 hull and 100 shield on every ship",
            "XRP payments on mainnet"
        ),
        "v1.1.0" to listOf(
            "Google sign-in and cloud save",
            "USD prices, paid in XRP at the live rate",
            "Single player works fully offline"
        ),
        "v1.0.0" to listOf("First release: 30 tracks, 22 vehicles")
    )
}

object CompanyInfo {
    const val COMPANY = "Beam Galactic INC"
    const val SUPPORT_EMAIL = "support@beamgalactic.com"
}

class UpgDef(val label: String, val stat: String, val get: () -> Int, val inc: () -> Unit)

class Game(private val context: Context, private val activity: Activity?) {
    val renderer = Renderer()
    val music = MusicPlayer(context).also { m ->
        m.onSongStart = { title -> toast("NOW PLAYING: $title") }
    }
    val sfx = SfxPlayer(context)
    val save: SaveData = SaveManager(context).load()
    private val saveManager = SaveManager(context)

    @Volatile var screen = Screen.TITLE
    var w = 1080
    var h = 1920

    // menus
    private var shopTab = 0 // 0 vehicles, 1 track pack
    private var scroll = 0f
    private var dragStartY = 0f
    private var dragStartScroll = 0f
    private var dragged = false
    private var selectedVehicleForDetail = 0
    private var payStatus = ""
    private var payChecking = false
    private var payThread: Thread? = null
    private var toast = ""
    private var toastUntil = 0L
    private var titleTime = 0f

    // race
    var race: Race? = null
    var raceTrack: Track? = null
    private var selectedTrack = 0
    private val input = PlayerInput()
    private val raceUi = RaceUi(input)
    private var lastFire = false
    private var raceStartWall = 0L
    private var lastCd = 99
    private var raceResult: RaceResult? = null

    private var tone: ToneGenerator? = null
    private var toneVol = -1

    // simple button system
    private var buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private var lastTouchDown = 0L

    // ---------------- online services (only touched when the player asks for them) ----------------
    private val mainQueue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val cloud: Cloud by lazy { Cloud(context) }
    @Volatile private var online = false
    private var lastOnlineCheck = 0L
    private var xrpUsd: Double? = null          // live XRP price, null = N/A
    @Volatile private var priceFetching = false
    private var syncDirty = false
    private var lastSync = 0L
    @Volatile private var syncing = false
    private var accountMsg = ""
    @Volatile private var signingIn = false
    private var mpBackend: MpFirebase? = null
    private var mp: MpSession? = null
    private var mpLink: MpRaceLink? = null
    private var mpRewarded = false
    private val verifiedHashes = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun post(f: () -> Unit) { mainQueue.add(f) }

    /** Save locally (always) and mark the cloud copy stale (uploaded later, only if signed in and online). */
    private fun persist() {
        save.updatedAt = System.currentTimeMillis()
        saveManager.save(save)
        syncDirty = true
    }

    private fun refreshOnline() {
        val now = System.currentTimeMillis()
        if (now - lastOnlineCheck > 2000) {
            lastOnlineCheck = now
            online = Net.isOnline(context)
            if (!online) xrpUsd = null
        }
    }

    private fun refreshPrice(force: Boolean = false) {
        if (!online || priceFetching) return
        if (!force && xrpUsd != null && System.currentTimeMillis() - PriceOracle.lastFetch < 60_000L) return
        priceFetching = true
        Thread {
            val p = PriceOracle.fetch(force)
            post { xrpUsd = p; priceFetching = false }
        }.start()
    }

    private fun cloudSignedIn(): Boolean = try { cloud.available && cloud.uid() != null } catch (_: Exception) { false }

    private fun maybeSync(force: Boolean = false) {
        if (syncing || !online) return
        if (!force && (!syncDirty || System.currentTimeMillis() - lastSync < 60_000L)) return
        if (!cloudSignedIn()) { syncDirty = false; return }
        syncing = true; syncDirty = false; lastSync = System.currentTimeMillis()
        val snapshot = SaveCodec.toJson(save)
        cloud.sync(snapshot) { merged, msg ->
            post {
                syncing = false
                accountMsg = msg
                if (merged != null) {
                    save.copyProgressFrom(SaveMerge.merge(save, SaveCodec.fromJson(merged)))
                    saveManager.save(save)
                }
            }
        }
    }

    fun update(dt: Float) {
        while (true) { val f = mainQueue.poll() ?: break; f() }
        drainTouch()
        titleTime += dt
        refreshOnline()
        if (screen != Screen.RACE) maybeSync()
        mp?.tick()
        if (screen == Screen.MP_LOBBY) {
            val s = mp
            if (s != null && s.phase == MpSession.Phase.STARTING) startMpRace()
        }
        val link = mpLink
        if (link != null && (screen == Screen.RACE || screen == Screen.PAUSED || screen == Screen.RESULTS)) {
            val r = link.race
            val toGo = link.secondsToStart()
            r.countdown = if (toGo > 0f) toGo else 0f
            if (r.countdown > 0f) {
                val ci = (r.countdown + 0.99f).toInt()
                if (ci != lastCd && ci <= 3) { lastCd = ci; beep(70, 120) }
            }
            r.update(dt, input, System.currentTimeMillis() - raceStartWall)
            if (screen == Screen.RACE) handleWeapons(r)
            drainSfx(r)
            link.publish()
            if (r.finished && screen != Screen.RESULTS) finishMpRace()
            syncAudio(dt)
            return
        }
        if (screen == Screen.RACE && race != null) {
            val r = race!!
            if (r.countdown > 0f) {
                val ci = (r.countdown + 0.99f).toInt()
                if (ci != lastCd) {
                    lastCd = ci
                    if (ci > 0) beep(70, 120) else beep(90, 250)
                }
            }
            r.update(dt, input, System.currentTimeMillis() - raceStartWall)
            // fire handling (tap FIRE button)
            handleWeapons(r)
            drainSfx(r)
            if (r.finished) {
                finishRace()
            }
        }
        syncAudio(dt)
    }

    private var lastSpecial = false
    private var lastCycle = false

    /** GUN fires while held; special fires once per tap; WPN cycles the special weapon. */
    private fun handleWeapons(r: Race) {
        val p = r.player
        if (raceUi.gunPressed && r.countdown <= 0f) r.fireGun(p)
        if (raceUi.cyclePressed && !lastCycle) { p.cycleSecondary(); beep(40, 40) }
        lastCycle = raceUi.cyclePressed
        if (raceUi.specialPressed && !lastSpecial && r.countdown <= 0f) {
            // Fire cues live in Race.fireSecondary. A failed tap only shows the HUD message.
            if (!r.fireSecondary(p)) r.events.add("NO ${Arms.short(p.secondary)} LEFT")
        }
        lastSpecial = raceUi.specialPressed
        raceUi.secondaryLabel = "${Arms.short(p.secondary)} ${p.count(p.secondary)}"
    }

    // ---------------- race lifecycle ----------------

    /**
     * Menu music on every front-end screen. Race music only after GO, and only
     * while the race is still running. The countdown and the results screen are silent.
     * The pause menu holds the race track in place instead of switching back to the menu.
     */
    private fun syncAudio(dt: Float) {
        val r = mpLink?.race ?: race
        val racing = r != null && r.countdown <= 0f && !r.finished
        val cue = when (screen) {
            Screen.RACE, Screen.PAUSED -> if (racing) MusicCue.RACE else MusicCue.NONE
            Screen.RESULTS -> MusicCue.NONE
            else -> MusicCue.MENU
        }
        music.update(cue, screen == Screen.PAUSED, save.music, save.musicVolume.coerceIn(0, 100) / 100f, dt)
        val sfxVol = if (save.sound) save.sfxVolume.coerceIn(0, 100) / 100f else 0f
        val driving = screen == Screen.RACE && r != null && r.countdown <= 0f && !r.finished && r.player.alive
        val top = r?.player?.maxSpeed()?.coerceAtLeast(1f) ?: 1f
        val ratio = if (r == null) 0f else r.player.speed / top
        sfx.updateEngine(driving, ratio, (r?.player?.boostT ?: 0f) > 0f, sfxVol)
        val charging = driving && r != null && r.player.pitCharging
        sfx.updatePit(charging, sfxVol)
        if (r != null && r.player.shieldJustFull) {
            if (driving) sfx.playPitFull(sfxVol)
            r.player.shieldJustFull = false
        }
        sfx.tick(dt, sfxVol > 0f)
    }

    private fun drainSfx(r: Race) {
        val cues = r.drainSfx()
        if (cues.isEmpty() || screen != Screen.RACE || !save.sound) return
        val vol = save.sfxVolume.coerceIn(0, 100) / 100f
        if (vol <= 0f) return
        // Player and nearer ships first, so the mix cap drops the far AI copies.
        val ordered = cues.sortedWith(compareBy({ !it.fromPlayer }, { it.distance }))
        for (c in ordered) sfx.play(c, vol)
    }

    /** App sent to background: the game loop stops, so pause music and effects directly. */
    fun onAppPause() {
        music.pause()
        sfx.onAppPause()
    }

    /** App returned to the foreground. Race music stays paused if the pause menu is up. */
    fun onAppResume() = sfx.onAppResume()

    /** Activity is going away. Release players so they cannot leak. */
    fun onAppDestroy() {
        music.release()
        sfx.release()
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }

    fun startRace(trackId: Int) {
        music.newRace()
        selectedTrack = trackId
        val tDef = Catalog.TRACKS[trackId]
        raceTrack = TrackGen.build(tDef)
        val track = raceTrack!!
        val vDef = Catalog.VEHICLES[save.selectedVehicle]
        val vs = save.vehicleState(save.selectedVehicle)
        val player = Racer(vDef, vs, true, 1f)
        val rng = Random(tDef.seed + 13)
        val ais = (0 until Catalog.RACERS_PER_RACE - 1).map {
            val vid = rng.nextInt(22)
            val skill = (0.80f + tDef.difficulty * 0.032f + rng.nextFloat() * 0.08f).coerceAtMost(1.02f)
            // AI weapons get stronger on harder tracks (difficulty 1-5 -> weapon levels 1-5)
            val d = tDef.difficulty.coerceIn(1, Catalog.FREE_UPGRADE_MAX)
            Racer(Catalog.VEHICLES[vid], VehicleState(true, 1, 1, 1, d, d, d, d), false, skill)
        }
        race = Race(track, ais + player, 0)
        raceStartWall = System.currentTimeMillis() + 4000
        lastCd = 99
        race!!.player.lastLapStart = 0
        input.left = false; input.right = false; input.gas = false; input.brake = false
        screen = Screen.RACE
        beep(70, 150)
    }

    private fun finishRace() {
        val r = race ?: return
        val player = r.player
        var credits = 0
        var firstWin = false
        if (player.finished && player.alive) {
            val rank = r.rank(player)
            credits = Catalog.rewardForRank(rank, raceTrack!!.def.difficulty)
            val ts = save.trackState(selectedTrack)
            if (rank == 1) {
                ts.wins++
                if (ts.wins == 1) {
                    firstWin = true
                    credits += 250
                }
            }
            val bestLap = player.lapTimes.minOrNull() ?: Long.MAX_VALUE
            if (bestLap < ts.bestLapMs || ts.bestLapMs == 0L) ts.bestLapMs = bestLap
        }
        save.credits += credits
        persist()
        raceResult = RaceResult(r, credits, firstWin)
        screen = Screen.RESULTS
    }

    fun retryRace() {
        startRace(selectedTrack)
    }

    // ---------------- multiplayer ----------------

    private fun openMultiplayer() {
        if (!online) { toast("Multiplayer needs an internet connection"); return }
        if (!cloud.available) { toast("Online features not configured (see README: Firebase setup)"); return }
        if (cloud.uid() == null) { toast("Sign in with Google to play online"); screen = Screen.ACCOUNT; return }
        val app = cloud.app ?: return
        if (mpBackend == null) mpBackend = MpFirebase(app)
        val s = MpSession(mpBackend!!)
        mp = s
        val vs = save.vehicleState(save.selectedVehicle)
        val owned = (0 until 30).filter { save.trackState(it).owned }.toSet()
        s.find(MpPlayer(cloud.uid()!!, cloud.displayName().split(" ").first(), save.selectedVehicle,
            vs.e, vs.a, vs.g, vs.w, owned, selectedTrack, 0L))
        screen = Screen.MP_LOBBY
    }

    private fun startMpRace() {
        music.newRace()
        val s = mp ?: return
        val room = s.room ?: return
        val tid = room.trackId.coerceIn(0, 29)
        val track = TrackGen.build(Catalog.TRACKS[tid])
        val link = s.createLink(track, Catalog.VEHICLES[save.selectedVehicle], save.vehicleState(save.selectedVehicle).copy()) ?: return
        mpLink = link
        race = link.race
        raceTrack = track
        mpRewarded = false
        val backend = mpBackend ?: return
        raceStartWall = System.currentTimeMillis() + (room.startAt - backend.serverNow())
        lastCd = 99
        link.race.player.lastLapStart = 0
        releaseAllControls()
        screen = Screen.RACE
    }

    private fun finishMpRace() {
        val link = mpLink ?: return
        val r = link.race
        var credits = 0
        if (!mpRewarded && r.player.finished && r.player.alive) {
            mpRewarded = true
            val rank = link.standings().indexOf(r.player) + 1
            credits = Catalog.rewardForRank(rank, link.track.def.difficulty)
            save.credits += credits
            val ts = save.trackState(link.track.def.id)
            val bestLap = r.player.lapTimes.minOrNull() ?: Long.MAX_VALUE
            if (ts.owned && (bestLap < ts.bestLapMs || ts.bestLapMs == 0L)) ts.bestLapMs = bestLap
            persist()
        }
        link.publish(force = true)
        raceResult = RaceResult(r, credits, false)
        screen = Screen.RESULTS
    }

    private fun leaveMultiplayer() {
        mp?.leave()
        mp = null
        mpLink = null
        race = null
        releaseAllControls()
    }

    init {
        // merchant address is developer-controlled (Xrp.kt); player save cannot override it
        if (save.merchantAddress.isBlank() || save.merchantAddress != Xrp.MERCHANT_ADDRESS) {
            save.merchantAddress = Xrp.MERCHANT_ADDRESS
        }
        if (!Xrp.ALLOW_TESTNET) save.mainnet = true
    }

    // ---------------- payment ----------------

    /** Checks what the player is trying to buy and returns a reason it is not allowed, or null. */
    private fun purchaseBlocker(item: Pricing.Item): String? = when (item) {
        is Pricing.Item.Vehicle -> if (save.vehicleState(item.vehicleId).owned) "Already owned" else null
        is Pricing.Item.Upgrade -> {
            val vs = save.vehicleState(item.vehicleId)
            if (!vs.owned) "Unlock the vehicle first" else if (vs.get(item.stat) >= 10) "Already at max level" else null
        }
        Pricing.Item.TrackPack -> if (save.hasTrackPack()) "Track Pack already owned" else null
    }

    fun startPayment(itemId: String) {
        if (save.merchantAddress.isBlank()) {
            toast("XRP purchases not configured: set MERCHANT_ADDRESS in Xrp.kt")
            return
        }
        if (!online) { toast("XRP checkout N/A - offline"); return }
        val item = Pricing.parse(itemId) ?: return
        purchaseBlocker(item)?.let { toast(it); return }
        if (priceFetching) { toast("Getting XRP price..."); return }
        priceFetching = true
        toast("Getting live XRP price...")
        Thread {
            val p = PriceOracle.fetch(force = true)
            post {
                priceFetching = false
                xrpUsd = p
                if (p == null) { toast("XRP price N/A - try again"); return@post }
                purchaseBlocker(item)?.let { toast(it); return@post }
                val cents = Pricing.usdCents(item)
                val now = System.currentTimeMillis()
                val invoice = Invoice(itemId, Pricing.dropsFor(cents, p), Random.nextLong(1_000_000L, 999_999_999L),
                    now, now + Xrp.INVOICE_TTL_MS, cents, p)
                save.pendingInvoice = invoice
                saveManager.save(save)
                payStatus = "Waiting for payment..."
                screen = Screen.PAY
                startPayPolling()
            }
        }.start()
    }

    private fun startPayPolling() {
        payThread?.let { }
        payThread = Thread {
            while (screen == Screen.PAY) {
                val res = checkInvoiceInternal()
                if (res == "VERIFIED") {
                    payStatus = "Payment verified - unlocked!"
                    break
                }
                if (res == "OFFLINE") payStatus = "Wallet check N/A - offline (will retry)"
                if (res == "EXPIRED" || res == "NO INVOICE") break
                try { Thread.sleep(6000) } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    private fun checkInvoiceInternal(): String {
        val inv = save.pendingInvoice ?: return "NO INVOICE"
        if (System.currentTimeMillis() > inv.expiresAt) return "EXPIRED"
        if (!Net.isOnline(context)) return "OFFLINE"
        val rpc = Xrp.rpcUrl(save.mainnet)
        val li = Xrp.ledgerIndex(rpc)
        if (li < 0) return "NET_ERROR"
        val txs = Xrp.accountTxs(rpc, save.merchantAddress, li - 2500)
        for (t in txs) {
            val chk = Xrp.verifyPayment(t, inv, save.merchantAddress, save.usedTxHashes)
            if (chk.ok) {
                // claim the hash once, then apply on the game thread
                if (!verifiedHashes.add(chk.txHash)) return "VERIFIED"
                post {
                    if (save.usedTxHashes.add(chk.txHash)) {
                        applyUnlock(inv.itemId)
                        if (save.pendingInvoice?.destTag == inv.destTag) save.pendingInvoice = null
                        persist()
                        maybeSync(force = true)
                        beep(100, 250)
                    }
                }
                return "VERIFIED"
            }
        }
        return "PENDING"
    }

    fun checkInvoiceNow() {
        if (payChecking) return
        payChecking = true
        payStatus = "Checking ledger..."
        Thread {
            val res = checkInvoiceInternal()
            payChecking = false
            when (res) {
                "VERIFIED" -> payStatus = "Payment verified - unlocked!"
                "EXPIRED" -> payStatus = "Invoice expired. Start a new one."
                "NET_ERROR" -> payStatus = "Network error. Check connection."
                "OFFLINE" -> payStatus = "Wallet check N/A - offline"
                "PENDING" -> payStatus = "No matching payment found yet..."
                else -> payStatus = "No active invoice."
            }
        }.start()
    }

    private fun applyUnlock(itemId: String) {
        when (val item = Pricing.parse(itemId)) {
            is Pricing.Item.Vehicle -> save.vehicleState(item.vehicleId).owned = true
            is Pricing.Item.Upgrade -> {
                val vs = save.vehicleState(item.vehicleId)
                if (vs.get(item.stat) < 10) vs.inc(item.stat)
                // already maxed by credits while the invoice was open: refund as credits
                else save.credits += Catalog.upgradeCost(item.vehicleId, 9)
            }
            Pricing.Item.TrackPack -> (Catalog.FREE_TRACKS until 30).forEach { save.trackState(it).owned = true }
            null -> when {
                // invoices created by v1.0 (individual tracks / bundles) are still honored
                itemId.startsWith("t") -> itemId.substring(1).toIntOrNull()?.let { if (it in 0..29) save.trackState(it).owned = true }
                itemId == "bundle_all_vehicles" -> (0 until 22).forEach { save.vehicleState(it).owned = true }
                itemId == "bundle_all_tracks" -> (0 until 30).forEach { save.trackState(it).owned = true }
                itemId == "bundle_everything" -> {
                    (0 until 22).forEach { save.vehicleState(it).owned = true }
                    (0 until 30).forEach { save.trackState(it).owned = true }
                }
            }
        }
        persist()
    }

    fun cancelPayment() {
        save.pendingInvoice = null
        saveManager.save(save)
        payStatus = ""
        screen = Screen.SHOP
    }

    // ---------------- drawing ----------------

    fun draw(c: Canvas) {
        w = c.width
        h = c.height
        buttons.clear()
        when (screen) {
            Screen.TITLE -> drawTitle(c)
            Screen.MENU -> drawMenu(c)
            Screen.TRACKS -> drawTracks(c)
            Screen.GARAGE -> drawGarage(c)
            Screen.VEHICLE -> drawVehicleDetail(c)
            Screen.SHOP -> drawShop(c)
            Screen.PAY -> drawPay(c)
            Screen.SETTINGS -> drawSettings(c)
            Screen.UPDATES -> drawUpdates(c)
            Screen.CREDITS_INFO -> drawCreditsInfo(c)
            Screen.ACCOUNT -> drawAccount(c)
            Screen.MP_LOBBY -> drawLobby(c)
            Screen.RACE -> {
                val r = race
                val t = raceTrack
                if (r != null && t != null) {
                    renderer.drawRace(c, r, t, input, raceUi)
                    if (screen == Screen.PAUSED) drawPauseOverlay(c)
                }
            }
            Screen.RESULTS -> {
                val r = race
                val t = raceTrack
                if (r != null && t != null) renderer.drawRace(c, r, t, input, raceUi)
                drawResults(c)
            }
            Screen.PAUSED -> {
                val r = race
                val t = raceTrack
                if (r != null && t != null) renderer.drawRace(c, r, t, input, raceUi)
                drawPauseOverlay(c)
            }
        }
        drawToast(c)
    }

    // Landscape layout unit: 1% of the screen's short side (height in landscape).
    private val U: Float get() = h / 100f
    // Scroll limit for the current list screen, set while drawing and used by the drag handler.
    private var scrollMax = 0f

    private fun bg(c: Canvas, color: Int = Color.argb(255, 10, 12, 24)) {
        val p = Paint()
        p.color = color
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun header(c: Canvas, title: String, back: (() -> Unit)? = null) {
        renderer.drawTextCentered(c, title, w / 2f, 10.5f * U, 6.5f * U, Color.WHITE, Paint.Align.CENTER, w * 0.5f)
        if (back != null) {
            val r = RectF(2f * U, 2.5f * U, 2f * U + w * 0.17f, 13.5f * U)
            renderer.drawButton(c, r, "◀ BACK", Color.argb(255, 60, 70, 110), false, 4.6f * U)
            buttons.add(r to back)
        }
    }

    private fun drawToast(c: Canvas) {
        if (toast.isNotEmpty() && System.currentTimeMillis() < toastUntil) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(225, 20, 24, 40)
            c.drawRoundRect(RectF(w * 0.2f, 84f * U, w * 0.8f, 95f * U), 2f * U, 2f * U, p)
            renderer.drawTextCentered(c, toast, w / 2f, 91.3f * U, 4.4f * U, Color.argb(255, 255, 230, 140), Paint.Align.CENTER, w * 0.58f)
        }
    }

    fun toast(msg: String) {
        toast = msg
        toastUntil = System.currentTimeMillis() + 2600
    }

    // ---------- TITLE ----------
    private fun drawTitle(c: Canvas) {
        bg(c)
        val t = titleTime
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gp = Paint()
        gp.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.6f, Color.argb(255, 26, 16, 66), Color.argb(255, 8, 10, 26), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gp)
        // animated speed lines
        p.strokeWidth = 0.5f * U
        for (i in 0 until 18) {
            val yy = h - ((i * 6f * U + t * 40f * U) % h)
            val ww = (i + 1) * 2.2f * U
            p.color = Color.argb(170, 0, (200 + i * 3).coerceAtMost(255), (150 + i * 6).coerceAtMost(255))
            c.drawLine(w * 0.5f - ww - 20f * U, yy, w * 0.5f - ww * 0.3f - 20f * U, yy, p)
            c.drawLine(w * 0.5f + ww * 0.3f + 20f * U, yy, w * 0.5f + ww + 20f * U, yy, p)
        }
        renderer.drawTextCentered(c, "XENORUSH", w / 2f, 32f * U, 19f * U, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "G P", w / 2f, 47f * U, 12f * U, Color.argb(255, 255, 60, 100))
        renderer.drawTextCentered(c, "FUTURISTIC COMBAT RACING", w / 2f, 56f * U, 4.2f * U, Color.argb(200, 160, 170, 210))
        val r = RectF(w * 0.33f, 64f * U, w * 0.67f, 79f * U)
        renderer.drawButton(c, r, "TAP TO START", Color.argb(255, 40, 60, 140), (System.currentTimeMillis() / 600) % 2 == 0L, 6f * U)
        buttons.add(r to { screen = Screen.MENU })
        renderer.drawTextCentered(c, "v${Changelog.VERSION}  •  single player works offline  •  © ${CompanyInfo.COMPANY}", w / 2f, 93f * U, 3.2f * U, Color.argb(160, 130, 140, 180))
    }

    // ---------- MAIN MENU ----------
    private fun drawMenu(c: Canvas) {
        bg(c)
        // left panel: brand, credits, selected vehicle
        val lx = w * 0.19f
        val lw = w * 0.34f
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 16, 19, 36) }
        c.drawRoundRect(RectF(1.5f * U, 2f * U, w * 0.375f, 98f * U), 2.5f * U, 2.5f * U, panel)
        renderer.drawTextCentered(c, "XENORUSH GP", lx, 13f * U, 8f * U, Color.argb(255, 0, 229, 255), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "CREDITS: ${save.credits} CR", lx, 22f * U, 4.8f * U, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, lw)
        val sel = Catalog.VEHICLES[save.selectedVehicle]
        renderer.drawCarShape(c, lx, 52f * U, minOf(w * 0.09f, 20f * U), sel.color, 0f)
        renderer.drawTextCentered(c, "${sel.name}  •  ${(sel.topSpeed * 0.045).toInt()} km/h", lx, 63f * U, 4.4f * U, Color.WHITE, Paint.Align.CENTER, lw)
        val vs = save.vehicleState(save.selectedVehicle)
        renderer.drawTextCentered(c, "ENG ${vs.e}  THR ${vs.a}  STB ${vs.g}  GUN ${vs.w}", lx, 70f * U, 3.4f * U, Color.argb(210, 160, 170, 210), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "RKT ${vs.r}  MSL ${vs.m}  MINE ${vs.n}", lx, 75f * U, 3.4f * U, Color.argb(210, 160, 170, 210), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, if (online) "ONLINE" else "OFFLINE  -  single player available", lx, 84f * U, 3.4f * U,
            if (online) Color.argb(220, 120, 230, 160) else Color.argb(220, 200, 170, 120), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "© ${CompanyInfo.COMPANY}", lx, 93f * U, 3f * U, Color.argb(150, 130, 140, 180), Paint.Align.CENTER, lw)

        // right panel: RACE button on top, then a 2 x 4 grid
        val ts = save.trackState(selectedTrack)
        val gx0 = w * 0.395f
        val gx1 = w - 2f * U
        val race = RectF(gx0, 3f * U, gx1, 19f * U)
        renderer.drawButton(c, race, "RACE  •  ${Catalog.TRACKS[selectedTrack].name}", Color.argb(255, 40, 60, 150), false, 6f * U)
        buttons.add(race to {
            if (ts.owned) startRace(selectedTrack) else { toast("Track locked - get the Track Pack"); screen = Screen.SHOP; shopTab = 1 }
        })
        val items = listOf(
            Triple("GARAGE", Color.argb(255, 70, 50, 140)) { scroll = 0f; screen = Screen.GARAGE },
            Triple("TRACK SELECT", Color.argb(255, 40, 100, 140)) { scroll = 0f; screen = Screen.TRACKS },
            Triple("XRP SHOP", Color.argb(255, 150, 110, 30)) { scroll = 0f; screen = Screen.SHOP; refreshPrice() },
            Triple("MULTIPLAYER - COMING SOON", Color.argb(255, 70, 60, 70)) { toast("Multiplayer is coming soon") },
            Triple("ACCOUNT / CLOUD SAVE", Color.argb(255, 40, 110, 90)) { accountMsg = ""; screen = Screen.ACCOUNT },
            Triple("UPDATES", Color.argb(255, 40, 90, 120)) { scroll = 0f; screen = Screen.UPDATES },
            Triple("CREDITS", Color.argb(255, 90, 60, 120)) { screen = Screen.CREDITS_INFO },
            Triple("SETTINGS", Color.argb(255, 80, 80, 100)) { screen = Screen.SETTINGS }
        )
        val gap = 2f * U
        val colW = (gx1 - gx0 - gap) / 2f
        val rowH = 16.5f * U
        items.forEachIndexed { i, (label, col, action) ->
            val cx = gx0 + (i % 2) * (colW + gap)
            val cy = 22f * U + (i / 2) * (rowH + gap * 0.6f)
            val r = RectF(cx, cy, cx + colW, cy + rowH)
            renderer.drawButton(c, r, label, col, false, 4.8f * U)
            buttons.add(r to action)
        }
    }

    // ---------- UPDATES ----------
    private fun drawUpdates(c: Canvas) {
        bg(c)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textAlign = Paint.Align.LEFT
        val top = 22f * U
        val x0 = w * 0.08f
        val maxW = w * 0.84f
        var y = top + 4f * U - scroll
        for ((ver, lines) in Changelog.ENTRIES) {
            p.textSize = 5f * U; p.color = Color.argb(255, 255, 214, 90)
            if (y > top + 3f * U && y < h + 6f * U) c.drawText(ver, x0, y, p)
            y += 6.5f * U
            for (ln in lines) {
                p.textSize = 3.8f * U; p.color = Color.argb(230, 210, 220, 245)
                if (y > top + 3f * U && y < h + 6f * U) {
                    val line = "•  $ln"
                    val lw = p.measureText(line)
                    if (lw > maxW) p.textSize *= maxW / lw
                    c.drawText(line, x0 + 2f * U, y, p)
                }
                y += 5.6f * U
            }
            y += 3f * U
        }
        val contentH = y + scroll - top
        scrollMax = (contentH - (h - top)).coerceAtLeast(0f)
        // header drawn last so the list scrolls underneath it
        c.drawRect(0f, 0f, w.toFloat(), top, Paint().apply { color = Color.argb(255, 10, 12, 24) })
        header(c, "UPDATES") { screen = Screen.MENU }
        renderer.drawTextCentered(c, "Installed: v${Changelog.VERSION}", w / 2f, 19.5f * U, 4f * U, Color.argb(255, 0, 229, 255))
        val chk = RectF(w - 2f * U - w * 0.26f, 2.5f * U, w - 2f * U, 13.5f * U)
        renderer.drawButton(c, chk, if (online) "CHECK FOR UPDATES" else "CHECK: N/A OFFLINE",
            if (online) Color.argb(255, 40, 60, 150) else Color.argb(255, 70, 70, 80), false, 4.2f * U)
        buttons.add(chk to { if (online) openStoreListing() else toast("Update check N/A - offline") })
    }

    private fun openStoreListing() {
        val act = activity ?: return
        act.runOnUiThread {
            try {
                act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=" + act.packageName)))
            } catch (_: Exception) {
                try {
                    act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + act.packageName)))
                } catch (_: Exception) { toast("No app store available") }
            }
        }
    }

    // ---------- CREDITS ----------
    private fun drawCreditsInfo(c: Canvas) {
        bg(c)
        header(c, "CREDITS") { screen = Screen.MENU }
        renderer.drawTextCentered(c, "XENORUSH GP", w / 2f, 26f * U, 8f * U, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "v${Changelog.VERSION}", w / 2f, 32.5f * U, 3.4f * U, Color.argb(180, 160, 170, 210))
        renderer.drawTextCentered(c, "Developed and published by", w / 2f, 41f * U, 4f * U, Color.argb(220, 170, 180, 220))
        renderer.drawTextCentered(c, CompanyInfo.COMPANY, w / 2f, 49.5f * U, 7f * U, Color.argb(255, 255, 214, 90))
        renderer.drawTextCentered(c, "Music by ${MusicConfig.ARTIST}", w / 2f, 58f * U, 4.6f * U, Color.argb(255, 0, 229, 255))
        val mail = RectF(w * 0.3f, 62.5f * U, w * 0.7f, 75.5f * U)
        renderer.drawButton(c, mail, "SUPPORT: ${CompanyInfo.SUPPORT_EMAIL}", Color.argb(255, 40, 60, 150), false, 4.6f * U)
        buttons.add(mail to { emailSupport() })
        renderer.drawTextCentered(c, "Tap to email support", w / 2f, 80.5f * U, 3.2f * U, Color.argb(170, 130, 140, 180))
        renderer.drawTextCentered(c, "Payments in XRP on the XRP Ledger", w / 2f, 87f * U, 3.4f * U, Color.argb(180, 160, 170, 210))
        renderer.drawTextCentered(c, "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} ${CompanyInfo.COMPANY}. All rights reserved.", w / 2f, 94f * U, 3.2f * U, Color.argb(160, 130, 140, 180))
    }

    private fun emailSupport() {
        val act = activity ?: return
        act.runOnUiThread {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + CompanyInfo.SUPPORT_EMAIL))
                i.putExtra(android.content.Intent.EXTRA_SUBJECT, "XenoRush GP support (v${Changelog.VERSION})")
                act.startActivity(i)
            } catch (_: Exception) { toast("Email ${CompanyInfo.SUPPORT_EMAIL}") }
        }
    }

    /** Two-column card grid helper for scrolling lists. Returns the card rect for item i (in screen space). */
    private fun gridCard(i: Int, top: Float, cardH: Float): RectF {
        val gap = 2f * U
        val colW = (w - 4f * U - gap) / 2f
        val x = 2f * U + (i % 2) * (colW + gap)
        val y = top + (i / 2) * (cardH + gap) - scroll
        return RectF(x, y, x + colW, y + cardH)
    }

    private fun listHeaderCover(c: Canvas, top: Float) {
        c.drawRect(0f, 0f, w.toFloat(), top - 1f * U, Paint().apply { color = Color.argb(255, 10, 12, 24) })
    }

    // ---------- TRACK SELECT ----------
    private fun drawTracks(c: Canvas) {
        bg(c)
        val top = 17f * U
        val cardH = 19f * U
        val rows = (Catalog.TRACKS.size + 1) / 2
        scrollMax = (rows * (cardH + 2f * U) - (h - top)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, scrollMax)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (t in Catalog.TRACKS) {
            val r = gridCard(t.id, top, cardH)
            if (r.bottom < top || r.top > h) continue
            val ts = save.trackState(t.id)
            val owned = ts.owned
            val col = if (owned) {
                if (t.id == selectedTrack) Color.argb(255, 30, 90, 60) else Color.argb(255, 34, 40, 66)
            } else Color.argb(255, 55, 34, 30)
            renderer.drawButton(c, r, "", col)
            val textW = if (owned) r.width() - 4f * U else r.width() - w * 0.13f - 5f * U
            p.textAlign = Paint.Align.LEFT
            p.color = Color.WHITE
            p.textSize = 4.6f * U
            val title = "${t.id + 1}. ${t.name}  ${"★".repeat(t.difficulty)}"
            p.measureText(title).let { if (it > textW) p.textSize *= textW / it }
            c.drawText(title, r.left + 2.5f * U, r.top + 8f * U, p)
            p.textSize = 3.4f * U
            p.color = if (owned) Color.argb(230, 140, 255, 170) else Color.argb(230, 255, 160, 120)
            val status = when {
                owned && ts.bestLapMs > 0 -> "OWNED  •  best ${fmtTime(ts.bestLapMs)}  •  wins ${ts.wins}"
                owned -> "OWNED  •  tap to race"
                else -> "LOCKED  -  Track Pack ${Pricing.fmtUsd(Pricing.TRACK_PACK_USD_CENTS)}"
            }
            p.measureText(status).let { if (it > textW) p.textSize *= textW / it }
            c.drawText(status, r.left + 2.5f * U, r.top + 14.5f * U, p)
            if (!owned) {
                val br = RectF(r.right - w * 0.13f - 1.5f * U, r.top + 3f * U, r.right - 1.5f * U, r.bottom - 3f * U)
                renderer.drawButton(c, br, "PACK", Color.argb(255, 150, 110, 30), false, 4.4f * U)
                buttons.add(br to { shopTab = 1; scroll = 0f; screen = Screen.SHOP; refreshPrice() })
            } else {
                buttons.add(r to { selectedTrack = t.id; startRace(t.id) })
            }
        }
        listHeaderCover(c, top)
        header(c, "SELECT TRACK") { screen = Screen.MENU }
        renderer.drawTextCentered(c, "Drag to scroll", w - 3f * U, 10f * U, 3.2f * U, Color.argb(150, 130, 140, 180), Paint.Align.RIGHT)
    }

    // ---------- GARAGE ----------
    private fun drawGarage(c: Canvas) {
        bg(c)
        val top = 17f * U
        val cardH = 19f * U
        val rows = (Catalog.VEHICLES.size + 1) / 2
        scrollMax = (rows * (cardH + 2f * U) - (h - top)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, scrollMax)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (v in Catalog.VEHICLES) {
            val r = gridCard(v.id, top, cardH)
            if (r.bottom < top || r.top > h) continue
            val vs = save.vehicleState(v.id)
            val col = when {
                save.selectedVehicle == v.id -> Color.argb(255, 30, 90, 60)
                vs.owned -> Color.argb(255, 34, 40, 66)
                else -> Color.argb(255, 55, 34, 30)
            }
            renderer.drawButton(c, r, "", col)
            renderer.drawCarShape(c, r.left + 10f * U, r.bottom - 5f * U, 6.5f * U, v.color, 0f)
            val tx = r.left + 20f * U
            val textW = r.width() - 20f * U - w * 0.11f
            p.textAlign = Paint.Align.LEFT
            p.textSize = 4.6f * U
            p.color = Color.WHITE
            val title = "${v.id + 1}. ${v.name}"
            p.measureText(title).let { if (it > textW) p.textSize *= textW / it }
            c.drawText(title, tx, r.top + 8f * U, p)
            p.textSize = 3.4f * U
            p.color = Color.argb(210, 170, 180, 220)
            c.drawText("SPD ${(v.topSpeed * 0.045).toInt()} km/h  •  ACC ${v.accel.toInt()}", tx, r.top + 14.5f * U, p)
            val badge = if (vs.owned) (if (save.selectedVehicle == v.id) "SELECTED" else "OWNED") else Pricing.fmtUsd(Pricing.VEHICLE_USD_CENTS)
            p.textAlign = Paint.Align.RIGHT
            p.textSize = 3.8f * U
            p.color = if (vs.owned) Color.argb(230, 140, 255, 170) else Color.argb(230, 255, 160, 120)
            c.drawText(badge, r.right - 2.5f * U, r.centerY() + 1.4f * U, p)
            buttons.add(r to { selectedVehicleForDetail = v.id; scroll = 0f; screen = Screen.VEHICLE })
        }
        listHeaderCover(c, top)
        header(c, "GARAGE") { screen = Screen.MENU }
        renderer.drawTextCentered(c, "Drag to scroll", w - 3f * U, 10f * U, 3.2f * U, Color.argb(150, 130, 140, 180), Paint.Align.RIGHT)
    }

    // ---------- VEHICLE DETAIL + UPGRADES ----------
    private fun drawVehicleDetail(c: Canvas) {
        bg(c)
        val v = Catalog.VEHICLES[selectedVehicleForDetail]
        val vs = save.vehicleState(v.id)
        header(c, v.name) { screen = Screen.GARAGE }

        // left panel: car, damage, credits, actions
        val lx = w * 0.18f
        val lw = w * 0.33f
        renderer.drawCarShape(c, lx, 34f * U, minOf(w * 0.08f, 16f * U), v.color, 0f)
        val dim = Color.argb(230, 170, 180, 220)
        renderer.drawTextCentered(c, "Gun ${Arms.bulletDamage(vs.w).toInt()}hp  •  Rocket ${Arms.rocketDamage(vs.r).toInt()}hp", lx, 43f * U, 3.5f * U, dim, Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "Missile ${Arms.missileDamage(vs.m).toInt()}hp  •  Mine ${Arms.mineDamage(vs.n).toInt()}hp", lx, 48.5f * U, 3.5f * U, dim, Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "CREDITS: ${save.credits} CR", lx, 56f * U, 4.4f * U, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, lw)
        if (vs.owned) {
            renderer.drawTextCentered(c, "Levels 1-5: race credits (win races)", lx, 62f * U, 3.1f * U, Color.argb(210, 255, 214, 90), Paint.Align.CENTER, lw)
            renderer.drawTextCentered(c, "Levels 6-10: ${Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS)} in XRP per level", lx, 66.5f * U, 3.1f * U, Color.argb(210, 255, 214, 90), Paint.Align.CENTER, lw)
        }
        val y2 = 71f * U
        val y3 = 84f * U
        if (vs.owned) {
            val half = (lw - 2f * U) / 2f
            val l0 = lx - lw / 2f
            val sel = RectF(l0, y2, l0 + half, y3)
            renderer.drawButton(c, sel, if (save.selectedVehicle == v.id) "SELECTED" else "SELECT", Color.argb(255, 30, 90, 60), false, 4.6f * U)
            buttons.add(sel to {
                save.selectedVehicle = v.id
                saveManager.save(save)
                toast("${v.name} selected")
            })
            val race = RectF(l0 + half + 2f * U, y2, l0 + lw, y3)
            renderer.drawButton(c, race, "RACE NOW", Color.argb(255, 40, 60, 150), false, 4.6f * U)
            buttons.add(race to {
                save.selectedVehicle = v.id
                saveManager.save(save)
                if (save.trackState(selectedTrack).owned) startRace(selectedTrack)
                else { toast("Track locked"); screen = Screen.TRACKS }
            })
        } else {
            val buy = RectF(lx - lw / 2f, y2, lx + lw / 2f, y3)
            renderer.drawButton(c, buy, "UNLOCK  ${Pricing.label(Pricing.VEHICLE_USD_CENTS, if (online) xrpUsd else null)}", Color.argb(255, 150, 110, 30), false, 4.4f * U)
            buttons.add(buy to { startPayment("v${v.id}") })
        }
        renderer.drawTextCentered(c, "Base: SPD ${(v.topSpeed * 0.045).toInt()}km/h  ACC ${v.accel.toInt()}  GRIP ${String.format("%.2f", v.grip)}  HULL ${Arms.MAX_HULL}  SHIELD ${Arms.MAX_SHIELD}",
            lx, 92f * U, 2.9f * U, Color.argb(200, 160, 170, 210), Paint.Align.CENTER, lw)

        // right panel: 7 upgrade rows
        val upgrades = listOf(
            UpgDef("ENGINE  (top speed)", "e", { vs.e }, { vs.e++ }),
            UpgDef("THRUSTERS  (acceleration)", "a", { vs.a }, { vs.a++ }),
            UpgDef("STABILIZERS  (grip)", "g", { vs.g }, { vs.g++ }),
            UpgDef("GUNS  (bullet dmg 5-15)", "w", { vs.w }, { vs.w++ }),
            UpgDef("ROCKETS  (dmg 20-40)", "r", { vs.r }, { vs.r++ }),
            UpgDef("MISSILES  (dmg 55-75)", "m", { vs.m }, { vs.m++ }),
            UpgDef("MINES  (dmg 40-60)", "n", { vs.n }, { vs.n++ })
        )
        val rx0 = w * 0.36f
        val barW = w * 0.32f
        val rowH = 11.6f * U
        var y = 16f * U
        val pseg = Paint()
        val rowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 18, 21, 40) }
        for (u in upgrades) {
            val lvl = u.get()
            c.drawRoundRect(RectF(rx0 - 1.5f * U, y, w - 1.5f * U, y + rowH - 1.2f * U), 1.5f * U, 1.5f * U, rowBg)
            renderer.drawTextCentered(c, "${u.label}   LVL $lvl/10", rx0, y + 4.4f * U, 3.4f * U, Color.WHITE, Paint.Align.LEFT, barW)
            val by = y + 6.2f * U
            for (i in 0 until 10) {
                // levels 6-10 (paid tier) shown in gold
                pseg.color = if (i < lvl) (if (i < Catalog.FREE_UPGRADE_MAX) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 196, 60))
                             else (if (i < Catalog.FREE_UPGRADE_MAX) Color.argb(255, 40, 45, 70) else Color.argb(255, 70, 58, 36))
                c.drawRect(rx0 + i * barW / 10f, by, rx0 + (i + 1) * barW / 10f - 0.5f * U, by + 2.8f * U, pseg)
            }
            if (vs.owned) {
                if (lvl < Catalog.MAX_UPGRADE) {
                    val r = RectF(w * 0.70f, y + 1.2f * U, w * 0.835f, y + rowH - 2.4f * U)
                    if (Catalog.creditUpgradeAllowed(lvl)) {
                        val cost = Catalog.upgradeCost(v.id, lvl)
                        val can = save.credits >= cost
                        renderer.drawButton(c, r, "$cost CR", if (can) Color.argb(255, 40, 60, 150) else Color.argb(255, 70, 70, 80), false, 3.8f * U)
                    } else {
                        renderer.drawButton(c, r, "PAID ONLY", Color.argb(255, 60, 52, 40), false, 3.6f * U)
                    }
                    buttons.add(r to { tryUpgrade(v.id, u) })
                    val rx = RectF(w * 0.845f, y + 1.2f * U, w - 2.5f * U, y + rowH - 2.4f * U)
                    renderer.drawButton(c, rx, if (online) Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS) else "N/A",
                        if (online) Color.argb(255, 150, 110, 30) else Color.argb(255, 70, 70, 80), false, 3.8f * U)
                    buttons.add(rx to { startPayment(Pricing.upgradeId(v.id, u.stat)) })
                } else {
                    renderer.drawTextCentered(c, "MAX", w * 0.84f, y + 6.6f * U, 4.4f * U, Color.argb(255, 255, 214, 90))
                }
            }
            y += rowH
        }
        if (screen == Screen.VEHICLE) refreshPrice()
    }

    // ---------- SHOP ----------
    private fun drawShop(c: Canvas) {
        bg(c)
        refreshPrice()
        val price = if (online) xrpUsd else null
        val top = 31f * U
        when (shopTab) {
            0 -> {
                val cardH = 12f * U
                val rows = (Catalog.VEHICLES.size + 1) / 2
                scrollMax = (rows * (cardH + 2f * U) - (h - top - 6f * U)).coerceAtLeast(0f)
                scroll = scroll.coerceIn(0f, scrollMax)
                for (v in Catalog.VEHICLES) {
                    val r = gridCard(v.id, top, cardH)
                    if (r.bottom < top || r.top > h) continue
                    val vs = save.vehicleState(v.id)
                    if (!vs.owned && v.id >= Catalog.FREE_VEHICLES) {
                        renderer.drawButton(c, r, "${v.name}  -  ${Pricing.label(Pricing.VEHICLE_USD_CENTS, price)}", Color.argb(255, 55, 42, 28), false, 4f * U)
                        buttons.add(r to { startPayment(Pricing.vehicleId(v.id)) })
                    } else {
                        renderer.drawButton(c, r, "${v.name}  -  ${if (v.id < Catalog.FREE_VEHICLES) "FREE" else "OWNED"}", Color.argb(255, 34, 40, 60), false, 4f * U)
                    }
                }
            }
            1 -> {
                scrollMax = 0f
                val owned = save.hasTrackPack()
                val r = RectF(w * 0.22f, top, w * 0.78f, top + 13f * U)
                renderer.drawButton(c, r, if (owned) "TRACK PACK OWNED" else "BUY TRACK PACK  -  ${Pricing.label(Pricing.TRACK_PACK_USD_CENTS, price)}",
                    if (owned) Color.argb(255, 30, 90, 60) else Color.argb(255, 150, 110, 30), false, 4.6f * U)
                if (!owned) buttons.add(r to { startPayment(Pricing.TRACK_PACK_ID) })
                renderer.drawTextCentered(c, "Unlocks all 25 paid tracks (tracks 6-30)", w / 2f, top + 19f * U, 4f * U, Color.WHITE)
                val paid = Catalog.TRACKS.drop(Catalog.FREE_TRACKS)
                val perCol = 9
                val colW = w * 0.31f
                paid.forEachIndexed { i, t ->
                    val cx = w * 0.035f + (i / perCol) * (colW + w * 0.01f)
                    val ty = top + 26f * U + (i % perCol) * 4.4f * U
                    renderer.drawTextCentered(c, "${t.id + 1}. ${t.name}  ${"*".repeat(t.difficulty)}", cx, ty, 3.2f * U,
                        if (save.trackState(t.id).owned) Color.argb(220, 140, 255, 170) else Color.argb(200, 170, 180, 220), Paint.Align.LEFT, colW)
                }
            }
        }
        listHeaderCover(c, top)
        // bottom note bar (vehicles tab only, tracks tab fills the screen)
        if (shopTab == 0) {
            c.drawRect(0f, h - 6f * U, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(255, 10, 12, 24) })
            renderer.drawTextCentered(c, "Upgrades: ${Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS)} per level in the Garage. Pay from any XRP wallet.", w / 2f, h - 1.8f * U, 3f * U, Color.argb(170, 140, 150, 190))
        }
        header(c, "XRP SHOP") { screen = Screen.MENU }
        val rate = if (!online) "XRP price: N/A (offline)" else xrpUsd?.let { "1 XRP = $" + String.format("%.4f", it) + " (live)" } ?: "XRP price: loading..."
        renderer.drawTextCentered(c, rate + if (!save.mainnet) "   TESTNET" else "", w - 3f * U, 10f * U, 3.4f * U,
            if (online) Color.argb(255, 140, 220, 255) else Color.argb(255, 255, 180, 60), Paint.Align.RIGHT, w * 0.24f)
        val tabs = listOf("VEHICLES  ${Pricing.fmtUsd(Pricing.VEHICLE_USD_CENTS)}", "TRACK PACK  ${Pricing.fmtUsd(Pricing.TRACK_PACK_USD_CENTS)}")
        val tw = w * 0.3f
        tabs.forEachIndexed { i, label ->
            val x = w / 2f - tw - 1f * U + i * (tw + 2f * U)
            val r = RectF(x, 16.5f * U, x + tw, 27.5f * U)
            renderer.drawButton(c, r, label, if (shopTab == i) Color.argb(255, 40, 60, 150) else Color.argb(255, 50, 55, 80), shopTab == i, 4.2f * U)
            buttons.add(r to { shopTab = i; scroll = 0f })
        }
    }

    // ---------- PAYMENT ----------
    private fun drawPay(c: Canvas) {
        bg(c)
        val inv = save.pendingInvoice
        if (inv == null) {
            header(c, "PAYMENT") { screen = Screen.SHOP }
            renderer.drawTextCentered(c, payStatus.ifEmpty { "No active invoice" }, w / 2f, 45f * U, 5f * U, Color.WHITE)
            val r = RectF(w * 0.35f, 56f * U, w * 0.65f, 69f * U)
            renderer.drawButton(c, r, "BACK TO SHOP", Color.argb(255, 60, 70, 110), false, 4.6f * U)
            buttons.add(r to { screen = Screen.SHOP })
            return
        }
        val remain = inv.expiresAt - System.currentTimeMillis()
        header(c, "PAY WITH XRP") { cancelPayment() }

        // QR of destination address (left)
        val qrSize = 66f * U
        val qx = w * 0.19f - qrSize / 2f
        val qy = 20f * U
        try {
            val matrix = QRCodeWriter().encode(save.merchantAddress, BarcodeFormat.QR_CODE, 0, 0)
            val n = matrix.width
            val cell = qrSize / n
            val qp = Paint()
            qp.color = Color.WHITE
            c.drawRect(qx - 2f * U, qy - 2f * U, qx + qrSize + 2f * U, qy + qrSize + 2f * U, qp)
            qp.color = Color.BLACK
            for (mx in 0 until n) for (my in 0 until matrix.height) {
                if (matrix.get(mx, my)) c.drawRect(qx + mx * cell, qy + my * cell, qx + (mx + 1) * cell + 0.5f, qy + (my + 1) * cell + 0.5f, qp)
            }
        } catch (_: Exception) {
        }
        renderer.drawTextCentered(c, "Scan with your XRP wallet", w * 0.19f, 95f * U, 3.2f * U, Color.argb(180, 160, 170, 210))

        // details (right)
        val cx = w * 0.665f
        val cw = w * 0.58f
        val name = Pricing.parse(inv.itemId)?.let { Pricing.displayName(it) } ?: inv.itemId
        renderer.drawTextCentered(c, name, cx, 22f * U, 5f * U, Color.WHITE, Paint.Align.CENTER, cw)
        renderer.drawTextCentered(c, Pricing.fmtXrpShort(inv.amountDrops) + if (inv.usdCents > 0) "  (${Pricing.fmtUsd(inv.usdCents)})" else "", cx, 31f * U, 7f * U, Color.argb(255, 0, 229, 255), Paint.Align.CENTER, cw)
        val sub = (if (save.mainnet) "MAINNET" else "TESTNET (no real value)") + "   •   " +
            (if (remain < 0) "EXPIRED - go back and start again" else "Expires in ${remain / 1000 / 60}:${String.format("%02d", remain / 1000 % 60)}")
        renderer.drawTextCentered(c, sub, cx, 38f * U, 3.6f * U, if (remain < 0) Color.argb(255, 255, 90, 90) else Color.argb(255, 255, 180, 60), Paint.Align.CENTER, cw)
        renderer.drawTextCentered(c, "Send XRP to:", cx, 47f * U, 3.8f * U, Color.WHITE)
        renderer.drawTextCentered(c, save.merchantAddress, cx, 53.5f * U, 4.2f * U, Color.argb(255, 0, 229, 255), Paint.Align.CENTER, cw)
        renderer.drawTextCentered(c, "Destination tag: ${inv.destTag}", cx, 62f * U, 5.4f * U, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, cw)
        renderer.drawTextCentered(c, "Exact amount: ${inv.amountDrops / 1_000_000.0} XRP (${inv.amountDrops} drops)", cx, 69f * U, 3.4f * U, Color.argb(220, 170, 180, 220), Paint.Align.CENTER, cw)

        val chk = RectF(w * 0.39f, 74f * U, w * 0.655f, 87f * U)
        renderer.drawButton(c, chk, if (online) "I'VE PAID - CHECK" else "CHECK: N/A OFFLINE", if (online) Color.argb(255, 30, 110, 80) else Color.argb(255, 70, 70, 80), payChecking, 4.4f * U)
        buttons.add(chk to { if (online) checkInvoiceNow() else payStatus = "Wallet check N/A - offline" })
        val cancel = RectF(w * 0.675f, 74f * U, w * 0.94f, 87f * U)
        renderer.drawButton(c, cancel, "CANCEL", Color.argb(255, 120, 50, 50), false, 4.4f * U)
        buttons.add(cancel to { cancelPayment() })
        renderer.drawTextCentered(c, payStatus, cx, 94f * U, 3.6f * U, Color.argb(255, 255, 230, 140), Paint.Align.CENTER, cw)
    }

    // ---------- SETTINGS ----------
    private fun drawSettings(c: Canvas) {
        bg(c)
        header(c, "SETTINGS") { screen = Screen.MENU }
        val gap = 2f * U
        val colW = (w - 6f * U - gap) / 2f
        val bh = 11.5f * U
        fun cell(i: Int): RectF {
            val x = 3f * U + (i % 2) * (colW + gap)
            val y = 18f * U + (i / 2) * (bh + gap)
            return RectF(x, y, x + colW, y + bh)
        }
        val ts = 4.2f * U
        val net = cell(0)
        renderer.drawButton(c, net, "NETWORK: ${if (save.mainnet) "MAINNET (real XRP)" else "TESTNET (play money)"}", if (save.mainnet) Color.argb(255, 150, 60, 40) else Color.argb(255, 40, 110, 90), false, ts)
        buttons.add(net to {
            if (!Xrp.ALLOW_TESTNET) { save.mainnet = true; toast("Payments use XRP mainnet (locked)"); return@to }
            if (!save.mainnet) toast("Mainnet enabled - real XRP payments") else toast("Testnet enabled")
            save.mainnet = !save.mainnet
            saveManager.save(save)
        })
        val addr = cell(1)
        renderer.drawButton(c, addr, "WALLET: ${if (save.merchantAddress.isBlank()) "NOT SET" else save.merchantAddress.take(10) + "..."} (locked)", Color.argb(255, 50, 55, 90), false, ts)
        buttons.add(addr to { toast("The merchant address is set by the developer in Xrp.kt") })
        val snd = cell(2)
        renderer.drawButton(c, snd, "SFX: ${if (save.sound) "ON" else "OFF"}", Color.argb(255, 60, 70, 110), false, ts)
        buttons.add(snd to { save.sound = !save.sound; saveManager.save(save) })
        val sfxVol = cell(3)
        renderer.drawButton(c, sfxVol, "SFX VOLUME: ${save.sfxVolume}%", Color.argb(255, 50, 70, 120), false, ts)
        buttons.add(sfxVol to { save.sfxVolume = nextVolume(save.sfxVolume); saveManager.save(save) })
        val mus = cell(4)
        renderer.drawButton(c, mus, if (music.songCount == 0) "MUSIC: no songs installed" else "RACE MUSIC: ${if (save.music) "ON" else "OFF"} (${music.songCount} songs)", Color.argb(255, 70, 60, 120), false, ts)
        buttons.add(mus to { save.music = !save.music; saveManager.save(save) })
        val musVol = cell(5)
        renderer.drawButton(c, musVol, "MUSIC VOLUME: ${save.musicVolume}%", Color.argb(255, 80, 55, 120), false, ts)
        buttons.add(musVol to { save.musicVolume = nextVolume(save.musicVolume); saveManager.save(save) })
        val reset = cell(6)
        renderer.drawButton(c, reset, "RESET ALL PROGRESS", Color.argb(255, 140, 40, 40), false, ts)
        buttons.add(reset to { confirmReset() })

        renderer.drawTextCentered(c, "Merchant wallet is developer-set and receives payments.", w / 2f, 78f * U, 3.4f * U, Color.argb(200, 160, 170, 210))
        renderer.drawTextCentered(c, "The game never asks for private keys or seeds.", w / 2f, 84f * U, 3.4f * U, Color.argb(200, 160, 170, 210))
        renderer.drawTextCentered(c, "Entitlements are stored on-device (prototype-grade).", w / 2f, 90f * U, 3.2f * U, Color.argb(160, 140, 150, 190))
    }

    private fun confirmReset() {
        val act = activity ?: return
        act.runOnUiThread {
            AlertDialog.Builder(act)
                .setTitle("Reset everything?")
                .setMessage("Credits, upgrades, unlocks and records will be wiped.")
                .setPositiveButton("RESET") { _, _ ->
                    val fresh = SaveData()
                    save.credits = fresh.credits
                    save.selectedVehicle = 0
                    save.vehicles.clear(); save.tracks.clear()
                    save.usedTxHashes.clear(); save.pendingInvoice = null
                    save.mainnet = !Xrp.ALLOW_TESTNET
                    saveManager.save(save)
                    toast("Progress reset")
                    screen = Screen.MENU
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------- RESULTS ----------
    private fun drawResults(c: Canvas) {
        if (mpLink != null) { drawMpResults(c); return }
        val res = raceResult ?: return
        val r = res.race
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(215, 5, 6, 14) })
        val lx = w * 0.25f
        val lw = w * 0.44f
        renderer.drawTextCentered(c, if (r.player.alive && r.player.finished) "FINISH!" else "DESTROYED", lx, 20f * U, 11f * U,
            if (r.player.alive) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 80, 80), Paint.Align.CENTER, lw)
        val n = r.racers.size
        val rank = if (r.player.alive) r.rank(r.player) else n
        renderer.drawTextCentered(c, "POSITION $rank / $n", lx, 32f * U, 6.5f * U, Color.WHITE, Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "Time ${fmtTime(r.raceTimeMs)}", lx, 40f * U, 4f * U, Color.argb(220, 170, 180, 220), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "REWARD: ${res.credits} CR${if (res.firstWin) "  (+250 first win!)" else ""}", lx, 51f * U, 5.2f * U, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, lw)
        val bestLap = r.player.lapTimes.minOrNull()
        if (bestLap != null) renderer.drawTextCentered(c, "Best lap ${fmtTime(bestLap)}", lx, 58f * U, 3.8f * U, Color.argb(220, 170, 180, 220), Paint.Align.CENTER, lw)

        // standings (right)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 18, 21, 40) }
        c.drawRoundRect(RectF(w * 0.52f, 8f * U, w * 0.97f, 92f * U), 2.5f * U, 2.5f * U, panel)
        renderer.drawTextCentered(c, "STANDINGS", w * 0.745f, 17f * U, 4.6f * U, Color.argb(255, 0, 229, 255))
        val sorted = r.racers.sortedByDescending { it.totalDist }
        var y = 30f * U
        sorted.forEachIndexed { i, rc ->
            val col = if (rc.isPlayer) Color.argb(255, 0, 229, 255) else Color.argb(220, 200, 210, 240)
            renderer.drawTextCentered(c, "${i + 1}. ${if (rc.isPlayer) "YOU" else rc.vehicle.name}", w * 0.55f, y, 4.4f * U, col, Paint.Align.LEFT, w * 0.26f)
            renderer.drawTextCentered(c, if (rc.alive) (if (rc.finished) fmtTime(rc.finishTimeMs) else "racing") else "DNF", w * 0.94f, y, 4.4f * U, col, Paint.Align.RIGHT, w * 0.14f)
            y += 11f * U
        }

        val bw = w * 0.145f
        val gap = w * 0.01f
        val x0 = w * 0.03f
        val by = 70f * U
        val by2 = 84f * U
        val retry = RectF(x0, by, x0 + bw, by2)
        renderer.drawButton(c, retry, "RETRY", Color.argb(255, 40, 60, 150), false, 4.6f * U)
        buttons.add(retry to { startRace(selectedTrack) })
        val tracks = RectF(x0 + bw + gap, by, x0 + 2 * bw + gap, by2)
        renderer.drawButton(c, tracks, "TRACKS", Color.argb(255, 40, 100, 140), false, 4.6f * U)
        buttons.add(tracks to { scroll = 0f; screen = Screen.TRACKS })
        val menu = RectF(x0 + 2 * (bw + gap), by, x0 + 3 * bw + 2 * gap, by2)
        renderer.drawButton(c, menu, "MAIN MENU", Color.argb(255, 80, 80, 100), false, 4.6f * U)
        buttons.add(menu to { screen = Screen.MENU })
    }

    // ---------- PAUSE ----------
    private fun drawPauseOverlay(c: Canvas) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(185, 5, 6, 14) })
        renderer.drawTextCentered(c, "PAUSED", w / 2f, 24f * U, 11f * U, Color.WHITE)
        val bw = w * 0.34f
        val bh = 13f * U
        var y = 33f * U
        val resume = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, resume, "RESUME", Color.argb(255, 30, 110, 80), false, 5f * U)
        buttons.add(resume to { screen = Screen.RACE })
        y += bh + 4f * U
        val restart = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        if (mpLink == null) {
            renderer.drawButton(c, restart, "RESTART", Color.argb(255, 40, 60, 150), false, 5f * U)
            buttons.add(restart to { startRace(selectedTrack) })
        } else renderer.drawTextCentered(c, "Online race keeps running", w / 2f, restart.centerY(), 4f * U, Color.argb(220, 255, 214, 90))
        y += bh + 4f * U
        val quit = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, quit, "QUIT TO MENU", Color.argb(255, 120, 50, 50), false, 5f * U)
        buttons.add(quit to { if (mpLink != null) leaveMultiplayer(); screen = Screen.MENU })
    }

    // ---------- ACCOUNT ----------
    private fun drawAccount(c: Canvas) {
        bg(c)
        header(c, "ACCOUNT") { screen = Screen.MENU }
        val bw = w * 0.44f
        val bh = 12f * U
        var y = 26f * U
        fun line(t: String, col: Int = Color.WHITE, size: Float = 4.2f * U) {
            renderer.drawTextCentered(c, t, w / 2f, y, size, col); y += 7f * U
        }
        if (!online) {
            line("Google sign-in: N/A (offline)", Color.argb(255, 255, 180, 60))
            line("Your progress is saved on this device.", Color.argb(220, 170, 180, 220))
            line("Cloud sync runs next time you are online.", Color.argb(220, 170, 180, 220))
            return
        }
        if (!cloud.available) {
            line("Online features not configured", Color.argb(255, 255, 180, 60))
            line("Developer: add app/google-services.json", Color.argb(220, 170, 180, 220), 3.6f * U)
            line("(Firebase project - see README)", Color.argb(220, 170, 180, 220), 3.6f * U)
            return
        }
        val uid = cloud.uid()
        if (uid == null) {
            line("Sign in to back up your progress and to play multiplayer.", Color.WHITE)
            y += 2f * U
            val r = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
            renderer.drawButton(c, r, if (signingIn) "SIGNING IN..." else "SIGN IN WITH GOOGLE", Color.argb(255, 40, 90, 170), signingIn, 4.6f * U)
            buttons.add(r to {
                val act = activity
                if (act != null && !signingIn) {
                    signingIn = true
                    cloud.signIn(act) { ok, msg -> post { signingIn = false; accountMsg = msg; if (ok) maybeSync(force = true) } }
                }
            })
            y += bh + 9f * U
        } else {
            line("Signed in as ${cloud.displayName()}", Color.argb(255, 140, 255, 170), 4.8f * U)
            line(cloud.email(), Color.argb(220, 170, 180, 220), 3.6f * U)
            val half = (bw - 2f * U) / 2f
            val l0 = (w - bw) / 2f
            val r = RectF(l0, y, l0 + half, y + bh)
            renderer.drawButton(c, r, if (syncing) "SYNCING..." else "SYNC NOW", Color.argb(255, 30, 110, 80), syncing, 4.6f * U)
            buttons.add(r to { maybeSync(force = true) })
            val o = RectF(l0 + half + 2f * U, y, l0 + bw, y + bh)
            renderer.drawButton(c, o, "SIGN OUT", Color.argb(255, 120, 50, 50), false, 4.6f * U)
            buttons.add(o to { leaveMultiplayer(); cloud.signOut(); accountMsg = "Signed out (local save kept)" })
            y += bh + 9f * U
        }
        line(accountMsg, Color.argb(255, 255, 230, 140))
        renderer.drawTextCentered(c, "Purchases and upgrades merge across devices.", w / 2f, 86f * U, 3.4f * U, Color.argb(180, 150, 160, 200))
        renderer.drawTextCentered(c, "Single player never needs an internet connection.", w / 2f, 92f * U, 3.4f * U, Color.argb(180, 150, 160, 200))
    }

    // ---------- MULTIPLAYER LOBBY ----------
    private fun drawLobby(c: Canvas) {
        bg(c)
        header(c, "MULTIPLAYER") { leaveMultiplayer(); screen = Screen.MENU }
        val s = mp
        if (!online) {
            renderer.drawTextCentered(c, "Multiplayer needs an internet connection", w / 2f, 45f * U, 4.6f * U, Color.argb(255, 255, 180, 60))
            return
        }
        renderer.drawTextCentered(c, s?.status ?: "", w / 2f, 23f * U, 4.6f * U, Color.WHITE)
        val dots = ".".repeat(((System.currentTimeMillis() / 400) % 4).toInt())
        if (s?.phase == MpSession.Phase.SEARCHING || s?.phase == MpSession.Phase.LOBBY) {
            renderer.drawTextCentered(c, dots, w / 2f, 29f * U, 6f * U, Color.argb(255, 0, 229, 255))
        }
        val room = s?.room
        var y = 37f * U
        if (room != null) {
            val host = MpRules.host(room)
            for (p in room.players.values.sortedBy { it.joinedAt }) {
                val label = "${p.name}  -  ${Catalog.VEHICLES[p.vehicleId.coerceIn(0, 21)].name}${if (p.uid == host) "  (host)" else ""}${if (p.uid == s.myUid) "  YOU" else ""}"
                renderer.drawTextCentered(c, label, w / 2f, y, 4f * U, if (p.uid == s.myUid) Color.argb(255, 0, 229, 255) else Color.WHITE)
                y += 6f * U
            }
            if (room.players.size >= 2 && room.status == "waiting" && room.secondJoinAt > 0) {
                val left = ((MpRules.FILL_WAIT_MS - (mpBackend!!.serverNow() - room.secondJoinAt)) / 1000).coerceAtLeast(0)
                renderer.drawTextCentered(c, "Starting in ${left}s (or when ${MpRules.MAX_PLAYERS} join)", w / 2f, y + 2f * U, 4f * U, Color.argb(255, 255, 214, 90))
            }
        }
        renderer.drawTextCentered(c, "2-5 players. Host's track is used if everyone owns it, otherwise a random free track.", w / 2f, 75f * U, 3.2f * U, Color.argb(180, 150, 160, 200))
        val bw = w * 0.28f
        if (s?.phase == MpSession.Phase.ERROR) {
            val r = RectF(w / 2f - bw - 1f * U, 80f * U, w / 2f - 1f * U, 92f * U)
            renderer.drawButton(c, r, "TRY AGAIN", Color.argb(255, 40, 60, 150), false, 4.6f * U)
            buttons.add(r to { leaveMultiplayer(); openMultiplayer() })
        }
        val cancel = if (s?.phase == MpSession.Phase.ERROR) RectF(w / 2f + 1f * U, 80f * U, w / 2f + bw + 1f * U, 92f * U)
                     else RectF((w - bw) / 2f, 80f * U, (w + bw) / 2f, 92f * U)
        renderer.drawButton(c, cancel, "CANCEL", Color.argb(255, 120, 50, 50), false, 4.6f * U)
        buttons.add(cancel to { leaveMultiplayer(); screen = Screen.MENU })
    }

    private fun drawMpResults(c: Canvas) {
        val link = mpLink ?: return
        val r = link.race
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(215, 5, 6, 14) })
        val me = r.player
        val lx = w * 0.25f
        val lw = w * 0.44f
        renderer.drawTextCentered(c, if (me.alive && me.finished) "FINISH!" else "DESTROYED", lx, 20f * U, 11f * U,
            if (me.alive) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 80, 80), Paint.Align.CENTER, lw)
        renderer.drawTextCentered(c, "ONLINE RACE  -  ${link.track.def.name}", lx, 31f * U, 4.4f * U, Color.WHITE, Paint.Align.CENTER, lw)
        if (!link.allDone()) renderer.drawTextCentered(c, "Waiting for other racers...", lx, 40f * U, 4f * U, Color.argb(200, 170, 180, 220), Paint.Align.CENTER, lw)
        val res = raceResult
        if (res != null && res.credits > 0) renderer.drawTextCentered(c, "REWARD: ${res.credits} CR", lx, 51f * U, 5.2f * U, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, lw)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 18, 21, 40) }
        c.drawRoundRect(RectF(w * 0.52f, 8f * U, w * 0.97f, 92f * U), 2.5f * U, 2.5f * U, panel)
        renderer.drawTextCentered(c, "STANDINGS", w * 0.745f, 17f * U, 4.6f * U, Color.argb(255, 0, 229, 255))
        var y = 30f * U
        link.standings().forEachIndexed { i, rc ->
            val col = if (rc.isPlayer) Color.argb(255, 0, 229, 255) else Color.argb(220, 210, 220, 245)
            renderer.drawTextCentered(c, "${i + 1}. ${if (rc.isPlayer) "YOU" else rc.displayName}  (${rc.vehicle.name})", w * 0.55f, y, 4f * U, col, Paint.Align.LEFT, w * 0.28f)
            renderer.drawTextCentered(c, when { rc.uid in link.left -> "LEFT"; !rc.alive -> "DNF"; rc.finished -> fmtTime(rc.finishTimeMs); else -> "racing" }, w * 0.94f, y, 4f * U, col, Paint.Align.RIGHT, w * 0.12f)
            y += 11f * U
        }
        val bw = w * 0.21f
        val by = 70f * U
        val again = RectF(w * 0.03f, by, w * 0.03f + bw, by + 14f * U)
        renderer.drawButton(c, again, "NEW MATCH", Color.argb(255, 150, 40, 90), false, 4.6f * U)
        buttons.add(again to { leaveMultiplayer(); openMultiplayer() })
        val menu = RectF(w * 0.04f + bw, by, w * 0.04f + 2 * bw, by + 14f * U)
        renderer.drawButton(c, menu, "MAIN MENU", Color.argb(255, 80, 80, 100), false, 4.6f * U)
        buttons.add(menu to { leaveMultiplayer(); screen = Screen.MENU })
    }

    /** Upgrade purchase: re-checks the CURRENT level and credits at execution time (cap 10). */
    private fun tryUpgrade(vehicleId: Int, u: UpgDef) {
        val lvl = u.get()
        if (lvl >= Catalog.MAX_UPGRADE) {
            toast("Already at max level")
            return
        }
        if (!Catalog.creditUpgradeAllowed(lvl)) {
            toast("Levels 6-10 are paid unlocks (${Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS)} in XRP each)")
            return
        }
        val cost = Catalog.upgradeCost(vehicleId, lvl)
        if (save.credits < cost) {
            toast("Not enough credits - win races to earn CR")
            return
        }
        save.credits -= cost
        u.inc()
        persist()
        beep(90, 120)
    }

    // ---------------- input ----------------

    class TouchEvt(val action: Int, val pid: Int, val x: Float, val y: Float)

    private val touchQueue = java.util.concurrent.ConcurrentLinkedQueue<TouchEvt>()

    /** Called from the Android UI thread - just queues a copy, never touches game state. */
    fun queueTouch(action: Int, pid: Int, x: Float, y: Float) {
        touchQueue.add(TouchEvt(action, pid, x, y))
    }

    private fun drainTouch() {
        while (true) {
            val e = touchQueue.poll() ?: break
            when (screen) {
                Screen.RACE, Screen.PAUSED, Screen.RESULTS -> handleRaceTouch(e)
                else -> handleMenuTouch(e)
            }
        }
    }

    private fun handleMenuTouch(e: TouchEvt) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = e.y
                dragStartScroll = scroll
                dragged = false
                lastTouchDown = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - dragStartY) > 24f) dragged = true
                if (dragged) scroll = dragStartScroll - (e.y - dragStartY)
                when (screen) {
                    Screen.TRACKS, Screen.GARAGE, Screen.UPDATES, Screen.SHOP -> scroll = scroll.coerceIn(0f, scrollMax)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && System.currentTimeMillis() - lastTouchDown < 400) {
                    for ((rect, action) in buttons) {
                        if (rect.contains(e.x, e.y)) { action(); break }
                    }
                }
            }
        }
    }

    private val pointerButtons = HashMap<Int, MutableSet<String>>()

    private fun handleRaceTouch(e: TouchEvt) {
        val L = Renderer.CtrlLayout(w, h)
        fun inCircle(x: Float, y: Float, b: FloatArray) = ((x - b[0]) * (x - b[0]) + (y - b[1]) * (y - b[1])) < b[2] * b[2]
        fun press(pid: Int, x: Float, y: Float) {
            val set = pointerButtons.getOrPut(pid) { mutableSetOf() }
            fun put(name: String) { set.add(name); setBtn(name, true) }
            if (inCircle(x, y, L.left)) put("left")
            if (inCircle(x, y, L.right)) put("right")
            if (inCircle(x, y, L.gas)) put("gas")
            if (inCircle(x, y, L.brake)) put("brake")
            if (inCircle(x, y, L.gun)) put("gun")
            if (inCircle(x, y, L.special)) put("special")
            if (inCircle(x, y, L.cycle)) put("cycle")
        }
        fun release(pid: Int) {
            pointerButtons.remove(pid)?.forEach { setBtn(it, false) }
        }

        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (screen == Screen.RACE) {
                    if (Renderer.CtrlLayout(w, h).pause.contains(e.x, e.y)) { screen = Screen.PAUSED; return }
                    press(e.pid, e.x, e.y)
                } else {
                    for ((rect, action) in buttons) {
                        if (rect.contains(e.x, e.y)) { action(); break }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> release(e.pid)
            MotionEvent.ACTION_UP -> {
                release(e.pid)
                pointerButtons.keys.toList().forEach { release(it) }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerButtons.keys.toList().forEach { release(it) }
            }
        }
    }

    private fun setBtn(name: String, v: Boolean) {
        when (name) {
            "left" -> input.left = v
            "right" -> input.right = v
            "gas" -> input.gas = v
            "brake" -> input.brake = v
            "gun" -> raceUi.gunPressed = v
            "special" -> raceUi.specialPressed = v
            "cycle" -> raceUi.cyclePressed = v
        }
    }

    fun releaseAllControls() {
        input.left = false; input.right = false; input.gas = false; input.brake = false
        raceUi.gunPressed = false; raceUi.specialPressed = false; raceUi.cyclePressed = false
    }

    // ---------------- sound ----------------

    /** Cycles 100 -> 75 -> 50 -> 25 -> 0 -> 100. */
    private fun nextVolume(current: Int): Int = when {
        current <= 0 -> 100
        current <= 25 -> 0
        current <= 50 -> 25
        current <= 75 -> 50
        else -> 75
    }

    fun beep(volume: Int, durationMs: Int) {
        if (!save.sound || save.sfxVolume <= 0) return
        val v = (volume * save.sfxVolume / 100).coerceIn(1, 100)
        try {
            if (tone == null || toneVol != v) {
                try { tone?.release() } catch (_: Exception) {}
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, v)
                toneVol = v
            }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, durationMs)
        } catch (_: Exception) {
        }
    }
}

data class RaceResult(val race: Race, val credits: Int, val firstWin: Boolean)
