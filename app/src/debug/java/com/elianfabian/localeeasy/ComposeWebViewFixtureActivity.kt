package com.elianfabian.localeeasy

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

class ComposeWebViewFixtureActivity : ComponentActivity() {
    lateinit var webView: WebView
        private set
    lateinit var textBeforeLoad: String
        private set
    lateinit var textAfterLoad: String
        private set

    @SuppressLint("AppBundleLocaleChanges") // The locale mismatch is synthetic and only used in debug tests.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val load = Load.valueOf(requireNotNull(intent.getStringExtra(EXTRA_LOAD)))
        setContent {
            AndroidView(factory = { context ->
                val view = FixtureWebView(context)
                // Force the mismatch after construction, even when the provider keeps the locale.
                val resources = application.resources
                val mismatch = Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
                resources.updateConfiguration(mismatch, resources.displayMetrics)
                textBeforeLoad = application.getString(R.string.string_resource_example)
                when (load) {
                    Load.URL_WITH_HEADERS -> view.loadUrl(FIXTURE_URL, mapOf("X-LocaleEasy-Test" to "true"))
                    Load.DATA -> view.loadData(FIXTURE_HTML, "text/html", "UTF-8")
                    Load.DATA_WITH_BASE_URL ->
                        view.loadDataWithBaseURL(FIXTURE_URL, FIXTURE_HTML, "text/html", "UTF-8", null)
                }
                textAfterLoad = application.getString(R.string.string_resource_example)
                webView = view
                view
            }, onRelease = { it.destroy() })
        }
    }

    enum class Load {
        URL_WITH_HEADERS,
        DATA,
        DATA_WITH_BASE_URL,
    }

    private class FixtureWebView(context: Context) : WebView(context)

    companion object {
        const val EXTRA_LOAD = "load"
        private const val FIXTURE_URL = "file:///android_asset/locale-fixture.html"
        private const val FIXTURE_HTML = "<html lang='en'><h1>WebView locale fixture</h1></html>"
    }
}
