package dev.rotatebrowser.debug

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.rotatebrowser.bridge.VideoState
import dev.rotatebrowser.rotation.RotateToFullscreen

class DebugHud(context: Context) : FrameLayout(context) {

    private val label: TextView

    init {
        visibility = View.GONE

        label = TextView(context).apply {
            setTextColor(Color.GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadius = dp(4).toFloat()
            }
            maxLines = 4
        }

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(4)
            marginEnd = dp(4)
        }
        addView(label, lp)
    }

    fun update(video: VideoState?, state: RotateToFullscreen.State) {
        if (video == null) {
            label.text = "no video | $state"
        } else {
            label.text = "${video.frameId.take(6)} ${video.videoW}x${video.videoH} " +
                "t=${String.format("%.1f", video.currentTime)} " +
                "playing=${video.playing} | $state"
        }
        visibility = View.VISIBLE
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
