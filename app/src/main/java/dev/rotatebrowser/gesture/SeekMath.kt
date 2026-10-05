package dev.rotatebrowser.gesture

import dev.rotatebrowser.bridge.VideoState
import kotlin.math.roundToLong

object SeekMath {

    fun delta(dx: Float, widthPx: Int, duration: Double): Double {
        val perWidth = if (duration.isFinite() && duration > 0) {
            (duration * 0.2).coerceIn(30.0, 300.0).coerceAtMost(duration)
        } else {
            60.0
        }
        return (dx / widthPx * perWidth).roundToLong().toDouble()
    }

    fun clamp(t: Double, state: VideoState): Double {
        return if (state.duration.isFinite() && state.duration > 0) {
            t.coerceIn(0.0, state.duration)
        } else {
            t.coerceAtLeast(0.0)
        }
    }

    fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun formatDelta(delta: Double): String {
        val prefix = if (delta >= 0) "+" else "-"
        return "$prefix${formatTime(kotlin.math.abs(delta))}"
    }
}
