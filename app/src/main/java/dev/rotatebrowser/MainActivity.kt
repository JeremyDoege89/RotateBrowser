package dev.rotatebrowser

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.rotatebrowser.bridge.BridgeEvent
import dev.rotatebrowser.bridge.VideoBridge
import dev.rotatebrowser.browser.BrowserViewClient
import dev.rotatebrowser.browser.UrlInput
import dev.rotatebrowser.fullscreen.FullscreenController
import dev.rotatebrowser.gesture.SeekGestureLayout
import dev.rotatebrowser.gesture.SeekHud
import dev.rotatebrowser.rotation.RotateToFullscreen
import dev.rotatebrowser.rotation.RotationWatcher

class MainActivity : ComponentActivity() {

    lateinit var webView: WebView
        private set
    lateinit var toolbar: LinearLayout
        private set
    lateinit var fullscreenContainer: FrameLayout
        private set

    private lateinit var progress: ProgressBar
    private lateinit var urlInput: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton

    private lateinit var fullscreenController: FullscreenController
    private lateinit var videoBridge: VideoBridge
    private lateinit var rotateToFullscreen: RotateToFullscreen
    private lateinit var rotationWatcher: RotationWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        urlInput = findViewById(R.id.url_input)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        val container = findViewById<FrameLayout>(R.id.webview_container)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(bars.left, bars.top, bars.right, 0)
            container.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(webView)

        fullscreenController = FullscreenController(this)

        videoBridge = VideoBridge(webView) { event ->
            when (event) {
                is BridgeEvent.FullscreenOk -> rotateToFullscreen.onRealFullscreenShown()
                is BridgeEvent.FullscreenFailed -> rotateToFullscreen.onFullscreenFailed()
                is BridgeEvent.VideoStateUpdate -> {}
                is BridgeEvent.Ready -> {}
            }
        }

        rotateToFullscreen = RotateToFullscreen(this, videoBridge, fullscreenController)

        rotationWatcher = RotationWatcher(this) { posture ->
            rotateToFullscreen.onPosture(posture)
        }

        configureWebView()
        setupNavigation()
        setupUrlInput()

        val trackerJs = assets.open("js/video-tracker.js").bufferedReader().readText()
        videoBridge.install(trackerJs)

        val url = intent?.dataString ?: "https://example.com"
        webView.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenController.isFullscreen) {
                    if (fullscreenController.isFake) {
                        fullscreenController.exitFake()
                    } else {
                        fullscreenController.hideCustomView()
                    }
                    rotateToFullscreen.onUserExited()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { webView.loadUrl(it) }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webViewClient = BrowserViewClient(
            onPageUrl = { url ->
                urlInput.setText(url)
            },
            onProgress = { p ->
                if (p < 100) {
                    progress.visibility = View.VISIBLE
                    progress.progress = p
                } else {
                    progress.visibility = View.GONE
                }
            },
            onPageStarted = { videoBridge.onNavigation() }
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val seekLayout = SeekGestureLayout(this@MainActivity).apply {
                    bridge = videoBridge
                    val seekHud = SeekHud(this@MainActivity)
                    hud = seekHud
                    addView(view)
                    addView(seekHud)
                }
                fullscreenController.showCustomView(seekLayout, callback)
                rotateToFullscreen.onRealFullscreenShown()
            }

            override fun onHideCustomView() {
                fullscreenController.hideCustomView()
                rotateToFullscreen.onUserExited()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {}
        }
    }

    private fun setupNavigation() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnReload.setOnClickListener { webView.reload() }
    }

    private fun setupUrlInput() {
        urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = v.text.toString().trim()
                val url = UrlInput.resolve(input)
                webView.loadUrl(url)
                urlInput.clearFocus()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        rotationWatcher.start()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        rotationWatcher.stop()
    }
}
