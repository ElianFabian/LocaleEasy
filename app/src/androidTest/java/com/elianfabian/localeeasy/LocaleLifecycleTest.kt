package com.elianfabian.localeeasy

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elianfabian.locale_easy.LocaleEasy
import com.elianfabian.locale_easy.LocalePreference
import com.elianfabian.localeeasy.ui.legacy.SampleFragment
import com.elianfabian.localeeasy.ui.legacy.SampleActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocaleLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val api get() = LocaleEasy.getInstance()

    @Before fun reset() { main { api.followSystem() }; instrumentation.waitForIdleSync() }
    @After fun cleanup() { main { api.followSystem() }; instrumentation.waitForIdleSync() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Locale/lifecycle condition did not become true within 15 seconds")
    }

    private fun translated(language: String): String {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(config).getString(R.string.string_resource_example)
    }

    @Test fun installingTwiceRetainsTheInstance() {
        val before = api
        main { LocaleEasy.install(app); LocaleEasy.install(app) }
        assertSame(before, api)
    }

    @Test fun backgroundMutationFailsWithoutChangingSelection() {
        val before = api.preference.value
        assertTrue(runCatching { api.setLocale(Locale.FRENCH) }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, api.preference.value)
    }

    @Test fun invalidInputDoesNotChangePreferenceOrStorage() {
        val prefs = app.getSharedPreferences("locale_easy_prefs", 0)
        val before = prefs.all
        main {
            assertTrue(runCatching { api.setLocales(emptyList()) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(LocalePreference.FollowSystem, api.preference.value)
        }
        assertEquals(before, prefs.all)
    }

    @Test fun plainActivityRecreatesItsXmlViewAndRestoresUserInput() {
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            var initialView: TextView? = null
            scenario.onActivity {
                initialId = it.instanceId
                initialView = it.findViewById(R.id.locale_fixture_label)
                it.findViewById<EditText>(R.id.locale_fixture_input).setText("keep this input")
                api.setLocale(Locale.FRENCH)
            }
            await {
                var ready = false
                scenario.onActivity {
                    ready = it.instanceId != initialId && it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("fr")
                }
                ready
            }
            scenario.onActivity {
                assertNotSame(initialView, it.findViewById(R.id.locale_fixture_label))
                assertEquals("keep this input", it.findViewById<EditText>(R.id.locale_fixture_input).text.toString())
            }
            // Natural recreation must retain the selected language too.
            scenario.recreate()
            scenario.onActivity { assertEquals(translated("fr"), it.findViewById<TextView>(R.id.locale_fixture_label).text) }
        }
    }

    @Test fun repeatedSelectionDoesNotRecreateAgainAndRapidUpdatesSettleOnLatest() {
        main { api.setLocale(Locale.FRENCH) }
        instrumentation.waitForIdleSync()
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId; api.setLocale(Locale.FRENCH) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(initialId, it.instanceId) }
            scenario.onActivity { api.setLocale(Locale.GERMAN); api.setLocale(Locale.ITALIAN) }
            await {
                var ready = false
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("it") }
                ready
            }
            assertEquals(LocalePreference.AppLocales(listOf(Locale.ITALIAN)), api.preference.value)
        }
    }

    @Test fun stoppedLegacyActivityRefreshesWhenStartedAgain() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId }
            scenario.moveToState(Lifecycle.State.CREATED)
            main { api.setLocale(Locale.FRENCH) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(initialId, it.instanceId) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await {
                var ready = false
                scenario.onActivity { ready = it.instanceId != initialId && it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("fr") }
                ready
            }
        }
    }

    @Test fun legacyModeSwitchWithIdenticalLanguagesStillRebuildsTheContext() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            var initialId = 0
            scenario.onActivity { initialId = it.instanceId; api.followSystemLocale = false }
            await {
                var replaced = false
                scenario.onActivity { replaced = it.instanceId != initialId }
                replaced
            }
            assertTrue(api.preference.value is LocalePreference.AppLocales)
        }
    }

    @Test fun appCompatRuntimeTitlesAndStoppedBackStackKeepTheirLocale() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.startActivity(android.content.Intent(it, SampleActivity::class.java).putExtra("level", 2))
            }
            fun topActivity(): SampleActivity? {
                return androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<SampleActivity>().firstOrNull()
            }
            await {
                var ready = false
                main { ready = topActivity()?.intent?.getIntExtra("level", 0) == 2 }
                ready
            }
            main { api.setLocale(Locale.FRENCH) }
            await {
                var ready = false
                main { ready = topActivity()?.title?.toString() == "Activité 2" }
                ready
            }
            main { topActivity()?.finish() }
            await {
                var ready = false
                scenario.onActivity { ready = it.title.toString() == "Activité 1" && it.getString(R.string.string_resource_example) == translated("fr") }
                ready
            }
        }
    }

    @Test fun fragmentDialogSurvivesActivityLocaleRecreationExactlyOnce() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                SampleFragment().showNow(it.supportFragmentManager, "locale-test-dialog")
                api.setLocale(Locale.FRENCH)
            }
            await {
                var ready = false
                scenario.onActivity {
                    val fragment = it.supportFragmentManager.findFragmentByTag("locale-test-dialog")
					ready = fragment?.getString(R.string.string_resource_example) == translated("fr") && fragment?.view != null
                }
                ready
            }
            scenario.onActivity {
                assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment.tag == "locale-test-dialog" })
            }
        }
    }

    @Test fun externalFrameworkSelectionUpdatesFlowAndResources() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            val system = api.systemLocaleList.value
            main { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(*system.toTypedArray()) }
            await { api.preference.value == LocalePreference.AppLocales(system) }
            main { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(Locale.FRENCH) }
            await {
                var ready = false
                scenario.onActivity { ready = it.findViewById<TextView>(R.id.locale_fixture_label).text == translated("fr") }
                ready && api.preference.value == LocalePreference.AppLocales(listOf(Locale.FRENCH))
            }
            main { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
            await { api.preference.value == LocalePreference.FollowSystem }
        }
    }

    @Test fun webViewInitializationAndNativeRepairPreservePreference() {
        main { api.setLocale(Locale.FRENCH) }
        instrumentation.waitForIdleSync()
        ActivityScenario.launch(LocaleFixtureActivity::class.java).use { scenario ->
            scenario.onActivity {
                val webView = android.webkit.WebView(it)
                webView.loadData("<html lang='fr'><body>Locale fixture</body></html>", "text/html", "UTF-8")
                LocaleEasy.fixWebViewLocale()
                assertEquals(LocalePreference.AppLocales(listOf(Locale.FRENCH)), api.preference.value)
                assertEquals(translated("fr"), it.getString(R.string.string_resource_example))
                webView.destroy()
            }
        }
    }
}
