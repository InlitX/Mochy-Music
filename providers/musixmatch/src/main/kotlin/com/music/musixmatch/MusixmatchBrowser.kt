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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * A hidden browser tab on musixmatch.com, for when plain requests are turned away. Musixmatch sits
 * behind an aws bot check that only a real browser passes, and on some networks a pass cookie
 * alone isn't enough, so the requests themselves are made from inside the page with fetch(), the
 * same way the site's own pages make them. One tab, kept a minute after its last use.
 */
internal object MusixmatchBrowser {
    private const val Site = "https://www.musixmatch.com"
    // the one page the bot check always stops, so loading it is how the check gets passed
    private const val Landing = "$Site/search?query=lyrics"
    private const val ReadyTimeoutMs = 25_000L
    private const val FetchTimeoutMs = 20_000L
    private const val PollMs = 150L
    private const val IdleMs = 60_000L

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val releaseWhenIdle = Runnable { release() }
    private var webView: WebView? = null
    private var ready = false
    private var calls = 0

    @Volatile
    var userAgent: String? = null
        private set

    fun restore(savedUserAgent: String?) {
        if (userAgent == null) userAgent = savedUserAgent
    }

    fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(Site) }.getOrNull()

    /** [url] fetched from inside the page: its http status and body, or null when the tab never got past the check */
    suspend fun fetch(context: Context, url: String, accept: String): Pair<Int, String>? = mutex.withLock {
        withContext(Dispatchers.Main) {
            main.removeCallbacks(releaseWhenIdle)
            try {
                repeat(2) {
                    val view = readyTab(context) ?: return@withContext null
                    val reply = withTimeoutOrNull(FetchTimeoutMs) { fetchIn(view, url, accept) } ?: return@withContext null
                    // the pass ran out between calls: the tab goes through the check again, once
                    if (reply.first != 202 && reply.first != 405) return@withContext reply
                    ready = false
                }
                null
            } finally {
                main.postDelayed(releaseWhenIdle, IdleMs)
            }
        }
    }

    // a tab that's past the bot check and on the real site, opened (or reopened) as needed
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readyTab(context: Context): WebView? {
        webView?.takeIf { ready }?.let { return it }
        val view = webView ?: run {
            // the webview's first start is heavy: the sheet that asked gets to finish opening first
            delay(600)
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                // never shown, but a zero-size view with paused timers can stall the check's script
                layout(0, 0, 360, 640)
                onResume()
                resumeTimers()
            }.also {
                webView = it
                userAgent = it.settings.userAgentString
            }
        }
        view.loadUrl(Landing)
        // the check's page has no next.js data; the real site does, once the check has passed
        val passed = withTimeoutOrNull(ReadyTimeoutMs) {
            while (evaluate(view, "!!document.getElementById('__NEXT_DATA__')") != "true") delay(300)
            true
        } ?: false
        if (!passed) {
            Timber.tag("Musixmatch").w("the bot check didn't let the browser through")
            release()
            return null
        }
        runCatching { CookieManager.getInstance().flush() }
        ready = true
        return view
    }

    private suspend fun fetchIn(view: WebView, url: String, accept: String): Pair<Int, String>? {
        val slot = "__mochyMxm${calls++}"
        val script = """
            (function () {
              window.$slot = null;
              fetch(${JsonPrimitive(url)}, { headers: { 'Accept': ${JsonPrimitive(accept)} }, credentials: 'include' })
                .then(function (r) { return r.text().then(function (t) { window.$slot = [r.status, t]; }); })
                .catch(function (e) { window.$slot = [0, String(e)]; });
            })();
        """.trimIndent()
        evaluate(view, script)
        val read = "(function(){var v=window.$slot;if(v===null||v===undefined)return null;delete window.$slot;return JSON.stringify(v);})()"
        while (true) {
            val raw = evaluate(view, read)
            if (raw != null && raw != "null") return decode(raw)
            delay(PollMs)
        }
    }

    // evaluateJavascript hands back the value as json: here a json string holding the json array [status, body]
    private fun decode(raw: String): Pair<Int, String>? = runCatching {
        val inner = (Json.parseToJsonElement(raw) as JsonPrimitive).content
        val array = Json.parseToJsonElement(inner) as JsonArray
        val status = (array[0] as JsonPrimitive).intOrNull ?: 0
        val body = (array[1] as JsonPrimitive).contentOrNull.orEmpty()
        status to body
    }.getOrNull()

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private fun release() {
        main.removeCallbacks(releaseWhenIdle)
        ready = false
        val view = webView ?: return
        webView = null
        runCatching {
            view.stopLoading()
            view.destroy()
        }
    }
}
