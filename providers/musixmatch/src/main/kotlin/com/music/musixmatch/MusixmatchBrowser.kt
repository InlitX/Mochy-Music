package com.music.musixmatch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resume

/** a hidden browser tab: musixmatch's song pages, and a web search when plain http is turned away, load in it like in a browser */
internal object MusixmatchBrowser {
    private const val Site = "https://www.musixmatch.com"
    private const val LoadTimeoutMs = 25_000L
    private const val PollMs = 250L
    private const val IdleMs = 60_000L

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val releaseWhenIdle = Runnable { release() }
    private var webView: WebView? = null

    /** the whole page at [url] once it has finished loading, or null when it never did */
    suspend fun html(context: Context, url: String): String? = withTab(context) { view ->
        view.loadUrl(url)
        val script = "(function(){return location.hostname==${JsonPrimitive(java.net.URI(url).host)}" +
            "&&document.readyState=='complete'?document.documentElement.outerHTML:null;})()"
        withTimeoutOrNull(LoadTimeoutMs) {
            var page: String? = null
            while (page == null) {
                page = evaluate(view, script)?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull }.getOrNull() }
                if (page == null) delay(PollMs)
            }
            page
        }
    }

    /** the page's next.js data once [path] has loaded past the check, or null when it never did */
    suspend fun nextData(context: Context, path: String): String? = withTab(context) { view ->
        view.loadUrl("$Site$path")
        val data = withTimeoutOrNull(LoadTimeoutMs) {
            var found: String? = null
            var walled = false
            while (found == null && !walled) {
                val state = evaluate(view, StateScript)?.let(::decode)
                // the sign-in wall: musixmatch wants an account for this, nothing to read
                walled = state?.host == "auth.musixmatch.com"
                if (state != null && state.host == "www.musixmatch.com" && state.path.trimEnd('/') == path.trimEnd('/')) found = state.data
                if (found == null && !walled) delay(PollMs)
            }
            found
        }
        if (data == null) android.util.Log.w("Musixmatch", "$path didn't load in the browser")
        data
    }

    private suspend fun <T> withTab(context: Context, block: suspend (WebView) -> T): T = mutex.withLock {
        withContext(Dispatchers.Main) {
            main.removeCallbacks(releaseWhenIdle)
            try {
                block(webView ?: createTab(context))
            } finally {
                main.postDelayed(releaseWhenIdle, IdleMs)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createTab(context: Context): WebView {
        // the webview's first start is heavy: the sheet that asked gets to finish opening first
        delay(600)
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            // never shown, but a zero-size view with paused timers can stall the check's script
            layout(0, 0, 360, 640)
            onResume()
            resumeTimers()
        }.also { webView = it }
    }

    private class PageState(val host: String, val path: String, val data: String?)

    // evaluateJavascript hands values back as json: a json string that itself holds the object we built
    private fun decode(raw: String): PageState? = runCatching {
        val inner = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull ?: return null
        val state = Json.parseToJsonElement(inner) as JsonObject
        PageState(
            host = (state["host"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            path = (state["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            data = (state["data"] as? JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private fun release() {
        main.removeCallbacks(releaseWhenIdle)
        val view = webView ?: return
        webView = null
        runCatching {
            view.stopLoading()
            view.destroy()
        }
    }

    // where the tab is, and the page's data once the real page (not the check's) is showing
    private const val StateScript = "(function(){var n=document.getElementById('__NEXT_DATA__');" +
        "return JSON.stringify({host:location.hostname,path:decodeURIComponent(location.pathname),data:n?n.textContent:null});})()"
}
