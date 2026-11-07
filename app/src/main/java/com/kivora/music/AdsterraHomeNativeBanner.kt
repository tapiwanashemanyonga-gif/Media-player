package com.kivora.music

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** Separate Adsterra native-banner placement on the Home dashboard. */
internal object AdsterraHomeNativeBanner {
    private const val AD_HOST = "bancadeltempoidea.org"
    private const val SCRIPT_URL = "https://bancadeltempoidea.org/21/0304c09311629f5e78df4f92df9e242b"
    private const val CONTAINER_ID = "container-0304c09311629f5e78df4f92df9e242b"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(context, 8), 0, dp(context, 12))
            contentDescription = "Sponsored advertisement"
        }
        container.addView(TextView(context).apply {
            text = "SPONSORED"
            textSize = 9f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(177, 157, 205))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 5))
        }, LinearLayout.LayoutParams(-1, -2))

        val availableWidth = (context.resources.displayMetrics.widthPixels - dp(context, 24)).coerceAtLeast(dp(context, 240))
        val bannerWidth = minOf(dp(context, 420), availableWidth)
        val webView = object : WebView(context) {
            override fun onDetachedFromWindow() {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
                super.onDetachedFromWindow()
            }
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                openAdDestinationExternally(context, request.url)


            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                // A third-party creative may crash Chromium's renderer. Contain that failure
                // to this ad slot instead of allowing WebView to terminate the whole player.
                view.stopLoading()
                view.visibility = View.GONE
                return true
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                openAdDestinationExternally(context, Uri.parse(url))
        }
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>
                * { box-sizing: border-box; }
                html, body { margin:0; padding:0; width:100%; min-height:220px; overflow:hidden; background:transparent; }
                #$CONTAINER_ID { width:100%; min-height:220px; margin:0 auto; overflow:hidden; }
                img { max-width:100%; height:auto; }
              </style>
            </head>
            <body>
              <script async="async" data-cfasync="false" src="$SCRIPT_URL"></script>
              <div id="$CONTAINER_ID"></div>
            </body>
            </html>
        """.trimIndent()
        container.addView(webView, LinearLayout.LayoutParams(bannerWidth, dp(context, 240)))
        webView.loadDataWithBaseURL("https://$AD_HOST/", html, "text/html", "UTF-8", null)
        return container
    }


    /** Open ad click-through destinations in the user's external browser, never inside the player. */
    private fun openAdDestinationExternally(context: Context, uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return true
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
