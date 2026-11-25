package com.music.musica.ui.onboarding

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.musica.R
import com.music.musica.core.auth.SapisidHashSigner
import com.music.musica.ui.components.FrostedCircleButton

/**
 * In-app WebView sign-in flow for YouTube Music:
 * loads Google sign-in redirecting to music.youtube.com and automatically captures the
 * authenticated SAPISID cookie once sign-in finishes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginWebView(
    onCookieCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var captured by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FrostedCircleButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
            Text(
                text = stringResource(R.string.auth_login_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                WebView(ctx).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                            false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (captured || url == null) return
                            if (url.startsWith("https://music.youtube.com")) {
                                val cookie = cookieManager.getCookie("https://music.youtube.com")
                                if (SapisidHashSigner.extractSapisid(cookie) != null && !cookie.isNullOrBlank()) {
                                    captured = true
                                    onCookieCaptured(cookie)
                                }
                            }
                        }
                    }
                    loadUrl(
                        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F",
                    )
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}
