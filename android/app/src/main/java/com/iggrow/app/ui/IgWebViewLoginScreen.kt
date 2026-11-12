package com.iggrow.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.iggrow.app.session.IgSession
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IgWebViewLoginScreen(
    onSessionCaptured: (IgSession) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connect Instagram Account") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        val ua = settings.userAgentString

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (captured || view == null) return
                                val cookies = cookieManager.getCookie("https://www.instagram.com").orEmpty()
                                val cookieMap = parseCookieMap(cookies)
                                val sessionId = cookieMap["sessionid"].orEmpty()
                                val dsUserId = cookieMap["ds_user_id"].orEmpty()
                                val csrfToken = cookieMap["csrftoken"].orEmpty()

                                if (sessionId.isNotBlank() && dsUserId.isNotBlank() && csrfToken.isNotBlank()) {
                                    // Extract fb_dtsg, lsd, and username from the authenticated DOM
                                    val js = """
                                        (function() {
                                          var dtsg = (document.querySelector('input[name="fb_dtsg"]') || {}).value || '';
                                          var lsd = (document.querySelector('input[name="lsd"]') || {}).value || '';
                                          var html = document.documentElement.innerHTML;
                                          if (!dtsg) {
                                            var m1 = html.match(/"DTSGInitialData"[^}]*"token":"([^"]+)"/);
                                            if (m1) dtsg = m1[1];
                                          }
                                          if (!lsd) {
                                            var m2 = html.match(/"LSD"[^}]*"token":"([^"]+)"/);
                                            if (m2) lsd = m2[1];
                                          }
                                          var u = html.match(/"username":"([^"]+)"/);
                                          return JSON.stringify({
                                            fbDtsg: dtsg,
                                            lsd: lsd,
                                            username: u ? u[1] : ('ig_' + '$dsUserId')
                                          });
                                        })();
                                    """.trimIndent()
                                    view.evaluateJavascript(js) { rawJson ->
                                        if (captured) return@evaluateJavascript
                                        val cleaned = rawJson
                                            ?.trim('"')
                                            ?.replace("\\\"", "\"")
                                            ?.replace("\\\\", "\\")
                                            .orEmpty()
                                        val obj = runCatching { JSONObject(cleaned) }.getOrNull()
                                        val session = IgSession(
                                            dsUserId = dsUserId,
                                            username = obj?.optString("username")?.ifBlank { "ig_$dsUserId" }
                                                ?: "ig_$dsUserId",
                                            sessionId = sessionId,
                                            csrfToken = csrfToken,
                                            cookieHeader = cookies,
                                            fbDtsg = obj?.optString("fbDtsg").orEmpty(),
                                            lsd = obj?.optString("lsd").orEmpty(),
                                            userAgent = ua
                                        )
                                        if (session.isComplete()) {
                                            captured = true
                                            onSessionCaptured(session)
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl("https://www.instagram.com/accounts/login/")
                    }
                }
            )
        }
    }
}

private fun parseCookieMap(cookieHeader: String): Map<String, String> =
    cookieHeader.split(";")
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }
        .toMap()
