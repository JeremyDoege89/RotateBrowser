package dev.rotatebrowser.rotation

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import dev.rotatebrowser.bridge.VideoBridge
import dev.rotatebrowser.fullscreen.FullscreenController

class RotateToFullscreen(
    private val activity: ComponentActivity,
    private val bridge: VideoBridge,
    private val fullscreen: FullscreenController
) {
    enum class State { IDLE, ENTERING, FULL, FAKE, NEED_PORTRAIT }

    var state = State.IDLE
        private set

    var enabled = true

    private val main = Handler(Looper.getMainLooper())
    private val fallback = Runnable {
        if (state == State.ENTERING) {
            Log.d(TAG, "fallback -> fake fullscreen")
            enterFake()
        }
    }

    private val isLargeScreen: Boolean
        get() = activity.resources.configuration.smallestScreenWidthDp >= 600

    fun onPosture(p: RotationWatcher.Posture) {
        Log.d(TAG, "posture=$p state=$state")
        when {
            p == RotationWatcher.Posture.PORTRAIT -> when (state) {
                State.FULL, State.FAKE, State.ENTERING -> exit()
                State.NEED_PORTRAIT -> {
                    state = State.IDLE
                    Log.d(TAG, "saw portrait -> IDLE")
                }
                else -> {}
            }
            (p == RotationWatcher.Posture.LANDSCAPE || p == RotationWatcher.Posture.REVERSE_LANDSCAPE)
                && state == State.IDLE && enabled -> {
                val v = bridge.activeVideo ?: return
                if (!v.playing || !v.qualifies() || v.isPortraitVideo) return

                state = State.ENTERING
                Log.d(TAG, "arming for fullscreen")
                bridge.arm(v.frameId)

                if (!isLargeScreen) {
                    activity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                main.postDelayed(fallback, 700)
            }
        }
    }

    fun onRealFullscreenShown() {
        main.removeCallbacks(fallback)
        state = State.FULL
        Log.d(TAG, "real fullscreen shown")
    }

    fun onFullscreenFailed() {
        if (state == State.ENTERING) {
            main.removeCallbacks(fallback)
            enterFake()
        }
    }

    fun onUserExited() {
        restore()
        state = State.NEED_PORTRAIT
        Log.d(TAG, "user exited -> NEED_PORTRAIT")
    }

    private fun enterFake() {
        val v = bridge.activeVideo
        if (v == null) { exit(); return }
        bridge.send(v.frameId, """{"cmd":"fake","on":true}""")
        fullscreen.enterFake()
        state = State.FAKE
        Log.d(TAG, "fake fullscreen entered")
    }

    private fun exit() {
        main.removeCallbacks(fallback)
        when (state) {
            State.FULL -> fullscreen.hideCustomView()
            State.FAKE -> {
                bridge.activeVideo?.let {
                    bridge.send(it.frameId, """{"cmd":"fake","on":false}""")
                }
                fullscreen.exitFake()
            }
            else -> bridge.activeVideo?.let {
                bridge.send(it.frameId, """{"cmd":"exit"}""")
            }
        }
        restore()
        state = State.IDLE
        Log.d(TAG, "exited -> IDLE")
    }

    private fun restore() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }

    companion object {
        private const val TAG = "RotateFS"
    }
}
