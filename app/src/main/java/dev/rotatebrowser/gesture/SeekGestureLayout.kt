package dev.rotatebrowser.gesture

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import dev.rotatebrowser.bridge.VideoBridge
import kotlin.math.abs

class SeekGestureLayout(context: Context) : FrameLayout(context) {

    var bridge: VideoBridge? = null
    var hud: SeekHud? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private val edgePx = (16 * resources.displayMetrics.density).toInt()
    private var downX = 0f
    private var downY = 0f
    private var seeking = false
    private var startT = 0.0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val strip = (32 * resources.displayMetrics.density).toInt()
        systemGestureExclusionRects = listOf(
            Rect(0, 0, strip, height),
            Rect(width - strip, 0, width, height)
        )
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                seeking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return false
                if (downX < edgePx || downX > width - edgePx) return false

                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > 2 * abs(dy)) {
                    val v = bridge?.activeVideo ?: return false
                    if (v.isLive && v.seekableWindow < 30) return false
                    seeking = true
                    startT = v.currentTime
                    downX = e.x
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!seeking) return false
        val v = bridge?.activeVideo ?: return false
        val delta = SeekMath.delta(dx = e.x - downX, widthPx = width, duration = v.duration)

        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                hud?.show(delta, SeekMath.clamp(startT + delta, v), v.duration)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                seeking = false
                hud?.hide()
            }
            MotionEvent.ACTION_UP -> {
                bridge?.seekBy(delta)
                seeking = false
                hud?.hideDelayed()
            }
        }
        return true
    }
}
