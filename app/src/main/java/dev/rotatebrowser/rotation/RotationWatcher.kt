package dev.rotatebrowser.rotation

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener

class RotationWatcher(
    context: Context,
    private val onStable: (Posture) -> Unit
) {
    enum class Posture { PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE, UNKNOWN }

    private val handler = Handler(Looper.getMainLooper())
    private var candidate = Posture.UNKNOWN
    private var reported = Posture.UNKNOWN
    private val dwellMs = 400L
    private val band = 25

    private val confirm = Runnable {
        if (candidate != reported) {
            reported = candidate
            onStable(candidate)
        }
    }

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
        override fun onOrientationChanged(deg: Int) {
            val p = when {
                deg == ORIENTATION_UNKNOWN -> return
                deg <= band || deg >= 360 - band -> Posture.PORTRAIT
                deg in (90 - band)..(90 + band) -> Posture.REVERSE_LANDSCAPE
                deg in (270 - band)..(270 + band) -> Posture.LANDSCAPE
                else -> return
            }
            if (p != candidate) {
                candidate = p
                handler.removeCallbacks(confirm)
                handler.postDelayed(confirm, dwellMs)
            }
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
        handler.removeCallbacks(confirm)
    }
}
