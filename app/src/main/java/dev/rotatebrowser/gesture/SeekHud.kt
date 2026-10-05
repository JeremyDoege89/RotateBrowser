package dev.rotatebrowser.gesture

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class SeekHud(context: Context) : FrameLayout(context) {

    private val label: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { visibility = View.GONE }

    init {
        visibility = View.GONE

        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(label, lp)
    }

    fun show(delta: Double, target: Double, duration: Double) {
        handler.removeCallbacks(hideRunnable)
        val d = SeekMath.formatDelta(delta)
        val t = SeekMath.formatTime(target)
        val dur = SeekMath.formatTime(duration)
        label.text = "$d → $t / $dur"
        visibility = View.VISIBLE
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        visibility = View.GONE
    }

    fun hideDelayed() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 800)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
