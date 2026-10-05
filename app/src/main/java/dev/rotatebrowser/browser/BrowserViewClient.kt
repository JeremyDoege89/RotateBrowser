package dev.rotatebrowser.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class BrowserViewClient(
    private val onPageUrl: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onPageStarted: () -> Unit = {}
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        return when (url.scheme) {
            "http", "https" -> false
            "intent" -> {
                runCatching {
                    val intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    view.context.startActivity(intent)
                }
                true
            }
            "tel", "mailto", "geo", "market" -> {
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                true
            }
            else -> false
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onPageUrl(url ?: "")
        onProgress(10)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageUrl(url ?: "")
        onProgress(100)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
    }
}
