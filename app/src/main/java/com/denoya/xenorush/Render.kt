package com.denoya.xenorush

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin

class Renderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastW = -1
    private var lastH = -1

    private fun ensureShaders(w: Int, h: Int, def: TrackDef?) {
        if (w == lastW && h == lastH && skyPaint.shader != null) return
        val d = def ?: Catalog.TRACKS[0]
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, d.skyTop, d.skyBottom, Shader.TileMode.CLAMP)
        lastW = w; lastH = h
    }

    fun drawRace(c: Canvas, race: Race, track: Track, input: PlayerInput, ui: RaceUi) {
        val w = c.width
        val h = c.height
        ensureShaders(w, h, track.def)
        val player = race.player

        // camera
        val baseSegment = track.segAt(player.z)
        val basePercent = (player.z % C.SEG_LEN) / C.SEG_LEN
        val playerY = lerp(baseSegment.p1.worldY, baseSegment.p2.worldY, basePercent)
        val camX = player.x * C.ROAD_WIDTH
        val camY = C.CAM_HEIGHT + playerY
        val camZ = player.z - C.PLAYER_Z

        // shake
        var shakeX = 0f
        var shakeY = 0f
        if (race.shakeT > 0f) {
            race.shakeT -= 1f / 60f
            shakeX = (Math.random().toFloat() - 0.5f) * 18f * race.shakeT * 4f
            shakeY = (Math.random().toFloat() - 0.5f) * 14f * race.shakeT * 4f
        }
        c.save()
        c.translate(shakeX, shakeY)

        drawSky(c, w, h, track)
        drawGroundBase(c, w, h, track)

        // road
        var maxy = h.toFloat()
        var x = 0f
        var dx = -(baseSegment.curve * basePercent)
        val camDepth = C.CAM_DEPTH

        for (n in 0 until C.DRAW_DIST) {
            val seg = track.segments[(baseSegment.index + n) % track.size]
            val looped = seg.index < baseSegment.index
            val camZoff = camZ - (if (looped) track.trackLength.toFloat() else 0f)
            project(seg.p1, camX - x, camY, camZoff, camDepth, w, h)
            project(seg.p2, camX - x - dx, camY, camZoff, camDepth, w, h)
            x += dx
            dx += seg.curve

            seg.clip = maxy

            if (seg.p1.cameraZ <= camDepth || seg.p2.screenY >= seg.p1.screenY || seg.p2.screenY >= maxy) {
                continue
            }
            drawSegment(c, seg, track, w)
            maxy = seg.p2.screenY
        }

        // sprites & cars back-to-front
        for (n in (C.DRAW_DIST - 1) downTo 0) {
            val segIdx = (baseSegment.index + n) % track.size
            val seg = track.segments[segIdx]
            val clipY = seg.clip
            // power-ups
            for (s in race.spritesAt(seg)) {
                if (race.isCollected(seg.index, s.type)) continue
                drawPowerUp(c, seg, s, w, clipY)
            }
            // mines
            for (m in race.mines) {
                if (!m.alive) continue
                val mIdx = ((m.z / C.SEG_LEN).toInt() % track.size + track.size) % track.size
                if (mIdx == segIdx) drawMine(c, seg, m.x, w, clipY)
            }
            // AI racers
            for (r in race.racers) {
                if (r.isPlayer || !r.alive) continue
                val rIdx = ((r.z / C.SEG_LEN).toInt() % track.size + track.size) % track.size
                if (rIdx == segIdx) drawCar(c, seg, r, w, clipY)
            }
            // projectiles
            for (p in race.projectiles) {
                if (!p.alive) continue
                val pIdx = ((p.z / C.SEG_LEN).toInt() % track.size + track.size) % track.size
                if (pIdx == segIdx) drawProjectile(c, seg, p, w, clipY)
            }
        }

        drawPlayerCar(c, w, h, player, input)
        drawProjectilesNear(c, race, w, h, camX, camY, camZ, camDepth)
        c.restore()

        drawHud(c, race, w, h, ui)
        drawMirror(c, race, track, mirrorRect(w, h))
    }

    private fun project(p: Point, camX: Float, camY: Float, camZ: Float, camDepth: Float, w: Int, h: Int) {
        p.cameraX = p.worldX - camX
        p.cameraY = p.worldY - camY
        p.cameraZ = p.worldZ - camZ
        p.screenScale = camDepth / p.cameraZ
        p.screenX = w / 2f + p.screenScale * p.cameraX * w / 2f
        p.screenY = h / 2f - p.screenScale * p.cameraY * h / 2f
        p.screenW = p.screenScale * C.ROAD_WIDTH * w / 2f
    }

    private fun drawSky(c: Canvas, w: Int, h: Int, track: Track) {
        c.drawRect(0f, 0f, w.toFloat(), h * 0.56f, skyPaint)
        // sun/planet glow
        paint.color = Color.argb(90, 255, 255, 255)
        val sr = minOf(w, h).toFloat()
        c.drawCircle(w * 0.5f, h * 0.34f, sr * 0.16f, paint)
        paint.color = Color.argb(160, 255, 240, 200)
        c.drawCircle(w * 0.5f, h * 0.34f, sr * 0.09f, paint)
        // stars
        paint.color = Color.argb(120, 255, 255, 255)
        val starRng = kotlin.random.Random(track.def.seed)
        for (i in 0 until 40) {
            val sx = starRng.nextFloat() * w
            val sy = starRng.nextFloat() * h * 0.2f
            c.drawCircle(sx, sy, 1.4f, paint)
        }
    }

    private fun drawGroundBase(c: Canvas, w: Int, h: Int, track: Track) {
        paint.color = Color.argb(255, 8, 9, 16)
        c.drawRect(0f, h * 0.5f, w.toFloat(), h.toFloat(), paint)
        paint.color = Color.argb(40, Color.red(track.def.neon), Color.green(track.def.neon), Color.blue(track.def.neon))
        c.drawRect(0f, h * 0.5f, w.toFloat(), h * 0.53f, paint)
    }

    private fun drawSegment(c: Canvas, seg: Segment, track: Track, w: Int) {
        val p1 = seg.p1
        val p2 = seg.p2
        // ground strip (void)
        paint.color = if (seg.light) Color.argb(255, 10, 11, 20) else Color.argb(255, 8, 9, 16)
        c.drawRect(0f, p2.screenY, w.toFloat(), p1.screenY + 1f, paint)

        // road half-width on screen: |x| = 1 (physics road edge) maps to screenW
        val l1 = p1.screenW
        val l2 = p2.screenW

        // rumble strips just outside the road edges
        val r1 = l1 / 6f
        val r2 = l2 / 6f
        paint.color = if (seg.light) Color.argb(255, 230, 230, 240) else Color.argb(255, 90, 95, 130)
        quad(c, p1.screenX - l1 - r1, p1.screenY, p1.screenX - l1, p1.screenY, p2.screenX - l2, p2.screenY, p2.screenX - l2 - r2, p2.screenY, paint.color)
        quad(c, p1.screenX + l1, p1.screenY, p1.screenX + l1 + r1, p1.screenY, p2.screenX + l2 + r2, p2.screenY, p2.screenX + l2, p2.screenY, paint.color)

        // road
        val road = if (seg.light) track.def.roadLight else track.def.roadDark
        quad(c, p1.screenX - l1, p1.screenY, p1.screenX + l1, p1.screenY, p2.screenX + l2, p2.screenY, p2.screenX - l2, p2.screenY, road)

        // lane markers on light segments
        if (seg.light) {
            val lw1 = l1 / 24f
            val lw2 = l2 / 24f
            val lanes = C.LANES
            for (lane in 1 until lanes) {
                val off = -1f + 2f * lane / lanes
                val lx1 = p1.screenX + l1 * off
                val lx2 = p2.screenX + l2 * off
                quad(c, lx1 - lw1, p1.screenY, lx1 + lw1, p1.screenY, lx2 + lw2, p2.screenY, lx2 - lw2, p2.screenY, Color.argb(160, 255, 255, 255))
            }
        }

        // start/finish line
        if (seg.index < 4) {
            val cw = l1 / 8f
            var sx = p1.screenX - l1
            var i = 0
            while (sx < p1.screenX + l1) {
                paint.color = if (i % 2 == 0) Color.WHITE else Color.BLACK
                quad(c, sx, p1.screenY, sx + cw, p1.screenY, sx + cw + (l2 / l1) * cw, p2.screenY, sx + (l2 / l1) * cw, p2.screenY, paint.color)
                sx += cw
                i++
            }
        }
    }

    private val quadPath = android.graphics.Path()

    private fun quad(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, color: Int) {
        paint.color = color
        quadPath.reset()
        quadPath.moveTo(x1, y1)
        quadPath.lineTo(x2, y2)
        quadPath.lineTo(x3, y3)
        quadPath.lineTo(x4, y4)
        quadPath.close()
        c.drawPath(quadPath, paint)
    }

    private fun drawPowerUp(c: Canvas, seg: Segment, s: TrackSprite, w: Int, clipY: Float) {
        val scale = seg.p1.screenScale
        val screenX = seg.p1.screenX + scale * s.x * C.ROAD_WIDTH * w / 2f
        val screenY = seg.p1.screenY
        val r = scale * C.ROAD_WIDTH * w / 2f * 0.045f
        if (screenY > clipY || r < 1.5f || screenX < -50 || screenX > w + 50) return
        val col = when (s.type) {
            PowerUpType.SHIELD -> Color.argb(255, 80, 190, 255)
            PowerUpType.REFLECT -> Color.argb(255, 200, 130, 255)
            PowerUpType.SPEED -> Color.argb(255, 90, 255, 170)
            PowerUpType.REPAIR -> Color.argb(255, 255, 210, 60)
            PowerUpType.AMMO -> Color.argb(255, 255, 110, 110)
            PowerUpType.MISSILE -> Color.argb(255, 255, 150, 40)
            PowerUpType.MINES -> Color.argb(255, 230, 60, 60)
        }
        // glow
        paint.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(screenX, screenY, r * 2.4f, paint)
        paint.color = col
        c.drawCircle(screenX, screenY, r, paint)
        paint.color = Color.WHITE
        paint.textSize = r * 1.1f
        val letter = when (s.type) {
            PowerUpType.SHIELD -> "S"
            PowerUpType.REFLECT -> "R"
            PowerUpType.SPEED -> "B"
            PowerUpType.REPAIR -> "+"
            PowerUpType.AMMO -> "A"
            PowerUpType.MISSILE -> "M"
            PowerUpType.MINES -> "X"
        }
        val tw = paint.measureText(letter)
        c.drawText(letter, screenX - tw / 2, screenY + r * 0.4f, paint)
    }

    private fun drawMine(c: Canvas, seg: Segment, laneX: Float, w: Int, clipY: Float) {
        val scale = seg.p1.screenScale
        val screenX = seg.p1.screenX + scale * laneX * C.ROAD_WIDTH * w / 2f
        val screenY = seg.p1.screenY
        val r = scale * C.ROAD_WIDTH * w / 2f * 0.03f
        if (screenY > clipY || r < 1.5f) return
        paint.color = Color.argb(70, 255, 60, 60)
        c.drawCircle(screenX, screenY, r * 2.5f, paint)
        paint.color = Color.argb(255, 200, 40, 40)
        c.drawCircle(screenX, screenY, r, paint)
    }

    private fun drawCar(c: Canvas, seg: Segment, r: Racer, w: Int, clipY: Float) {
        val scale = seg.p1.screenScale
        val screenX = seg.p1.screenX + scale * r.x * C.ROAD_WIDTH * w / 2f
        val screenY = seg.p1.screenY
        val cw = scale * C.ROAD_WIDTH * w / 2f * 0.16f // half width
        if (screenY > clipY || cw < 2f) return
        drawCarShape(c, screenX, screenY, cw, r.vehicle.color, 0f)
    }

    fun drawCarShape(c: Canvas, cx: Float, bottomY: Float, halfW: Float, color: Int, steer: Float) {
        val h = halfW * 0.55f
        // engine glow
        paint.color = Color.argb(90, 0, 220, 255)
        c.drawCircle(cx, bottomY, halfW * 1.2f, paint)
        // body
        paint.color = color
        val p = android.graphics.Path()
        p.moveTo(cx - halfW, bottomY - h * 0.4f)
        p.lineTo(cx - halfW * 0.8f, bottomY - h)
        p.lineTo(cx + halfW * 0.8f, bottomY - h)
        p.lineTo(cx + halfW, bottomY - h * 0.4f)
        p.lineTo(cx + halfW * 0.5f, bottomY)
        p.lineTo(cx - halfW * 0.5f, bottomY)
        p.close()
        c.drawPath(p, paint)
        // canopy
        paint.color = Color.argb(200, 30, 40, 60)
        c.drawCircle(cx + steer * halfW * 0.1f, bottomY - h * 0.75f, halfW * 0.28f, paint)
        // stripe
        paint.color = Color.argb(230, 255, 255, 255)
        c.drawRect(cx - halfW * 0.06f, bottomY - h * 0.95f, cx + halfW * 0.06f, bottomY - h * 0.2f, paint)
    }

    private fun drawProjectile(c: Canvas, seg: Segment, p: Projectile, w: Int, clipY: Float) {
        val scale = seg.p1.screenScale
        val screenX = seg.p1.screenX + scale * p.x * C.ROAD_WIDTH * w / 2f
        val screenY = seg.p1.screenY
        val r = scale * C.ROAD_WIDTH * w / 2f * 0.03f
        if (screenY > clipY || r < 1.5f) return
        val col = when {
            p.reflected -> Color.argb(255, 120, 255, 255)
            p.type == WeaponType.BULLET -> Color.argb(255, 255, 240, 120)
            p.type == WeaponType.ROCKET -> Color.argb(255, 255, 120, 60)
            p.type == WeaponType.SEEKER -> Color.argb(255, 120, 200, 255)
            p.type == WeaponType.HUNTER -> Color.argb(255, 255, 90, 200)
            else -> Color.argb(255, 255, 215, 0) // LEADER
        }
        val size = if (p.type == WeaponType.BULLET) r * 0.45f else if (p.type.guided) r * 1.25f else r
        paint.color = Color.argb(80, Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(screenX, screenY, size * 2.8f, paint)
        paint.color = col
        c.drawCircle(screenX, screenY, size, paint)
    }

    private fun drawProjectilesNear(c: Canvas, race: Race, w: Int, h: Int, camX: Float, camY: Float, camZ: Float, camDepth: Float) {
        // draw projectiles between camera and first segment (very close)
        for (p in race.projectiles) {
            if (!p.alive) continue
            if (p.cameraZSafe(camZ) > 0f) {
                val pt = Point()
                pt.worldY = camY - C.CAM_HEIGHT * 0.4f
                pt.worldZ = p.z
                project(pt, camX, camY, camZ, camDepth, w, h)
                if (pt.cameraZ > camDepth && pt.screenY < h) {
                    paint.color = Color.argb(200, 255, 140, 60)
                    c.drawCircle(pt.screenX, pt.screenY, 7f, paint)
                }
            }
        }
    }

    private fun Projectile.cameraZSafe(camZ: Float): Float = z - camZ

    private fun drawPlayerCar(c: Canvas, w: Int, h: Int, player: Racer, input: PlayerInput) {
        val cx = w / 2f
        val bottomY = h * 0.86f
        val halfW = minOf(w * 0.11f, h * 0.19f)
        val steer = (if (input.left) -1f else 0f) + (if (input.right) 1f else 0f)
        val bob = sin(System.currentTimeMillis() / 90.0).toFloat() * halfW * 0.03f
        drawCarShape(c, cx, bottomY + bob, halfW, player.vehicle.color, steer)
        // boost flame
        if (player.boostT > 0f) {
            paint.color = Color.argb(160, 0, 255, 200)
            c.drawCircle(cx, bottomY + bob + halfW * 0.15f, halfW * 0.5f, paint)
        }
        // shield bubble
        if (player.shield > 0) {
            paint.color = Color.argb(30 + player.shield, 80, 190, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            c.drawCircle(cx, bottomY - halfW * 0.4f, halfW * 1.25f, paint)
            paint.style = Paint.Style.FILL
        }
        if (player.reflectT > 0f) {
            paint.color = Color.argb(50, 200, 130, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            c.drawCircle(cx, bottomY - halfW * 0.4f, halfW * 1.4f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    // ---------------- HUD ----------------

    fun drawHud(c: Canvas, race: Race, w: Int, h: Int, ui: RaceUi) {
        val player = race.player
        val u = h / 100f
        val panel = Color.argb(150, 5, 8, 18)

        // ---- left panel: hull + shield ----
        paint.color = panel
        c.drawRoundRect(RectF(1.5f * u, 1.5f * u, w * 0.27f, 18f * u), 2f * u, 2f * u, paint)
        val ebX = 3.5f * u
        val ebW = w * 0.27f - 5.5f * u
        val ebY = 7f * u
        val ebH = 2.6f * u
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.argb(235, 200, 210, 255)
        textPaint.textSize = 3.4f * u
        c.drawText("HULL ${player.energy}/${Arms.MAX_HULL}", ebX, ebY - 1f * u, textPaint)
        paint.color = Color.argb(255, 40, 40, 60)
        c.drawRect(ebX, ebY, ebX + ebW, ebY + ebH, paint)
        val pct = player.energy.toFloat() / player.maxEnergy().toFloat()
        paint.color = when {
            pct > 0.6f -> Color.argb(255, 80, 220, 120)
            pct > 0.3f -> Color.argb(255, 255, 200, 60)
            else -> Color.argb(255, 255, 80, 60)
        }
        c.drawRect(ebX, ebY, ebX + ebW * clamp(pct, 0f, 1f), ebY + ebH, paint)
        val sbY = 14f * u
        textPaint.textSize = 2.9f * u
        c.drawText("SHIELD ${player.shield}/${Arms.MAX_SHIELD}", ebX, sbY - 0.9f * u, textPaint)
        paint.color = Color.argb(255, 30, 40, 70)
        c.drawRect(ebX, sbY, ebX + ebW, sbY + ebH * 0.75f, paint)
        paint.color = Color.argb(255, 80, 190, 255)
        c.drawRect(ebX, sbY, ebX + ebW * clamp(player.shield / Arms.MAX_SHIELD.toFloat(), 0f, 1f), sbY + ebH * 0.75f, paint)

        // ---- lap / position (between hull panel and mirror) ----
        paint.color = panel
        c.drawRoundRect(RectF(w * 0.28f, 1.5f * u, w * 0.36f, 18f * u), 2f * u, 2f * u, paint)
        drawTextCentered(c, "LAP ${minOf(player.lap + 1, C.LAPS)}/${C.LAPS}", w * 0.32f, 8.5f * u, 4.6f * u, Color.WHITE, Paint.Align.CENTER, w * 0.074f)
        drawTextCentered(c, "POS ${race.rank(player)}/${race.racers.size}", w * 0.32f, 15f * u, 4f * u, Color.argb(255, 255, 214, 90), Paint.Align.CENTER, w * 0.074f)

        // ---- time + speed (right of mirror) ----
        val L = CtrlLayout(w, h)
        paint.color = panel
        c.drawRoundRect(RectF(w * 0.64f, 1.5f * u, L.pause.left - 1.5f * u, 18f * u), 2f * u, 2f * u, paint)
        val rx = L.pause.left - 3.5f * u
        drawTextCentered(c, fmtTime(race.raceTimeMs), rx, 6.5f * u, 3.6f * u, Color.WHITE, Paint.Align.RIGHT)
        val kmh = (player.speed * 0.045f).toInt()
        drawTextCentered(c, "$kmh", rx - 9f * u, 15.5f * u, 7.5f * u, if (player.boostT > 0f) Color.argb(255, 90, 255, 200) else Color.WHITE, Paint.Align.RIGHT)
        drawTextCentered(c, "km/h", rx, 15.5f * u, 3f * u, Color.argb(220, 170, 180, 220), Paint.Align.RIGHT)

        // ---- pause button ----
        paint.color = panel
        c.drawRoundRect(L.pause, 2f * u, 2f * u, paint)
        paint.color = Color.WHITE
        val pc = L.pause.centerX()
        val py = L.pause.centerY()
        c.drawRect(pc - 2.4f * u, py - 3.5f * u, pc - 0.9f * u, py + 3.5f * u, paint)
        c.drawRect(pc + 0.9f * u, py - 3.5f * u, pc + 2.4f * u, py + 3.5f * u, paint)

        // ---- buff timers (under the hull panel) ----
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 3.6f * u
        var by = 24f * u
        if (player.reflectT > 0f) { textPaint.color = Color.argb(255, 200, 130, 255); c.drawText("REFLECT ${player.reflectT.toInt()}s", 3f * u, by, textPaint); by += 5f * u }
        if (player.boostT > 0f) { textPaint.color = Color.argb(255, 90, 255, 170); c.drawText("BOOST ${player.boostT.toInt()}s", 3f * u, by, textPaint) }

        // ---- events (below the mirror) ----
        val shown = race.events.takeLast(3)
        shown.forEachIndexed { i, e ->
            drawTextCentered(c, e, w * 0.5f, 31f * u + i * 5f * u, 3.8f * u, Color.argb(230 - i * 60, 255, 230, 120), Paint.Align.CENTER, w * 0.5f)
        }
        if (race.events.size > 6) {
            val n = race.events.size - 6
            race.events.subList(0, n).clear()
        }

        // ---- weapons readout (left side, above steering) ----
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 3.5f * u
        var ay = 44f * u
        val lines = listOf(
            "GUN  ${player.bullets}/${Arms.MAX_BULLETS}  (${player.bulletDamage().toInt()}hp)" to Color.argb(255, 255, 240, 140),
            "ROCKETS  ${player.rockets}/${Arms.MAX_ROCKETS}" to Color.argb(255, 255, 150, 110),
            "MISSILES  " + (if (player.missiles.isEmpty()) "-" else player.missiles.joinToString(" ") { Arms.short(it) }) to Color.argb(255, 140, 210, 255),
            "MINES  ${player.mines}/${Arms.MAX_MINES}" to Color.argb(255, 255, 110, 110)
        )
        paint.color = Color.argb(110, 5, 8, 18)
        c.drawRoundRect(RectF(1.5f * u, ay - 4.6f * u, w * 0.2f, ay + 3 * 5.2f * u + 2f * u), 2f * u, 2f * u, paint)
        for ((t, col) in lines) { textPaint.color = col; c.drawText(t, 3f * u, ay, textPaint); ay += 5.2f * u }

        // ---- countdown ----
        if (race.countdown > 0f) {
            val col = when {
                race.countdown > 3f -> Color.argb(255, 255, 90, 90)
                race.countdown > 2f -> Color.argb(255, 255, 180, 60)
                race.countdown > 1f -> Color.argb(255, 120, 255, 120)
                else -> Color.WHITE
            }
            val label = if (race.countdown > 3f) "3" else if (race.countdown > 2f) "2" else if (race.countdown > 1f) "1" else "GO!"
            drawTextCentered(c, label, w * 0.5f, 56f * u, 24f * u, col)
        }

        // controls
        drawControls(c, w, h, ui)
    }

    // ---------------- rear-view mirror ----------------

    /** Top-middle of the screen, between the lap panel and the speed panel. */
    fun mirrorRect(w: Int, h: Int): RectF = RectF(w * 0.37f, h * 0.015f, w * 0.63f, h * 0.215f)

    private val rvA = RearView.Pt()
    private val rvB = RearView.Pt()
    private val mirrorClip = android.graphics.Path()

    fun drawMirror(c: Canvas, race: Race, track: Track, rect: RectF) {
        val rv = RearView(track, race.player)
        val mw = rect.width()
        val mh = rect.height()
        val cx = rect.centerX()
        val cy = rect.top + mh * 0.40f
        val corner = mh * 0.16f
        fun sx(p: RearView.Pt) = cx + p.x * mw / 2f
        fun sy(p: RearView.Pt) = cy - p.y * mh / 2f
        fun sw(p: RearView.Pt) = p.w * mw / 2f

        c.save()
        mirrorClip.reset()
        mirrorClip.addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW)
        c.clipPath(mirrorClip)
        // sky + void ground
        paint.color = track.def.skyTop
        c.drawRect(rect.left, rect.top, rect.right, cy, paint)
        paint.color = track.def.skyBottom
        c.drawRect(rect.left, cy - mh * 0.08f, rect.right, cy, paint)
        paint.color = Color.argb(255, 8, 9, 16)
        c.drawRect(rect.left, cy, rect.right, rect.bottom, paint)

        // road, near to far, skipping anything hidden behind a crest
        var maxy = rect.bottom
        for (k in 0 until RearView.SEGMENTS) {
            val s = rv.seg(k)
            if (!rv.project(0f, s.p2.worldY, rv.nearDist(k), rv.xNear[k], rvA)) continue
            if (!rv.project(0f, s.p1.worldY, rv.farDist(k), rv.xFar[k], rvB)) continue
            val x1 = sx(rvA); val y1 = sy(rvA); val l1 = sw(rvA)
            val x2 = sx(rvB); val y2 = sy(rvB); val l2 = sw(rvB)
            if (y2 >= y1 || y2 >= maxy) continue
            val bottom = minOf(y1, maxy)
            val r1 = l1 / 6f
            val r2 = l2 / 6f
            val rumble = if (s.light) Color.argb(255, 230, 230, 240) else Color.argb(255, 90, 95, 130)
            quad(c, x1 - l1 - r1, bottom, x1 + l1 + r1, bottom, x2 + l2 + r2, y2, x2 - l2 - r2, y2, rumble)
            quad(c, x1 - l1, bottom, x1 + l1, bottom, x2 + l2, y2, x2 - l2, y2, if (s.light) track.def.roadLight else track.def.roadDark)
            if (s.light) {
                for (lane in 1 until C.LANES) {
                    val o = -1f + 2f * lane / C.LANES
                    val lx1 = x1 + l1 * o; val lx2 = x2 + l2 * o
                    quad(c, lx1 - l1 / 30f, bottom, lx1 + l1 / 30f, bottom, lx2 + l2 / 30f, y2, lx2 - l2 / 30f, y2, Color.argb(150, 255, 255, 255))
                }
            }
            maxy = y2
        }

        // things behind you, farthest first
        class Item(val dist: Float, val draw: () -> Unit)
        val items = ArrayList<Item>()
        for ((r, p) in rv.racersBehind(race.racers)) {
            val x = sx(p); val y = sy(p)
            val hw = (sw(p) * 0.16f).coerceAtMost(mw * 0.16f)
            if (hw < 1.5f) continue
            items.add(Item(p.dist) {
                drawCarShape(c, x, y, hw, r.vehicle.color, 0f)
                // headlights: you're seeing the front of the car
                paint.color = Color.argb(240, 255, 250, 210)
                c.drawCircle(x - hw * 0.55f, y - hw * 0.2f, hw * 0.13f, paint)
                c.drawCircle(x + hw * 0.55f, y - hw * 0.2f, hw * 0.13f, paint)
            })
        }
        for (m in race.mines) {
            if (!m.alive) continue
            val p = RearView.Pt()
            if (!rv.place(m.z, m.x, p)) continue
            val x = sx(p); val y = sy(p); val rr = (sw(p) * 0.035f).coerceAtLeast(1.5f)
            items.add(Item(p.dist) {
                paint.color = Color.argb(80, 255, 60, 60); c.drawCircle(x, y, rr * 2.4f, paint)
                paint.color = Color.argb(255, 200, 40, 40); c.drawCircle(x, y, rr, paint)
            })
        }
        var incoming = false
        for (pr in race.projectiles) {
            if (!pr.alive) continue
            val p = RearView.Pt()
            if (!rv.place(pr.z, pr.x, p)) continue
            val x = sx(p); val y = sy(p)
            val threat = pr.target === race.player && pr.owner !== race.player
            if (threat) incoming = true
            val col = when {
                threat -> Color.argb(255, 255, 60, 60)
                pr.type == WeaponType.BULLET -> Color.argb(255, 255, 240, 120)
                pr.type == WeaponType.ROCKET -> Color.argb(255, 255, 120, 60)
                else -> Color.argb(255, 255, 200, 90)
            }
            val rr = ((if (pr.type == WeaponType.BULLET) 0.015f else 0.035f) * sw(p)).coerceAtLeast(if (threat) 3f else 1.5f)
            items.add(Item(p.dist) {
                paint.color = Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(x, y - rr, rr * 2.8f, paint)
                paint.color = col; c.drawCircle(x, y - rr, rr, paint)
            })
        }
        items.sortByDescending { it.dist }
        items.forEach { it.draw() }
        // slight glass tint
        paint.color = Color.argb(28, 140, 200, 255)
        c.drawRect(rect, paint)
        c.restore()

        // frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = mh * 0.045f
        val blink = incoming && (System.currentTimeMillis() / 180) % 2 == 0L
        paint.color = if (blink) Color.argb(255, 255, 60, 60) else Color.argb(255, 60, 66, 92)
        c.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.FILL
        if (incoming) drawTextCentered(c, "MISSILE LOCK", cx, rect.bottom + mh * 0.17f, mh * 0.13f, Color.argb(255, 255, 80, 80))
    }

    // ---------------- shared control layout (drawing + input use the same geometry) ----------------

    /** Landscape layout sized from the screen height, so buttons stay thumb-sized on any phone. */
    class CtrlLayout(w: Int, h: Int) {
        private val u = h / 100f
        val left = floatArrayOf(15f * u, 82f * u, 13f * u)
        val right = floatArrayOf(44f * u, 82f * u, 13f * u)
        val gas = floatArrayOf(w - 17f * u, 80f * u, 15f * u)
        val brake = floatArrayOf(w - 49f * u, 85f * u, 11f * u)
        val gun = floatArrayOf(w - 17f * u, 50f * u, 12f * u)
        val special = floatArrayOf(w - 45f * u, 58f * u, 11f * u)
        val cycle = floatArrayOf(w - 43f * u, 35f * u, 8f * u)
        val pause = RectF(w - 15.5f * u, 1.5f * u, w - 1.5f * u, 18f * u)
    }

    private fun drawControls(c: Canvas, w: Int, h: Int, ui: RaceUi) {
        val L = CtrlLayout(w, h)
        val btn = { b: FloatArray, label: String, pressed: Boolean, col: Int ->
            paint.color = if (pressed) Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)) else Color.argb(90, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(b[0], b[1], b[2], paint)
            paint.color = Color.argb(40, 255, 255, 255)
            c.drawCircle(b[0], b[1], b[2] * 0.94f, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = b[2] * 0.5f
            textPaint.color = Color.WHITE
            c.drawText(label, b[0], b[1] + b[2] * 0.18f, textPaint)
        }
        btn(L.left, "◀", ui.input.left, Color.argb(255, 60, 120, 220))
        btn(L.right, "▶", ui.input.right, Color.argb(255, 60, 120, 220))
        btn(L.gas, "GAS", ui.input.gas, Color.argb(255, 40, 180, 110))
        btn(L.brake, "BRK", ui.input.brake, Color.argb(255, 190, 60, 60))
        btn(L.gun, "GUN", ui.gunPressed, Color.argb(255, 230, 190, 40))
        val sp = ui.secondaryLabel
        btn(L.special, sp, ui.specialPressed, Color.argb(255, 220, 90, 50))
        btn(L.cycle, "WPN", ui.cyclePressed, Color.argb(255, 120, 120, 170))
    }

    // ---------------- shared UI helpers ----------------

    fun drawButton(c: Canvas, rect: RectF, label: String, color: Int, pressed: Boolean = false, textSize: Float = 34f) {
        paint.color = if (pressed) Color.argb(230, Color.red(color), Color.green(color), Color.blue(color)) else Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
        c.drawRoundRect(rect, 18f, 18f, paint)
        paint.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(RectF(rect.left + 4, rect.top + 4, rect.right - 4, rect.top + rect.height() * 0.4f), 14f, 14f, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = textSize
        val maxW = rect.width() * 0.92f
        val tw = textPaint.measureText(label)
        if (tw > maxW && tw > 0f) textPaint.textSize = textSize * maxW / tw
        val ty = rect.centerY() + textPaint.textSize * 0.32f
        c.drawText(label, rect.centerX(), ty, textPaint)
    }

    fun drawTextCentered(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, maxWidth: Float = 0f) {
        textPaint.textAlign = align
        textPaint.color = color
        textPaint.textSize = size
        // shrink long lines so they never run off the screen
        val maxW = if (maxWidth > 0f) maxWidth else c.width * 0.94f
        val tw = textPaint.measureText(text)
        if (tw > maxW && tw > 0f) textPaint.textSize = size * maxW / tw
        c.drawText(text, x, y, textPaint)
    }
}

class RaceUi(val input: PlayerInput) {
    var gunPressed = false        // hold for automatic fire
    var specialPressed = false    // fires the selected rocket / missile / mine
    var cyclePressed = false      // switches the selected special weapon
    var secondaryLabel = "RKT"
}
