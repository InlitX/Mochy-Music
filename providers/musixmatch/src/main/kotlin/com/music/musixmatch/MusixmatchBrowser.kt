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
import java.net.URLEncoder
import kotlin.coroutines.resume

/** a hidden musixmatch.com tab: passes the bot check like a browser, searches through the site's own page, fetches from inside it */
internal object MusixmatchBrowser {
    private const val Site = "https://www.musixmatch.com"
    private const val SearchTimeoutMs = 25_000L
    private const val FetchTimeoutMs = 20_000L
    private const val PollMs = 250L
    private const val IdleMs = 60_000L

    /** a search result: the song page's path ("/lyrics/eill-3/Finale") and the words shown with it */
    data class Hit(val path: String, val text: String)

    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val releaseWhenIdle = Runnable { release() }
    private var webView: WebView? = null
    // the tab is on the real site (past the check), so a fetch from inside it carries the pass
    private var onSite = false
    private var calls = 0

    @Volatile
    var userAgent: String? = null
        private set

    fun restore(savedUserAgent: String?) {
        if (userAgent == null) userAgent = savedUserAgent
    }

    fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(Site) }.getOrNull()

    /** the song links the site's search shows once [good] likes them, empty when it found nothing, null when the check held the tab */
    suspend fun search(context: Context, query: String, good: (List<Hit>) -> Boolean): List<Hit>? = withTab(context) { view ->
        onSite = false
        view.loadUrl("$Site/search?query=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}")
        var last: List<Hit>? = null
        withTimeoutOrNull(SearchTimeoutMs) {
            while (true) {
                if (!onSite && evaluate(view, OnSiteScript) == "true") onSite = true
                val hits = evaluate(view, LinksScript)?.let(::decodeHits).orEmpty()
                if (hits.isNotEmpty()) {
                    last = hits
                    if (good(hits)) break
                }
                delay(PollMs)
            }
        }
        if (!onSite) Timber.tag("Musixmatch").w("the bot check didn't let the browser through")
        last ?: if (onSite) emptyList() else null
    }

    /** [url] fetched from inside the tab: its http status and body, or null when the tab can't get onto the site */
    suspend fun fetch(context: Context, url: String, accept: String): Pair<Int, String>? = withTab(context) { view ->
        if (!onSite && !openSite(view)) return@withTab null
        val reply = withTimeoutOrNull(FetchTimeoutMs) { fetchIn(view, url, accept) }
        // the pass ran out: the next call goes through the check again
        if (reply == null || reply.first == 202 || reply.first == 405) onSite = false
        reply
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
        }.also {
            webView = it
            userAgent = it.settings.userAgentString
        }
    }

    // the search page is the one the check always stops: loading it is how the check gets passed
    private suspend fun openSite(view: WebView): Boolean {
        view.loadUrl("$Site/search?query=lyrics")
        onSite = withTimeoutOrNull(SearchTimeoutMs) {
            while (evaluate(view, OnSiteScript) != "true") delay(PollMs)
            true
        } ?: false
        if (onSite) runCatching { CookieManager.getInstance().flush() }
        return onSite
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
            if (raw != null && raw != "null") return decodeReply(raw)
            delay(PollMs)
        }
    }

    // evaluateJavascript hands values back as json: a json string that itself holds the json we built
    private fun unwrap(raw: String): JsonArray? = runCatching {
        val inner = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull ?: return null
        Json.parseToJsonElement(inner) as? JsonArray
    }.getOrNull()

    private fun decodeReply(raw: String): Pair<Int, String>? {
        val array = unwrap(raw) ?: return null
        val status = (array[0] as? JsonPrimitive)?.intOrNull ?: 0
        val body = (array[1] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return status to body
    }

    private fun decodeHits(raw: String): List<Hit>? = unwrap(raw)?.mapNotNull { item ->
        val pair = item as? JsonArray ?: return@mapNotNull null
        val path = (pair[0] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Hit(path, (pair[1] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private fun release() {
        main.removeCallbacks(releaseWhenIdle)
        onSite = false
        val view = webView ?: return
        webView = null
        runCatching {
            view.stopLoading()
            view.destroy()
        }
    }

    // the check's own page has no next.js data; the real site always does
    private const val OnSiteScript = "!!document.getElementById('__NEXT_DATA__')"

    // every link to a song page ("/lyrics/<artist>/<song>"), once each, with the text the result shows
    private val LinksScript = """
        (function () {
          var seen = {}, out = [];
          document.querySelectorAll('a[href*="/lyrics/"]').forEach(function (a) {
            var m = (a.getAttribute('href') || '').match(/^(?:https?:\/\/www\.musixmatch\.com)?(\/lyrics\/[^\/?#]+\/[^\/?#]+)\/?(?:[?#].*)?$/);
            if (!m || seen[m[1]]) return;
            seen[m[1]] = 1;
            var card = a.closest('[role="listitem"], li, [data-testid]') || a;
            out.push([m[1], (card.innerText || a.innerText || '').replace(/\s+/g, ' ').trim()]);
          });
          return out.length ? JSON.stringify(out) : null;
        })()
    """.trimIndent()
}
