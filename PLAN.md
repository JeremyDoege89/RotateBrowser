# Plan for Code: An Android Browser With Rotate-to-Fullscreen Video and Swipe-to-Seek

Build it on Android System WebView in Kotlin. The trick that makes rotate-to-fullscreen work is one Chromium already allows: a page may call `requestFullscreen()` with no user gesture while it is handling an orientation-change event. Your app controls when the screen rotates, so it can force a real rotation when you turn the phone, even with auto-rotate locked. A script you inject into every frame then requests fullscreen on the playing video inside that rotation event. Swipe-to-seek is a native touch layer wrapped around the fullscreen view. It sends `currentTime` changes over a JavaScript bridge to whichever frame owns the video.

## TL;DR

- **Engine: Android System WebView, not GeckoView or a Chromium fork.** Blink, WebView's engine, lets a page call `requestFullscreen()` from inside an orientation-change handler. WebView's `addDocumentStartJavaScript` plus `addWebMessageListener` can reach videos in cross-origin iframes such as YouTube or Vimeo embeds. You also get Widevine DRM, a tiny APK and engine updates from Google Play for free. GeckoView only wins if you need uBlock Origin–style extensions; in return you'd ship a large engine yourself and update it every month.
- **Rotate-to-fullscreen.** A native `OrientationEventListener` with hysteresis notices the phone has physically turned to landscape while the injected tracker says a visible video is playing. The app calls `setRequestedOrientation(SENSOR_LANDSCAPE)`. The screen really rotates, the page receives an orientation event, and the injected script calls `requestFullscreen()` on the player container, which WebView shows through `onShowCustomView`. Turning back to portrait exits. If a site rejects the request, the app falls back to "fake fullscreen": it hides its own toolbars and system bars and uses injected CSS to make the player fill the viewport.
- **Swipe-to-seek.** A custom `FrameLayout` wraps the fullscreen view and claims the gesture only once the finger has clearly moved sideways. Taps and vertical swipes go to the site's own player untouched. An on-screen readout shows something like "+0:45 → 12:34 / 20:00", and the seek is applied when you lift your finger. Re-extracting the stream into ExoPlayer breaks on DRM, MSE/`blob:` sources and YouTube, so it's a stretch goal for plain MP4/HLS only.

## Key Findings

**How Chrome does it.** Chromium's `MediaControlsRotateToFullscreenDelegate` sets the bar Chrome uses:
- The video must be at least 200 px in both dimensions (`kMinVideoSize = 200`) and at least 75% visible (`kIntersectionThreshold = 0.75`). [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc)
- It must be playing, use the browser's native controls (`ShouldShowControls()`), not have `controlsList="nofullscreen"`, and not be in picture-in-picture. [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc)
- The device must report usable orientation-sensor values, and 180° flips are ignored. [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc)

When all of that holds, the delegate marks the page as user-activated and calls the media controls' `EnterFullscreen()`; on a turn away from the video's orientation, it exits. [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc) The native-controls requirement is why Chrome's built-in feature does nothing on YouTube, Twitch and most real sites, which draw their own controls. Your injected script removes that restriction.

