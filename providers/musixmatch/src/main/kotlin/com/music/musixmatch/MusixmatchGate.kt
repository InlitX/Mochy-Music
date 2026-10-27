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
import timber.log.Timber
import kotlin.coroutines.resume

// musixmatch hides behind an aws bot check that only a real browser passes, so a hidden webview passes it once
internal object MusixmatchGate {
    private const val Site = "https://www.musixmatch.com"
    private const val TokenCookie = "aws-waf-token="
    private const val SolveTimeoutMs = 25_000L
    private const val PollMs = 300L

    private val mutex = Mutex()

    @Volatile
    var userAgent: String? = null
        private set

    fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(Site) }.getOrNull()

    fun hasToken(): Boolean = cookies()?.contains(TokenCookie) == true

    fun restore(savedUserAgent: String?) {
        if (userAgent == null) userAgent = savedUserAgent
    }

    // true once the browser has the pass cookie; the cookie and its user agent then go with every plain request
    suspend fun clear(context: Context, url: String, tokenBefore: String?): Boolean = mutex.withLock {
        // someone else solved it while we waited for the lock
        if (hasToken() && cookies() != tokenBefore) return@withLock true
        // the webview's first start is heavy, let the sheet that asked finish opening first
        delay(600)
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(SolveTimeoutMs) { solveOnMain(context.applicationContext, url, tokenBefore) } ?: false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveOnMain(context: Context, url: String, tokenBefore: String?): Boolean =
        suspendCancellableCoroutine { continuation ->
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = WebViewClient()
            // never shown, but a zero-size view with paused timers can stall the check's script
            webView.layout(0, 0, 360, 640)
            webView.onResume()
            webView.resumeTimers()
            userAgent = webView.settings.userAgentString
            val handler = Handler(Looper.getMainLooper())
            var done = false

            fun finish(solved: Boolean) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { cookieManager.flush() }
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
                if (continuation.isActive) continuation.resume(solved)
            }

            val poll = object : Runnable {
                override fun run() {
                    val now = cookies()
                    if (now?.contains(TokenCookie) == true && now != tokenBefore) {
                        Timber.tag("Musixmatch").d("bot check passed")
                        finish(true)
                    } else {
                        handler.postDelayed(this, PollMs)
                    }
                }
            }
            continuation.invokeOnCancellation { handler.post { finish(false) } }
            webView.loadUrl(url)
            handler.postDelayed(poll, PollMs)
        }
}
