package com.zzzyy.healthcoach.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * 账号密码登录：内嵌**小米官方登录页**，用户把密码输给小米，本应用只取 cookie。
 *
 * 为什么这么做，而不是自己写一个「账号 + 密码」表单：
 *  1. 自己收密码 = 这个 App 有能力偷密码。哪怕它现在是干净的，这个能力本身
 *     就是风险面（有人改了代码重新打包上传，用户看不出来）。
 *  2. 小米登录有签名、设备指纹、风控验证码，第三方自建表单成功率低，
 *     而且每次失败都会被记成一次异常登录，反而更容易触发账号保护。
 *  3. 走官方页面，密码只经过小米自己的 TLS 通道，App 拿到的只是**登录态票据**
 *     （passToken）—— 它读不了密码，也能被用户随时远程吊销。
 *
 * ⚠️ 日志里**永远只打印「有没有拿到 passToken」和长度**，绝不打它的值。
 */
private const val TAG = "HealthCoach.Login"
private const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=miothealth&_locale=zh_CN"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiAccountLoginDialog(
    onDismiss: () -> Unit,
    /** 拿到 cookie 后回调（userId 纯数字、passToken 票据）。 */
    onCollected: (userId: String, passToken: String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember {
        mutableStateOf("在下面的页面里登录小米账号。密码只输入给小米，本应用不读取、不记录。")
    }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        var round = 0
        // 页面登录成功后 cookie 才会出现 passToken，这里轮询取（而不是监听输入框）
        while (!finished) {
            delay(1200)
            round++
            val cookie = runCatching { manager.getCookie("https://account.xiaomi.com") }
                .getOrNull()
                .orEmpty()
            val passToken = cookieValue(cookie, "passToken")
            val userId = cookieValue(cookie, "userId")
            if (passToken.isNotEmpty() && userId.isNotEmpty()) {
                // 只打长度，不打内容
                Log.i(
                    TAG,
                    "WebView 登录成功：passToken 长度=${passToken.length}，userId 长度=${userId.length}",
                )
                finished = true
                status = "已拿到登录票据，正在换取云端凭证…"
                onCollected(userId, passToken)
            } else if (round % 8 == 0) {
                // 每约 10 秒打一次进度，方便定位「点了没反应」
                Log.i(
                    TAG,
                    "仍在等待登录：已轮询 ${round} 次，cookie 长度=${cookie.length}，" +
                        "有 passToken=${passToken.isNotEmpty()}，有 userId=${userId.isNotEmpty()}",
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.i(TAG, "关闭账号密码登录窗口，销毁 WebView")
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("用小米账号登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // URL 只到 host + path，避免把查询串里的参数打进日志
                                    Log.i(TAG, "WebView 页面加载完成：${url?.substringBefore('?')}")
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    Log.w(
                                        TAG,
                                        "WebView 加载失败：${request?.url?.toString()?.substringBefore('?')} -> " +
                                            "${error?.description}",
                                    )
                                    status = "页面加载失败，检查网络后重试，或改用「手动填 passToken」"
                                }
                            }
                            loadUrl(LOGIN_URL)
                            webView = this
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/** 从 `a=1; b=2` 形式的 cookie 串里取某个键的值。 */
private fun cookieValue(raw: String, name: String): String {
    if (raw.isEmpty()) return ""
    for (part in raw.split(';')) {
        val item = part.trim()
        if (item.startsWith("$name=")) return item.substringAfter('=')
    }
    return ""
}
