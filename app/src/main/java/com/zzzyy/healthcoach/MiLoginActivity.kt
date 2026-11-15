package com.zzzyy.healthcoach

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding

/**
 * 账号密码登录页（**独立 Activity**，不是一个 Dialog）。
 *
 * 为什么必须是 Activity 而不是 Compose 的 Dialog：
 * Compose `Dialog` 会新建一个自己的 Window，那个 Window 在部分设备/Compose 版本上
 * **没有开硬件加速**，WebView 在里面就是纯白一片 —— 页面其实加载完了，
 * 只是没被合成出来。用 Activity 的窗口（默认硬件加速）就没有这个问题。
 *
 * 安全边界：这是小米自己的 `account.xiaomi.com` 页面，密码只输给小米。
 * 本 Activity **不读输入框、不注入 JS 去取密码**，只等登录成功后从 cookie 里
 * 取 `userId` 和 `passToken` 两个票据。
 *
 * 日志：只打「有没有拿到」和长度，**绝不打票据内容**。
 */
class MiLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var finished = false
    private var lastLogAt = 0L
    private var pollRound = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12))
        }

        root.addView(
            TextView(this).apply {
                text = "用小米账号登录"
                textSize = 18f
                setTextColor(Color.parseColor("#111111"))
            },
        )
        val status = TextView(this).apply {
            text = "正在打开小米登录页…"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, dp(6))
        }
        statusView = status
        root.addView(status)

        val web = WebView(this)
        webView = web
        web.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // 关键：把 UA 里的 "; wv"（WebView 标记）去掉并伪装成普通 Chrome。
            // 很多站点的风控/前端会按 UA 判断「这是不是内嵌浏览器」，带 wv 时
            // 可能直接不渲染或弹「请在浏览器中打开」。
            userAgentString = (userAgentString ?: "")
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        }
        // 登录页里的 window.open（短信验证、协议弹层）在本窗口内消化，别开新窗口
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 0..100) {
                    status.text = "加载中… $newProgress%"
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                Log.i(TAG, "开始加载：${safeUrl(url)}")
                status.text = "加载中…"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 关键诊断点：能走到这里说明「页面加载完了」
                Log.i(TAG, "加载完成：${safeUrl(url)}，标题=${view?.title}")
                status.text = "请在上方页面登录小米账号（密码只输给小米）"
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString()
                    Log.e(TAG, "主框架加载失败：${safeUrl(request.url?.toString())} -> $desc")
                    status.text = "页面加载失败：$desc\n可改用「手动填 passToken」"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(
                        TAG,
                        "主框架 HTTP 错误：${safeUrl(request.url?.toString())} -> ${errorResponse?.statusCode}",
                    )
                    status.text = "小米返回 HTTP ${errorResponse?.statusCode}，可改用「手动填 passToken」"
                }
            }
        }

        root.addView(web)

        root.addView(
            Button(this).apply {
                text = "取消"
                setOnClickListener { finishCancelled() }
            },
        )

        setContentView(root)

        Log.i(TAG, "打开账号密码登录页，UA=${web.settings.userAgentString}")
        pollCookies()
        web.loadUrl(LOGIN_URL)
    }

    /** 轮询 cookie：登录成功后小米才会种下 passToken。 */
    private fun pollCookies() {
        val manager = CookieManager.getInstance()
        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                pollRound++
                val cookie = runCatching { manager.getCookie("https://account.xiaomi.com") }
                    .getOrNull()
                    .orEmpty()
                val passToken = cookieValue(cookie, "passToken")
                val userId = cookieValue(cookie, "userId")
                if (passToken.isNotEmpty() && userId.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "拿到登录票据：passToken 长度=${passToken.length}，userId 长度=${userId.length}",
                    )
                    finishWithResult(userId, passToken)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastLogAt > 10_000) {
                    lastLogAt = now
                    Log.i(
                        TAG,
                        "等待登录中：第 $pollRound 次轮询，cookie 长度=${cookie.length}，" +
                            "有 passToken=${passToken.isNotEmpty()}，有 userId=${userId.isNotEmpty()}",
                    )
                    if (pollRound > 8) {
                        statusView?.text = "还没登录成功。若页面一直空白，用「手动填 passToken」那条路。"
                    }
                }
                handler.postDelayed(this, 1200)
            }
        }
        handler.postDelayed(tick, 1200)
    }

    private fun finishWithResult(userId: String, passToken: String) {
        if (finished) return
        finished = true
        val data = Intent().apply {
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_PASS_TOKEN, passToken)
        }
        Log.i(TAG, "返回登录票据给主界面（不打印内容）")
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishCancelled() {
        if (finished) return
        finished = true
        Log.i(TAG, "用户取消账号密码登录")
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        Log.i(TAG, "销毁登录页 WebView")
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun safeUrl(url: String?): String {
        // 只留 host + path，查询串里可能带 sign / callback 之类的参数
        return url?.substringBefore('?') ?: "(null)"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "HealthCoach.Login"
        private const val LOGIN_URL =
            "https://account.xiaomi.com/pass/serviceLogin?sid=miothealth&_locale=zh_CN"
        const val EXTRA_USER_ID = "mi_user_id"
        const val EXTRA_PASS_TOKEN = "mi_pass_token"

        /**
         * 从 `a=1; b=2` 形式的 cookie 串里取某个键的值。
         * 注意：passToken 是 HttpOnly，但 `CookieManager.getCookie` 读的是浏览器自己的
         * cookie 库（不是 JS 的 document.cookie），所以拿得到。
         */
        fun cookieValue(raw: String, name: String): String {
            if (raw.isEmpty()) return ""
            for (part in raw.split(';')) {
                val item = part.trim()
                if (item.startsWith("$name=")) return item.substringAfter('=')
            }
            return ""
        }
    }
}
