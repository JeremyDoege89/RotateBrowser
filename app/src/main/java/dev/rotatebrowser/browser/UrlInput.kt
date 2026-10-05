package dev.rotatebrowser.browser

import android.net.Uri

object UrlInput {

    fun resolve(input: String): String {
        if (input.isBlank()) return "https://example.com"

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input
        }

        if (looksLikeUrl(input)) {
            return "https://$input"
        }

        return "https://www.google.com/search?q=${Uri.encode(input)}"
    }

    private fun looksLikeUrl(input: String): Boolean {
        if (input.contains(' ')) return false
        val dot = input.indexOf('.')
        if (dot <= 0 || dot >= input.length - 1) return false
        val tld = input.substringAfterLast('.').substringBefore('/')
        return tld.length in 2..10
    }
}
