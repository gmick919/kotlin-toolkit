package org.readium.r2.lingVisSdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.*
import org.readium.r2.navigator.BuildConfig

import java.util.*

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.shared.publication.Publication
import java.lang.Exception
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class ChangeLanguageParams(val l2: String = "", val l1: String = "", val proceed: Boolean = true)


@SuppressLint("SetJavaScriptEnabled")
class LingVisSDK(val navigatorFragment: EpubNavigatorFragment?, val context: Context, val publication: Publication?) {
    private val webViews = mutableListOf<WebView>()
    private val handlers = mutableListOf<LingVisHandler>()
    private val bookId = if (publication == null) "" else publication.metadata.title + ":" + (publication.metadata.identifier ?: "")
    private val uiScope = CoroutineScope(Dispatchers.Main)

    var willChangeLanguage: ((Publication) -> ChangeLanguageParams)? = null
    var didChangeLanguage: ((Result<String>) -> Unit)? = null

    companion object {
        fun prepare(app: String, clientData: String) {
            LingVisSDK.app = app
            LingVisSDK.clientData = clientData
        }
        private const val defaultLang = "sv"
        internal var app: String = "unknown"
        internal var updating: Boolean = false
        internal var updatingInternal: Boolean = false
        @SuppressLint("StaticFieldLeak")
        private lateinit var mainWebView: WebView
        @SuppressLint("StaticFieldLeak")
        private var mainHandler: LingVisHandler? = null
        private var currLang: String = ""
        internal var clientData: String = ""
    }

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        if (navigatorFragment == null) {
            mainWebView = WebView(context)
            mainHandler = LingVisHandler(mainWebView, context, true, "")
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
            if (publication != null) {
                var lang = publication.metadata.languages.firstOrNull() ?: defaultLang
                if (lang != "" && lang != currLang) {
                    val parts = lang.split("|")
                    lang = parts[0]
                    var l1 = ""
                    var proceed = true
                    if (willChangeLanguage != null) {
                        val params = willChangeLanguage!!.invoke(publication)
                        proceed = params.proceed
                        if (params.l2 != "") {
                            lang = params.l2
                        }
                        if (params.l1 != "") {
                            l1 = params.l1
                        }
                    }
                    if (proceed) {
                        uiScope.launch {
                            updatingInternal = true
                            val result = updateSettings(lang, l1, "")
                            updatingInternal = false
                            if (result.isSuccess) {
                                updating = false
                                currLang = lang
                                attachToWebViews()
                            }
                            if (didChangeLanguage != null) {
                                didChangeLanguage!!(result)
                            }
                        }
                    }
                } else {
                    attachToWebViews()
                }
            }
        }
    }

    private fun attachToWebViews() {
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                var pager: R2ViewPager? = null
                try {
                    pager = navigatorFragment?.resourcePager
                } catch (e: Exception) {
                }
                if (pager == null) return
                val currentFragment = (pager.adapter as R2PagerAdapter).getCurrentFragment()
                if (!(currentFragment is R2EpubPageFragment)) return
                val webView = currentFragment.webView
                if (webView == null) return
                if (webViews.contains(webView)) return
                val handler = LingVisHandler(webView, currentFragment.requireActivity(), false, bookId)
                handlers.add(handler)
                webViews.add(webView)
            }
        }, 200, 1000)
    }

    suspend fun signIn(email: String, password: String, newAccount: Boolean, autogenerated: Boolean): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumSignIn('${contId}', '', '${escape(email)}', '${escape(password)}'," +
                "'${escape(app)}', '', ${newAccount}, '', ${autogenerated})", null)
    }

    suspend fun signOut(): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumSignOut('${contId}')", null)
    }

    suspend fun getSettings(): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumGetSettings('${contId}')", null)
    }

    suspend fun updateSettings(l2: String, l1: String, level: String): Result<String> = suspendCoroutine { cont ->
        val contId = mainHandler!!.addContinuation(cont)
        if (l2 != "") {
            updating = true
        }
        mainWebView.evaluateJavascript("lingVisSdk.polyReadiumUpdateSettings('${contId}', '${l2}', '${l1}', '${level}')", null)
        if (!updatingInternal) {
            updating = false
        }
    }
}

class LingVisHandler(val webView: WebView, val context: Context, val isMain: Boolean, val bookId: String) {
    companion object {
        private var token = ""
        private var gotToken = true
    }

    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val continuations = hashMapOf<String, Continuation<Result<String>>>()

    init {
        uiScope.launch {
            webView.addJavascriptInterface(this@LingVisHandler, "LingVisSDK")
            //tbd: should I prevent a leak? Search for: See https://github.com/readium/r2-navigator-kotlin/issues/52
            webView.settings.domStorageEnabled = true
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
        if (!gotToken || LingVisSDK.updating) {
            uiScope.launch {
                delay(200)
                ready(args)
            }
            return
        }
        uiScope.launch {
            val id = if (isMain) "" else escape(bookId)
            if (id == "") {
                gotToken = false
            }
            webView.evaluateJavascript(
                "lingVisSdk.polyReadiumSignIn('', '${token}', '', '', " +
                        "'${escape(LingVisSDK.app)}', '${id}', '', '${escape(LingVisSDK.clientData)}')", null
            )
        }
    }

    @android.webkit.JavascriptInterface
    fun token(args: String) {
        val parts = args.split("|")
        val callback = parts[0]
        token = parts[1]
        gotToken = true
        val error = parts[2]
        invokeContinuation(callback, token, error)
    }

    @android.webkit.JavascriptInterface
    fun callback(args: String) {
        val parts = args.split("|")
        invokeContinuation(parts[0], parts[1], parts[2])
    }

    @android.webkit.JavascriptInterface
    fun onSelect(args: String) {
        if (context !is Activity) return
        uiScope.launch {
            context.hideSystemUi()
        }
    }

    @android.webkit.JavascriptInterface
    fun log(args: String) {
        Log.d("lingVis", args)
    }
}

internal fun escape(str: String?): String {
    if (str == null) return ""
    return str
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}

// from testapp/utils/SystemUiManagement.kt
// Using ViewCompat and WindowInsetsCompat does not work properly in all versions of Android
@Suppress("DEPRECATION")
/** Returns `true` if fullscreen or immersive mode is not set. */
private fun Activity.isSystemUiVisible(): Boolean {
    return this.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
}
// Using ViewCompat and WindowInsetsCompat does not work properly in all versions of Android
@Suppress("DEPRECATION")
/** Enable fullscreen or immersive mode. */
fun Activity.hideSystemUi() {
    this.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
}



