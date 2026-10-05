package dev.rotatebrowser.fullscreen

import android.util.Log

object FakeFullscreen {
    private const val TAG = "FakeFS"

    fun enter() {
        Log.d(TAG, "fake fullscreen entered (CSS-only, handled by JS)")
    }

    fun exit() {
        Log.d(TAG, "fake fullscreen exited")
    }
}
