package dev.rotatebrowser.bridge

import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class VideoBridge(
    private val webView: WebView,
    private val onEvent: (BridgeEvent) -> Unit
) {
    private val proxies = mutableMapOf<String, JavaScriptReplyProxy>()
    private val states = mutableMapOf<String, VideoState>()

    val activeVideo: VideoState?
        get() = states.values
            .filter { it.playing }
            .maxByOrNull { it.updatedAt }

    fun install(trackerJs: String) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "WEB_MESSAGE_LISTENER not supported"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "DOCUMENT_START_SCRIPT not supported"
        }

        WebViewCompat.addWebMessageListener(
            webView, "__vb", setOf("*")
        ) { _, msg, origin, isMain, reply ->
            val data = msg.data ?: return@addWebMessageListener
            if (data.length > 4096) return@addWebMessageListener

            val json = runCatching { JSONObject(data) }.getOrNull()
                ?: return@addWebMessageListener
            val id = json.optString("frameId").take(16)
            if (id.isEmpty()) return@addWebMessageListener

            proxies[id] = reply

            when (json.optString("type").take(16)) {
                "state" -> {
                    val state = VideoState.from(id, json, origin.toString(), isMain)
                    states[id] = state
                    onEvent(BridgeEvent.VideoStateUpdate(id, state))
                    Log.d("VideoBridge", "state frame=$id playing=${state.playing}")
                }
                "fs-ok" -> {
                    Log.d("VideoBridge", "fullscreen ok frame=$id")
                    onEvent(BridgeEvent.FullscreenOk(id))
                }
                "fs-fail" -> {
                    Log.d("VideoBridge", "fullscreen failed frame=$id")
                    onEvent(BridgeEvent.FullscreenFailed(id))
                }
                "ready" -> {
                    val isTop = json.optBoolean("top", isMain)
                    val o = json.optString("origin").take(256)
                    Log.d("VideoBridge", "ready frame=$id top=$isTop origin=$o")
                    onEvent(BridgeEvent.Ready(id, isTop, o))
                }
            }
        }

        WebViewCompat.addDocumentStartJavaScript(webView, trackerJs, setOf("*"))
        Log.d("VideoBridge", "installed tracker + listener")
    }

    fun send(frameId: String, json: String) {
        proxies[frameId]?.postMessage(json)
    }

    fun arm(frameId: String) {
        proxies.values.forEach { it.postMessage("""{"cmd":"arm"}""") }
    }

    fun seekBy(delta: Double) {
        activeVideo?.let { send(it.frameId, """{"cmd":"seekBy","delta":$delta}""") }
    }

    fun onNavigation() {
        proxies.clear()
        states.clear()
    }
}
