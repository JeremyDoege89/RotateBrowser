# Project: RotateBrowser (Android, Kotlin, Android System WebView)

## Goal
1. Rotate-to-fullscreen: when a visible video is playing and the phone is physically turned to landscape,
   the video goes fullscreen automatically (even with system auto-rotate locked); turning back to portrait exits.
2. Swipe-to-seek: in fullscreen, a horizontal drag seeks the video backward/forward with an on-screen HUD.

## Architecture (do not change without asking)
- Engine: android.webkit.WebView + androidx.webkit 1.17.x. No GeckoView, no Chromium fork.
- JS injection: WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*")) — runs in ALL frames incl. cross-origin iframes.
- Bridge: WebViewCompat.addWebMessageListener(webView, "__vb", setOf("*"), listener). One JavaScriptReplyProxy per frame,
  keyed by a random frameId generated in JS. NEVER use addJavascriptInterface.
- Fullscreen: the page calls requestFullscreen() synchronously inside 'orientationchange' / screen.orientation 'change'
  (Blink allows this without a user gesture). Native code arms the script and forces rotation with
  setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE). WebView delivers fullscreen via
  WebChromeClient.onShowCustomView/onHideCustomView. Fallback = "fake fullscreen" (CSS + hide app/system bars).
- Seek gesture: SeekGestureLayout wraps the custom view; intercepts only clear horizontal drags,
  taps pass through. Seek applied via bridge (video.currentTime) on finger-up.
- Activity uses android:configChanges for orientation|screenSize|screenLayout|smallestScreenSize.

## Tooling (MX Linux, no Android Studio)
- JDK 17+, Android SDK cmdline-tools at ~/Android/Sdk, AGP 9.4.0, Gradle wrapper 9.6.0.
- Build: ./gradlew assembleDebug   Install: ./gradlew installDebug   Logs: adb logcat -s RotateFS:V VideoBridge:V SeekGesture:V
- Debug JS: WebView.setWebContentsDebuggingEnabled(true) in debug builds; inspect via chrome://inspect.
- minSdk 29, targetSdk 36. Kotlin only. Non-deprecated APIs (WindowInsetsControllerCompat, OnBackPressedDispatcher).

## Rules
- Work phase by phase from PLAN.md. After each phase: build, install, and tell me the exact adb/manual tests to run.
- Pure logic (SeekMath, state machine transitions) gets JVM unit tests.
- Treat every bridge message as untrusted: fixed message types, JSON parse with limits, clamp numbers.
- Never silently proceed on SSL errors. Never change system settings (no WRITE_SETTINGS).
- If an API behaves differently than this file assumes, stop and report with logcat + chrome://inspect evidence.
