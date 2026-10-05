package dev.rotatebrowser.fullscreen

import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rotatebrowser.MainActivity

class FullscreenController(private val activity: MainActivity) {

    private var customViewCallback: CustomViewCallback? = null
    private var currentView: View? = null
    val isFullscreen: Boolean get() = currentView != null || isFake
    var isFake = false
        private set

    fun showCustomView(view: View, callback: CustomViewCallback) {
        if (currentView != null) {
            callback.onCustomViewHidden()
            return
        }

        currentView = view
        customViewCallback = callback

        activity.toolbar.visibility = View.GONE
        activity.fullscreenContainer.addView(view)
        activity.fullscreenContainer.visibility = View.VISIBLE

        enterImmersive()
    }

    fun hideCustomView() {
        val view = currentView ?: return
        activity.fullscreenContainer.removeView(view)
        activity.fullscreenContainer.visibility = View.GONE
        activity.toolbar.visibility = View.VISIBLE

        customViewCallback?.onCustomViewHidden()
        currentView = null
        customViewCallback = null

        exitImmersive()
    }

    fun enterFake() {
        isFake = true
        activity.toolbar.visibility = View.GONE
        enterImmersive()
    }

    fun exitFake() {
        isFake = false
        activity.toolbar.visibility = View.VISIBLE
        exitImmersive()
    }

    private fun enterImmersive() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun exitImmersive() {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
    }
}
