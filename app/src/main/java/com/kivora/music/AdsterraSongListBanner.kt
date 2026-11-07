package com.kivora.music

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Isolated Adsterra iframe banner for the local song list.
 * The WebView is intentionally scoped to this placement and destroys itself
 * when the list row is removed or the screen is rebuilt.
 */
internal object AdsterraSongListBanner {
    private const val AD_KEY = "ff28c7116d6569c8812a7c433e1a3c3f"
    private const val AD_HOST = "bancadeltempoidea.org"
    private const val SCRIPT_URL = "https://bancadeltempoidea.org/22/ff28c7116d6569c8812a7c433e1a3c3f"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(context, 5), 0, dp(context, 9))
            contentDescription = "Advertisement"
        }
        container.addView(TextView(context).apply {
            text = "ADVERTISEMENT"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(170, 153, 190))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 4))
        }, LinearLayout.LayoutParams(-1, -2))

        val maxWidth = dp(context, 320)
        val availableWidth = (context.resources.displayMetrics.widthPixels - dp(context, 32))
            .coerceAtLeast(dp(context, 240))
        val bannerWidth = minOf(maxWidth, availableWidth)
        val webView = object : WebView(context) {
            override fun onDetachedFromWindow() {
                stopLoading()
                loadUrl("about:blank")
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
                super.onDetachedFromWindow()
            }
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                safeBrowsingEnabled = true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                AdsterraExternalNavigation.shouldOpenExternally(
                    view.context,
                    request.url,
                    request.hasGesture()
                )

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val scheme = android.net.Uri.parse(url).scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }
        }

        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>
                html, body { margin:0; padding:0; width:100%; height:50px; overflow:hidden; background:transparent; }
                body { display:flex; align-items:center; justify-content:center; }
              </style>
            </head>
            <body>
              <script>
                var atOptions = {
                  'key': '$AD_KEY',
                  'format': 'iframe',
                  'height': 50,
                  'width': 320,
                  'params': {}
                };
              </script>
              <script src="$SCRIPT_URL"></script>
            </body>
            </html>
        """.trimIndent()
        // Keep the advert inside a fixed 320x50 viewport so a third-party iframe
        // cannot draw over or intercept the following song row.
        val adFrame = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            isClickable = false
            isFocusable = false
        }
        adFrame.addView(
            webView,
            FrameLayout.LayoutParams(bannerWidth, dp(context, 50), Gravity.CENTER)
        )
        container.addView(adFrame, LinearLayout.LayoutParams(bannerWidth, dp(context, 50)))
        webView.loadDataWithBaseURL("https://$AD_HOST/", html, "text/html", "UTF-8", null)
        return container
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
