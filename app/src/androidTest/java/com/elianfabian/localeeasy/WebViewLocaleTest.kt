package com.elianfabian.localeeasy

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.setAppLocale
import com.elianfabian.localeeasy.ui.legacy.WebViewFragment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewLocaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val api get() = LocaleEasy.getInstance()

    @Test
    @Suppress("DEPRECATION")
    fun repairsAnActualApplicationResourceMismatch() = preservePreference {
        instrumentation.runOnMainSync {
            api.setAppLocale(Locale.FRENCH)
            val preference = api.preference.value
            val effective = api.effectiveLocaleList.value
            val resources = app.resources
            val spanish = Locale.forLanguageTag("es")
            val mismatch = Configuration(resources.configuration).apply { setLocale(spanish) }
            try {
                resources.updateConfiguration(mismatch, resources.displayMetrics)
                assertEquals(spanish, ConfigurationCompat.getLocales(resources.configuration)[0])
                assertEquals("Cadena Localizada: Verde", app.getString(R.string.string_resource_example))
                assertEquals(effective, api.effectiveLocaleList.value)

                LocaleEasy.fixWebViewLocale()

                assertEquals(Locale.FRENCH, ConfigurationCompat.getLocales(resources.configuration)[0])
                assertEquals("Chaîne Localisée: Vert", app.getString(R.string.string_resource_example))
                assertEquals(preference, api.preference.value)
                assertEquals(effective, api.effectiveLocaleList.value)
            } finally {
                LocaleEasy.fixWebViewLocale()
            }
        }
    }

    @Test
    fun observesLoadedWebViewAcrossLocalRecreation() = preservePreference {
        instrumentation.runOnMainSync { api.setAppLocale(Locale.FRENCH) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var originalActivity: MainActivity
            lateinit var originalView: WebView
            lateinit var beforeOpening: String
            lateinit var afterOpening: String
            scenario.onActivity { activity ->
                originalActivity = activity
                beforeOpening = nativeLocales(activity)
                WebViewFragment().showNow(activity.supportFragmentManager, WEB_DIALOG)
                originalView = requireNotNull(webView(activity))
                originalView.settings.javaScriptEnabled = true
                afterOpening = nativeLocales(activity)
            }
            awaitPage(originalView)

            instrumentation.runOnMainSync { originalView.loadUrl(SECOND_PAGE) }
            awaitPage(originalView, expectedUrl = SECOND_PAGE)
            val edited = evaluatePage(originalView, """
                () => {
                    const input = document.createElement('input');
                    input.id = 'locale-test-input';
                    input.value = 'retained text';
                    document.body.insertBefore(input, document.body.firstChild);
                    document.body.style.minHeight = '300vh';
                    window.scrollTo(0, 300);
                }
            """.trimIndent())
            assertEquals("retained text", edited.getString("input"))
            assertTrue(edited.getInt("scroll") > 0)

            lateinit var beforeRepair: String
            lateinit var afterRepair: String
            scenario.onActivity {
                assertTrue(originalView.canGoBack())
                beforeRepair = nativeLocales(it)
                LocaleEasy.fixWebViewLocale()
                afterRepair = nativeLocales(it)
                assertEquals("Chaîne Localisée: Vert", app.getString(R.string.string_resource_example))
                assertEquals(listOf(Locale.FRENCH), api.effectiveLocaleList.value)
                assertTrue(originalView.canGoBack())
            }
            assertEquals(edited.toString(), evaluatePage(originalView).toString())
            scenario.onActivity {
                api.setLanguageTag("es")
            }

            var replacement: WebView? = null
            awaitCondition("replacement WebView in recreated activity") {
                scenario.onActivity { activity ->
                    if (activity !== originalActivity) {
                        replacement = webView(activity)
                        replacement?.settings?.javaScriptEnabled = true
                    }
                }
                replacement != null
            }
            val recreated = requireNotNull(replacement)
            assertNotSame(originalView, recreated)
            val loaded = awaitPage(recreated)
            scenario.onActivity { activity ->
                assertEquals("Cadena Localizada: Verde", activity.getString(R.string.string_resource_example))
                assertEquals(activity.getString(R.string.open_settings),
                    dialog(activity)?.requireView()?.findViewById<Button>(R.id.btnSettings)?.text?.toString())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("webViewProvider", providerVersion())
                    putString("nativeBeforeOpening", beforeOpening)
                    putString("nativeAfterOpening", afterOpening)
                    putString("nativeBeforeRepair", beforeRepair)
                    putString("nativeAfterRepair", afterRepair)
                    putString("nativeAfterSwitch", nativeLocales(activity))
                    putString("webBeforeSwitch", edited.toString())
                    putString("webAfterSwitch", loaded.toString())
                    putBoolean("webAfterSwitchCanGoBack", recreated.canGoBack())
                })
            }
            assertEquals("en", loaded.getString("language"))
        }
    }

    private fun preservePreference(test: () -> Unit) {
        val preference = api.preference.value
        try {
            test()
        } finally {
            instrumentation.runOnMainSync { api.setPreference(preference) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun dialog(activity: MainActivity): WebViewFragment? =
        activity.supportFragmentManager.findFragmentByTag(WEB_DIALOG) as? WebViewFragment

    private fun webView(activity: MainActivity): WebView? =
        dialog(activity)?.view?.findViewById(R.id.webView)

    private fun nativeLocales(activity: MainActivity): String =
        "application=${ConfigurationCompat.getLocales(app.resources.configuration).toLanguageTags()}, " +
            "activity=${ConfigurationCompat.getLocales(activity.resources.configuration).toLanguageTags()}, " +
            "text=${app.getString(R.string.string_resource_example)} / ${activity.getString(R.string.string_resource_example)}"

    private fun providerVersion(): String = if (Build.VERSION.SDK_INT >= 26) {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    } else {
        "Provider query requires API 26"
    }

    private fun awaitPage(view: WebView, expectedUrl: String? = null): JSONObject {
        var loaded = JSONObject()
        awaitCondition("fully loaded local HTML fixture") {
            loaded = evaluatePage(view)
            loaded.optString("ready") == "complete" &&
                loaded.optString("heading") == "WebView locale fixture" &&
                (expectedUrl == null || loaded.optString("url") == expectedUrl)
        }
        return loaded
    }

    private fun evaluatePage(view: WebView, edit: String = "() => {}"): JSONObject {
        val completion = CountDownLatch(1)
        var value: String? = null
        instrumentation.runOnMainSync {
            view.evaluateJavascript("""
                (() => {
                    ($edit)();
                    const heading = document.querySelector('h1');
                    const input = document.getElementById('locale-test-input');
                    return {
                        ready: document.readyState,
                        heading: heading ? heading.textContent : '',
                        url: location.href,
                        language: document.documentElement.lang,
                        input: input ? input.value : '',
                        scroll: window.scrollY
                    };
                })()
            """.trimIndent()) {
                value = it
                completion.countDown()
            }
        }
        assertTrue("WebView JavaScript callback timed out", completion.await(5, TimeUnit.SECONDS))
        return value?.takeIf { it.startsWith("{") }?.let(::JSONObject) ?: JSONObject()
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val WEB_DIALOG = "webview_fragment"
        const val SECOND_PAGE = "file:///android_asset/locale-fixture.html?step=next"
    }
}