**Pages can request fullscreen from an orientation change without a gesture.** Mounir Lamouri's blink-dev post "Intent to Implement and Ship: request fullscreen on orientation change" says it plainly: "Allow web pages to request fullscreen while an orientation change event handler is running." The same post warns that the page gets the event only after the app has rotated, "so the fullscreen request will happen on the following frame, creating a potentially visible transition." The WHATWG fullscreen issue behind it describes "videos play inline when the device is in portrait mode and fullscreen when the user changes the orientation to landscape." [github](https://github.com/whatwg/fullscreen/issues/34) This is the gap your design uses.
- One open-source Android browser project (Zenium, PR #366, September 2026) reports that this exemption is Blink's `ScopedAllowFullscreen::kOrientationChange`, that Blink's own rotate delegate is active in phone WebViews (`video_rotate_to_fullscreen_enabled = device_is_phone`), and that `screen.orientation.lock()` is disabled in WebView. [github](https://github.com/BenItBuhner/Zenium/pull/366) [github](https://github.com/BenItBuhner/Zenium/pull/366)
- That project is AI-agent-driven and is a secondary source, so the plan below re-tests each of these claims on your phone.

**The auto-rotate catch, and how you get around it.** If system auto-rotate is off, the screen never rotates, so the page never gets an orientation event. Your app owns the Activity, though. An `OrientationEventListener` reads the accelerometer whatever the system lock says. When it sees steady landscape, the app requests `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, which allows both landscape directions. The window rotates, Blink fires the event, and the injected script calls fullscreen inside the handler. Firefox's Android Components switched to this same constant so that "when auto rotate is disabled" a fullscreen video plays "both in landscape and landscape reverse." [github](https://github.com/mozilla-mobile/android-components/issues/12034)

**Firefox doesn't auto-enter fullscreen on rotation.** Android Components' `MediaSessionFullscreenFeature` only sets the orientation after a video is already fullscreen: `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` while fullscreen, `SCREEN_ORIENTATION_USER` on exit. [fossies](https://fossies.org/linux/www/firefox-134.0.source.tar.xz/firefox-134.0/mobile/android/android-components/components/feature/media/src/main/java/mozilla/components/feature/media/fullscreen/MediaSessionFullscreenFeature.kt) No Fenix feature or bug was found that enters fullscreen when you rotate; Mozilla's support article only promises that "If you rotate your device while the video is in full screen mode, it will stay in landscape." [mozilla](https://support.mozilla.org/dsb/kb/play-videos-full-screen-firefox-android)
- In Gecko, the pref `full-screen-api.allow-trusted-requests-only=false` removes the gesture requirement for every page. [mozilla](https://bugzilla.mozilla.org/show_bug.cgi?id=1388479)
- GeckoView's `MediaSession.Delegate.onFullscreen(...)` passes `ElementMetadata` (width, height, duration), and `MediaSession.seekTo(time, fast)` exists. [searchfox +2](https://searchfox.org/firefox-main/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/MediaSession.java)
- So GeckoView could do the job too, but you'd write more of it yourself.

**Cross-origin iframes are solvable in WebView.**
- `WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOriginRules)` "will be executed in any frame whose origin matches allowedOriginRules", so the rule `"*"` covers YouTube and Vimeo embed iframes. [android](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- `addWebMessageListener` "works across all frames that match the origin rules". [android](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge) Each frame gets its own reply proxy, so native code can send a seek command to exactly the frame holding the video.
- The older `addJavascriptInterface` is injected into every frame with no origin information. [android](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge) [microsoft](https://learn.microsoft.com/pl-pl/dotnet/api/android.webkit.webview.addjavascriptinterface) GitHub advisory GHSA-7jp2-p2fw-mgvf ("Cross-origin iframe access token exfiltration via WebView JS bridge callback injection", rated High, published May 11, 2026) hit "All versions prior to 2026.4.4" of the Home Assistant Companion app for Android. The fix in 2026.4.4 was a new bridge, `externalAppV2`, which per Home Assistant's docs "requires the WebView to support WebViewFeature.WEB_MESSAGE_LISTENER". Don't use `addJavascriptInterface`.

**Platform rules that shape the design.**
- **Tablets and unfolded foldables.** Android's "Behavior changes: Apps targeting Android 16 or higher" says that for apps targeting API 36, "orientation, resizability, and aspect ratio restrictions no longer apply on displays with smallest width >= 600dp". `setRequestedOrientation()` is on its list of ignored APIs, with games (`android:appCategory`) exempt. The "Device compatibility mode" page adds that for apps targeting API 37 or higher the restrictions "will always be ignored", so the temporary opt-out goes away. On big screens you can only follow real rotation.
- **Gesture exclusion.** `setSystemGestureExclusionRects` normally takes effect over only 200dp of each edge's height, but Android's docs add: "The limit does not apply while the navigation bar is stickily hidden." [microsoft](https://learn.microsoft.com/en-us/dotnet/api/android.views.view.systemgestureexclusionrects?view=net-android-35.0&viewFallbackFrom=xamarin-android-sdk-12) [medium](https://medium.com/androiddevelopers/gesture-navigation-handling-gesture-conflicts-8ee9c2665c69) So in immersive fullscreen you can protect the full height of both edges from the back gesture.

**Current versions (October 2026).**

| Component | Version | Notes |
|---|---|---|
| Android Gradle Plugin | 9.4.0 (Sep 2026) | Requires Gradle 9.6.0 and JDK 17; supports API up to 37 | [android](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
| androidx.webkit | 1.17.1 stable (Sep 23, 2026); 1.18.0-alpha02 | `addDocumentStartJavaScript` since 1.9.0 | [android](https://developer.android.com/jetpack/androidx/releases/webkit) [github](https://github.com/flutter/packages/pull/12343)
| Media3 (optional) | 1.11.1 (Sep 10, 2026) | Only for the stretch-goal native player | [android](https://developer.android.com/jetpack/androidx/releases/media3) [android](https://developer.android.com/jetpack/androidx/versions/all-channel)
| GeckoView (if you go that way) | 157.0 (late Sep 2026; a third-party repo bumped to it, and APKMirror lists Firefox 157.0 uploaded September 27, 2026) | Check maven.mozilla.org |

## Engine Choice: WebView vs GeckoView vs Chromium Fork

| Factor | Android System WebView | GeckoView | Chromium fork (Cromite/Kiwi-style) |
|---|---|---|---|
| APK size | Tiny (a few MB); engine is a system app | Large; Firefox 155.0.1's APK is 63.48 MB (arm-v7a), 69.53 MB (arm64-v8a) and 70.25 MB (x86-64), per Wikipedia | Huge; you ship all of Chromium |
| Engine updates | Automatic through Play Store | You bump the dependency monthly for security fixes | You rebase and rebuild Chromium yourself |
| Rotate-to-fullscreen | Orientation-event exemption, plus Blink's own delegate as a bonus | Flip `full-screen-api.allow-trusted-requests-only` or use a built-in WebExtension | Full source control; you could patch Blink | [mozilla](https://bugzilla.mozilla.org/show_bug.cgi?id=1388479)
| Reaching cross-origin iframes | `addDocumentStartJavaScript("*")` + `addWebMessageListener` | WebExtension content scripts with `all_frames: true` | Anything |
| DRM (Widevine) | Works through platform MediaDrm; grant `RESOURCE_PROTECTED_MEDIA_ID` in `onPermissionRequest` | App must approve `PERMISSION_MEDIA_KEY_SYSTEM_ACCESS` (`media.eme.require-app-approval` is on by default in GeckoView) | Works if built in | [mozilla](https://bugzilla.mozilla.org/show_bug.cgi?id=1620102) [mozilla](https://hg.mozilla.org/releases/mozilla-beta/rev/9c9b960cb8bfb499892ac207fe864097c54e73ea)
| Ad blocking / extensions | DIY host blocking via `shouldInterceptRequest` | Real WebExtensions; uBlock Origin possible | Varies |
| Difficulty for a solo hobbyist | Lowest; huge amount of example code | Medium; fewer examples, more moving parts | Not realistic. Chromium's own Android build docs require "At least 100GB of free disk space" and say "More than 16GB [RAM] is highly recommended". Building works only on x86-64 Linux, and you'd rebase constantly |

**Decision: WebView.** It gives you the most control over fullscreen for the least effort, and the engine updates itself. Pick GeckoView only if real ad-block extensions matter more to you than simplicity; then start from Mozilla's Reference Browser (GeckoView + Android Components) and use `MediaSessionFullscreenFeature` as your model.

## The Hard Problems and the Chosen Solutions

### 1. Fullscreen without a user gesture

Ways to get fullscreen, best first:

1. **Request inside an orientation event (main path).** The injected script listens for both `window 'orientationchange'` and `screen.orientation 'change'`. Inside the handler, it calls `requestFullscreen()` right away, with no `await` before the call, and only if native code has "armed" it (the app itself caused this rotation because a video was playing). WebView turns the request into `WebChromeClient.onShowCustomView(view, callback)`.
2. **Blink's built-in delegate.** On videos with native `controls` it fires on its own. Calling `requestFullscreen()` on an element that is already fullscreen does nothing, so two requesters are harmless. [github](https://github.com/BenItBuhner/Zenium/pull/366) [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc)
3. **Fake fullscreen (fallback whenever the promise rejects).** This covers iframes without `allow="fullscreen"` and odd players. Native code hides the app bar and system bars. JS styles the player container `position:fixed; inset:0; z-index:2147483647; width:100vw; height:100vh; background:#000`, with `object-fit:contain` on the `<video>`. Inside an iframe, the parent frame's script styles the `<iframe>` element itself, found by matching `event.source` against each `iframe.contentWindow`.
4. **Synthetic tap (off by default, experimental).** `MotionEvent`s you dispatch to a WebView arrive as trusted input, so they count as user activation. Tapping the site's fullscreen button this way works but is fragile: the button moves, and a tap on the video usually toggles play/pause. Keep it behind a debug setting.

