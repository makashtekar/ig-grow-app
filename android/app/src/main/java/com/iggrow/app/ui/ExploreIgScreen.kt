package com.iggrow.app.ui

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.iggrow.app.session.IgSession
import com.iggrow.app.ui.theme.StatusPill
import com.iggrow.app.ui.theme.StudioColors

@Composable
fun ExploreIgScreen(
    session: IgSession,
    competitorHandle: String?,
    onSessionRefreshed: (IgSession) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.BgObsidian)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioColors.SurfaceCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webViewRef?.goBack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StudioColors.TextPrimary
                )
            }
            IconButton(onClick = { webViewRef?.loadUrl("https://www.instagram.com/") }) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home",
                    tint = StudioColors.TextPrimary
                )
            }
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reload",
                    tint = StudioColors.TextPrimary
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickNavChip("My Profile") {
                    webViewRef?.loadUrl("https://www.instagram.com/${session.username}/")
                }
                if (!competitorHandle.isNullOrBlank()) {
                    QuickNavChip("@${competitorHandle.removePrefix("@")}") {
                        webViewRef?.loadUrl("https://www.instagram.com/${competitorHandle.removePrefix("@")}/")
                    }
                }
                QuickNavChip("Sync Tokens") {
                    webViewRef?.let { wv ->
                        IgWebViewSupport.extractSessionFromWebView(wv) { updated ->
                            if (updated != null) {
                                onSessionRefreshed(updated)
                                syncMessage = "Tokens synced"
                            }
                        }
                    }
                }
            }
        }

        if (syncMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioColors.SurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                StatusPill(text = syncMessage!!, color = StudioColors.SuccessEmerald)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = StudioColors.Magenta
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        IgWebViewSupport.configure(this)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                view?.let {
                                    IgWebViewSupport.extractSessionFromWebView(it) { updated ->
                                        if (updated != null) onSessionRefreshed(updated)
                                    }
                                }
                            }
                        }
                        webViewRef = this
                        loadUrl("https://www.instagram.com/")
                    }
                }
            )
        }
    }
}

@Composable
private fun QuickNavChip(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = StudioColors.SurfaceElevated,
            labelColor = StudioColors.TextPrimary
        )
    )
}
