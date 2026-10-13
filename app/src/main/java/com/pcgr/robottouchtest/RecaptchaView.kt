package com.pcgr.robottouchtest

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class RecaptchaView(
    context: Context,
    targets: List<CircleTarget>,
    topOffset: Float,
    private val onReady: () -> Unit,
    private val onVerified: (String) -> Unit,
    private val onError: (String) -> Unit,
    blocked: () -> Boolean,
    touched: (Float, Float) -> Boolean,
) : WebView(context) {
    private var failed = false
    private val readyIds = mutableSetOf<String>()
    private val timeout = Runnable { fail("Check the internet connection and restart the test.") }

    init {
        val density = resources.displayMetrics.density
        val layout = JSONArray(targets.map { target -> JSONObject().apply {
            put("id", target.id)
            put("left", (target.x - TouchTestLogic.CAPTCHA_CHECKBOX_X_DP * target.radius / 14) / density)
            put("top", (target.y - TouchTestLogic.CAPTCHA_CHECKBOX_Y_DP * target.radius / 14 - topOffset) / density)
            put("scale", target.radius / (14 * density))
        } })
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.url.host in setOf("www.google.com", "www.gstatic.com", "recaptcha.google.com")) {
                    fail("reCAPTCHA could not load. Check the internet connection and restart the test.")
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val parts = message.message().split(':', limit = 3)
                if (parts.size != 3 || parts[0] != "robot-captcha") return false
                if (failed) return true
                when (parts[1]) {
                    "ready" -> if (targets.any { it.id == parts[2] } && readyIds.add(parts[2]) && readyIds.size == targets.size) {
                        removeCallbacks(timeout)
                        onReady()
                    }
                    "verified" -> if (parts[2] in readyIds) {
                        removeCallbacks(timeout)
                        onVerified(parts[2])
                    }
                    "error" -> fail("reCAPTCHA could not load or verify. Check the internet connection and restart the test.")
                }
                return true
            }
        }
        var ignoreGesture = false
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreGesture = blocked() || failed
            if (!ignoreGesture && event.actionMasked == MotionEvent.ACTION_UP && touched(event.x, event.y + topOffset)) {
                postDelayed(timeout, 30_000)
            }
            ignoreGesture
        }
        loadDataWithBaseURL("https://robot-touch-test.example/", """
            <!doctype html><html lang="en"><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
            <style>html,body{margin:0;background:transparent;overflow:hidden}.widget{position:absolute;transform-origin:0 0}</style>
            <script>
            function renderCaptcha() {
                const targets = $layout;
                targets.forEach(target => {
                    const widget = document.createElement('div');
                    widget.id = target.id;
                    widget.className = 'widget';
                    widget.style.left = target.left + 'px';
                    widget.style.top = target.top + 'px';
                    widget.style.transform = 'scale(' + target.scale + ')';
                    document.body.appendChild(widget);
                    const observer = new MutationObserver(() => {
                        const frame = widget.querySelector('iframe');
                        if (!frame) return;
                        observer.disconnect();
                        frame.addEventListener('load', () => console.log('robot-captcha:ready:' + target.id), {once:true});
                    });
                    observer.observe(widget, {childList:true,subtree:true});
                    grecaptcha.render(widget, {
                        // Public Google test key: no image challenges; not a production CAPTCHA.
                        sitekey: '6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI',
                        callback: () => console.log('robot-captcha:verified:' + target.id),
                        'error-callback': () => console.log('robot-captcha:error:network'),
                        'expired-callback': () => console.log('robot-captcha:error:expired')
                    });
                });
            }
            </script>
            <script src="https://www.google.com/recaptcha/api.js?onload=renderCaptcha&amp;render=explicit&amp;hl=en" async defer
                onerror="console.log('robot-captcha:error:script')"></script>
            </head><body></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
        postDelayed(timeout, 30_000)
    }

    private fun fail(message: String) {
        if (failed) return
        failed = true
        removeCallbacks(timeout)
        onError(message)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(timeout)
        failed = true
        stopLoading()
        super.onDetachedFromWindow()
        destroy()
    }
}
