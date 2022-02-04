package org.readium.r2.testapp

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import java.util.*

import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pager.R2ViewPager
import java.lang.Exception


class LingVisSDK(val navigatorFragment: EpubNavigatorFragment, val context: Context) {
    private val webViews = mutableListOf<WebView>()
    private val handlers = mutableListOf<LingVisHandler>()

    init {
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                var pager: R2ViewPager? = null
                try {
                    pager = navigatorFragment.resourcePager
                } catch (e: Exception) {}
                if (pager == null) return
                if (pager.childCount <= webViews.size) return
                for (i in 0 until pager.childCount) {
                    val webView = pager.getChildAt(i).findViewById(R.id.webView) as? R2WebView ?: continue
                    if (webViews.contains(webView)) continue
                    val handler = LingVisHandler(webView, context)
                    handlers.add(handler)
                    webViews.add(webView)
                }
            }
        },200,1000)
    }

}

class LingVisHandler(val webView: WebView, val context: Context) {
    private val uiScope = CoroutineScope(Dispatchers.Main)

    init {
        uiScope.launch {
            webView.addJavascriptInterface(this@LingVisHandler, "LingVisSDK")
            //tbd: should I prevent a leak? Search for: See https://github.com/readium/r2-navigator-kotlin/issues/52
            webView.settings.domStorageEnabled = true;
            webView.reload()
        }

    }

    @android.webkit.JavascriptInterface
    fun ready(args: String) {
        uiScope.launch {
            webView.evaluateJavascript("lingVisSdk.polyReadiumSignIn('', '', '', '', '', 'my-book-id')", null)
        }
    }

    @android.webkit.JavascriptInterface
    fun token(args: String) {
        //tbd
    }

}
