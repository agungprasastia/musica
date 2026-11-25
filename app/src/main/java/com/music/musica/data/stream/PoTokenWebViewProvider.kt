package com.music.musica.data.stream

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight WebView-backed TokenProvider for InnerTubeX.
 * Safely degrades to null if WebView/BotGuard is unavailable or times out so playback still falls back smoothly.
 */
class PoTokenWebViewProvider(
    private val appContext: Context,
) : TokenProvider {

    private val mutex = Mutex()
    private var cachedVisitor: String? = null
    private var cachedToken: PoTokenResult? = null
    private var cachedAtMs: Long = 0L

    override val capabilities: TokenProviderCapabilities = TokenProviderCapabilities(
        providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        usesWebView = true,
    )

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? = mutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (existing != null && cachedVisitor == visitorData && now - cachedAtMs < TOKEN_TTL_MS) {
            return existing
        }

        val minted = withTimeoutOrNull(MINT_TIMEOUT_MS) {
            mintViaWebView(visitorData)
        }
        if (minted != null) {
            cachedVisitor = visitorData
            cachedToken = minted
            cachedAtMs = now
        }
        minted
    }

    override suspend fun close() {
        cachedToken = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun mintViaWebView(visitorData: String): PoTokenResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<PoTokenResult?>()
        var webView: WebView? = null
        try {
            webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onToken(token: String) {
                            if (token.isNotBlank() && !deferred.isCompleted) {
                                deferred.complete(
                                    PoTokenResult(
                                        playerRequestToken = token,
                                        streamingDataToken = token,
                                        visitorData = visitorData,
                                    ),
                                )
                            }
                        }

                        @JavascriptInterface
                        fun onError() {
                            if (!deferred.isCompleted) deferred.complete(null)
                        }
                    },
                    "MusicaBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Extract visitor/session PoToken when available in ytcfg.
                        view?.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var cfg = window.ytcfg && window.ytcfg.data_ ? window.ytcfg.data_ : {};
                                var tok = cfg.PO_TOKEN || cfg.SERVICE_INTEGRITY_DIMENSIONS && cfg.SERVICE_INTEGRITY_DIMENSIONS.poToken;
                                if (tok) { MusicaBridge.onToken(String(tok)); }
                                else { MusicaBridge.onError(); }
                              } catch (e) { MusicaBridge.onError(); }
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                loadUrl("https://music.youtube.com/embed/")
            }
            deferred.await()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    companion object {
        private const val MINT_TIMEOUT_MS = 8_000L
        private const val TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
