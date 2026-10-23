package com.denoya.xenorush

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    var game: Game? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var lastTime = 0L

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        isFocusable = true
    }

    fun attachGame(g: Game) {
        game = g
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (running) return
        running = true
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        thread?.join(1500)
        thread = null
    }

    override fun run() {
        lastTime = System.currentTimeMillis()
        while (running) {
            val holder = holder
            if (!holder.surface.isValid) {
                try { Thread.sleep(16) } catch (_: Exception) {}
                continue
            }
            val now = System.currentTimeMillis()
            var dt = (now - lastTime) / 1000f
            lastTime = now
            if (dt > 0.1f) dt = 0.1f
            game?.update(dt)
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas(null)
                if (canvas != null) {
                    game?.draw(canvas)
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
            // ~60fps
            val frameTime = System.currentTimeMillis() - now
            if (frameTime < 14) try { Thread.sleep(14 - frameTime) } catch (_: Exception) {}
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Copy the event data and hand it to the game thread via a queue - never touch
        // game state directly from the UI thread.
        val action = e.actionMasked
        val idx = e.actionIndex
        val pid = try { e.getPointerId(idx) } catch (_: Exception) { 0 }
        val x = if (action == MotionEvent.ACTION_MOVE) e.x else e.getX(idx)
        val y = if (action == MotionEvent.ACTION_MOVE) e.y else e.getY(idx)
        game?.queueTouch(action, pid, x, y)
        return true
    }

    fun pauseGame() {
        running = false
        thread?.join(1500)
        thread = null
        game?.releaseAllControls()
        game?.onAppPause()
        game?.let { g -> if (g.screen == Screen.RACE) g.screen = Screen.PAUSED }
    }

    fun resumeGame() {
        game?.releaseAllControls()
        if (!running && holder.surface.isValid) {
            running = true
            thread = Thread(this).also { it.start() }
        }
    }
}
