package org.readium.r2.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.LinearLayout
import kotlinx.coroutines.*
import org.readium.r2.navigator.BuildConfig

import java.util.*

import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pager.R2ViewPager
import java.lang.Exception
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


@SuppressLint("SetJavaScriptEnabled")
class LingVisSDK(val navigatorFragment: EpubNavigatorFragment?, val context: Context) {
    private val webViews = mutableListOf<WebView>()
    private val handlers = mutableListOf<LingVisHandler>()
    private lateinit var mainWebView: WebView
    private var mainHandler: LingVisHandler? = null

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        if (navigatorFragment == null) {
            mainWebView = WebView(context)
            mainHandler = LingVisHandler(mainWebView, context, true)
            mainWebView.settings.javaScriptEnabled = true
            mainWebView.settings.domStorageEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mainWebView.settings.safeBrowsingEnabled = false
            }
            mainWebView.loadUrl("file:///android_asset/readium/scripts/poly-core.html")
        }
    }

    init {
        if (navigatorFragment != null) {
            Timer().scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    var pager: R2ViewPager? = null
                    try {
                        pager = navigatorFragment.resourcePager
                    } catch (e: Exception) {
                    }
                    if (pager == null) return
                    if (pager.childCount <= webViews.size) return
                    for (i in 0 until pager.childCount) {
                        val webView =
                            pager.getChildAt(i).findViewById(R.id.webView) as? R2WebView ?: continue
                        if (webViews.contains(webView)) continue
                        val handler = LingVisHandler(webView, context, false)
                        handlers.add(handler)
                        webViews.add(webView)
                    }
                }
            }, 200, 1000)
        }
    }

    private fun escape(str: String?): String {
        if (str == null) return "";
        return str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
    }

    suspend fun signIn(email: String, password: String, newAccount: Boolean): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumSignIn('${contId}', '', '${escape(email)}', '${escape(password)}', '', '', ${newAccount})", null)
    }

    suspend fun getSettings(): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumGetSettings('${contId}')", null)
    }

    suspend fun updateSettings(l2: String, l1: String, level: String): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumUpdateSettings('${contId}', '${l2}', '${l1}', '${level}')", null)
    }
}

class LingVisHandler(val webView: WebView, val context: Context, val isMain: Boolean) {
    companion object {
        private var token = ""
    }
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val continuations = hashMapOf<String, Continuation<Result<String>>>()
    private var gotToken = true

    init {
        uiScope.launch {
            webView.addJavascriptInterface(this@LingVisHandler, "LingVisSDK")
            //tbd: should I prevent a leak? Search for: See https://github.com/readium/r2-navigator-kotlin/issues/52
            webView.settings.domStorageEnabled = true;
            webView.reload()
        }

    }

    fun addContinuation(cont: Continuation<Result<String>>): String {
        val id = UUID.randomUUID().toString()
        continuations[id] = cont
        return id
    }

    private fun invokeContinuation(id: String, args: String, error: String) {
        val cont = continuations[id] ?: return
        continuations.remove(id)
        if (error != "") {
            cont.resume(failure(Exception(error)))
        } else {
            cont.resume(success(args))
        }
    }

    @android.webkit.JavascriptInterface
    fun ready(args: String) {
        if (!gotToken) {
            uiScope.launch {
                delay(200)
                ready(args)
            }
            return
        }
        uiScope.launch {
            val bookId = if (isMain) "" else "my-book-id"
            webView.evaluateJavascript("lingVisSdk.polyReadiumSignIn('', '${token}', '', '', '', '${bookId}')", null)
        }
    }

    @android.webkit.JavascriptInterface
    fun token(args: String) {
        val parts = args.split("|")
        val callback = parts[0]
        token = parts[1]
        val error = parts[2]
        invokeContinuation(callback, token, error)
    }

    @android.webkit.JavascriptInterface
    fun callback(args: String) {
        val parts = args.split("|")
        invokeContinuation(parts[0], parts[1], parts[2])
    }
}
