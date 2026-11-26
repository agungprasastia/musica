package com.music.musica.data.stream

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.music.musica.core.network.HttpClients
import com.music.musica.data.stream.potoken.BOTGUARD_PAGE_HTML
import com.music.musica.data.stream.potoken.BotGuardCodec
import com.music.musica.data.stream.potoken.BotGuardException
import com.music.musica.data.stream.potoken.UnusableWebViewException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real BotGuard WebView PoToken provider for InnerTubeX web clients.
 *
 * Fetches the BotGuard challenge via YouTube's WAA endpoints (`Create` + `GenerateIT`),
 * executes the BotGuard interpreter inside a network-blocked offscreen WebView, caches
 * the reusable minter + session streaming token until expiration, and mints per-video
 * player tokens on demand. Safely degrades to `null` if WebView is absent, crashes, or
 * times out so playback still falls back smoothly.
 */
class PoTokenWebViewProvider(
    private val appContext: Context,
    private val httpClient: OkHttpClient = HttpClients.okHttp,
) : TokenProvider {

    private val mutex = Mutex()
    private var webViewUnusable = false
    private var activeSessionId: String? = null
    private var sessionStreamingToken: String? = null
    private var minter: BotGuardWebViewMinter? = null

    override val capabilities: TokenProviderCapabilities = TokenProviderCapabilities(
        providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        usesWebView = true,
    )

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        if (webViewUnusable || visitorData.isBlank()) return null
        return try {
            withTimeout(OUTER_TIMEOUT_MS) {
                mintTokenPair(videoId = videoId, sessionId = visitorData, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "PoToken mint timed out after ${OUTER_TIMEOUT_MS}ms; continuing without token")
            clearMinter()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnusableWebViewException) {
            Log.w(TAG, "WebView unavailable for BotGuard PoToken: ${e.message}")
            webViewUnusable = true
            clearMinter()
            null
        } catch (e: Throwable) {
            Log.w(TAG, "PoToken generation failed (${e::class.simpleName}): ${e.message}")
            null
        }
    }

    override suspend fun close() {
        clearMinter()
    }

    private suspend fun clearMinter() {
        mutex.withLock {
            runCatching { minter?.close() }
            minter = null
            sessionStreamingToken = null
            activeSessionId = null
        }
    }

    private suspend fun mintTokenPair(
        videoId: String,
        sessionId: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val (activeMinter, streamingPot, wasRecreated) = mutex.withLock {
            val current = minter
            val shouldRecreate = forceRecreate ||
                current == null ||
                current.isExpired ||
                current.isDead ||
                activeSessionId != sessionId

            if (shouldRecreate) {
                runCatching { current?.close() }
                minter = null
                sessionStreamingToken = null
                activeSessionId = null

                val created = BotGuardWebViewMinter.create(appContext, httpClient)
                val sessionToken = try {
                    created.mint(sessionId)
                } catch (t: Throwable) {
                    runCatching { created.close() }
                    throw t
                }
                minter = created
                sessionStreamingToken = sessionToken
                activeSessionId = sessionId
            }
            Triple(requireNotNull(minter), requireNotNull(sessionStreamingToken), shouldRecreate)
        }

        val contentToken = try {
            activeMinter.mint(videoId)
        } catch (t: Throwable) {
            if (wasRecreated || t is CancellationException) throw t
            return mintTokenPair(videoId = videoId, sessionId = sessionId, forceRecreate = true)
        }

        return PoTokenResult(
            playerRequestToken = streamingPot,
            streamingDataToken = contentToken,
            visitorData = sessionId,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private class BotGuardWebViewMinter private constructor(
        context: Context,
        private val httpClient: OkHttpClient,
        private val initContinuation: Continuation<BotGuardWebViewMinter>,
    ) {
        private val webView = WebView(context)
        private val scope = MainScope()
        private val initResumed = AtomicBoolean(false)
        private val pendingMints = ConcurrentHashMap<String, Continuation<String>>()
        private val requestCounter = AtomicLong()

        @Volatile
        private var closed = false

        @Volatile
        var isDead: Boolean = false
            private set

        @Volatile
        private var expiresAtMs: Long = Long.MAX_VALUE

        val isExpired: Boolean
            get() = System.currentTimeMillis() >= expiresAtMs

        private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            failInitialization(throwable)
        }

        init {
            webView.settings.apply {
                javaScriptEnabled = true
                userAgentString = USER_AGENT
                blockNetworkLoads = true
            }
            webView.addJavascriptInterface(this, JS_BRIDGE)

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val msg = consoleMessage.message().orEmpty()
                    if (msg.contains("Uncaught")) {
                        val detail = "\"$msg\" (${consoleMessage.lineNumber()})"
                        if (initResumed.get()) {
                            isDead = true
                            val err = BotGuardException(detail)
                            close()
                            drainPendingMints(err)
                        } else {
                            val err = UnusableWebViewException(detail)
                            failInitialization(err)
                            drainPendingMints(err)
                        }
                    }
                    return true
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    val crashed = runCatching { detail?.didCrash() }.getOrNull()
                    Log.w(TAG, "BotGuard WebView render process gone (crashed=$crashed)")
                    isDead = true
                    val err = BotGuardException("WebView render process gone (crashed=$crashed)")
                    failInitialization(err)
                    drainPendingMints(err)
                    return true
                }
            }
        }

        private fun start() {
            scope.launch(exceptionHandler) {
                val html = BOTGUARD_PAGE_HTML.replaceFirst(
                    "</script>",
                    "\n$JS_BRIDGE.onHarnessReady()</script>",
                )
                webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
            }
        }

        @JavascriptInterface
        fun onHarnessReady() {
            postBotGuardRequest(
                url = "https://www.youtube.com/api/jnn/v1/Create",
                bodyJson = """["$REQUEST_KEY"]""",
            ) { responseBody ->
                val challengeJs = BotGuardCodec.parseChallengeData(responseBody)
                webView.evaluateJavascript(
                    """try {
                        var challenge = $challengeJs;
                        runBotGuard(challenge).then(function (result) {
                            window.__musicaWebPoSignalOutput = result.webPoSignalOutput;
                            $JS_BRIDGE.onBotGuardSnapshot(result.botguardResponse);
                        }, function (error) {
                            $JS_BRIDGE.onInitError(String(error));
                        });
                    } catch (error) {
                        $JS_BRIDGE.onInitError(String(error));
                    }""",
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onBotGuardSnapshot(botguardResponse: String) {
            postBotGuardRequest(
                url = "https://www.youtube.com/api/jnn/v1/GenerateIT",
                bodyJson = """["$REQUEST_KEY","$botguardResponse"]""",
            ) { responseBody ->
                val (integrityTokenJs, ttlSec) = BotGuardCodec.parseIntegrityTokenData(responseBody)
                val safeTtlSec = (ttlSec - EXPIRY_MARGIN_SEC).coerceAtLeast(60L)
                expiresAtMs = System.currentTimeMillis() + safeTtlSec * 1_000L

                webView.evaluateJavascript(
                    """try {
                        var tokenBytes = $integrityTokenJs;
                        createPoTokenMinter(window.__musicaWebPoSignalOutput, tokenBytes).then(function () {
                            $JS_BRIDGE.onMinterReady();
                        }).catch(function (error) {
                            $JS_BRIDGE.onInitError(String(error));
                        });
                    } catch (error) {
                        $JS_BRIDGE.onInitError(String(error));
                    }""",
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onMinterReady() {
            if (initResumed.compareAndSet(false, true)) {
                initContinuation.resume(this)
            }
        }

        @JavascriptInterface
        fun onInitError(error: String) {
            val mapped = if (error.contains("SyntaxError") || error.contains("ReferenceError")) {
                UnusableWebViewException(error)
            } else {
                BotGuardException(error)
            }
            failInitialization(mapped)
        }

        suspend fun mint(identifier: String): String {
            if (isDead || closed) {
                throw BotGuardException("BotGuard WebView minter is closed or dead")
            }
            val requestId = "${identifier.hashCode()}_${requestCounter.incrementAndGet()}"
            return try {
                withTimeout(MINT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            pendingMints[requestId] = cont
                            cont.invokeOnCancellation { pendingMints.remove(requestId) }
                            val u8Arg = BotGuardCodec.stringToJsUint8Array(identifier)
                            webView.evaluateJavascript(
                                """(function () {
                                    var reqId = "$requestId";
                                    try {
                                        obtainPoToken($u8Arg).then(function (u8) {
                                            $JS_BRIDGE.onMintResult(reqId, u8.join(","));
                                        }).catch(function (error) {
                                            $JS_BRIDGE.onMintError(reqId, String(error));
                                        });
                                    } catch (error) {
                                        $JS_BRIDGE.onMintError(reqId, String(error));
                                    }
                                })();""",
                                null,
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                isDead = true
                pendingMints.remove(requestId)
                throw BotGuardException("PoToken mint timed out after ${MINT_TIMEOUT_MS}ms", e)
            }
        }

        @JavascriptInterface
        fun onMintResult(requestId: String, csvBytes: String) {
            val cont = pendingMints.remove(requestId) ?: return
            runCatching { BotGuardCodec.u8CsvToUrlSafeBase64(csvBytes) }
                .onSuccess { token -> runCatching { cont.resume(token) } }
                .onFailure { err -> runCatching { cont.resumeWithException(err) } }
        }

        @JavascriptInterface
        fun onMintError(requestId: String, error: String) {
            pendingMints.remove(requestId)?.let { cont ->
                runCatching { cont.resumeWithException(BotGuardException(error)) }
            }
        }

        private fun postBotGuardRequest(
            url: String,
            bodyJson: String,
            onSuccess: (String) -> Unit,
        ) {
            scope.launch(exceptionHandler) {
                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody())
                    .headers(
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Content-Type" to "application/json+protobuf",
                            "x-goog-api-key" to GOOGLE_API_KEY,
                            "x-user-agent" to "grpc-web-javascript/0.1",
                        ).toHeaders(),
                    )
                    .build()

                val (code, payload) = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { resp ->
                        resp.code to if (resp.code == 200) resp.body.string() else null
                    }
                }
                if (payload.isNullOrEmpty()) {
                    failInitialization(BotGuardException("BotGuard HTTP $code from $url"))
                } else {
                    onSuccess(payload)
                }
            }
        }

        private fun failInitialization(error: Throwable) {
            close()
            if (initResumed.compareAndSet(false, true)) {
                runCatching { initContinuation.resumeWithException(error) }
            }
        }

        private fun drainPendingMints(error: Throwable) {
            val snapshot = pendingMints.values.toList()
            pendingMints.clear()
            snapshot.forEach { cont -> runCatching { cont.resumeWithException(error) } }
        }

        fun close() {
            if (closed) return
            closed = true
            scope.cancel()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                destroyWebView()
            } else {
                Handler(Looper.getMainLooper()).post { destroyWebView() }
            }
        }

        @MainThread
        private fun destroyWebView() {
            runCatching {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            }
        }

        companion object {
            private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
            private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
            private const val JS_BRIDGE = "MusicaBotGuard"
            private const val EXPIRY_MARGIN_SEC = 600L
            private const val INIT_TIMEOUT_MS = 20_000L
            private const val MINT_TIMEOUT_MS = 8_000L
            private const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            suspend fun create(
                context: Context,
                httpClient: OkHttpClient,
            ): BotGuardWebViewMinter {
                var instance: BotGuardWebViewMinter? = null
                return try {
                    withTimeout(INIT_TIMEOUT_MS) {
                        withContext(Dispatchers.Main) {
                            suspendCancellableCoroutine { cont ->
                                val minter = BotGuardWebViewMinter(context, httpClient, cont)
                                instance = minter
                                minter.start()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        instance?.initResumed?.set(true)
                        instance?.close()
                    }
                    throw e
                }
            }
        }
    }

    companion object {
        private const val TAG = "MusicaPoToken"
        private const val OUTER_TIMEOUT_MS = 10_000L
    }
}
