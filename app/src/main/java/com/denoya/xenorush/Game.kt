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
    const val VERSION = "1.4.0"
    val ENTRIES = listOf(
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
        m.onSongStart = { title -> toast("NOW PLAYING: $title - ${MusicConfig.ARTIST}") }
    }
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
        syncMusic(dt)
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
            link.publish()
            if (r.finished && screen != Screen.RESULTS) finishMpRace()
            return
        }
        if (screen == Screen.RACE) {
            val r = race ?: return
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
            if (r.finished) {
                finishRace()
            }
        }
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
            if (r.fireSecondary(p)) beep(60, 90) else r.events.add("NO ${Arms.short(p.secondary)} LEFT")
        }
        lastSpecial = raceUi.specialPressed
        raceUi.secondaryLabel = "${Arms.short(p.secondary)} ${p.count(p.secondary)}"
    }

    // ---------------- race lifecycle ----------------

    /** Music only plays while a race is actually running (after GO). */
    private fun syncMusic(dt: Float) {
        val r = mpLink?.race ?: race
        val want = when (screen) {
            Screen.RACE -> if (r != null && r.countdown <= 0f && !r.finished) MusicWant.PLAY else MusicWant.STOP
            Screen.PAUSED -> MusicWant.PAUSE
            else -> MusicWant.STOP
        }
        music.update(want, save.music, dt)
    }

    /** App sent to background: the game loop stops, so pause music directly. */
    fun onAppPause() = music.pause()

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

    private fun bg(c: Canvas, color: Int = Color.argb(255, 10, 12, 24)) {
        val p = Paint()
        p.color = color
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun header(c: Canvas, title: String, back: (() -> Unit)? = null) {
        renderer.drawTextCentered(c, title, w / 2f, h * 0.055f, h * 0.032f, Color.WHITE)
        if (back != null) {
            val r = RectF(w * 0.03f, h * 0.025f, w * 0.3f, h * 0.075f)
            renderer.drawButton(c, r, "◀ BACK", Color.argb(255, 60, 70, 110))
            buttons.add(r to back)
        }
    }

    private fun drawToast(c: Canvas) {
        if (toast.isNotEmpty() && System.currentTimeMillis() < toastUntil) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.argb(200, 20, 24, 40)
            val tw = p.measureText(toast)
            c.drawRect(w * 0.1f, h * 0.86f, w * 0.9f, h * 0.86f + h * 0.05f, p)
            renderer.drawTextCentered(c, toast, w / 2f, h * 0.895f, h * 0.024f, Color.argb(255, 255, 230, 140))
        }
    }

    fun toast(msg: String) {
        toast = msg
        toastUntil = System.currentTimeMillis() + 2600
    }

    // ---------- TITLE ----------
    private fun drawTitle(c: Canvas) {
        bg(c)
        // animated road backdrop
        val t = titleTime
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(255, 8, 10, 26)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.5f, p)
        val gp = Paint()
        gp.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.5f, Color.argb(255, 26, 16, 66), Color.argb(255, 8, 10, 26), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.5f, gp)
        for (i in 0 until 14) {
            val yy = h * 0.5f - ((i * 40 + t * 220) % (h * 0.5f)).toInt()
            val ww = (i + 1) * 6f
            p.color = Color.argb(200, 0, (200 + i * 4).coerceAtMost(255), (150 + i * 7).coerceAtMost(255))
            c.drawLine(w * 0.5f - ww, yy, w * 0.5f - ww * 0.3f, yy, p)
            c.drawLine(w * 0.5f + ww * 0.3f, yy, w * 0.5f + ww, yy, p)
        }
        renderer.drawTextCentered(c, "XENORUSH", w / 2f, h * 0.2f, h * 0.09f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "G P", w / 2f, h * 0.28f, h * 0.07f, Color.argb(255, 255, 60, 100))
        renderer.drawTextCentered(c, "FUTURISTIC COMBAT RACING", w / 2f, h * 0.35f, h * 0.022f, Color.argb(200, 160, 170, 210))
        val r = RectF(w * 0.25f, h * 0.62f, w * 0.75f, h * 0.70f)
        renderer.drawButton(c, r, "TAP TO START", Color.argb(255, 40, 60, 140), (System.currentTimeMillis() / 600) % 2 == 0L, h * 0.03f)
        buttons.add(r to { screen = Screen.MENU })
        renderer.drawTextCentered(c, "v${Changelog.VERSION}  •  single player works offline", w / 2f, h * 0.95f, h * 0.018f, Color.argb(140, 130, 140, 180))
    }

    // ---------- MAIN MENU ----------
    private fun drawMenu(c: Canvas) {
        bg(c)
        renderer.drawTextCentered(c, "XENORUSH GP", w / 2f, h * 0.08f, h * 0.05f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "CREDITS: ${save.credits} CR", w / 2f, h * 0.13f, h * 0.026f, Color.argb(255, 255, 214, 90))

        val bw = w * 0.8f
        val rowH = h * 0.062f
        var y = h * 0.16f
        val sel = Catalog.VEHICLES[save.selectedVehicle]
        val ts = save.trackState(selectedTrack)
        val items = listOf(
            Triple("RACE  •  ${Catalog.TRACKS[selectedTrack].name}", Color.argb(255, 40, 60, 150)) {
                if (ts.owned) startRace(selectedTrack) else { toast("Track locked - get the Track Pack"); screen = Screen.SHOP; shopTab = 1 }
            },
            Triple("MULTIPLAYER  -  COMING SOON", Color.argb(255, 70, 60, 70)) { toast("Multiplayer is coming soon") },
            Triple("GARAGE", Color.argb(255, 70, 50, 140)) { screen = Screen.GARAGE },
            Triple("TRACK SELECT", Color.argb(255, 40, 100, 140)) { screen = Screen.TRACKS },
            Triple("XRP SHOP", Color.argb(255, 150, 110, 30)) { screen = Screen.SHOP; refreshPrice() },
            Triple("ACCOUNT  /  CLOUD SAVE", Color.argb(255, 40, 110, 90)) { accountMsg = ""; screen = Screen.ACCOUNT },
            Triple("UPDATES", Color.argb(255, 40, 90, 120)) { scroll = 0f; screen = Screen.UPDATES },
            Triple("CREDITS", Color.argb(255, 90, 60, 120)) { screen = Screen.CREDITS_INFO },
            Triple("SETTINGS", Color.argb(255, 80, 80, 100)) { screen = Screen.SETTINGS }
        )
        for ((label, col, action) in items) {
            val r = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + rowH * 0.8f)
            renderer.drawButton(c, r, label, col)
            buttons.add(r to action)
            y += rowH
        }
        renderer.drawTextCentered(c, if (online) "ONLINE" else "OFFLINE  -  single player available", w / 2f, y + h * 0.01f, h * 0.018f,
            if (online) Color.argb(200, 120, 230, 160) else Color.argb(200, 200, 170, 120))
        // current vehicle preview
        renderer.drawCarShape(c, w / 2f, h * 0.81f, w * 0.12f, sel.color, 0f)
        renderer.drawTextCentered(c, "${sel.name}  •  ${(sel.topSpeed * 0.045).toInt()} km/h", w / 2f, h * 0.865f, h * 0.024f, Color.WHITE)
        val vs = save.vehicleState(save.selectedVehicle)
        renderer.drawTextCentered(c, "ENG ${vs.e}  THR ${vs.a}  STB ${vs.g}  GUN ${vs.w}  RKT ${vs.r}  MSL ${vs.m}  MINE ${vs.n}", w / 2f, h * 0.9f, h * 0.019f, Color.argb(200, 160, 170, 210))
        renderer.drawTextCentered(c, "© ${CompanyInfo.COMPANY}", w / 2f, h * 0.94f, h * 0.017f, Color.argb(140, 130, 140, 180))
    }

    // ---------- UPDATES ----------
    private fun drawUpdates(c: Canvas) {
        bg(c)
        header(c, "UPDATES") { screen = Screen.MENU }
        renderer.drawTextCentered(c, "Installed: v${Changelog.VERSION}", w / 2f, h * 0.11f, h * 0.022f, Color.argb(255, 0, 229, 255))
        val chk = RectF(w * 0.2f, h * 0.13f, w * 0.8f, h * 0.18f)
        renderer.drawButton(c, chk, if (online) "CHECK FOR UPDATES" else "CHECK FOR UPDATES  -  N/A offline",
            if (online) Color.argb(255, 40, 60, 150) else Color.argb(255, 70, 70, 80), false, h * 0.02f)
        buttons.add(chk to { if (online) openStoreListing() else toast("Update check N/A - offline") })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textAlign = Paint.Align.LEFT
        var y = h * 0.23f - scroll
        for ((ver, lines) in Changelog.ENTRIES) {
            p.textSize = h * 0.026f; p.color = Color.argb(255, 255, 214, 90)
            if (y > h * 0.19f && y < h) c.drawText(ver, w * 0.06f, y, p)
            y += h * 0.038f
            p.textSize = h * 0.02f; p.color = Color.argb(230, 210, 220, 245)
            for (ln in lines) {
                if (y > h * 0.19f && y < h) {
                    val line = "•  $ln"
                    val ts = p.textSize; val mw = w * 0.9f; val lw = p.measureText(line)
                    if (lw > mw) p.textSize = ts * mw / lw
                    c.drawText(line, w * 0.08f, y, p); p.textSize = ts
                }
                y += h * 0.032f
            }
            y += h * 0.02f
        }
        updatesContentH = y + scroll - h * 0.23f
    }
    private var updatesContentH = 0f

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
        renderer.drawTextCentered(c, "XENORUSH GP", w / 2f, h * 0.18f, h * 0.045f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "v${Changelog.VERSION}", w / 2f, h * 0.22f, h * 0.02f, Color.argb(180, 160, 170, 210))
        renderer.drawTextCentered(c, "Developed and published by", w / 2f, h * 0.31f, h * 0.022f, Color.argb(220, 170, 180, 220))
        renderer.drawTextCentered(c, CompanyInfo.COMPANY, w / 2f, h * 0.36f, h * 0.036f, Color.argb(255, 255, 214, 90))
        renderer.drawTextCentered(c, "Support", w / 2f, h * 0.47f, h * 0.022f, Color.argb(220, 170, 180, 220))
        val mail = RectF(w * 0.12f, h * 0.49f, w * 0.88f, h * 0.55f)
        renderer.drawButton(c, mail, CompanyInfo.SUPPORT_EMAIL, Color.argb(255, 40, 60, 150), false, h * 0.024f)
        buttons.add(mail to { emailSupport() })
        renderer.drawTextCentered(c, "Tap to email support", w / 2f, h * 0.58f, h * 0.018f, Color.argb(160, 130, 140, 180))
        renderer.drawTextCentered(c, "Music by ${MusicConfig.ARTIST}", w / 2f, h * 0.64f, h * 0.024f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "Payments in XRP on the XRP Ledger", w / 2f, h * 0.70f, h * 0.019f, Color.argb(180, 160, 170, 210))
        renderer.drawTextCentered(c, "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} ${CompanyInfo.COMPANY}. All rights reserved.", w / 2f, h * 0.92f, h * 0.018f, Color.argb(160, 130, 140, 180))
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

    // ---------- TRACK SELECT ----------
    private fun drawTracks(c: Canvas) {
        bg(c)
        header(c, "SELECT TRACK") { screen = Screen.MENU }
        val rowH = h * 0.085f
        val top = h * 0.1f
        val bottomPad = h * 0.06f
        val contentH = 30 * rowH
        scroll = scroll.coerceIn(0f, (contentH - (h - top - bottomPad)).coerceAtLeast(0f))
        var y = top - scroll
        for (t in Catalog.TRACKS) {
            val ts = save.trackState(t.id)
            if (y > -rowH && y < h) {
                val r = RectF(w * 0.04f, y + 4, w * 0.96f, y + rowH - 4)
                val owned = ts.owned
                val col = if (owned) {
                    if (t.id == selectedTrack) Color.argb(255, 30, 90, 60) else Color.argb(255, 34, 40, 66)
                } else Color.argb(255, 55, 34, 30)
                renderer.drawButton(c, r, "", col)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.textSize = rowH * 0.3f
                p.color = Color.WHITE
                p.textAlign = Paint.Align.LEFT
                c.drawText("${t.id + 1}. ${t.name}  ${"★".repeat(t.difficulty)}", r.left + 20f, r.centerY() - 6f, p)
                p.textSize = rowH * 0.24f
                p.color = if (owned) Color.argb(230, 140, 255, 170) else Color.argb(230, 255, 160, 120)
                val status = when {
                    owned && ts.bestLapMs > 0 -> "OWNED  best ${fmtTime(ts.bestLapMs)}  wins ${ts.wins}"
                    owned -> "OWNED"
                    else -> "LOCKED  -  in Track Pack ${Pricing.fmtUsd(Pricing.TRACK_PACK_USD_CENTS)}"
                }
                c.drawText(status, r.left + 20f, r.centerY() + rowH * 0.22f, p)
                if (!owned) {
                    val br = RectF(r.right - w * 0.2f, r.top + 6f, r.right - 10f, r.bottom - 6f)
                    renderer.drawButton(c, br, "PACK", Color.argb(255, 150, 110, 30), false, rowH * 0.26f)
                    buttons.add(br to { shopTab = 1; scroll = 0f; screen = Screen.SHOP; refreshPrice() })
                } else {
                    buttons.add(r to { selectedTrack = t.id; startRace(t.id) })
                }
            }
            y += rowH
        }
        renderer.drawTextCentered(c, "Drag to scroll", w / 2f, h * 0.965f, h * 0.018f, Color.argb(140, 130, 140, 180))
    }

    // ---------- GARAGE ----------
    private fun drawGarage(c: Canvas) {
        bg(c)
        header(c, "GARAGE") { screen = Screen.MENU }
        val rowH = h * 0.085f
        val top = h * 0.1f
        val bottomPad = h * 0.06f
        val contentH = 22 * rowH
        scroll = scroll.coerceIn(0f, (contentH - (h - top - bottomPad)).coerceAtLeast(0f))
        var y = top - scroll
        for (v in Catalog.VEHICLES) {
            val vs = save.vehicleState(v.id)
            if (y > -rowH && y < h) {
                val r = RectF(w * 0.04f, y + 4, w * 0.96f, y + rowH - 4)
                val col = when {
                    save.selectedVehicle == v.id -> Color.argb(255, 30, 90, 60)
                    vs.owned -> Color.argb(255, 34, 40, 66)
                    else -> Color.argb(255, 55, 34, 30)
                }
                renderer.drawButton(c, r, "", col)
                renderer.drawCarShape(c, r.left + rowH * 0.55f, r.bottom - rowH * 0.18f, rowH * 0.3f, v.color, 0f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.textSize = rowH * 0.28f
                p.color = Color.WHITE
                p.textAlign = Paint.Align.LEFT
                c.drawText("${v.id + 1}. ${v.name}", r.left + rowH * 1.2f, r.top + rowH * 0.38f, p)
                p.textSize = rowH * 0.22f
                p.color = Color.argb(210, 170, 180, 220)
                c.drawText("SPD ${(v.topSpeed * 0.045).toInt()}km/h  ACC ${v.accel.toInt()}", r.left + rowH * 1.2f, r.top + rowH * 0.66f, p)
                val badge = if (vs.owned) (if (save.selectedVehicle == v.id) "SELECTED" else "OWNED") else Pricing.fmtUsd(Pricing.VEHICLE_USD_CENTS)
                val p2 = Paint(Paint.ANTI_ALIAS_FLAG)
                p2.textSize = rowH * 0.24f
                p2.textAlign = Paint.Align.RIGHT
                p2.color = if (vs.owned) Color.argb(230, 140, 255, 170) else Color.argb(230, 255, 160, 120)
                c.drawText(badge, r.right - 14f, r.centerY() + rowH * 0.09f, p2)
                buttons.add(r to { selectedVehicleForDetail = v.id; scroll = 0f; screen = Screen.VEHICLE })
            }
            y += rowH
        }
        renderer.drawTextCentered(c, "Drag to scroll", w / 2f, h * 0.965f, h * 0.018f, Color.argb(140, 130, 140, 180))
    }

    // ---------- VEHICLE DETAIL + UPGRADES ----------
    private fun drawVehicleDetail(c: Canvas) {
        bg(c)
        val v = Catalog.VEHICLES[selectedVehicleForDetail]
        val vs = save.vehicleState(v.id)
        header(c, v.name) { screen = Screen.GARAGE }
        renderer.drawCarShape(c, w / 2f, h * 0.17f, w * 0.2f, v.color, 0f)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = h * 0.022f
        p.color = Color.argb(230, 170, 180, 220)
        p.textAlign = Paint.Align.CENTER
        p.textSize = h * 0.019f
        val dmgLine = "Gun ${Arms.bulletDamage(vs.w).toInt()}hp  •  Rocket ${Arms.rocketDamage(vs.r).toInt()}hp  •  Missile ${Arms.missileDamage(vs.m).toInt()}hp  •  Mine ${Arms.mineDamage(vs.n).toInt()}hp"
        p.measureText(dmgLine).let { if (it > w * 0.94f) p.textSize *= w * 0.94f / it }
        c.drawText("Gun ${Arms.bulletDamage(vs.w).toInt()}hp  •  Rocket ${Arms.rocketDamage(vs.r).toInt()}hp  •  Missile ${Arms.missileDamage(vs.m).toInt()}hp  •  Mine ${Arms.mineDamage(vs.n).toInt()}hp", w / 2f, h * 0.20f, p)
        p.textSize = h * 0.022f
        c.drawText("CREDITS: ${save.credits} CR", w / 2f, h * 0.23f, p)

        // stat bars with upgrade buttons
        val upgrades = listOf(
            UpgDef("ENGINE  (top speed)", "e", { vs.e }, { vs.e++ }),
            UpgDef("THRUSTERS  (acceleration)", "a", { vs.a }, { vs.a++ }),
            UpgDef("STABILIZERS  (grip)", "g", { vs.g }, { vs.g++ }),
            UpgDef("GUNS  (bullet dmg 5-15)", "w", { vs.w }, { vs.w++ }),
            UpgDef("ROCKETS  (dmg 20-40)", "r", { vs.r }, { vs.r++ }),
            UpgDef("MISSILES  (dmg 55-75)", "m", { vs.m }, { vs.m++ }),
            UpgDef("MINES  (dmg 40-60)", "n", { vs.n }, { vs.n++ })
        )
        var y = h * 0.265f
        for (u in upgrades) {
            val label = u.label
            val lvl = u.get()
            renderer.drawTextCentered(c, "$label   LVL $lvl/10", w * 0.04f, y, h * 0.018f, Color.WHITE, Paint.Align.LEFT)
            // 10 segment bar
            val barW = w * 0.5f
            val bx = w * 0.04f
            val by = y + h * 0.016f
            for (i in 0 until 10) {
                val pseg = Paint()
                // levels 6-10 (paid tier) shown in gold
                pseg.color = if (i < lvl) (if (i < Catalog.FREE_UPGRADE_MAX) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 196, 60))
                             else (if (i < Catalog.FREE_UPGRADE_MAX) Color.argb(255, 40, 45, 70) else Color.argb(255, 70, 58, 36))
                c.drawRect(bx + i * barW / 10f, by, bx + (i + 1) * barW / 10f - 3f, by + h * 0.016f, pseg)
            }
            if (vs.owned) {
                if (lvl < Catalog.MAX_UPGRADE) {
                    val r = RectF(w * 0.56f, by - h * 0.01f, w * 0.75f, by + h * 0.028f)
                    if (Catalog.creditUpgradeAllowed(lvl)) {
                        val cost = Catalog.upgradeCost(v.id, lvl)
                        val can = save.credits >= cost
                        renderer.drawButton(c, r, "$cost CR", if (can) Color.argb(255, 40, 60, 150) else Color.argb(255, 70, 70, 80), false, h * 0.017f)
                    } else {
                        renderer.drawButton(c, r, "PAID ONLY", Color.argb(255, 60, 52, 40), false, h * 0.016f)
                    }
                    buttons.add(r to { tryUpgrade(v.id, u) })
                    val rx = RectF(w * 0.77f, by - h * 0.01f, w * 0.96f, by + h * 0.028f)
                    renderer.drawButton(c, rx, if (online) Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS) else "N/A",
                        if (online) Color.argb(255, 150, 110, 30) else Color.argb(255, 70, 70, 80), false, h * 0.017f)
                    buttons.add(rx to { startPayment(Pricing.upgradeId(v.id, u.stat)) })
                } else {
                    renderer.drawTextCentered(c, "MAX", w * 0.76f, by + h * 0.02f, h * 0.02f, Color.argb(255, 255, 214, 90))
                }
            }
            y += h * 0.055f
        }

        // bottom actions
        val btnW = w * 0.44f
        val y2 = h * 0.66f
        if (vs.owned) {
            val sel = RectF(w * 0.04f, y2, w * 0.04f + btnW, y2 + h * 0.06f)
            renderer.drawButton(c, sel, if (save.selectedVehicle == v.id) "SELECTED" else "SELECT", Color.argb(255, 30, 90, 60))
            buttons.add(sel to {
                save.selectedVehicle = v.id
                saveManager.save(save)
                toast("${v.name} selected")
            })
            val race = RectF(w * 0.52f, y2, w * 0.52f + btnW, y2 + h * 0.06f)
            renderer.drawButton(c, race, "RACE NOW", Color.argb(255, 40, 60, 150))
            buttons.add(race to {
                save.selectedVehicle = v.id
                saveManager.save(save)
                if (save.trackState(selectedTrack).owned) startRace(selectedTrack)
                else { toast("Track locked"); screen = Screen.TRACKS }
            })
        } else {
            val buy = RectF(w * 0.04f, y2, w * 0.96f, y2 + h * 0.06f)
            renderer.drawButton(c, buy, "UNLOCK  ${Pricing.label(Pricing.VEHICLE_USD_CENTS, if (online) xrpUsd else null)}", Color.argb(255, 150, 110, 30), false, h * 0.022f)
            buttons.add(buy to { startPayment("v${v.id}") })
        }
        if (vs.owned) {
            renderer.drawTextCentered(c, "Levels 1-5: race credits (win races)  •  Levels 6-10: paid", w / 2f, h * 0.745f, h * 0.018f, Color.argb(200, 255, 214, 90))
            renderer.drawTextCentered(c, "${Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS)} in XRP per level (any level)", w / 2f, h * 0.772f, h * 0.018f, Color.argb(200, 255, 214, 90))
        }
        if (screen == Screen.VEHICLE) refreshPrice()
        // stats summary
        renderer.drawTextCentered(c, "Base: SPD ${(v.topSpeed * 0.045).toInt()}km/h  ACC ${v.accel.toInt()}  GRIP ${String.format("%.2f", v.grip)}  HULL ${Arms.MAX_HULL}  SHIELD ${Arms.MAX_SHIELD}", w / 2f, h * 0.81f, h * 0.019f, Color.argb(200, 160, 170, 210))
    }

    // ---------- SHOP ----------
    private fun drawShop(c: Canvas) {
        bg(c)
        header(c, "XRP SHOP") { screen = Screen.MENU }
        refreshPrice()
        val rate = if (!online) "XRP price: N/A (offline)" else xrpUsd?.let { "1 XRP = $" + String.format("%.4f", it) + " (live)" } ?: "XRP price: loading..."
        renderer.drawTextCentered(c, rate + if (!save.mainnet) "   TESTNET" else "", w / 2f, h * 0.08f, h * 0.019f,
            if (online) Color.argb(255, 140, 220, 255) else Color.argb(255, 255, 180, 60))
        val tabs = listOf("VEHICLES  ${Pricing.fmtUsd(Pricing.VEHICLE_USD_CENTS)}", "TRACK PACK  ${Pricing.fmtUsd(Pricing.TRACK_PACK_USD_CENTS)}")
        val tw = w * 0.46f
        tabs.forEachIndexed { i, label ->
            val r = RectF(w * 0.03f + i * (tw + w * 0.02f), h * 0.095f, w * 0.03f + i * (tw + w * 0.02f) + tw, h * 0.14f)
            renderer.drawButton(c, r, label, if (shopTab == i) Color.argb(255, 40, 60, 150) else Color.argb(255, 50, 55, 80), shopTab == i, h * 0.02f)
            buttons.add(r to { shopTab = i; scroll = 0f })
        }
        val price = if (online) xrpUsd else null
        val rowH = h * 0.075f
        val top = h * 0.16f
        var y = top - scroll
        when (shopTab) {
            0 -> for (v in Catalog.VEHICLES) {
                val vs = save.vehicleState(v.id)
                if (y > -rowH && y < h) {
                    val r = RectF(w * 0.04f, y + 3, w * 0.96f, y + rowH - 3)
                    if (!vs.owned && v.id >= Catalog.FREE_VEHICLES) {
                        renderer.drawButton(c, r, "${v.name}  -  ${Pricing.label(Pricing.VEHICLE_USD_CENTS, price)}", Color.argb(255, 55, 42, 28), false, h * 0.021f)
                        buttons.add(r to { startPayment(Pricing.vehicleId(v.id)) })
                    } else {
                        renderer.drawButton(c, r, "${v.name}  -  ${if (v.id < Catalog.FREE_VEHICLES) "FREE" else "OWNED"}", Color.argb(255, 34, 40, 60))
                    }
                }
                y += rowH
            }
            1 -> {
                val owned = save.hasTrackPack()
                val r = RectF(w * 0.06f, h * 0.2f, w * 0.94f, h * 0.29f)
                renderer.drawButton(c, r, if (owned) "TRACK PACK OWNED" else "BUY TRACK PACK  -  ${Pricing.label(Pricing.TRACK_PACK_USD_CENTS, price)}",
                    if (owned) Color.argb(255, 30, 90, 60) else Color.argb(255, 150, 110, 30), false, h * 0.022f)
                if (!owned) buttons.add(r to { startPayment(Pricing.TRACK_PACK_ID) })
                renderer.drawTextCentered(c, "Unlocks all 25 paid tracks (tracks 6-30)", w / 2f, h * 0.33f, h * 0.022f, Color.WHITE)
                var ty = h * 0.38f
                for (t in Catalog.TRACKS.drop(Catalog.FREE_TRACKS)) {
                    renderer.drawTextCentered(c, "${t.id + 1}. ${t.name}  ${"*".repeat(t.difficulty)}", w / 2f, ty, h * 0.018f,
                        if (save.trackState(t.id).owned) Color.argb(220, 140, 255, 170) else Color.argb(200, 170, 180, 220))
                    ty += h * 0.021f
                }
            }
        }
        renderer.drawTextCentered(c, "Upgrades: ${Pricing.fmtUsd(Pricing.UPGRADE_USD_CENTS)} per level in Garage. Pay from any XRP wallet.", w / 2f, h * 0.94f, h * 0.018f, Color.argb(160, 140, 150, 190))
    }

    // ---------- PAYMENT ----------
    private fun drawPay(c: Canvas) {
        bg(c)
        val inv = save.pendingInvoice
        if (inv == null) {
            header(c, "PAYMENT") { screen = Screen.SHOP }
            renderer.drawTextCentered(c, payStatus.ifEmpty { "No active invoice" }, w / 2f, h * 0.4f, h * 0.026f, Color.WHITE)
            val r = RectF(w * 0.25f, h * 0.55f, w * 0.75f, h * 0.62f)
            renderer.drawButton(c, r, "BACK TO SHOP", Color.argb(255, 60, 70, 110))
            buttons.add(r to { screen = Screen.SHOP })
            return
        }
        val remain = inv.expiresAt - System.currentTimeMillis()
        header(c, "PAY WITH XRP") { cancelPayment() }

        val name = Pricing.parse(inv.itemId)?.let { Pricing.displayName(it) } ?: inv.itemId
        renderer.drawTextCentered(c, name, w / 2f, h * 0.12f, h * 0.03f, Color.WHITE)
        renderer.drawTextCentered(c, Pricing.fmtXrpShort(inv.amountDrops) + if (inv.usdCents > 0) "  (${Pricing.fmtUsd(inv.usdCents)})" else "", w / 2f, h * 0.17f, h * 0.04f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, if (save.mainnet) "MAINNET" else "TESTNET (no real value)", w / 2f, h * 0.205f, h * 0.02f, Color.argb(255, 255, 180, 60))
        if (remain < 0) {
            renderer.drawTextCentered(c, "EXPIRED - go back and start again", w / 2f, h * 0.24f, h * 0.024f, Color.argb(255, 255, 90, 90))
        } else {
            renderer.drawTextCentered(c, "Expires in ${remain / 1000 / 60}:${String.format("%02d", remain / 1000 % 60)}", w / 2f, h * 0.24f, h * 0.022f, Color.argb(200, 160, 170, 210))
        }

        // QR of destination address
        val qrSize = (w * 0.4f).toInt()
        try {
            val matrix = QRCodeWriter().encode(save.merchantAddress, BarcodeFormat.QR_CODE, qrSize, qrSize)
            val qp = Paint()
            val off = (w - qrSize) / 2f
            val qy = h * 0.27f
            qp.color = Color.WHITE
            c.drawRect(off - 12, qy - 12, off + qrSize + 12f, qy + qrSize + 12f, qp)
            qp.color = Color.BLACK
            for (qx in 0 until qrSize) {
                for (qy2 in 0 until qrSize) {
                    if (matrix.get(qx, qy2)) c.drawRect(off + qx, qy + qy2, off + qx + 1f, qy + qy2 + 1f, qp)
                }
            }
        } catch (_: Exception) {
        }
        renderer.drawTextCentered(c, "Send XRP to:", w / 2f, h * 0.72f, h * 0.022f, Color.WHITE)
        renderer.drawTextCentered(c, save.merchantAddress, w / 2f, h * 0.75f, h * 0.02f, Color.argb(255, 0, 229, 255))
        renderer.drawTextCentered(c, "Destination tag: ${inv.destTag}", w / 2f, h * 0.79f, h * 0.026f, Color.argb(255, 255, 214, 90))
        renderer.drawTextCentered(c, "Exact amount: ${inv.amountDrops / 1_000_000.0} XRP (${inv.amountDrops} drops)", w / 2f, h * 0.825f, h * 0.02f, Color.argb(220, 170, 180, 220))

        val chk = RectF(w * 0.08f, h * 0.86f, w * 0.52f, h * 0.92f)
        renderer.drawButton(c, chk, if (online) "I'VE PAID - CHECK" else "CHECK: N/A OFFLINE", if (online) Color.argb(255, 30, 110, 80) else Color.argb(255, 70, 70, 80), payChecking, h * 0.02f)
        buttons.add(chk to { if (online) checkInvoiceNow() else payStatus = "Wallet check N/A - offline" })
        val cancel = RectF(w * 0.56f, h * 0.86f, w * 0.92f, h * 0.92f)
        renderer.drawButton(c, cancel, "CANCEL", Color.argb(255, 120, 50, 50))
        buttons.add(cancel to { cancelPayment() })
        renderer.drawTextCentered(c, payStatus, w / 2f, h * 0.95f, h * 0.02f, Color.argb(255, 255, 230, 140))
    }

    // ---------- SETTINGS ----------
    private fun drawSettings(c: Canvas) {
        bg(c)
        header(c, "SETTINGS") { screen = Screen.MENU }
        var y = h * 0.15f
        val bw = w * 0.84f
        val bh = h * 0.065f

        val net = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, net, "NETWORK: ${if (save.mainnet) "MAINNET (real XRP)" else "TESTNET (play money)"}", if (save.mainnet) Color.argb(255, 150, 60, 40) else Color.argb(255, 40, 110, 90))
        buttons.add(net to {
            if (!Xrp.ALLOW_TESTNET) { save.mainnet = true; toast("Payments use XRP mainnet (locked)"); return@to }
            if (!save.mainnet) toast("Mainnet enabled - real XRP payments") else toast("Testnet enabled")
            save.mainnet = !save.mainnet
            saveManager.save(save)
        })
        y += bh * 1.3f

        val addr = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, addr, "WALLET: ${if (save.merchantAddress.isBlank()) "NOT SET" else save.merchantAddress.take(10) + "..."} (locked)", Color.argb(255, 50, 55, 90))
        buttons.add(addr to { toast("The merchant address is set by the developer in Xrp.kt") })
        y += bh * 1.3f

        val snd = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, snd, "SOUND: ${if (save.sound) "ON" else "OFF"}", Color.argb(255, 60, 70, 110))
        buttons.add(snd to { save.sound = !save.sound; saveManager.save(save) })
        y += bh * 1.3f

        val mus = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, mus, if (music.songCount == 0) "MUSIC: no songs installed" else "RACE MUSIC: ${if (save.music) "ON" else "OFF"}  (${music.songCount} songs)", Color.argb(255, 70, 60, 120))
        buttons.add(mus to { save.music = !save.music; saveManager.save(save) })
        y += bh * 1.3f

        val reset = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
        renderer.drawButton(c, reset, "RESET ALL PROGRESS", Color.argb(255, 140, 40, 40))
        buttons.add(reset to { confirmReset() })

        renderer.drawTextCentered(c, "Merchant wallet (developer-set, receives payments)", w / 2f, h * 0.78f, h * 0.02f, Color.argb(200, 160, 170, 210))
        renderer.drawTextCentered(c, "The game never asks for private keys or seeds.", w / 2f, h * 0.82f, h * 0.02f, Color.argb(200, 160, 170, 210))
        renderer.drawTextCentered(c, "Entitlements are stored on-device (prototype-grade).", w / 2f, h * 0.86f, h * 0.018f, Color.argb(160, 140, 150, 190))
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
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(200, 5, 6, 14) })
        renderer.drawTextCentered(c, if (r.player.alive && r.player.finished) "FINISH!" else "DESTROYED", w / 2f, h * 0.1f, h * 0.055f, if (r.player.alive) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 80, 80))
        val rank = if (r.player.alive) r.rank(r.player) else 8
        renderer.drawTextCentered(c, "POSITION $rank / 8", w / 2f, h * 0.16f, h * 0.034f, Color.WHITE)
        renderer.drawTextCentered(c, "Time ${fmtTime(r.raceTimeMs)}", w / 2f, h * 0.2f, h * 0.024f, Color.argb(220, 170, 180, 220))

        // standings
        val sorted = r.racers.sortedByDescending { it.totalDist }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = h * 0.022f
        var y = h * 0.26f
        sorted.forEachIndexed { i, rc ->
            p.textAlign = Paint.Align.LEFT
            p.color = if (rc.isPlayer) Color.argb(255, 0, 229, 255) else Color.argb(210, 200, 210, 240)
            val nm = "${i + 1}. ${if (rc.isPlayer) "YOU" else rc.vehicle.name}"
            c.drawText(nm, w * 0.12f, y, p)
            p.textAlign = Paint.Align.RIGHT
            c.drawText(if (rc.alive) (if (rc.finished) fmtTime(rc.finishTimeMs) else "racing") else "DNF", w * 0.88f, y, p)
            y += h * 0.036f
        }

        renderer.drawTextCentered(c, "REWARD: ${res.credits} CR${if (res.firstWin) "  (+250 first win!)" else ""}", w / 2f, y + h * 0.03f, h * 0.03f, Color.argb(255, 255, 214, 90))
        val bestLap = r.player.lapTimes.minOrNull()
        if (bestLap != null) renderer.drawTextCentered(c, "Best lap ${fmtTime(bestLap)}", w / 2f, y + h * 0.08f, h * 0.022f, Color.argb(220, 170, 180, 220))

        val bw = w * 0.42f
        val by = h * 0.78f
        val retry = RectF(w * 0.04f, by, w * 0.04f + bw, by + h * 0.06f)
        renderer.drawButton(c, retry, "RETRY", Color.argb(255, 40, 60, 150))
        buttons.add(retry to { startRace(selectedTrack) })
        val tracks = RectF(w * 0.54f, by, w * 0.54f + bw, by + h * 0.06f)
        renderer.drawButton(c, tracks, "TRACKS", Color.argb(255, 40, 100, 140))
        buttons.add(tracks to { screen = Screen.TRACKS })
        val menu = RectF(w * 0.29f, by + h * 0.075f, w * 0.71f, by + h * 0.135f)
        renderer.drawButton(c, menu, "MAIN MENU", Color.argb(255, 80, 80, 100))
        buttons.add(menu to { screen = Screen.MENU })
    }

    // ---------- PAUSE ----------
    private fun drawPauseOverlay(c: Canvas) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(180, 5, 6, 14) })
        renderer.drawTextCentered(c, "PAUSED", w / 2f, h * 0.3f, h * 0.06f, Color.WHITE)
        val bw = w * 0.6f
        var y = h * 0.42f
        val resume = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + h * 0.06f)
        renderer.drawButton(c, resume, "RESUME", Color.argb(255, 30, 110, 80))
        buttons.add(resume to { screen = Screen.RACE })
        y += h * 0.08f
        val restart = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + h * 0.06f)
        if (mpLink == null) {
            renderer.drawButton(c, restart, "RESTART", Color.argb(255, 40, 60, 150))
            buttons.add(restart to { startRace(selectedTrack) })
        } else renderer.drawTextCentered(c, "Online race keeps running", w / 2f, restart.centerY(), h * 0.022f, Color.argb(220, 255, 214, 90))
        y += h * 0.08f
        val quit = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + h * 0.06f)
        renderer.drawButton(c, quit, "QUIT TO MENU", Color.argb(255, 120, 50, 50))
        buttons.add(quit to { if (mpLink != null) leaveMultiplayer(); screen = Screen.MENU })
    }

    // ---------- ACCOUNT ----------
    private fun drawAccount(c: Canvas) {
        bg(c)
        header(c, "ACCOUNT") { screen = Screen.MENU }
        val bw = w * 0.84f
        val bh = h * 0.065f
        var y = h * 0.14f
        fun line(t: String, col: Int = Color.WHITE, size: Float = h * 0.022f) {
            renderer.drawTextCentered(c, t, w / 2f, y, size, col); y += h * 0.038f
        }
        if (!online) {
            line("Google sign-in: N/A (offline)", Color.argb(255, 255, 180, 60))
            line("Your progress is saved on this device.", Color.argb(220, 170, 180, 220))
            line("Cloud sync runs next time you are online.", Color.argb(220, 170, 180, 220))
            return
        }
        if (!cloud.available) {
            line("Online features not configured", Color.argb(255, 255, 180, 60))
            line("Developer: add app/google-services.json", Color.argb(220, 170, 180, 220), h * 0.019f)
            line("(Firebase project - see README)", Color.argb(220, 170, 180, 220), h * 0.019f)
            return
        }
        val uid = cloud.uid()
        if (uid == null) {
            line("Sign in to back up your progress", Color.WHITE)
            line("and to play multiplayer.", Color.WHITE)
            y += h * 0.02f
            val r = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
            renderer.drawButton(c, r, if (signingIn) "SIGNING IN..." else "SIGN IN WITH GOOGLE", Color.argb(255, 40, 90, 170), signingIn)
            buttons.add(r to {
                val act = activity
                if (act != null && !signingIn) {
                    signingIn = true
                    cloud.signIn(act) { ok, msg -> post { signingIn = false; accountMsg = msg; if (ok) maybeSync(force = true) } }
                }
            })
            y += bh * 1.5f
        } else {
            line("Signed in as ${cloud.displayName()}", Color.argb(255, 140, 255, 170), h * 0.026f)
            line(cloud.email(), Color.argb(220, 170, 180, 220), h * 0.019f)
            y += h * 0.02f
            val r = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
            renderer.drawButton(c, r, if (syncing) "SYNCING..." else "SYNC CLOUD SAVE NOW", Color.argb(255, 30, 110, 80), syncing)
            buttons.add(r to { maybeSync(force = true) })
            y += bh * 1.3f
            val o = RectF((w - bw) / 2f, y, (w + bw) / 2f, y + bh)
            renderer.drawButton(c, o, "SIGN OUT", Color.argb(255, 120, 50, 50))
            buttons.add(o to { leaveMultiplayer(); cloud.signOut(); accountMsg = "Signed out (local save kept)" })
            y += bh * 1.5f
        }
        line(accountMsg, Color.argb(255, 255, 230, 140))
        renderer.drawTextCentered(c, "Purchases and upgrades merge across devices.", w / 2f, h * 0.86f, h * 0.019f, Color.argb(180, 150, 160, 200))
        renderer.drawTextCentered(c, "Single player never needs an internet connection.", w / 2f, h * 0.89f, h * 0.019f, Color.argb(180, 150, 160, 200))
    }

    // ---------- MULTIPLAYER LOBBY ----------
    private fun drawLobby(c: Canvas) {
        bg(c)
        header(c, "MULTIPLAYER") { leaveMultiplayer(); screen = Screen.MENU }
        val s = mp
        if (!online) {
            renderer.drawTextCentered(c, "Multiplayer needs an internet connection", w / 2f, h * 0.4f, h * 0.024f, Color.argb(255, 255, 180, 60))
            return
        }
        renderer.drawTextCentered(c, s?.status ?: "", w / 2f, h * 0.16f, h * 0.026f, Color.WHITE)
        val dots = ".".repeat(((System.currentTimeMillis() / 400) % 4).toInt())
        if (s?.phase == MpSession.Phase.SEARCHING || s?.phase == MpSession.Phase.LOBBY) {
            renderer.drawTextCentered(c, dots, w / 2f, h * 0.2f, h * 0.04f, Color.argb(255, 0, 229, 255))
        }
        val room = s?.room
        var y = h * 0.28f
        if (room != null) {
            val host = MpRules.host(room)
            for (p in room.players.values.sortedBy { it.joinedAt }) {
                val label = "${p.name}  -  ${Catalog.VEHICLES[p.vehicleId.coerceIn(0, 21)].name}${if (p.uid == host) "  (host)" else ""}${if (p.uid == s.myUid) "  YOU" else ""}"
                renderer.drawTextCentered(c, label, w / 2f, y, h * 0.023f, if (p.uid == s.myUid) Color.argb(255, 0, 229, 255) else Color.WHITE)
                y += h * 0.045f
            }
            if (room.players.size >= 2 && room.status == "waiting" && room.secondJoinAt > 0) {
                val left = ((MpRules.FILL_WAIT_MS - (mpBackend!!.serverNow() - room.secondJoinAt)) / 1000).coerceAtLeast(0)
                renderer.drawTextCentered(c, "Starting in ${left}s (or when 4 join)", w / 2f, y + h * 0.02f, h * 0.022f, Color.argb(255, 255, 214, 90))
            }
        }
        renderer.drawTextCentered(c, "2-5 players. Host's track is used if everyone owns it,", w / 2f, h * 0.7f, h * 0.018f, Color.argb(180, 150, 160, 200))
        renderer.drawTextCentered(c, "otherwise a random free track.", w / 2f, h * 0.725f, h * 0.018f, Color.argb(180, 150, 160, 200))
        val bw = w * 0.6f
        if (s?.phase == MpSession.Phase.ERROR) {
            val r = RectF((w - bw) / 2f, h * 0.76f, (w + bw) / 2f, h * 0.82f)
            renderer.drawButton(c, r, "TRY AGAIN", Color.argb(255, 40, 60, 150))
            buttons.add(r to { leaveMultiplayer(); openMultiplayer() })
        }
        val cancel = RectF((w - bw) / 2f, h * 0.84f, (w + bw) / 2f, h * 0.9f)
        renderer.drawButton(c, cancel, "CANCEL", Color.argb(255, 120, 50, 50))
        buttons.add(cancel to { leaveMultiplayer(); screen = Screen.MENU })
    }

    private fun drawMpResults(c: Canvas) {
        val link = mpLink ?: return
        val r = link.race
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(200, 5, 6, 14) })
        val me = r.player
        renderer.drawTextCentered(c, if (me.alive && me.finished) "FINISH!" else "DESTROYED", w / 2f, h * 0.1f, h * 0.055f,
            if (me.alive) Color.argb(255, 0, 229, 255) else Color.argb(255, 255, 80, 80))
        val st = link.standings()
        renderer.drawTextCentered(c, "ONLINE RACE  -  ${link.track.def.name}", w / 2f, h * 0.16f, h * 0.024f, Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = h * 0.026f
        var y = h * 0.24f
        st.forEachIndexed { i, rc ->
            p.textAlign = Paint.Align.LEFT
            p.color = if (rc.isPlayer) Color.argb(255, 0, 229, 255) else Color.argb(220, 210, 220, 245)
            c.drawText("${i + 1}. ${if (rc.isPlayer) "YOU" else rc.displayName}  (${rc.vehicle.name})", w * 0.08f, y, p)
            p.textAlign = Paint.Align.RIGHT
            c.drawText(when { rc.uid in link.left -> "LEFT"; !rc.alive -> "DNF"; rc.finished -> fmtTime(rc.finishTimeMs); else -> "racing" }, w * 0.92f, y, p)
            y += h * 0.045f
        }
        if (!link.allDone()) renderer.drawTextCentered(c, "Waiting for other racers...", w / 2f, y + h * 0.02f, h * 0.022f, Color.argb(200, 170, 180, 220))
        val res = raceResult
        if (res != null && res.credits > 0) renderer.drawTextCentered(c, "REWARD: ${res.credits} CR", w / 2f, y + h * 0.07f, h * 0.03f, Color.argb(255, 255, 214, 90))
        val bw = w * 0.42f
        val by = h * 0.8f
        val again = RectF(w * 0.04f, by, w * 0.04f + bw, by + h * 0.06f)
        renderer.drawButton(c, again, "NEW MATCH", Color.argb(255, 150, 40, 90))
        buttons.add(again to { leaveMultiplayer(); openMultiplayer() })
        val menu = RectF(w * 0.54f, by, w * 0.54f + bw, by + h * 0.06f)
        renderer.drawButton(c, menu, "MAIN MENU", Color.argb(255, 80, 80, 100))
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
                    Screen.TRACKS -> scroll = scroll.coerceIn(0f, (30 * h * 0.085f - h * 0.84f).coerceAtLeast(0f))
                    Screen.GARAGE -> scroll = scroll.coerceIn(0f, (22 * h * 0.085f - h * 0.84f).coerceAtLeast(0f))
                    Screen.UPDATES -> scroll = scroll.coerceIn(0f, (updatesContentH - h * 0.72f).coerceAtLeast(0f))
                    Screen.SHOP -> {
                        val rows = if (shopTab == 0) 22f else 0f
                        scroll = scroll.coerceIn(0f, (rows * h * 0.075f - h * 0.8f).coerceAtLeast(0f))
                    }
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
                    if (e.x > w * 0.75f && e.y < h * 0.12f) { screen = Screen.PAUSED; return }
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

    fun beep(volume: Int, durationMs: Int) {
        if (!save.sound) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, durationMs)
        } catch (_: Exception) {
        }
    }
}

data class RaceResult(val race: Race, val credits: Int, val firstWin: Boolean)