There is no WebView setting to switch off the gesture requirement. `setMediaPlaybackRequiresUserGesture(false)` only affects autoplay, not fullscreen.

**What to make fullscreen.** On custom players, fullscreening the bare `<video>` throws away the site's controls and captions. Walk up from the video to the nearest ancestor within about 10% of the video's size — that is the player container — and fullscreen that. Site adapters override it for YouTube (`.html5-video-player` / `#player-container-id`), Twitch and Vimeo.

### 2. Deciding which video is "being watched"

The document-start script runs in every frame and keeps a registry of media elements, found three ways:
- **Capture-phase listeners** for `play`, `playing`, `pause`, `ended`, `loadedmetadata` and `emptied` on `document`. Media events don't bubble, but capture listeners still see them.
- **Patching `play()`** on `HTMLMediaElement.prototype` to catch script-created elements.
- **Patching `attachShadow`.** Media events are not "composed", so they don't cross shadow-DOM boundaries (this matters for Reddit's `shreddit-player`). The wrapper adds the same listeners inside every shadow root, including closed ones, because the script runs before the page's own code.

Each frame scores its candidates: playing, at least 200 px in both dimensions, at least 75% visible (Chrome's thresholds), largest visible area wins. [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc) It reports `{frameId, playing, rect, duration, currentTime, isLive, videoW, videoH}`. Native code picks the "active" video across frames, preferring the most recent `playing` report.

### 3. Seeking a video inside a cross-origin iframe

The app never reaches into another origin's DOM. Every frame runs its own copy of the script with its own reply-proxy link to native code, so `{"cmd":"seekBy","delta":30}` goes only to the frame that owns the active video. Live streams report `duration === Infinity`; clamp seeks to `video.seekable.start(0)` … `video.seekable.end(last)`, and disable seeking if that window is under 30 s.

## Phased Build Plan

Each phase ends with tests you run on a real phone over adb. Don't start the next phase until they pass.

### Phase 0 — Development environment on MX Linux (no Android Studio needed)

**Goal:** go from source to an APK installed on your phone, all from the terminal.

**Tasks:**
1. Install a JDK: `sudo apt install openjdk-17-jdk unzip git`. AGP 9.4 requires JDK 17 minimum and uses it by default. [android](https://developer.android.com/build/releases/agp-9-4-0-release-notes) Check with `java -version`.
2. Install the SDK command-line tools:
   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
   # download "Command line tools only" for Linux from developer.android.com/studio
   unzip ~/Downloads/commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
   echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
   echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
   source ~/.bashrc
   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
   ```
   Use this SDK's `platform-tools` adb, not an old one already on your PATH (`which adb`).
3. Install Gradle once, only to generate the wrapper (SDKMAN: `sdk install gradle 9.6.0`). After that, always use `./gradlew`.
4. Write `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, the manifest and one `MainActivity`, then run `gradle wrapper --gradle-version 9.6.0`.
5. Build and install with `./gradlew installDebug`; launch with `adb shell am start -n <pkg>/.MainActivity`.
6. In debug builds call `WebView.setWebContentsDebuggingEnabled(true)`, then open `chrome://inspect` in Chromium on MX Linux to see injected-script consoles and DOM inside iframes.

**Acceptance:** `./gradlew assembleDebug` succeeds; `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs it; the app shows example.com; `chrome://inspect` lists the page.

### Phase 1 — Minimal real browser shell

**Goal:** a single-tab browser you could actually use.

**Files:** `MainActivity.kt` (address bar, back/forward/reload, progress bar), `BrowserWebView.kt` (settings and clients), `UrlInput.kt` (URL vs search), `res/layout/activity_main.xml`, `AndroidManifest.xml`.

**Key APIs and settings:**
- `WebSettings`: `javaScriptEnabled`, `domStorageEnabled`, `mediaPlaybackRequiresUserGesture = true` (keep the default).
- `WebViewClient.shouldOverrideUrlLoading` for `intent:`, `market:` and `tel:` links; `onReceivedError` / `onReceivedSslError` show an error page and never call `proceed()` silently.
- `OnBackPressedDispatcher` → `webView.goBack()`.
- Intent filters for `ACTION_VIEW` with `http`/`https` and `BROWSABLE`, so the app can be chosen as default browser.
- `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density"`, so rotating never recreates the Activity or reloads the page.
- minSdk 29 (gesture-exclusion APIs, consistent WebView features), targetSdk 36.

**Pitfalls:** with `targetSdk ≥ 35`, edge-to-edge is enforced, so pad the toolbar using `WindowInsetsCompat`. Don't call `addJavascriptInterface` anywhere.

**Acceptance:** `adb shell am start -a android.intent.action.VIEW -d https://en.m.wikipedia.org` offers your app; rotating on a long article keeps your scroll position with no reload; Back navigates history, then exits.

### Phase 2 — Real fullscreen plumbing

**Goal:** a site's own fullscreen button works properly.

**Files:** `fullscreen/FullscreenController.kt` (custom view, system bars, orientation, keep-screen-on), `fullscreen/FullscreenContainer.kt` (the `FrameLayout` that later handles seek gestures).

**Key APIs:**
- **Show:** `onShowCustomView(view, callback)` adds `view` to a full-window container on top of everything, hides the browser UI and keeps the `callback`. **Hide:** `onHideCustomView()` removes it and restores the UI. [unt](https://webarchive.library.unt.edu/web/20141030220733mp_/http://developer.android.com/reference/android/webkit/WebView.html)
- **System bars:** `WindowCompat.getInsetsController(window, decorView)`, `hide(WindowInsetsCompat.Type.systemBars())`, `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
- **Cutout:** `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` while fullscreen; restore after.
- **Screen on:** `FLAG_KEEP_SCREEN_ON` while fullscreen; clear on exit.
- **Back to exit:** an `OnBackPressedCallback`, enabled only while fullscreen, calls `callback.onCustomViewHidden()`.
- **Orientation:** remember the previous `requestedOrientation` and restore it on exit.

**Pitfalls:** some pages request fullscreen twice; if a custom view is already showing, call the new `callback.onCustomViewHidden()` immediately. Don't call `webView.onPause()` while fullscreen.

**Acceptance:** on a plain MP4 page and on m.youtube.com, the player's fullscreen button fills the screen with no status or nav bar; Back exits with the page in the same place; the screen doesn't dim during a 5-minute video.

### Phase 3 — Injected video tracker and bridge

**Goal:** native code always knows which video is active, in any frame.

**Files:** `assets/js/video-tracker.js`, `bridge/VideoBridge.kt` (the `WebMessageListener`, frameId → `JavaScriptReplyProxy` map), `bridge/VideoState.kt`, `debug/DebugHud.kt` (debug-only overlay).

**Key APIs:** check `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` and `WEB_MESSAGE_LISTENER`; register `WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))` and `WebViewCompat.addWebMessageListener(webView, "__vb", setOf("*"), listener)` before the first `loadUrl`.

**Security:** the bridge is visible to every site. Accept only a fixed set of message types, parse JSON with limits, clamp numbers, and never treat page data as code or URLs to open. A hostile page can at worst lie about its own video state.

**Acceptance:** in `chrome://inspect`, each frame logs `[vt] ready frame=<id>`, including the youtube-nocookie/vimeo iframe on an embed test page; the debug HUD updates within 500 ms of play/pause on a plain MP4, m.youtube.com, a YouTube-embed page, and reddit.com.

### Phase 4 — Rotate-to-fullscreen

**Goal:** headline feature 1.

**Files:** `rotation/RotationWatcher.kt` (`OrientationEventListener` with hysteresis), `rotation/RotateToFullscreen.kt` (state machine), `fullscreen/FakeFullscreen.kt`, `settings/Prefs.kt` ("Rotate to fullscreen", "Work even when auto-rotate is locked", "Exit on portrait" — all on).

**State machine (Chrome's rules, applied to every player):**
- **`IDLE`** (portrait, nothing fullscreen). Requested orientation is `SCREEN_ORIENTATION_USER`, which follows the system lock.
- **`IDLE → ENTERING`** when the watcher sees landscape (90°±25° or 270°±25°) held for 400 ms — matching Chrome's "lock-to-any" delay as described by the Zenium project — and the active video is playing and qualifies. [github](https://github.com/BenItBuhner/Zenium/pull/366) Send `{"cmd":"arm"}` to the video's frame and its ancestors. If auto-rotate is on, the page's orientation event arrives by itself; otherwise call `setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)`. The armed script calls `requestFullscreen()` in the handler and reports `fs-ok` or `fs-fail`.
- **`ENTERING → FULLSCREEN`** when `onShowCustomView` arrives. Keep `SENSOR_LANDSCAPE`, so a 180° turn flips the picture.
- **`ENTERING → FAKE_FULLSCREEN`** on `fs-fail`, or if no `onShowCustomView` arrives within 700 ms.
- **`FULLSCREEN/FAKE → IDLE`** when portrait (0°±25°) is held for 400 ms: call `customViewCallback.onCustomViewHidden()` (or fake-off), then set orientation back to `USER`.
- **Back press or the site's own exit** returns to `USER` orientation and requires portrait to be seen once before arming again (`NEED_PORTRAIT`); otherwise it loops while the phone is still sideways.
- **Ignored:** phone lying flat (`ORIENTATION_UNKNOWN`); paused video; portrait video (h > w — enter only when screen orientation matches video orientation, like Chrome); PiP or multi-window; large screens (`smallestScreenWidthDp ≥ 600`), where only real system rotations count. [googlesource](https://chromium.googlesource.com/chromium/src/+/HEAD/third_party/blink/renderer/modules/media_controls/media_controls_rotate_to_fullscreen_delegate.cc)

**Pitfalls:** read `Settings.System.ACCELEROMETER_ROTATION` to know if the lock is on, but never change it (that needs `WRITE_SETTINGS`). `OrientationEventListener` reports device angle, not display rotation; map it carefully on natural-landscape devices. Disable the listener in `onPause`.

**Acceptance:**
- Auto-rotate locked, plain MP4 playing: turn the phone → fullscreen within about 1 s; turn back → exits; `adb shell settings get system accelerometer_rotation` still returns `0`.
- m.youtube.com: rotate → fullscreen with YouTube's controls; repeat 20 times with no stuck states.
- Paused video: nothing happens. No video: rotation behaves as the system setting says.
- Simulate the auto-rotate-on path with `adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1` (then `0`); watch `adb logcat -s RotateFS`.
- YouTube iframe embed page: rotate → real or fake fullscreen, never a half state.

### Phase 5 — Swipe-to-seek in fullscreen

**Goal:** headline feature 2.

**Files:** `gesture/SeekGestureLayout.kt` (upgrades `FullscreenContainer`), `gesture/SeekMath.kt` (pure, unit-tested), `gesture/SeekHud.kt` (centered pill with change and target time).

**Approach:**
- `onInterceptTouchEvent` returns `false` on `ACTION_DOWN`, so taps still reach the page. On `ACTION_MOVE`, once `|dx| > touchSlop*2` and `|dx| > 2·|dy|`, it returns `true`; children get `ACTION_CANCEL`. Vertical swipes are left alone (reserved for brightness/volume). A second finger cancels.
- Mapping: `secondsPerWidth = clamp(duration × 0.2, 30, 300)`, never more than `duration`; change = `dx / width × secondsPerWidth`, rounded to whole seconds.
- Show "+0:45 → 12:34 / 20:00" while dragging and commit on `ACTION_UP` — the YouTube/MX Player feel, and it avoids flooding MSE players with seeks. Optional "live scrub" throttles writes to one per 250 ms.
- Edges: full-height 32dp gesture-exclusion strips on both sides (the 200dp cap doesn't apply while the nav bar is stickily hidden), plus a 16dp dead zone as backup. [microsoft](https://learn.microsoft.com/dotnet/api/android.views.view.systemgestureexclusionrects)
- Live streams: clamp to the seekable range; show "LIVE" and disable seeking if the window is under 30 s.

**Acceptance:** `adb shell input swipe 300 600 900 600 400` on a fullscreen MP4 seeks forward by the expected amount (check HUD and logged `currentTime`); the reverse swipe goes back; a quick tap still toggles the site's controls; YouTube's double-tap ±10 s still works; edge swipes with gesture navigation don't trigger Back; on Twitch live, seeking stays inside the DVR window or is disabled.

### Phase 6 — Site adapters and hardening

**Goal:** make the test matrix pass. **Files:** `assets/js/adapters/{youtube,twitch,vimeo,reddit,x,facebook}.js`, each `{match(host), container(video), fullscreen(), exit()}`.

**Notes:** YouTube mobile calls `screen.orientation.lock()` from its own fullscreen, which fails in WebView, so native code must set landscape itself. Re-scan on SPA navigations that swap the `<video>` (YouTube's `yt-navigate-finish`, `popstate`, `MutationObserver`).

**Acceptance:** every test-matrix row reaches "pass" or a documented known limitation.

### Phase 7 — Browser essentials

- **Downloads:** `setDownloadListener` → `DownloadManager` with `URLUtil.guessFileName` and `CookieManager` cookies; defer `blob:` downloads.
- **File upload:** `onShowFileChooser` → `ActivityResultContracts.OpenMultipleDocuments`.
- **Permissions:** camera/mic/geolocation via `onPermissionRequest` / `onGeolocationPermissionsShowPrompt` with remembered per-origin choices; DRM `RESOURCE_PROTECTED_MEDIA_ID` prompts once per site.
- **Desktop-site toggle:** strip "Mobile"/"Android" from the user agent, set `useWideViewPort`, reload.
- **History/bookmarks** in Room; **tabs** as a list of `WebView`s (stay single-tab until everything else is solid); a **settings screen** for toggles and seek sensitivity.

**Acceptance:** a PDF downloads; an image uploads to a test form; a camera test page asks for access; the desktop toggle changes YouTube's layout; history survives a restart.

### Phase 8 — Stretch goals

Inline (non-fullscreen) swipe-seek limited to the video's rect; double-tap ±10 s; vertical swipes for brightness/volume; picture-in-picture (`setAutoEnterEnabled`, API 31+); background audio (fragile in WebView — experimental); host-list ad blocking via `shouldInterceptRequest`; a Media3 1.11.x native player only for plain `http(s)` MP4/HLS that isn't `blob:`/MSE or DRM.

## Code Sketches

### RotationWatcher.kt (hysteresis and dwell time)

```kotlin
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
        if (candidate != reported) { reported = candidate; onStable(candidate) }
    }

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
        override fun onOrientationChanged(deg: Int) {
            val p = when {
                deg == ORIENTATION_UNKNOWN -> return          // phone flat: ignore
                deg <= band || deg >= 360 - band -> Posture.PORTRAIT
                deg in (90 - band)..(90 + band) -> Posture.REVERSE_LANDSCAPE
                deg in (270 - band)..(270 + band) -> Posture.LANDSCAPE
                else -> return                                // in-between zone = hysteresis
            }
            if (p != candidate) {
                candidate = p
                handler.removeCallbacks(confirm)
                handler.postDelayed(confirm, dwellMs)
            }
        }
    }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun stop() { listener.disable(); handler.removeCallbacks(confirm) }
}
```

### RotateToFullscreen.kt (core)

```kotlin
class RotateToFullscreen(
    private val activity: ComponentActivity,
    private val bridge: VideoBridge,
    private val fullscreen: FullscreenController,
    private val prefs: Prefs
) {
    private enum class S { IDLE, ENTERING, FULL, FAKE, NEED_PORTRAIT }
    private var s = S.IDLE
    private val main = Handler(Looper.getMainLooper())
    private val fallback = Runnable { if (s == S.ENTERING) enterFake() }
    private val isLargeScreen get() =
        activity.resources.configuration.smallestScreenWidthDp >= 600

    fun onPosture(p: RotationWatcher.Posture) {
        when {
            p == RotationWatcher.Posture.PORTRAIT -> when (s) {
                S.FULL, S.FAKE, S.ENTERING -> exit()
                S.NEED_PORTRAIT -> s = S.IDLE
                else -> {}
            }
            s == S.IDLE && prefs.rotateToFullscreen -> {
                val v = bridge.activeVideo ?: return
                if (!v.playing || !v.qualifies() || v.isPortraitVideo) return
                s = S.ENTERING
                bridge.arm(v.frameId)
                if (!isLargeScreen) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                main.postDelayed(fallback, 700)
            }
        }
    }

    fun onRealFullscreenShown() { main.removeCallbacks(fallback); s = S.FULL }      // from onShowCustomView
    fun onFullscreenFailed() { if (s == S.ENTERING) { main.removeCallbacks(fallback); enterFake() } }
    fun onUserExited() { restore(); s = S.NEED_PORTRAIT }                            // back press / page exit

    private fun enterFake() {
        val v = bridge.activeVideo ?: return exit()
        bridge.send(v.frameId, """{"cmd":"fake","on":true}""")
        fullscreen.enterFake()
        s = S.FAKE
    }

    private fun exit() {
        main.removeCallbacks(fallback)
        when (s) {
            S.FULL -> fullscreen.hideCustomView()           // CustomViewCallback.onCustomViewHidden()
            S.FAKE -> { bridge.activeVideo?.let { bridge.send(it.frameId, """{"cmd":"fake","on":false}""") }
                        fullscreen.exitFake() }
            else -> bridge.activeVideo?.let { bridge.send(it.frameId, """{"cmd":"exit"}""") }
        }
        restore(); s = S.IDLE
    }

    private fun restore() { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER }
}
```

### video-tracker.js (document-start, every frame)

```javascript
(() => {
  if (window.__vtInstalled) return; window.__vtInstalled = true;
  const bridge = window.__vb;                 // injected by addWebMessageListener
  if (!bridge) return;
  const frameId = Math.random().toString(36).slice(2);
  const vids = new Set();
  let armedUntil = 0, fakeEl = null;
  const send = (o) => { try { bridge.postMessage(JSON.stringify({ frameId, ...o })); } catch (e) {} };
  const track = (v) => { if (v instanceof HTMLVideoElement) vids.add(v); };

  const EVTS = ['play','playing','pause','ended','loadedmetadata','emptied','seeked'];
  const hook = (root) => EVTS.forEach(t => root.addEventListener(t, e => { track(e.target); report(); }, true));
  hook(document);
  const origAttach = Element.prototype.attachShadow;      // media events aren't composed
  Element.prototype.attachShadow = function (init) { const r = origAttach.call(this, init); hook(r); return r; };
  const origPlay = HTMLMediaElement.prototype.play;       // script-created players
  HTMLMediaElement.prototype.play = function () { track(this); return origPlay.apply(this, arguments); };

  function pick() {
    let best = null, bestArea = 0;
    for (const v of vids) {
      if (!v.isConnected) { vids.delete(v); continue; }
      const r = v.getBoundingClientRect();
      const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
      const vis = (w * h) / Math.max(1, r.width * r.height);
      const ok = !v.paused && !v.ended && r.width >= 200 && r.height >= 200 && vis >= 0.75;
      if (ok && w * h > bestArea) { best = v; bestArea = w * h; }
    }
    return best;
  }
  let t = 0;
  function report() {
    clearTimeout(t);
    t = setTimeout(() => {
      const v = pick();
      if (!v) return send({ type: 'state', playing: false });
      const r = v.getBoundingClientRect();
      send({ type: 'state', playing: true, duration: v.duration, currentTime: v.currentTime,
             isLive: v.duration === Infinity, videoW: v.videoWidth, videoH: v.videoHeight,
             rect: [r.left, r.top, r.width, r.height], dpr: devicePixelRatio, top: window === top });
    }, 150);
  }
  addEventListener('scroll', report, { passive: true, capture: true });
  setInterval(report, 2000);

  function container(v) {                     // nearest ancestor roughly the video's size
    const vr = v.getBoundingClientRect(); let el = v, best = v;
    while ((el = el.parentElement || (el.getRootNode() && el.getRootNode().host))) {
      const r = el.getBoundingClientRect();
      if (r.width > vr.width * 1.1 || r.height > vr.height * 1.25) break;
      best = el;
    }
    return (window.__vtAdapter && window.__vtAdapter.container(v)) || best;
  }

  function onOrient() {                       // Blink allows fullscreen here without a gesture
    if (Date.now() > armedUntil) return;
    armedUntil = 0;
    const v = pick(); if (!v || document.fullscreenElement) return;
    const el = container(v);
    const p = (el.requestFullscreen || el.webkitRequestFullscreen).call(el); // synchronous call
    if (p && p.then) p.then(() => send({ type: 'fs-ok' }), () => send({ type: 'fs-fail' }));
  }
  addEventListener('orientationchange', onOrient);
  if (screen.orientation) screen.orientation.addEventListener('change', onOrient);

  const FAKE = ';position:fixed!important;inset:0!important;width:100vw!important;' +
               'height:100vh!important;z-index:2147483647!important;background:#000!important';
  function fake(on) {
    const v = pick() || [...vids][0]; if (!v) return;
    if (on) { fakeEl = container(v); fakeEl.dataset.vtCss = fakeEl.style.cssText;
              fakeEl.style.cssText += FAKE; v.style.objectFit = 'contain'; }
    else if (fakeEl) { fakeEl.style.cssText = fakeEl.dataset.vtCss || ''; fakeEl = null; }
    if (window !== top) parent.postMessage({ __vt: 'fake-iframe', on }, '*');
  }
  addEventListener('message', (e) => {        // parent side: style the <iframe> that asked
    if (!e.data || e.data.__vt !== 'fake-iframe') return;
    for (const f of document.querySelectorAll('iframe')) if (f.contentWindow === e.source) {
      if (e.data.on) { f.dataset.vtCss = f.style.cssText; f.style.cssText += FAKE; }
      else f.style.cssText = f.dataset.vtCss || '';
    }
  });

  function clampSeek(v, target) {
    if (v.duration === Infinity && v.seekable.length)
      return Math.min(Math.max(target, v.seekable.start(0)), v.seekable.end(v.seekable.length - 1) - 1);
    return Math.min(Math.max(target, 0), Math.max(0, v.duration - 0.5));
  }
  bridge.onmessage = (e) => {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    const v = pick() || [...vids].find(x => !x.paused) || [...vids][0];
    switch (m.cmd) {
      case 'arm': armedUntil = Date.now() + 1500; break;
      case 'seekBy': if (v && isFinite(+m.delta)) v.currentTime = clampSeek(v, v.currentTime + +m.delta); report(); break;
      case 'seekTo': if (v && isFinite(+m.t)) v.currentTime = clampSeek(v, +m.t); report(); break;
      case 'exit': if (document.fullscreenElement) document.exitFullscreen(); break;
      case 'fake': fake(!!m.on); break;
      case 'query': report(); break;
    }
  };
  send({ type: 'ready', top: window === top, origin: location.origin });
})();
```

### VideoBridge.kt (one reply proxy per frame)

```kotlin
class VideoBridge(private val webView: WebView, private val onEvent: (BridgeEvent) -> Unit) {
    private val proxies = mutableMapOf<String, JavaScriptReplyProxy>()
    private val states = mutableMapOf<String, VideoState>()
    val activeVideo: VideoState? get() = states.values.filter { it.playing }.maxByOrNull { it.updatedAt }

    fun install(trackerJs: String) {
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        require(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        WebViewCompat.addWebMessageListener(webView, "__vb", setOf("*")) { _, msg, origin, isMain, reply ->
            val json = runCatching { JSONObject(msg.data ?: return@addWebMessageListener) }.getOrNull()
                ?: return@addWebMessageListener
            val id = json.optString("frameId").take(16).ifEmpty { return@addWebMessageListener }
            proxies[id] = reply
            when (json.optString("type")) {
                "state" -> states[id] = VideoState.from(id, json, origin.toString(), isMain)
                "fs-ok" -> onEvent(BridgeEvent.FullscreenOk(id))
                "fs-fail" -> onEvent(BridgeEvent.FullscreenFailed(id))
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, trackerJs, setOf("*"))
    }

    fun send(frameId: String, json: String) { proxies[frameId]?.postMessage(json) }
    fun arm(frameId: String) = proxies.values.forEach { it.postMessage("""{"cmd":"arm"}""") } // ancestors too
    fun seekBy(delta: Double) = activeVideo?.let { send(it.frameId, """{"cmd":"seekBy","delta":$delta}""") }
    fun onNavigation() { proxies.clear(); states.clear() }    // call from main-frame onPageStarted
}
```

### SeekGestureLayout.kt (taps pass through, horizontal drags get claimed)

```kotlin
class SeekGestureLayout(ctx: Context) : FrameLayout(ctx) {
    var bridge: VideoBridge? = null
    var hud: SeekHud? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private val edgePx = (16 * resources.displayMetrics.density).toInt()
    private var downX = 0f; private var downY = 0f; private var seeking = false; private var startT = 0.0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val strip = (32 * resources.displayMetrics.density).toInt()
        // Full-height exclusion is honoured while nav bars are stickily hidden (immersive)
        systemGestureExclusionRects = listOf(Rect(0, 0, strip, height), Rect(width - strip, 0, width, height))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; seeking = false }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || downX < edgePx || downX > width - edgePx) return false
                val dx = e.x - downX; val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > 2 * abs(dy)) {
                    val v = bridge?.activeVideo ?: return false
                    if (v.isLive && v.seekableWindow < 30) return false
                    seeking = true; startT = v.currentTime; downX = e.x   // children get ACTION_CANCEL
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
            MotionEvent.ACTION_MOVE -> hud?.show(delta, SeekMath.clamp(startT + delta, v), v.duration)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { seeking = false; hud?.hide() }
            MotionEvent.ACTION_UP -> { bridge?.seekBy(delta); seeking = false; hud?.hideDelayed() }
        }
        return true
    }
}

object SeekMath {
    fun delta(dx: Float, widthPx: Int, duration: Double): Double {
        val perWidth = if (duration.isFinite()) (duration * 0.2).coerceIn(30.0, 300.0).coerceAtMost(duration) else 60.0
        return Math.round(dx / widthPx * perWidth).toDouble()
    }
    fun clamp(t: Double, v: VideoState) = t.coerceIn(0.0, if (v.duration.isFinite()) v.duration else t)
}
```

In `FullscreenController.onShowCustomView`, put WebView's custom `view` inside a `SeekGestureLayout` added to `decorView`; the site's controls live inside `view`, so taps reach them normally.

## Starting Point: Fork or Write It Yourself?

**Recommendation: write it yourself on WebView, and borrow ideas.** A purpose-built app of about 15–25 Kotlin files is something one person can understand completely. A full browser fork has thousands of files of features you don't need.

| Project | Engine | License | Use it for |
|---|---|---|---|
| Fulguris | WebView, Kotlin | Mixed MPL-2.0 (old Lightning code) + CPAL-1.0 (newer files); F-Droid v2.0.10, Apr 2026 | Tabs, downloads, settings, ad blocking reference. CPAL requires visible attribution if you copy code | [github +2](https://github.com/Slion/Fulguris/blob/main/LICENSE)
| Mozilla Reference Browser / Android Components | GeckoView | MPL-2.0 | The model if you choose GeckoView |
| FOSS Browser, EinkBro, Privacy Browser | WebView | Copyleft; check each LICENSE before copying | Small, readable WebView browsers |
| Cromite, Kiwi | Chromium forks | — | Not viable to build alone |

**Reference implementations of your two features:**
- **Greasy Fork "Mobile Video Seek Gesture"** (MIT, v9.3, updated September 2026): "swipe left or right to seek the video, long press to speed up", with a `SEEK_SENSITIVITY` constant. [greasyfork](https://greasyfork.org/en/scripts/524654-mobile-video-seek-gesture)
- **Greasy Fork "HTML5 Video Gestures"**: horizontal swipe-to-seek, vertical brightness/volume, stacked double-tap, and "Edge Anti-touch" so screen edges don't trigger swipes. [greasyfork](https://greasyfork.org/en/scripts/570331-html5%E8%A7%86%E9%A2%91%E6%89%8B%E5%8A%BF)
- **videojs-mobile-ui**: an `enterOnRotate` option — the in-page version of rotate-to-fullscreen. [github](https://github.com/mister-ben/videojs-mobile-ui)
- **Zenium PR #366**: a full WebView rotate-to-fullscreen design (OFF → HELD → FOLLOWING, a 400 ms hand-off) with useful edge cases. [github](https://github.com/BenItBuhner/Zenium/pull/366)

## Project Structure

```
rotatebrowser/
├── SPEC.md
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── app/build.gradle.kts             # minSdk 29, targetSdk 36, compileSdk 36
└── app/src/main/
    ├── AndroidManifest.xml
    ├── assets/js/video-tracker.js
    ├── assets/js/adapters/{youtube,twitch,vimeo,reddit,x,facebook}.js
    └── java/dev/you/rotatebrowser/
        ├── MainActivity.kt
        ├── browser/{BrowserWebView.kt, UrlInput.kt, BrowserChromeClient.kt, BrowserViewClient.kt, Downloads.kt, Permissions.kt}
        ├── bridge/{VideoBridge.kt, VideoState.kt, BridgeEvent.kt}
        ├── fullscreen/{FullscreenController.kt, FakeFullscreen.kt}
        ├── rotation/{RotationWatcher.kt, RotateToFullscreen.kt}
        ├── gesture/{SeekGestureLayout.kt, SeekMath.kt, SeekHud.kt}
        ├── settings/{Prefs.kt, SettingsActivity.kt}
        └── debug/DebugHud.kt
```

## Ready-to-Paste Project Instructions

### SPEC.md

```markdown
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
- JDK 17, Android SDK cmdline-tools at ~/Android/Sdk, AGP 9.4.0, Gradle wrapper 9.6.0.
- Build: ./gradlew assembleDebug   Install: ./gradlew installDebug   Logs: adb logcat -s RotateFS:V VideoBridge:V SeekGesture:V
- Debug JS: WebView.setWebContentsDebuggingEnabled(true) in debug builds; inspect via chrome://inspect.
- minSdk 29, targetSdk 36. Kotlin only. Non-deprecated APIs (WindowInsetsControllerCompat, OnBackPressedDispatcher).

## Rules
- Work phase by phase from PLAN.md. After each phase: build, install, and tell me the exact adb/manual tests to run.
- Pure logic (SeekMath, state machine transitions) gets JVM unit tests.
- Treat every bridge message as untrusted: fixed message types, JSON parse with limits, clamp numbers.
- Never silently proceed on SSL errors. Never change system settings (no WRITE_SETTINGS).
- If an API behaves differently than this file assumes, stop and report with logcat + chrome://inspect evidence.
```

### Per-phase prompts

1. **Phase 0:** "Read SPEC.md. Hand-write a Gradle project (AGP 9.4.0, wrapper 9.6.0, version catalog, minSdk 29, targetSdk 36) with one MainActivity showing a WebView on https://example.com and debugging on in debug builds. Give me exact commands to generate the wrapper, build, install over adb, and verify my JDK and SDK."
2. **Phase 1:** "Implement Phase 1 from PLAN.md, then give me its test checklist."
3. **Phase 2:** "Implement Phase 2 from PLAN.md (FullscreenController, immersive mode, cutout, keep-screen-on, back-to-exit, exact restore)."
4. **Phase 3:** "Implement Phase 3 from PLAN.md using the video-tracker.js and VideoBridge sketches; add the debug HUD."
5. **Phase 4:** "Implement Phase 4 from PLAN.md. First build a 20-line test page proving requestFullscreen() works inside an orientation handler in this WebView, and report the result. Make the state machine a pure Kotlin class with JVM tests: paused video, portrait video, flapping angles, back-press exit, auto-rotate on vs off."
6. **Phase 5:** "Implement Phase 5 from PLAN.md using the SeekGestureLayout/SeekMath sketches; unit-test SeekMath."
7. **Phase 6:** "Implement Phase 6 site adapters, then walk the PLAN.md test matrix with me one site at a time."
8. **Phase 7:** "Implement Phase 7 browser essentials from PLAN.md."

## Test Matrix

| # | Site / scenario | Expected: rotate-to-fullscreen | Expected: swipe-to-seek | Known issues |
|---|---|---|---|---|
| 1 | Plain MP4 (e.g., Big Buck Bunny MP4 opened directly) | Real fullscreen; Blink's delegate may fire too | Works | None expected |
| 2 | m.youtube.com watch page | Real fullscreen on player container (adapter); YouTube controls kept | Works; YouTube double-tap still works | SPA swaps the `<video>`; `screen.orientation.lock()` fails in WebView; ads change the active video |
| 3 | Page with a YouTube iframe embed | Real fullscreen from inside the iframe if it has `allow="fullscreen"`; otherwise fake fullscreen styles the `<iframe>` | Works (seek goes to the iframe's frame) | Whether orientation events reach the iframe in time is unconfirmed; fake fallback covers it |
| 4 | Vimeo (vimeo.com and player.vimeo.com embed) | Real fullscreen | Works | Custom player; may need adapter container |
| 5 | Twitch live | Real fullscreen | DVR window only or disabled; VOD works | Little or no seekable range |
| 6 | Reddit (`shreddit-player`) | Real or fake | Works | Shadow DOM (handled by attachShadow hook) |
| 7 | X/Twitter, Facebook mobile | Real or fake (Facebook likely fake) | Works on longer videos; Facebook partial | Aggressive DOM recycling; Facebook breaks often, lowest priority |
| 8 | HLS stream (Mux test stream or hls.js demo) | Real fullscreen | Works (MSE seek) | Seeks land on segment boundaries |
| 9 | DRM (Shaka Player or Bitmovin Widevine demo) | Real fullscreen after protected-media prompt | Works | Native-player route impossible |
| 10 | Auto-rotate ON / OFF | ON: system rotation triggers it. OFF: app sensor triggers it; setting untouched | — | Must not loop after a back-press exit |
| 11 | Paused video; 180° flip while fullscreen | Paused: nothing. Flip: picture flips, stays fullscreen | — | — |
| 12 | Tablet / unfolded foldable (sw ≥ 600dp) | Only with real system rotation | Works | Android 16+ ignores `setRequestedOrientation` there | [android](https://developer.android.com/about/versions/16/behavior-changes-16)

## Caveats

- **Re-test the secondhand claims first.** The orientation-event exemption is documented by Blink's own announcement, but how it behaves in WebView on your phone comes from one third-party project (Zenium): that `screen.orientation` change counts as well as `orientationchange`, that Blink's rotate delegate is active in WebView, and that `screen.orientation.lock()` is disabled. [github](https://github.com/BenItBuhner/Zenium/pull/366) [google](https://groups.google.com/a/chromium.org/g/blink-dev/c/Z2fllXoSjcU/m/P-kHtCVJBQAJ) Phase 4 starts with a test page proving `requestFullscreen()` succeeds inside the handler. If it doesn't, the app uses fake fullscreen everywhere, which still meets your requirement with less polish.
- **Iframes and the exemption.** Whether a cross-origin iframe's own orientation handler gets the same exemption in time is unconfirmed; the parent-styles-the-iframe fallback exists for this.
- **Firefox behavior** comes from Android Components code (a Firefox 134 snapshot) and bug reports; Mozilla might add rotate-to-enter later. GeckoView DRM support in third-party apps is inferred from its EME permission design (`media.eme.require-app-approval`), not a direct Mozilla statement. [fossies](https://fossies.org/linux/www/firefox-134.0.source.tar.xz/firefox-134.0/mobile/android/android-components/components/feature/media/src/main/java/mozilla/components/feature/media/fullscreen/MediaSessionFullscreenFeature.kt) [mozilla](https://bugzilla.mozilla.org/show_bug.cgi?id=1620102)
- **Version numbers move.** GeckoView 157 comes from a third-party repo's version bump and an APKMirror listing of Firefox 157.0 uploaded September 27, 2026, not from Mozilla's own release page. Check the latest AGP, androidx.webkit and Media3 versions before Phase 0.
- **Site breakage is permanent maintenance.** YouTube, Facebook and X change their DOMs often. Generic detection (playing, size, visibility) keeps the basic feature working when an adapter breaks.
- **Security trade-off of `"*"` origin rules.** The bridge is visible to every site you visit. Keep its command set tiny and validated; never add file, URL-opening or settings commands to it.