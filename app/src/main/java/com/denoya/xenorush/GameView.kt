package com.denoya.xenorush

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    var game: Game? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var appliedRate = 0f

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        isFocusable = true
    }

    fun attachGame(g: Game) {
        game = g
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        game?.ensureDisplayMode()
        applySurfaceRate()
        requestFocus()
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
        var last = System.nanoTime()
        while (running) {
            val start = System.nanoTime()
            val holder = holder
        if (!holder.surface.isValid) {
            sleepNs(game?.frameBudgetNs() ?: FrameCap.FRAME_NS)
                last = System.nanoTime()
                continue
            }
            var dt = (start - last) / 1_000_000_000f
            last = start
            if (dt > 0.1f) dt = 0.1f
            if (dt < 0f) dt = 0f
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
            val elapsed = System.nanoTime() - start
            game?.noteFrame(elapsed)
            applySurfaceRate()
            val budget = game?.frameBudgetNs() ?: FrameCap.FRAME_NS
            val remain = budget - elapsed
            if (remain > 1_000_000L) sleepNs(remain)
        }
    }

    /** Ask the panel for the current target. API 30+ sets the surface rate; older builds keep the sleep cap. */
    private fun applySurfaceRate() {
        if (Build.VERSION.SDK_INT < 30) return
        val hz = (game?.targetHz ?: FrameCap.TARGET_FPS).toFloat()
        if (hz == appliedRate || !holder.surface.isValid) return
        try {
            holder.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            appliedRate = hz
        } catch (_: Exception) {
        }
    }

    /** Sleep the rest of the [FrameCap.TARGET_FPS] budget so a 90 Hz panel does not run past the cap. */
    private fun sleepNs(ns: Long) {
        if (ns <= 0L) return
        val ms = ns / 1_000_000L
        val extra = (ns % 1_000_000L).toInt()
        try { Thread.sleep(ms, extra) } catch (_: Exception) {}
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

    /** Keys from a gamepad or a D-pad. Returns true when the event was consumed. */
    fun dispatchGamepadKey(event: KeyEvent): Boolean {
        val btn = PadMap.key(event.keyCode) ?: return false
        val src = event.source
        val pad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            event.keyCode >= KeyEvent.KEYCODE_BUTTON_A
        if (!pad && btn != PadMap.Btn.LEFT && btn != PadMap.Btn.RIGHT && btn != PadMap.Btn.UP && btn != PadMap.Btn.DOWN) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        game?.queueKey(event.keyCode, down, event.repeatCount > 0)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchGamepadKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchGamepadKey(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val stick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!stick) return super.onGenericMotionEvent(event)
        val gas = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val brake = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        game?.queueAxes(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            gas, brake)
        return true
    }

    fun releaseGame() {
        running = false
        try { thread?.join(1500) } catch (_: Exception) {}
        thread = null
        game?.onAppDestroy()
    }

    fun pauseGame() {
        running = false
        thread?.join(1500)
        thread = null
        game?.releaseAllControls()
        game?.onAppPause()
    }

    fun resumeGame() {
        game?.onAppResume()
        game?.releaseAllControls()
        if (!running && holder.surface.isValid) {
            running = true
            thread = Thread(this).also { it.start() }
        }
    }
}
