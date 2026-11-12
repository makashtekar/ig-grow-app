package com.iggrow.app.ui

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import com.iggrow.app.session.IgSession
import org.json.JSONObject

object IgWebViewSupport {
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        if (isEmulator()) {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = MOBILE_UA
        }
        webView.webChromeClient = WebChromeClient()
    }

    fun extractSessionFromWebView(
        webView: WebView,
        onExtracted: (IgSession?) -> Unit
    ) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://www.instagram.com").orEmpty()
        val cookieMap = parseCookieMap(cookies)
        val sessionId = cookieMap["sessionid"].orEmpty()
        val dsUserId = cookieMap["ds_user_id"].orEmpty()
        val csrfToken = cookieMap["csrftoken"].orEmpty()

        if (sessionId.isBlank() || dsUserId.isBlank() || csrfToken.isBlank()) {
            onExtracted(null)
            return
        }

        cookieManager.flush()
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

        webView.evaluateJavascript(js) { rawJson ->
            val cleaned = rawJson
                ?.trim('"')
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                .orEmpty()
            val obj = runCatching { JSONObject(cleaned) }.getOrNull()
            val session = IgSession(
                dsUserId = dsUserId,
                username = obj?.optString("username")?.ifBlank { "ig_$dsUserId" } ?: "ig_$dsUserId",
                sessionId = sessionId,
                csrfToken = csrfToken,
                cookieHeader = cookies,
                fbDtsg = obj?.optString("fbDtsg").orEmpty(),
                lsd = obj?.optString("lsd").orEmpty(),
                userAgent = MOBILE_UA
            )
            onExtracted(if (session.isComplete()) session else null)
        }
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    private fun parseCookieMap(cookieHeader: String): Map<String, String> =
        cookieHeader.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .toMap()
}
