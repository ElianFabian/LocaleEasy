package com.elianfabian.localeeasy

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elianfabian.locale_easy.LocaleEasy
import java.util.Locale

class ComposeWebViewFixtureActivity : ComponentActivity() {
    lateinit var webView: WebView
        private set
    lateinit var textBeforeLoad: String
        private set
    lateinit var textAfterLoad: String
        private set
    private val constructionReadings = mutableStateOf<ConstructionReadings?>(null)
    private val applicationTextAfterLoad = mutableStateOf<String?>(null)

    @SuppressLint("AppBundleLocaleChanges") // The locale mismatch is synthetic and only used in debug tests.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_CONSTRUCTION_PROBE, false)) {
            showConstructionProbe()
            return
        }
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

    private fun showConstructionProbe() {
        val repairEarly = intent.getBooleanExtra(EXTRA_REPAIR_EARLY, false)
        setContent {
            Column {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    factory = { context ->
                        val before = application.getString(R.string.string_resource_example)
                        WebView(context).also { view ->
                            val afterConstruction = application.getString(R.string.string_resource_example)
                            if (repairEarly) LocaleEasy.fixWebViewLocale()
                            constructionReadings.value = ConstructionReadings(
                                before,
                                afterConstruction,
                                application.getString(R.string.string_resource_example),
                                context.getString(R.string.string_resource_example),
                            )
                            webView = view
                        }
                    },
                    onRelease = { it.destroy() },
                )
                constructionReadings.value?.let { readings ->
                    Text("Before construction: ${readings.beforeConstruction}")
                    Text("After construction: ${readings.afterConstruction}")
                    Text("Displayed application text: ${readings.applicationText}")
                    Text("Factory context text: ${readings.factoryText}")
                    Text("Compose resource: ${stringResource(R.string.string_resource_example)}")
                }
                applicationTextAfterLoad.value?.let { Text("After load: $it") }
            }
        }
    }

    fun loadConstructionProbe() {
        webView.loadUrl(FIXTURE_URL)
        applicationTextAfterLoad.value = application.getString(R.string.string_resource_example)
    }

    private data class ConstructionReadings(
        val beforeConstruction: String,
        val afterConstruction: String,
        val applicationText: String,
        val factoryText: String,
    )

    enum class Load {
        URL_WITH_HEADERS,
        DATA,
        DATA_WITH_BASE_URL,
    }

    private class FixtureWebView(context: Context) : WebView(context)

    companion object {
        const val EXTRA_LOAD = "load"
        const val EXTRA_CONSTRUCTION_PROBE = "construction_probe"
        const val EXTRA_REPAIR_EARLY = "repair_early"
        private const val FIXTURE_URL = "file:///android_asset/locale-fixture.html"
        private const val FIXTURE_HTML = "<html lang='en'><h1>WebView locale fixture</h1></html>"
    }
}
